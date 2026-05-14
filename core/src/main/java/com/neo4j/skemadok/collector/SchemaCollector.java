package com.neo4j.skemadok.collector;

import com.neo4j.skemadok.model.*;
import org.neo4j.driver.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Collects schema information from a Neo4j database using native Cypher procedures.
 * No APOC dependency. Handles privilege errors gracefully by returning partial data.
 */
public class SchemaCollector {

    private static final Logger log = LoggerFactory.getLogger(SchemaCollector.class);

    /**
     * Counts and connectivity patterns per relationship type, derived from actual graph data.
     */
    private record RelationshipTypeStats(
            Map<String, Long> counts,
            Map<String, List<Connection>> connections
    ) {
    }

    /**
     * A single row from db.schema.nodeTypeProperties() after UNWIND, carrying the original
     * nodeLabels size so Java can distinguish single-label rows (authoritative for mandatory)
     * from multi-label rows (mandatory flag describes the combination, not the individual label).
     */
    private record RawPropertyRow(String propertyName, List<String> types, boolean mandatory, int labelCount) {
    }

    /**
     * Result of label property collection: the merged property list per label, plus the set of
     * labels that appear in at least one single-label node combination (i.e. ever appear alone).
     */
    private record LabelSchema(Map<String, List<PropertyInfo>> properties, Set<String> appearsAlone) {
    }

    /**
     * Runs a full schema introspection against the target database.
     *
     * @param uri            Bolt URI (e.g. bolt://localhost:7687)
     * @param username       database username
     * @param password       database password
     * @param database       target database name
     * @param groupThreshold minimum number of instances required to collapse a family of
     *                       parameterised relationship type names into a single grouped entry
     * @return collected schema document; user annotations are added later via the UI
     */
    public SchemaDocument collect(String uri, String username, String password, String database, int groupThreshold) {

        try (var driver = GraphDatabase.driver(uri, AuthTokens.basic(username, password))) {
            driver.verifyConnectivity();
            log.info("Connected to '{}'", uri);
            var config = SessionConfig.builder().withDatabase(database).build();
            try (var session = driver.session(config)) {
                return detectSchema(session, uri, database, groupThreshold);
            }
        }
    }

    private SchemaDocument detectSchema(Session session, String uri, String database, int groupThreshold) {

        var doc = new SchemaDocument(uri, database, detectDatabaseVersion(session), Instant.now());
        doc.setNodeLabels(collectLabels(session));

        var relTypeGrouper = new RelTypeGrouper();

        doc.setRelationshipTypes(relTypeGrouper.group(collectRelationshipInformation(session, groupThreshold), groupThreshold));
        doc.setIndexes(collectIndexes(session));
        doc.setConstraints(collectConstraints(session));
        return doc;
    }

    private List<LabelInfo> collectLabels(Session session) {

        log.info("Collecting labels...");
        var labelSchema = collectLabelInformation(session);
        var labelPairs = collectLabelPairs(session);
        var labelInfos = new ArrayList<LabelInfo>();

        labelSchema.properties().keySet().forEach(label -> {
            var nodeCount = getLabelCount(session, label);
            var info = new LabelInfo(label, nodeCount,
                    labelPairs.get(label), labelSchema.properties().get(label));
            if (labelSchema.appearsAlone().contains(label)) {
                info.setRole(LabelRole.ENTITY);
            } else {
                info.setRole(LabelRole.TAG);
            }
            labelInfos.add(info);
        });

        log.info("done collecting, found {} labels", labelInfos.size());
        return labelInfos;
    }


    private long getLabelCount(Session session, String label) {

        try {
            return session.executeRead(tx -> tx.run(
                            String.format("""
                                    MATCH (n:%s) RETURN count(n) AS cnt
                                    """, label)).single())
                    .get("cnt").asLong();
        } catch (Exception e) {
            log.error("Error while collecting labels for '{}'", label, e);
            return -1;
        }
    }

