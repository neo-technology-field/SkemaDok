package com.neo4j.skemadok.seeder;

import com.neo4j.skemadok.model.*;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static com.neo4j.skemadok.seeder.SeedPlanner.getStringRelationshipTypeInfoMap;

/**
 * Runs a {@link SeedPlan} against a live Neo4j {@link Driver}.
 *
 * <p><b>Pipeline</b> (order is intentional):
 * <ol>
 *   <li>Drop existing data or verify the target is empty.</li>
 *   <li>Replay constraints before writing nodes so violations surface early.</li>
 *   <li>Build {@code __seedId} indexes — only for labels used in rel endpoint MATCHes
 *       and TAG ordering, not every label.</li>
 *   <li>Seed nodes → apply tag labels → seed relationships.</li>
 *   <li>Remove {@code __seedId}; replay schema indexes; drop {@code __seedId} indexes.</li>
 * </ol>
 *
 * <p><b>{@code __seedId}</b>: a synthetic dense integer assigned to every node at creation
 * time and indexed per label. It makes random node lookup O(1) —
 * {@code MATCH (n:Label {__seedId: k})} — so relationship seeding can assemble batches of
 * random (start, end) pairs in Java and send them as a single {@code UNWIND} per batch.
 * HIERARCHY and TAG labels occupy contiguous slices of the same global space so that a
 * child-label node is reachable from both its own label index and its parent's index under
 * the same key. {@link SeedPlan#labelRanges()} records which slice belongs to each label.
 * See {@code docs/seeding.md} for the full design rationale.
 *
 * <p><b>Parallel relationship seeding</b> uses the mix-and-batch matrix technique. Start and
 * end seedId spaces are each split into {@value #MATRIX_N} buckets, forming an N×N cell
 * matrix. Cells are grouped into diagonal stripes by {@code (col - row + N) % N}; within a stripe
 * no two cells share a bucket so their node sets are guaranteed disjoint. Each cell runs on its
 * own Java virtual thread; within each cell, UNWIND batches execute as sequential auto-commit
 * transactions each in their own {@link Session} — opening a fresh session per batch prevents
 * causal-consistency bookmarks from accumulating and causing {@code TransientException} on
 * single-server cluster topology (Raft-based even with one node). Stripes execute sequentially —
 * the next stripe starts only after all cells of the current stripe have committed. Self-loops
 * fold the matrix to upper-triangular to avoid mirror-cell deadlocks. See {@link #buildRelMatrix}.
 *
 * <p>Construction is lightweight; {@link #execute} is single-shot.
 */
final class SeedExecutor {

    private static final Logger log = LoggerFactory.getLogger(SeedExecutor.class);

    static final String SEED_ID = "__seedId";
    static final String SEED_LABEL = "__Seed";
    private static final int NODE_BATCH = 20_000;
    private static final int NODE_INNER_BATCH = 5_000;
    private static final int TAG_BATCH = 10_000;
    private static final int REL_BATCH = 10_000;
    private static final int DROP_BATCH = 10_000;
    private static final int MATRIX_N = 8;

    private final Driver driver;
    private final PropertyValueGenerator valueGenerator;
    private final SeedOptions options;

    private final SplittableRandom random;
    private UniquenessConstraints uniqueness;

    SeedExecutor(Driver driver, PropertyValueGenerator valueGenerator, SeedOptions options) {
        this.driver = driver;
        this.valueGenerator = valueGenerator;
        this.options = options;
        this.random = new SplittableRandom(options.randomSeed());
    }

    /**
     * Drives the full seed against {@code plan}. Caller must already have logged the breakdown.
     */
    void execute(SchemaDocument schema, SeedPlan plan) {
        uniqueness = UniquenessConstraints.from(schema.getConstraints());
        var labelsByName = indexLabels(schema.getNodeLabels());

        var sessionConfig = SessionConfig.builder().withDatabase(options.database()).build();
        try (var session = driver.session(sessionConfig)) {
            if (options.drop()) {
                drop(session);
            } else {
                ensureEmpty(session);
            }

            createSeedNodeConstraintPhase(session);
            createConstraintsPhase(session, schema.getConstraints());

            seedNodes(session, plan, labelsByName);
            applyTagsPhase(session, plan);
            seedRelationships(schema, plan);

            dropSeedNodeConstraintPhase(session);
            removeSeedNodePhase(session);
            createSchemaIndexesPhase(session, schema.getIndexes());

        }
    }

