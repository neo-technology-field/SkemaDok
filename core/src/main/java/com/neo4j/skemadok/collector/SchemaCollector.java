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
     * @param groupThreshold minimum number of instances required to collapse a group of
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
        doc.setRelationshipTypes(collectRelationshipInformation(session, groupThreshold));
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

        inferTaggedEntities(labelInfos);

        log.info("done collecting, found {} labels", labelInfos.size());
        return labelInfos;
    }

    /**
     * Auto-populates {@code taggedEntities} for TAG labels whose host entity is unambiguous:
     * exactly one co-label with ENTITY role means there is no decision to defer to the user.
     *
     * <p>TAGs with zero or multiple ENTITY co-labels are left empty for manual annotation.
     * Existing (non-empty) values are never overwritten, preserving user annotations carried
     * forward via SchemaMerger.
     */
    private void inferTaggedEntities(List<LabelInfo> labelInfos) {

        var roleByLabel = labelInfos.stream()
                .collect(Collectors.toMap(LabelInfo::getName, LabelInfo::getRole));

        for (var info : labelInfos) {
            if (info.getRole() != LabelRole.TAG || !info.getTaggedEntities().isEmpty()) {
                continue;
            }
            var entityCoLabels = Objects.requireNonNullElse(info.getCoLabels(), List.<String>of())
                    .stream()
                    .filter(coLabel -> roleByLabel.get(coLabel) == LabelRole.ENTITY)
                    .toList();
            if (entityCoLabels.size() == 1) {
                info.setTaggedEntities(entityCoLabels);
            }
        }
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
     * Collects properties per label, using {@code db.labels()} as the authoritative label
     * inventory and {@code db.schema.nodeTypeProperties()} for property metadata.
     *
     * <p>The property procedure only emits rows for (nodeLabels[] × propertyName) combinations
     * that have at least one unique property type. TAG labels whose properties are all covered
     * by a co-occurring ENTITY label produce no rows and would therefore be silently omitted if
     * we relied on the procedure alone as the label source. {@code db.labels()} closes that gap:
     * any label absent from the property result is seeded with an empty property list.
     *
     * <p>The {@code mandatory} flag from the procedure is only trustworthy when
     * {@code size(nodeLabels) == 1}; for multi-label combinations it describes the combination,
     * not the individual label.
     */
    private LabelSchema collectLabelInformation(Session session) {

        var allLabelNames = session.executeRead(tx -> tx.run("""
                        CALL db.labels() YIELD label RETURN label ORDER BY label
                        """).list()).stream()
                .map(r -> r.get("label").asString())
                .toList();

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

        // Labels known to the token store but absent from the property procedure have no
        // unique properties — seed them so they are not silently dropped from the result.
        for (var label : allLabelNames) {
            rawByLabel.putIfAbsent(label, List.of());
        }

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
            if (row.propertyName().isBlank()) {
                continue;
            }
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
     * Collects relationship type metadata in two phases to avoid the global
     * {@code db.schema.relTypeProperties()} scan, which is prohibitively slow on large schemas.
     *
     * <h3>Phase 1 — Name discovery</h3>
     * {@code CALL db.relationshipTypes()} returns all type names from the token registry in
     * milliseconds, regardless of data volume. Group detection runs on the name list alone.
     *
     * <h3>Phase 2a — Non-parameterised types</h3>
     * Each plain type is scanned individually: one query for count + connectivity, one for
     * property schema from a bounded sample.
     *
     * <h3>Phase 2b — Parameterised groups</h3>
     * Each group is sampled via a small set of representative member types that covers all
     * variable slots with low cardinality (<10 distinct values).
     */
    private List<RelationshipTypeInfo> collectRelationshipInformation(Session session, int groupThreshold) {
        log.info("Collecting relationship types...");

        var allTypeNames = session.executeRead(tx -> tx.run("""
                        CALL db.relationshipTypes()
                        YIELD relationshipType
                        RETURN relationshipType
                        ORDER BY relationshipType
                        """).list()).stream()
                .map(r -> r.get("relationshipType").asString())
                .toList();

        log.info("found {} distinct relationship types", allTypeNames.size());

        var grouper = new RelTypeGrouper(allTypeNames, groupThreshold);

        var result = new ArrayList<RelationshipTypeInfo>();

        if (grouper.getGroups().isEmpty()) {
            log.info("Relationship strategy: per-type queries ({} types)", allTypeNames.size());
            result = collectPlainTypes(session, allTypeNames);
        } else {
            result = collectDynamicTypes(session, grouper);
        }

        result.sort(Comparator.comparing(RelationshipTypeInfo::getName));
        log.info("done collecting, found {} relationship type entries", result.size());
        return result;
    }

    private ArrayList<RelationshipTypeInfo> collectDynamicTypes(Session session, RelTypeGrouper grouper) {

        var groups = grouper.getGroups();
        log.info("Relationship strategy: representative sampling ({} parameterised groups, {} total variants)",
                groups.size(), groups.values().stream().mapToInt(List::size).sum());
        groups.forEach((base, members) ->
                log.info("  Group: {} ({} variants)", base, members.size()));

        var result = new ArrayList<RelationshipTypeInfo>();
        if (!grouper.getUngroupedNames().isEmpty()) {
            log.info("Collecting {} non-parameterised types...", grouper.getUngroupedNames().size());
            result = collectPlainTypes(session, grouper.getUngroupedNames());
        }

        int i = 0;
        for (var entry : groups.entrySet()) {
            log.info("Collecting group {} of {}: '{}' ({} variants)",
                    ++i, groups.size(), entry.getKey(), entry.getValue().size());
            try {
                var grouped = collectGroupedRelType(session, entry.getKey(), entry.getValue());
                // Trailing _ marks this as a parameterised group, preventing name collision
                // with any plain rel type that shares the same base (e.g. FOO_BASE and FOO_BASE_*)
                result.add(new RelationshipTypeInfo(
                        grouped.getName() + "_",
                        grouped.getCount(),
                        grouped.getConnections(),
                        grouped.getProperties(),
                        grouped.getTypeParameters(),
                        grouped.getInstances()));
            } catch (Exception e) {
                log.warn("Could not collect group '{}': {}", entry.getKey(), e.getMessage());
            }
        }
        return result;
    }

    /**
     * Collects individual non-parameterised types into {@code result}, logging progress every
     * 10 types so the user can see activity during a potentially long loop.
     */
    private ArrayList<RelationshipTypeInfo> collectPlainTypes(Session session, List<String> typeNames) {
        var result = new ArrayList<RelationshipTypeInfo>();
        int logInterval = Math.max(1, typeNames.size() / 10);
        for (int i = 0; i < typeNames.size(); i++) {
            if (i % logInterval == 0) {
                log.info("  done {} of {}", i + 1, typeNames.size());
            }
            try {
                result.add(collectSingleRelType(session, typeNames.get(i)));
            } catch (Exception e) {
                log.warn("Could not collect stats for relationship type {}: {}",
                        typeNames.get(i), e.getMessage());
            }
        }
        return result;
    }

    /**
     * Collects full metadata for a single non-parameterised relationship type.
     * Count and connectivity come from an exact MATCH; property schema is sampled
     * from up to 1000 relationships using {@code valueType()} for type inference.
     *
     * <p>{@code nullable} is exact when the total count fits within the 1000-row sample;
     * otherwise it conservatively defaults to {@code true}.
     */
    private RelationshipTypeInfo collectSingleRelType(Session session, String typeName) {

        var escaped = typeName.replace("`", "``");

        var rows = session.executeRead(tx -> tx.run(
                "MATCH (s)-[r:`" + escaped + "`]->(e) " +
                        "RETURN labels(s) AS startLabels, labels(e) AS endLabels, count(*) AS cnt"
        ).list());
        var connections = buildConnectionList(rows);
        var totalCount = connections.stream().mapToLong(Connection::count).sum();

        var propRows = session.executeRead(tx -> tx.run(
                "MATCH ()-[r:`" + escaped + "`]->() WITH r LIMIT 1000 " +
                        "UNWIND keys(r) AS propKey " +
                        "RETURN propKey, collect(DISTINCT valueType(r[propKey])) AS types, count(*) AS seen"
        ).list());

        var properties = propRows.stream()
                .filter(r -> !r.get("propKey").asString().isBlank())
                .map(r -> {
                    var propName = r.get("propKey").asString();
                    var types = r.get("types").asList(Value::asString).stream().sorted().toList();
                    var seen = r.get("seen").asLong();
                    var nullable = totalCount > 1000 || seen < totalCount;
                    return new PropertyInfo(propName, types, nullable);
                })
                .toList();

        return new RelationshipTypeInfo(typeName, totalCount, connections, properties);
    }

    /**
     * Selects representative type names for schema sampling of a parameterised group.
     *
     * <p>For every variable slot with fewer than 10 distinct values, one representative is
     * selected per distinct value at that slot — these are the semantically meaningful
     * distinctions that may carry different properties or connectivity. If no slot qualifies,
     * the first three alphabetically are used.
     *
     * @param actualBase  stable prefix, e.g. {@code COMP_HAS_COSTS_FOR_PROD}
     * @param memberNames sorted list of all member type names in this segment-count bucket
     */
    private Collection<String> selectGroupRepresentatives(String actualBase, List<String> memberNames) {

        int baseSegCount = actualBase.split("_").length;
        int varCount = memberNames.isEmpty() ? 0 : memberNames.getFirst().split("_").length - baseSegCount;

        var representatives = new LinkedHashSet<String>();

        for (int slot = 0; slot < varCount; slot++) {
            final int segIdx = baseSegCount + slot;
            var distinctValues = memberNames.stream()
                    .filter(n -> n.split("_").length > segIdx)
                    .map(n -> n.split("_")[segIdx])
                    .distinct()
                    .toList();

            if (distinctValues.size() < 10) {
                for (var value : distinctValues) {
                    memberNames.stream()
                            .filter(n -> n.split("_").length > segIdx
                                    && n.split("_")[segIdx].equals(value))
                            .findFirst()
                            .ifPresent(representatives::add);
                }
            }
        }

        if (representatives.isEmpty()) {
            memberNames.stream().limit(3).forEach(representatives::add);
        }

        return representatives;
    }

    /**
     * Collects schema for a parameterised group by sampling representative members.
     * Count is the number of distinct variants, not a relationship count —  the total
     * relationship count for thousands of variants would require an expensive full scan.
     * Property {@code nullable} defaults to {@code true}; sampling cannot give a reliable answer.
     *
     * @param actualBase  stable base, e.g. {@code COMP_HAS_COSTS_FOR_PROD}
     * @param memberNames all member type names for this segment-count bucket
     */
    private RelationshipTypeInfo collectGroupedRelType(
            Session session,
            String actualBase, List<String> memberNames) {

        var representatives = selectGroupRepresentatives(actualBase, memberNames);
        log.info("Sampling {} representative(s) for group '{}'", representatives.size(), actualBase);

        var seenConnectionKeys = new LinkedHashSet<String>();
        var mergedConnections = new ArrayList<Connection>();
        var mergedProps = new LinkedHashMap<String, PropertyInfo>();

        for (var representative : representatives) {
            var escaped = representative.replace("`", "``");
            try {
                var connRows = session.executeRead(tx -> tx.run(
                        "MATCH (s)-[r:`" + escaped + "`]->(e) " +
                                "RETURN DISTINCT labels(s) AS startLabels, labels(e) AS endLabels"
                ).list());
                for (var row : connRows) {
                    var startLabels = row.get("startLabels").asList(Value::asString);
                    var endLabels = row.get("endLabels").asList(Value::asString);
                    var key = startLabels + "->" + endLabels;
                    if (seenConnectionKeys.add(key)) {
                        mergedConnections.add(new Connection(startLabels, endLabels, 0L));
                    }
                }

                var propRows = session.executeRead(tx -> tx.run(
                        "MATCH ()-[r:`" + escaped + "`]->() WITH r LIMIT 1000 " +
                                "UNWIND keys(r) AS propKey " +
                                "RETURN propKey, collect(DISTINCT valueType(r[propKey])) AS types"
                ).list());
                for (var row : propRows) {
                    var propName = row.get("propKey").asString();
                    if (propName.isBlank()) {
                        continue;
                    }
                    var types = row.get("types").asList(Value::asString).stream().sorted().toList();
                    mergedProps.merge(propName, new PropertyInfo(propName, types, true),
                            (existing, incoming) -> {
                                var merged = new ArrayList<>(existing.types());
                                merged.addAll(incoming.types());
                                return new PropertyInfo(propName, merged.stream().distinct().sorted().toList(), true);
                            });
                }
            } catch (Exception e) {
                log.warn("Could not sample representative '{}' for group '{}': {}",
                        representative, actualBase, e.getMessage());
            }
        }

        var sortedConnections = mergedConnections.stream()
                .sorted(Comparator.comparing((Connection c) -> c.startLabels().toString())
                        .thenComparing(c -> c.endLabels().toString()))
                .toList();

        return RelTypeGrouper.buildGrouped(
                actualBase, memberNames, List.copyOf(mergedProps.values()),
                memberNames.size(), sortedConnections);
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
     * Converts a driver {@link Value} to a sorted String list.
     * Returns an empty list for NULL — SHOW INDEXES returns NULL for {@code labelsOrTypes}
     * and {@code properties} on LOOKUP-type indexes.
     */
    private List<String> asSortedStringList(Value value) {
        if (value.isNull()) {
            return List.of();
        }
        return value.asList(Value::asString).stream().sorted().toList();
    }

    /**
     * Extracts the {@code indexConfig} inner map from the {@code options} column and formats it
     * as a sorted {@code key=value} string. Returns an empty string for RANGE indexes (empty config)
     * and for LOOKUP indexes (null options).
     */
    private String formatIndexConfig(Value options) {
        if (options.isNull()) {
            return "";
        }
        var config = options.get("indexConfig");
        if (config.isNull()) {
            return "";
        }
        return config.asMap().entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .sorted()
                .collect(java.util.stream.Collectors.joining(", "));
    }
}