    /**
     * Retrieves an adjacency map of node labels that coexist on the same nodes.
     *
     * @param session The Neo4j {@link Session} used to execute the query.
     * @return A {@link Map} where keys are labels and values are lists of associated labels.
     */
    private Map<String, List<String>> collectLabelPairs(Session session) {
        try {
            return session.executeRead(tx -> tx.run("""
                            CALL db.schema.nodeTypeProperties() YIELD nodeLabels
                            WITH DISTINCT nodeLabels WHERE size(nodeLabels) > 1
                            RETURN nodeLabels
                            """).stream()
                    .map(record -> record.get("nodeLabels").asList(Value::asString))
                    .flatMap(labels -> labels.stream()
                            .flatMap(current -> labels.stream()
                                    .filter(other -> !other.equals(current))
                                    .map(other -> Map.entry(current, other))))
                    .collect(Collectors.groupingBy(
                            Map.Entry::getKey,
                            Collectors.mapping(
                                    Map.Entry::getValue,
                                    Collectors.collectingAndThen(Collectors.toSet(), ArrayList::new)
                            )
                    )));
        } catch (Exception e) {
            log.error("Failed to collect label pairs from Neo4j: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }


    /**
     * Returns the Neo4j server version string from dbms.components().
     * Falls back to "unknown" on any error (privilege, legacy version, etc.).
     */
    private String detectDatabaseVersion(Session session) {
        try {
            var version = session.executeRead(tx -> tx.run("""
                            CALL dbms.components()
                            YIELD name, versions
                            WHERE name = 'Neo4j Kernel'
                            RETURN versions[0] AS version
                            """).single())
                    .get("version").asString("unknown");
            log.info("Neo4j version: {}", version);
            return version;
        } catch (Exception e) {
            log.warn("Unable to collect database version, using 'unknown', error: {}", e.getMessage());
            return "unknown";
        }
    }

    /**
     * Collects properties per label via db.schema.nodeTypeProperties(), returning merged
     * PropertyInfo lists and the set of labels that appear in at least one single-label
     * node combination.
     *
     * <p>The procedure returns one row per (nodeLabels[], propertyName) combination. After
     * UNWIND the same property name can appear multiple times for one label — once per
     * nodeLabels combination containing it. The {@code mandatory} flag is only trustworthy
     * when {@code size(nodeLabels) == 1}; for multi-label combinations it describes the
     * combination, not the individual label. We carry {@code labelCount} through so Java
     * can apply the right nullable logic.
     *
     * <p>A label that never appears in a single-label combination is a structural subtype
     * tag (e.g. {@code :PressRelease}, {@code :Admin}) and is auto-classified as
     * {@link LabelRole#TAG} by the caller.
     */
    private LabelSchema collectLabelInformation(Session session) {

        var rawByLabel = session.executeRead(tx -> tx.run("""
                        CALL db.schema.nodeTypeProperties()
                        YIELD nodeLabels, propertyName, propertyTypes, mandatory
                        UNWIND nodeLabels AS label
                        RETURN label, propertyName, propertyTypes, mandatory, size(nodeLabels) AS labelCount
                        ORDER BY label, propertyName
                        """).list()).stream()
                .collect(Collectors.groupingBy(
                        r -> r.get("label").asString(),
                        LinkedHashMap::new,
                        Collectors.mapping(r -> toRawPropertyRow(r, r.get("labelCount").asInt()), Collectors.toList())
                ));

        var properties = new LinkedHashMap<String, List<PropertyInfo>>();
        var appearsAlone = new HashSet<String>();

        for (var entry : rawByLabel.entrySet()) {
            var label = entry.getKey();
            var rows = entry.getValue();
            if (rows.stream().anyMatch(r -> r.labelCount() == 1)) {
                appearsAlone.add(label);
            }
            properties.put(label, mergePropertiesByName(rows));
        }

        return new LabelSchema(properties, appearsAlone);
    }


    /**
     * Maps a driver record from db.schema.nodeTypeProperties() or db.schema.relTypeProperties()
     * to a {@link RawPropertyRow}.
     *
     * @param labelCount the number of labels in the originating nodeLabels combination, or 1 for
     *                   relationship type rows (which are always single-valued and fully authoritative).
     */
    private RawPropertyRow toRawPropertyRow(org.neo4j.driver.Record record, int labelCount) {
        var typesValue = record.get("propertyTypes");
        var types = typesValue.isNull() ? List.<String>of() : typesValue.asList(Value::asString);
        return new RawPropertyRow(
                record.get("propertyName").asString(""),
                types,
                record.get("mandatory").asBoolean(false),
                labelCount
        );
    }

    /**
     * Deduplicates raw property rows that share the same name after UNWIND, merging their
     * type lists and resolving nullable from the most authoritative available source.
     *
     * <p>Nullable is {@code false} only when a single-label row ({@code labelCount == 1})
     * explicitly says {@code mandatory = true}. If no single-label row exists for a property
     * (it only appears in multi-label combinations) the mandatory flag cannot be trusted for
     * the individual label, so the property is conservatively marked nullable.
     */
    private List<PropertyInfo> mergePropertiesByName(List<RawPropertyRow> rows) {

        var types = new LinkedHashMap<String, Set<String>>();
        var singleMandatory = new HashMap<String, Boolean>();

        for (var row : rows) {
            if (row.propertyName().isBlank()) continue;
            types.computeIfAbsent(row.propertyName(), k -> new LinkedHashSet<>())
                    .addAll(row.types());
            if (row.labelCount() == 1) {
                // AND-fold: nullable if any single-label row says not mandatory
                singleMandatory.merge(row.propertyName(), row.mandatory(), Boolean::logicalAnd);
            }
        }

        return types.entrySet().stream()
                .map(e -> {
                    var name = e.getKey();
                    var sorted = e.getValue().stream().sorted().toList();
                    boolean nullable = !Boolean.TRUE.equals(singleMandatory.get(name));
                    return new PropertyInfo(name, sorted, nullable);
                })
                .toList();
    }

    /**
     * Collects relationship type properties via db.schema.relTypeProperties(), with the same
     * per-(type, property) deduplication applied to label properties.
     *
     * <p>Connectivity and counts come from actual graph data rather than db.schema.visualization(),
     * which can return stale or over-approximate results. Two code paths are used:
     * <ul>
     *   <li>Per-type queries (default): one MATCH per type, accurate and targeted.</li>
     *   <li>Per-group scans: one MATCH per detected parameterised group, used when the number of
     *       individual types would make per-type round trips impractical. Non-parameterised types
     *       still use per-type queries.</li>
     * </ul>
     */
    private List<RelationshipTypeInfo> collectRelationshipInformation(Session session, int groupThreshold) {
        log.info("Collecting ovrview of relationships and their property types...");
        var rawByType = session.executeRead(tx -> tx.run("""
                        CALL db.schema.relTypeProperties()
                        YIELD relType, propertyName, propertyTypes, mandatory
                        RETURN relType, propertyName, propertyTypes, mandatory
                        ORDER BY relType, propertyName
                        """).list()).stream()
                .collect(Collectors.groupingBy(
                        r -> cleanRelType(r.get("relType").asString()),
                        LinkedHashMap::new,
                        // rel types are single-valued so every row is authoritative for mandatory — labelCount=1
                        Collectors.mapping(r -> toRawPropertyRow(r, 1), Collectors.toList())
                ));

        log.info("found {} distinct relationship types", rawByType.size());

        var propsByType = new LinkedHashMap<String, List<PropertyInfo>>();
        rawByType.forEach((relType, rows) ->
                propsByType.put(relType, rows.stream()
                        .filter(r -> !r.propertyName().isBlank())
                        .map(r -> new PropertyInfo(r.propertyName(), r.types().stream().sorted().toList(), !r.mandatory()))
                        .toList()));

        var relTypeGrouper = new RelTypeGrouper();
        var groupSizes = relTypeGrouper.detectedGroupSizes(propsByType.keySet(), groupThreshold);

        List<RelationshipTypeInfo> result;
        if (groupSizes.isEmpty()) {
            log.info("Relationship strategy: per-type queries ({} types)", propsByType.size());
            var stats = collectRelationshipStatsPerType(session, propsByType.keySet());
            result = propsByType.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(e -> new RelationshipTypeInfo(
                            e.getKey(),
                            stats.counts().getOrDefault(e.getKey(), 0L),
                            stats.connections().getOrDefault(e.getKey(), List.of()),
                            e.getValue()
                    ))
                    .toList();
        } else {
            var totalVariants = groupSizes.values().stream().mapToInt(Integer::intValue).sum();
            log.info("Relationship strategy: per-group scan ({} parameterised groups, {} total variants)",
                    groupSizes.size(), totalVariants);
            groupSizes.forEach((base, size) ->
                    log.info("  Parameterised group: {} ({} variants)", base, size));

            var grouped = new ArrayList<>(
                    collectGroupedRelationshipTypes(session, groupSizes, propsByType, relTypeGrouper));

            var ungroupedNames = propsByType.keySet().stream()
                    .filter(name -> groupSizes.keySet().stream()
                            .noneMatch(base -> name.startsWith(base + "_")))
                    .collect(Collectors.toSet());

            if (!ungroupedNames.isEmpty()) {
                log.info("Collecting stats for {} non-parameterised types via per-type queries",
                        ungroupedNames.size());
                var ungroupedStats = collectRelationshipStatsPerType(session, ungroupedNames);
                ungroupedNames.stream()
                        .sorted()
                        .forEach(name -> grouped.add(new RelationshipTypeInfo(
                                name,
                                ungroupedStats.counts().getOrDefault(name, 0L),
                                ungroupedStats.connections().getOrDefault(name, List.of()),
                                propsByType.get(name))));
            }

            result = grouped;
        }

        log.info("done collecting, found {} relationship types", result.size());
        return result;
    }

    /**
     * Collects per-type relationship counts and connectivity by running one MATCH query per type.
     * Each query returns the distinct (startLabelSet, endLabelSet) combinations with their counts,
     * preserving multi-label node groupings rather than flattening to individual label pairs.
     */
    private RelationshipTypeStats collectRelationshipStatsPerType(Session session, Set<String> relationshipTypes) {
        var counts = new HashMap<String, Long>();
        var connections = new HashMap<String, List<Connection>>();
        for (var relationshipType : relationshipTypes) {
            try {
                var escaped = relationshipType.replace("`", "``");
                var rows = session.executeRead(tx -> tx.run(
                        "MATCH (start)-[:`" + escaped + "`]->(end) " +
                                "RETURN labels(start) AS startLabels, labels(end) AS endLabels, count(*) AS cnt"
                ).list());
                var connectionList = buildConnectionList(rows);
                connections.put(relationshipType, connectionList);
                counts.put(relationshipType, connectionList.stream().mapToLong(Connection::count).sum());
            } catch (Exception e) {
                log.warn("Could not collect stats for relationship type {}: {}", relationshipType, e.getMessage());
            }
        }
        log.info("Per-type stats complete: {} types", counts.size());
        return new RelationshipTypeStats(counts, connections);
    }

    /**
     * Collects relationship data for parameterised groups by running one full-graph scan per
     * group, filtered by the group's underscore-delimited prefix. A single scan per group
     * bounds the server-side EagerAggregation to the connection patterns of that group
     * (typically a handful of rows) rather than aggregating across all relationship types at
     * once, which would require memory proportional to the entire type × connection-pattern
     * cardinality.
     */
    private List<RelationshipTypeInfo> collectGroupedRelationshipTypes(
            Session session,
            Map<String, Integer> groupSizes,
            Map<String, List<PropertyInfo>> propsByType,
            RelTypeGrouper relTypeGrouper) {

        var result = new ArrayList<RelationshipTypeInfo>();
        for (var entry : groupSizes.entrySet()) {
            var base = entry.getKey();
            var variantCount = entry.getValue();
            var prefix = base + "_";
            log.info("Scanning parameterised group '{}_*' ({} variants)...", base, variantCount);
            var startTime = System.currentTimeMillis();
            try {
                var rows = session.executeRead(tx -> tx.run("""
                        MATCH (start)-[r]->(end)
                        WHERE type(r) STARTS WITH $prefix
                        RETURN labels(start) AS startLabels,
                               labels(end) AS endLabels,
                               count(*) AS cnt
                        """, Map.of("prefix", prefix)).list());

                var connections = buildConnectionList(rows);
                var totalCount = connections.stream().mapToLong(Connection::count).sum();

                var memberNames = propsByType.keySet().stream()
                        .filter(name -> name.startsWith(prefix))
                        .sorted()
                        .toList();
                if (memberNames.isEmpty()) {
                    log.warn("Group '{}_*' has no member types in schema properties — skipping", base);
                    continue;
                }

                var mergedPropsMap = new LinkedHashMap<String, PropertyInfo>();
                for (var memberName : memberNames) {
                    for (var p : propsByType.getOrDefault(memberName, List.of())) {
                        mergedPropsMap.merge(p.name(), p,
                                (existing, incoming) -> incoming.nullable() ? existing : existing.withNullable(false));
                    }
                }

                result.add(relTypeGrouper.buildGrouped(
                        base, memberNames, new ArrayList<>(mergedPropsMap.values()), totalCount, connections));

                log.info("Group '{}_*' complete: {} instances, {} connection patterns, {} ms",
                        base, memberNames.size(), connections.size(), System.currentTimeMillis() - startTime);
            } catch (Exception e) {
                log.warn("Could not scan group '{}_*': {}", base, e.getMessage());
            }
        }
        return result;
    }

    /**
     * Converts query rows into a sorted list of {@link Connection} entries.
     *
     * <p>Each row carries the full label sets of start and end nodes along with a count. Rows with
     * the same normalised (startLabels, endLabels) pair are merged by summing counts — this can
     * happen when the full-scan query groups differently from the per-type path.
     */
    private List<Connection> buildConnectionList(List<org.neo4j.driver.Record> rows) {
        // Use a zero-count sentinel as map key so equal label-set pairs collapse regardless of count.
        var countsByPattern = new LinkedHashMap<Connection, Long>();
        for (var row : rows) {
            var startLabels = row.get("startLabels").asList(Value::asString);
            var endLabels = row.get("endLabels").asList(Value::asString);
            var rowCount = row.get("cnt").asLong();
            var key = new Connection(startLabels, endLabels, 0L);
            countsByPattern.merge(key, rowCount, Long::sum);
        }
        return countsByPattern.entrySet().stream()
                .map(entry -> new Connection(
                        entry.getKey().startLabels(),
                        entry.getKey().endLabels(),
                        entry.getValue()))
                .sorted(Comparator.comparing((Connection conn) -> conn.startLabels().toString())
                        .thenComparing(conn -> conn.endLabels().toString()))
                .toList();
    }

    private List<IndexInfo> collectIndexes(Session session) {
        log.info("Collecting indexes...");
        try {
            var indexes = session.executeRead(tx -> tx.run("""
                            SHOW INDEXES
                            YIELD name, type, entityType, labelsOrTypes, properties, state, readCount, options
                            WHERE state <> 'FAILED'
                            RETURN name, type, entityType, labelsOrTypes, properties, state, readCount, options
                            ORDER BY name
                            """).list()).stream()
                    .map(r -> new IndexInfo(
                            r.get("name").asString(),
                            r.get("type").asString(),
                            r.get("entityType").asString().toLowerCase(),
                            asSortedStringList(r.get("labelsOrTypes")),
                            asSortedStringList(r.get("properties")),
                            r.get("state").asString(),
                            r.get("readCount").asLong(0),
                            formatIndexConfig(r.get("options"))
                    ))
                    .toList();
            log.info("done collecting, found {} indexes", indexes.size());
            return indexes;
        } catch (Exception e) {
            log.warn("Could not collect indexes: {}", e.getMessage());
            return List.of();
        }
    }

    private List<ConstraintInfo> collectConstraints(Session session) {
        log.info("Collecting constraints...");
        try {
            var constraints = session.executeRead(tx -> tx.run("""
                            SHOW CONSTRAINTS
                            YIELD name, type, entityType, labelsOrTypes, properties
                            RETURN name, type, entityType, labelsOrTypes, properties
                            ORDER BY name
                            """).list()).stream()
                    .map(r -> new ConstraintInfo(
                            r.get("name").asString(),
                            r.get("type").asString(),
                            r.get("entityType").asString().toLowerCase(),
                            asSortedStringList(r.get("labelsOrTypes")),
                            asSortedStringList(r.get("properties"))
                    ))
                    .toList();
            log.info("done collecting, found {} constraints", constraints.size());
            return constraints;
        } catch (Exception e) {
            log.warn("Could not collect constraints: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Strips the Neo4j internal type decoration (e.g. {@code :`TYPE_NAME`} → {@code TYPE_NAME}).
     */
    private String cleanRelType(String raw) {
        return raw.replaceAll("[:`]", "");
    }

    /**
     * Converts a driver {@link Value} to a sorted String list.
     * Returns an empty list for NULL — SHOW INDEXES returns NULL for {@code labelsOrTypes}
     * and {@code properties} on LOOKUP-type indexes.
     */
    private List<String> asSortedStringList(Value value) {
        if (value.isNull()) return List.of();
        return value.asList(Value::asString).stream().sorted().toList();
    }

    /**
     * Extracts the {@code indexConfig} inner map from the {@code options} column and formats it
     * as a sorted {@code key=value} string. Returns an empty string for RANGE indexes (empty config)
     * and for LOOKUP indexes (null options).
     */
    private String formatIndexConfig(Value options) {
        if (options.isNull()) return "";
        var config = options.get("indexConfig");
        if (config.isNull()) return "";
        return config.asMap().entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .sorted()
                .collect(java.util.stream.Collectors.joining(", "));
    }
}