    // -------------------------------------------------------- drop / empty

    private void drop(Session session) {
        log.info("Dropping existing data...");
        session.run("MATCH (n) CALL { WITH n DETACH DELETE n } IN TRANSACTIONS OF " + DROP_BATCH + " ROWS")
                .consume();
        dropExistingConstraints(session);
        dropExistingIndexes(session);
    }

    private void dropExistingConstraints(Session session) {
        var names = session.run("SHOW CONSTRAINTS YIELD name").list(r -> r.get("name").asString());
        for (var name : names) {
            try {
                session.run("DROP CONSTRAINT `" + name + "` IF EXISTS").consume();
            } catch (Exception e) {
                log.warn("Could not drop constraint {}: {}", name, e.getMessage());
            }
        }
    }

    private void dropExistingIndexes(Session session) {
        var rows = session.run("SHOW INDEXES YIELD name, type")
                .list(r -> Map.entry(r.get("name").asString(), r.get("type").asString()));
        for (var row : rows) {
            if ("LOOKUP".equalsIgnoreCase(row.getValue())) {
                continue;
            }
            try {
                session.run("DROP INDEX `" + row.getKey() + "` IF EXISTS").consume();
            } catch (Exception e) {
                log.warn("Could not drop index {}: {}", row.getKey(), e.getMessage());
            }
        }
    }

    private void ensureEmpty(Session session) {
        var hasData = session.run("MATCH (n) RETURN count(n) > 0 AS hasData").single().get("hasData").asBoolean();
        if (hasData) {
            throw new IllegalStateException(
                    "Target database is not empty. Re-run with --drop to clear it first.");
        }
    }

    // --------------------------------------------------------- __Seed

    /**
     * Creates a uniqueness constraint on {@code __Seed.__seedId} before any nodes are written.
     * The constraint creates the backing index automatically and enforces the global uniqueness
     * that the matrix approach relies on.
     */
    private void createSeedNodeConstraintPhase(Session session) {
        log.info("Creating {} uniqueness constraint", SEED_LABEL);
        session.run("""
                CREATE CONSTRAINT __seed_node_id IF NOT EXISTS
                FOR (n:%s) REQUIRE n.%s IS UNIQUE
                """.formatted(SEED_LABEL, SEED_ID)).consume();
        session.run("CALL db.awaitIndexes()").consume();
    }

    /**
     * Removes the {@code __Seed} label and the {@code __seedId} property from every node in one
     * pass, then drops the uniqueness constraint. No internal artefact lingers.
     */
    private void removeSeedNodePhase(Session session) {
        log.info("Removing {} label and {} property", SEED_LABEL, SEED_ID);
        session.run("""
                MATCH (n:%s)
                CALL (n) { REMOVE n:%s, n.%s } IN TRANSACTIONS OF %d ROWS
                """.formatted(SEED_LABEL, SEED_LABEL, SEED_ID, DROP_BATCH)).consume();
    }

    private void dropSeedNodeConstraintPhase(Session session) {
        log.info("Dropping {} constraint", SEED_LABEL);
        session.run("DROP CONSTRAINT __seed_node_id IF EXISTS").consume();
    }

    // ------------------------------------------------------------- nodes

    /**
     * Iterates the plan's NodeCreate buckets and runs the batched CREATE for each.
     */
    private void seedNodes(Session session, SeedPlan plan, Map<String, LabelInfo> labelsByName) {
        for (var bucket : plan.nodeCreates()) {
            if (bucket.count() <= 0) {
                continue;
            }
            createNodes(session, bucket, labelsByName);
        }
    }

    private void createNodes(Session session, SeedPlan.NodeCreate bucket,
                             Map<String, LabelInfo> labelsByName) {
        log.info("Seeding {} nodes labelled {} starting at __seedId={}",
                bucket.count(), bucket.labels(), bucket.seedIdStart());
        var labelClause = SEED_LABEL + ":" + bucket.labels().stream()
                .map(l -> "`" + l + "`")
                .collect(Collectors.joining(":"));
        var stmt = """
                UNWIND $rows AS row
                CALL (row) {
                    CREATE (n:%s)
                    SET n = row.props
                } IN CONCURRENT TRANSACTIONS OF %d ROWS
                """.formatted(labelClause, NODE_INNER_BATCH);

        var batch = new ArrayList<Map<String, Object>>(NODE_BATCH);
        for (long i = 0; i < bucket.count(); i++) {
            batch.add(Map.of("props", buildNodeProps(bucket, i, labelsByName)));
            if (batch.size() >= NODE_BATCH) {
                session.run(stmt, Map.of("rows", batch)).consume();
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            session.run(stmt, Map.of("rows", batch)).consume();
        }
    }

    /**
     * Builds the property map for one node in a bucket. Iterates the bucket's labels in
     * root-to-leaf order so properties on outer labels are set first; later labels skip any
     * property name already filled (HIERARCHY children may legally share a property name with
     * the base, in which case the base's value wins).
     */
    private Map<String, Object> buildNodeProps(SeedPlan.NodeCreate bucket, long position,
                                               Map<String, LabelInfo> labelsByName) {
        var props = new LinkedHashMap<String, Object>();
        props.put(SEED_ID, bucket.seedIdStart() + position);

        for (var labelName : bucket.labels()) {
            var labelInfo = labelsByName.get(labelName);
            if (labelInfo == null || labelInfo.getProperties() == null) {
                continue;
            }
            var uniqueSet = uniqueness.uniquePropertiesOf(labelName);
            var indexForLabel = bucket.indexForLabel(labelName, position);
            for (var property : labelInfo.getProperties()) {
                if (props.containsKey(property.name())) {
                    continue;
                }
                var mustBeUnique = uniqueSet.contains(property.name());
                var value = valueGenerator.generate(labelName, property, indexForLabel, mustBeUnique);
                if (value != null) {
                    props.put(property.name(), value);
                }
            }
        }
        return props;
    }

    // -------------------------------------------------------------- tags

    private void applyTagsPhase(Session session, SeedPlan plan) {
        if (plan.tagApplies().isEmpty()) {
            return;
        }
        log.info("Applying {} tag label(s)", plan.tagApplies().size());
        for (var tag : plan.tagApplies()) {
            for (var entry : tag.hostCounts().entrySet()) {
                var rangeStart = tag.hostRangeStart().get(entry.getKey());
                applyOneTag(session, tag.tagLabel(), rangeStart, entry.getValue());
            }
        }
    }

    /**
     * Applies {@code tagLabel} to the {@code count} nodes whose {@code __seedId} falls in
     * {@code [rangeStart, rangeStart + count)}. Uses a range predicate on the global
     * {@code __Seed} index instead of an ORDER BY scan so no per-label index is needed.
     */
    private void applyOneTag(Session session, String tagLabel, long rangeStart, long count) {
        if (count <= 0) {
            return;
        }
        log.debug("Applying tag :{} to {} nodes starting at __seedId={}", tagLabel, count, rangeStart);
        var stmt = """
                MATCH (n:%s)
                WHERE n.%s >= $start AND n.%s < $end
                CALL (n) { SET n:`%s` } IN CONCURRENT TRANSACTIONS OF %d ROWS
                """.formatted(SEED_LABEL, SEED_ID, SEED_ID, tagLabel, TAG_BATCH);
        session.run(stmt, Map.of("start", rangeStart, "end", rangeStart + count)).consume();
        var applied = session.run("MATCH (n:`" + tagLabel + "`) RETURN count(n) AS c")
                .single().get("c").asLong();
        log.info("  Applied :{} to {} nodes", tagLabel, applied);
    }

    // ---------------------------------------------------- relationships

    /**
     * One cell of the rel matrix: a chosen start-bucket × end-bucket slice with its own RNG,
     * target count, and a pre-assigned relIndex offset. Cells assigned to the same stripe never
     * share a start or end bucket, so parallel sessions for those cells are deadlock-free.
     * The relIndexOffset ensures globally unique property-index values without a shared counter.
     */
    private record RelCell(List<long[]> startRanges, List<long[]> endRanges,
                           long target, long relIndexOffset, SplittableRandom rng) {
    }

    /**
     * Seeds relationships using the mix-and-batch matrix technique.
     *
     * <p>For each {@link SeedPlan.RelPlan} the start and end {@code __seedId} spaces are sliced
     * into {@value #MATRIX_N} equal buckets, forming an N×N cell matrix. Each cell (i, j) is
     * assigned to stripe {@code (j - i + N) % N}; within any stripe no two cells share a start
     * or end bucket. Cells within a stripe run on parallel Java virtual threads, each with its
     * own {@link Session}. Stripes run sequentially — the next stripe begins only after all cells
     * of the current stripe have committed. Rel plans run sequentially.
     */
    private void seedRelationships(SchemaDocument schema, SeedPlan plan) {
        var relByDisplayName = getStringRelationshipTypeInfoMap(schema.getRelationshipTypes());
        var sessionConfig = SessionConfig.builder().withDatabase(options.database()).build();
        var relIndexBase = 0L;

        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var group : plan.relGroups()) {
                logRelGroup(group);
                var info = relByDisplayName.get(firstPlanType(group));
                for (var relPlan : group.plans()) {
                    var startRanges = plan.labelRanges().get(relPlan.startLabel());
                    var endRanges = plan.labelRanges().get(relPlan.endLabel());
                    if (startRanges == null || endRanges == null) {
                        continue;
                    }
                    if (relPlan.target() <= 0) {
                        continue;
                    }

                    var selfLoop = relPlan.startLabel().equals(relPlan.endLabel());
                    var stripes = buildRelMatrix(startRanges, endRanges, relPlan.target(), selfLoop, relIndexBase);
                    relIndexBase += relPlan.target();

                    for (var stripe : stripes) {
                        if (stripe.isEmpty()) {
                            continue;
                        }
                        runStripe(stripe, relPlan, info, sessionConfig, pool);
                    }
                }
            }
        }
    }

    /**
     * Submits each cell in {@code stripe} as a virtual-thread task and blocks until all have
     * committed. The caller must not submit the next stripe until this method returns.
     */
    private void runStripe(List<RelCell> stripe, SeedPlan.RelPlan relPlan, RelationshipTypeInfo info,
                           SessionConfig sessionConfig, ExecutorService pool) {
        var futures = stripe.stream()
                .map(cell -> pool.submit(() -> runCell(cell, relPlan, info, sessionConfig)))
                .toList();
        for (var future : futures) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted waiting for cell to complete", e);
            } catch (ExecutionException e) {
                throw new RuntimeException(e.getCause());
            }
        }
    }

    /**
     * Seeds one cell of the relationship matrix. Each UNWIND batch runs in its own fresh
     * {@link Session} to prevent causal-consistency bookmarks from accumulating across batches.
     * In driver 6.x, auto-commit {@code session.run()} stores the server's returned bookmark in
     * {@code lastReceivedBookmarks} and includes it in every subsequent call within the same
     * session. On single-server cluster topology (Raft-based), the "latest database version"
     * can briefly lag behind a just-committed bookmark, causing a {@code TransientException}.
     * A fresh session per batch resets that state, so each batch carries no causal requirement.
     * Deadlock-free because the matrix guarantees disjoint node sets per stripe.
     */
    private void runCell(RelCell cell, SeedPlan.RelPlan relPlan, RelationshipTypeInfo info,
                         SessionConfig sessionConfig) {
        var startSize = totalRangeSize(cell.startRanges());
        var endSize = totalRangeSize(cell.endRanges());
        if (startSize == 0 || endSize == 0) {
            return;
        }

        // ORDER BY id(a), id(b) reorders matched pairs by physical block address before writing.
        // With Neo4j's block format, co-located node+rel data means sequential id() order maps
        // directly to sequential block access on both endpoints — dramatically fewer page faults
        // for dense rel types with large relationship stores.
        var stmt = """
                UNWIND $rows AS row
                MATCH (a:%s {%s: row.start})
                MATCH (b:%s {%s: row.end})
                WITH a, b, row ORDER BY id(a), id(b)
                CREATE (a)-[r:`%s`]->(b)
                SET r = row.props
                """.formatted(SEED_LABEL, SEED_ID, SEED_LABEL, SEED_ID, relPlan.relType());

        var uniqueSet = (info == null) ? Set.<String>of() : uniqueness.uniquePropertiesOf(info.getName());
        var relIndex = cell.relIndexOffset();

        var batch = new ArrayList<Map<String, Object>>(REL_BATCH);
        for (long i = 0; i < cell.target(); i++) {
            var row = new LinkedHashMap<String, Object>();
            row.put("start", sampleSeedId(cell.startRanges(), startSize, cell.rng()));
            row.put("end", sampleSeedId(cell.endRanges(), endSize, cell.rng()));
            row.put("props", buildRelProps(info, relIndex++, uniqueSet));
            batch.add(row);
            if (batch.size() >= REL_BATCH) {
                flushRelBatch(stmt, batch, sessionConfig);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            flushRelBatch(stmt, batch, sessionConfig);
        }
    }

    /**
     * Executes one UNWIND batch of relationship rows in a fresh session. A new session is used
     * rather than reusing a cell-scoped one so that no causal bookmark from a prior batch is
     * forwarded to the server — see {@link #runCell} for the full rationale.
     */
    private void flushRelBatch(String stmt, List<Map<String, Object>> batch, SessionConfig sessionConfig) {
        try (var session = driver.session(sessionConfig)) {
            session.run(stmt, Map.of("rows", batch)).consume();
        }
    }

    /**
     * Partitions start and end node ranges into a cell matrix and groups cells by diagonal
     * stripe. Within any stripe, no two cells share a start or end bucket.
     *
     * <p>For ordinary rel plans the matrix is the full N x N. For self-loops it folds to the
     * upper triangular (cells (i, j) with i ≤ j) so mirror cells are never both scheduled —
     * those would deadlock by locking buckets in opposite orders.
     *
     * <p>The total target is distributed round-robin across the live cells so every cell ends
     * up with either {@code floor(target/cells)} or {@code ceil(target/cells)} rels and the sum
     * is exactly {@code totalTarget}. There is no leftover.
     */
    private List<List<RelCell>> buildRelMatrix(List<long[]> startRanges, List<long[]> endRanges,
                                               long totalTarget, boolean selfLoop, long relIndexBase) {
        // Cap N to the smaller of the two endpoint pools so we never end up with empty
        // partitions — a cell with an empty endpoint range would silently drop its share of
        // rels (sampling from an empty range produces nothing).
        var startSize = totalRangeSize(startRanges);
        var endSize = totalRangeSize(endRanges);
        var smallest = selfLoop ? startSize : Math.min(startSize, endSize);
        var n = (int) Math.clamp(smallest, 1L, (long) MATRIX_N);

        var startPartitions = splitRanges(startRanges, n);
        var endPartitions = selfLoop ? startPartitions : splitRanges(endRanges, n);

        var totalCells = selfLoop ? n * (n + 1) / 2 : n * n;
        var baseTarget = totalTarget / totalCells;
        var extras = totalTarget - baseTarget * totalCells;

        var stripes = new ArrayList<List<RelCell>>(n);
        for (int k = 0; k < n; k++) {
            stripes.add(new ArrayList<>());
        }

        // cellOffset accumulates the relIndex starting position for each cell so parallel
        // cell threads can generate globally unique property index values without a shared counter.
        var cellOffset = relIndexBase;
        var cellIndex = 0;
        for (int i = 0; i < n; i++) {
            var jStart = selfLoop ? i : 0;
            for (int j = jStart; j < n; j++) {
                var cellTarget = baseTarget + (cellIndex < extras ? 1L : 0L);
                cellIndex++;
                if (cellTarget == 0) {
                    continue;
                }
                var stripe = (j - i + n) % n;
                stripes.get(stripe).add(new RelCell(
                        startPartitions.get(i), endPartitions.get(j),
                        cellTarget, cellOffset, random.split()));
                cellOffset += cellTarget;
            }
        }
        return stripes;
    }

    private static String firstPlanType(SeedPlan.RelGroup group) {
        return group.plans().getFirst().relType();
    }

    private static void logRelGroup(SeedPlan.RelGroup group) {
        if (group.parameterised() && group.plans().size() > 1) {
            log.info("Seeding {} relationships across {} instances of {}_{{...}} ({} -> {})",
                    String.format("%,d", group.totalCount()), group.plans().size(),
                    group.logicalType(), group.startLabel(), group.endLabel());
        } else {
            log.info("Seeding {} relationships of type {} ({} -> {})",
                    String.format("%,d", group.totalCount()), group.logicalType(),
                    group.startLabel(), group.endLabel());
        }
    }

    private Map<String, Object> buildRelProps(RelationshipTypeInfo info, long index, Set<String> uniqueSet) {
        var props = new LinkedHashMap<String, Object>();
        if (info == null || info.getProperties() == null) {
            return props;
        }
        for (var property : info.getProperties()) {
            var mustBeUnique = uniqueSet.contains(property.name());
            var value = valueGenerator.generate(info.getName(), property, index, mustBeUnique);
            if (value != null) {
                props.put(property.name(), value);
            }
        }
        return props;
    }

    /**
     * Picks a {@code __seedId} uniformly at random across the union of {@code ranges} using
     * the supplied {@code rng}. The caller must pass the pre-computed {@code totalSize} to
     * avoid recomputing it per row.
     */
    private static long sampleSeedId(List<long[]> ranges, long totalSize, SplittableRandom rng) {
        var offset = rng.nextLong(totalSize);
        for (var range : ranges) {
            var size = range[1] - range[0];
            if (offset < size) {
                return range[0] + offset;
            }
            offset -= size;
        }
        throw new IllegalStateException("Sampling offset escaped range list — inconsistent plan state");
    }

    /**
     * Carves {@code ranges} into {@code n} equal-capacity sub-range lists. Ranges that straddle
     * a partition boundary are cut at the boundary point. The last part absorbs any remainder so
     * the total element count is preserved exactly.
     *
     * <p>Used by {@link #buildRelMatrix} to bucket the start and end {@code __seedId} spaces
     * into the row and column partitions of the rel matrix. Sampling within a partition then
     * yields seedIds guaranteed to belong to that bucket — no runtime {@code bucket(seedId)}
     * computation is ever required.
     */
    private static List<List<long[]>> splitRanges(List<long[]> ranges, int n) {
        var totalSize = 0L;
        for (var r : ranges) {
            totalSize += r[1] - r[0];
        }
        var baseSlot = totalSize / n;
        var extras = totalSize - baseSlot * n;       // round-robin remainder, first `extras` slots get +1

        var parts = new ArrayList<List<long[]>>(n);
        var rangeIdx = 0;
        var rangeOffset = 0L;

        for (int part = 0; part < n; part++) {
            var currentPart = new ArrayList<long[]>();
            var remaining = baseSlot + (part < extras ? 1L : 0L);

            while (remaining > 0 && rangeIdx < ranges.size()) {
                var range = ranges.get(rangeIdx);
                var available = (range[1] - range[0]) - rangeOffset;
                var take = Math.min(available, remaining);
                currentPart.add(new long[]{range[0] + rangeOffset, range[0] + rangeOffset + take});
                remaining -= take;
                rangeOffset += take;
                if (rangeOffset >= range[1] - range[0]) {
                    rangeIdx++;
                    rangeOffset = 0;
                }
            }
            parts.add(currentPart);
        }
        return parts;
    }

    // -------------------------------------- constraints / schema indexes

    private void createConstraintsPhase(Session session, List<ConstraintInfo> constraints) {
        if (constraints == null || constraints.isEmpty()) {
            return;
        }
        log.info("Replaying {} constraint(s)", constraints.size());
        var failures = new ArrayList<String>();
        for (var constraint : constraints) {
            var cypher = ConstraintDdl.build(constraint);
            if (cypher == null) {
                continue;
            }
            try {
                session.run(cypher).consume();
            } catch (Exception e) {
                log.warn("Failed to create constraint {}: {}", constraint.name(), e.getMessage());
                failures.add(constraint.name() + " — " + e.getMessage());
            }
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException("Failed to create " + failures.size() + " constraint(s):\n  - "
                    + String.join("\n  - ", failures));
        }
    }

    private void createSchemaIndexesPhase(Session session, List<IndexInfo> indexes) {
        if (options.skipNonKeyIndexes()) {
            return;
        }
        if (indexes == null || indexes.isEmpty()) {
            return;
        }
        log.info("Replaying {} schema index(es)", indexes.size());
        for (var index : indexes) {
            var cypher = IndexDdl.build(index);
            if (cypher == null) {
                continue;
            }
            try {
                session.run(cypher).consume();
            } catch (Exception e) {
                log.warn("Could not create index {}: {}", index.name(), e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------ helpers

    /**
     * Sums the sizes of a list of half-open intervals {@code [low, high)}, returning the total
     * number of {@code __seedId} values covered. Used to determine pool sizes for matrix
     * partitioning and per-cell relationship count distribution.
     */
    private static long totalRangeSize(List<long[]> ranges) {
        if (ranges == null) {
            return 0L;
        }
        return ranges.stream().mapToLong(r -> r[1] - r[0]).sum();
    }

    private static Map<String, LabelInfo> indexLabels(List<LabelInfo> labels) {
        var map = new LinkedHashMap<String, LabelInfo>(labels.size() * 2);
        for (var label : labels) {
            map.put(label.getName(), label);
        }
        return map;
    }

}
