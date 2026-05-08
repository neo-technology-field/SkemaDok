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
     *                       parameterised relationship type names into a single canonical entry
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

        doc.setRelationshipTypes(relTypeGrouper.group(collectRelationshipTypes(session, groupThreshold), groupThreshold));
        doc.setIndexes(collectIndexes(session));
        doc.setConstraints(collectConstraints(session));
        return doc;
    }

    private List<LabelInfo> collectLabels(Session session) {

        log.info("Collecting labels...");
        var labelSchema = collectLabelProperties(session);
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
    private LabelSchema collectLabelProperties(Session session) {

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
                        Collectors.mapping(r -> {
                            var typesValue = r.get("propertyTypes");
                            var types = typesValue.isNull() ? List.<String>of() : typesValue.asList(Value::asString);
                            return new RawPropertyRow(
                                    r.get("propertyName").asString(""),
                                    types,
                                    r.get("mandatory").asBoolean(false),
                                    r.get("labelCount").asInt()
                            );
                        }, Collectors.toList())
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
     *   <li>Full scan: one MATCH over all relationships, used when parameterised type groups are
     *       detected and the number of individual types would make per-type round trips impractical.</li>
     * </ul>
     */
    private List<RelationshipTypeInfo> collectRelationshipTypes(Session session, int groupThreshold) {
        log.info("Collecting relationship types...");
        // Group raw rows per rel type, then merge duplicates (same property name, different
        // mandatory/types across node-pair patterns). Relationship types are single-valued so
        // every row is treated as authoritative for mandatory — equivalent to labelCount=1.
        var propsByType = session.executeRead(tx -> tx.run("""
                        CALL db.schema.relTypeProperties()
                        YIELD relType, propertyName, propertyTypes, mandatory
                        RETURN relType, propertyName, propertyTypes, mandatory
                        ORDER BY relType, propertyName
                        """).list()).stream()
                .collect(Collectors.groupingBy(
                        r -> cleanRelType(r.get("relType").asString()),
                        LinkedHashMap::new,
                        Collectors.mapping(r -> {
                            var typesValue = r.get("propertyTypes");
                            var types = typesValue.isNull() ? List.<String>of() : typesValue.asList(Value::asString);
                            return new RawPropertyRow(
                                    r.get("propertyName").asString(""),
                                    types,
                                    r.get("mandatory").asBoolean(false),
                                    1   // rel types are single-valued; treat every row as authoritative
                            );
                        }, Collectors.toList())
                )).entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> mergePropertiesByName(e.getValue()),
                        (a, b) -> a,
                        LinkedHashMap::new
                ));

        var relTypeGrouper = new RelTypeGrouper();
        var useFullScan = relTypeGrouper.detectsGroups(propsByType.keySet(), groupThreshold);
        if (useFullScan) {
            log.info("Relationship strategy: full graph scan (parameterised groups detected)");
        } else {
            log.info("Relationship strategy: per-type queries ({} types)", propsByType.size());
        }

        var stats = useFullScan
                ? collectRelationshipStatsByFullScan(session)
                : collectRelationshipStatsPerType(session, propsByType.keySet());

        var result = propsByType.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new RelationshipTypeInfo(
                        e.getKey(),
                        stats.counts().getOrDefault(e.getKey(), 0L),
                        stats.connections().getOrDefault(e.getKey(), List.of()),
                        e.getValue()
                ))
                .toList();
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
     * Collects relationship counts and connectivity in a single full graph scan.
     * Used when parameterised type groups are detected, since the number of individual types
     * can reach tens of thousands, making per-type round trips impractical.
     */
    private RelationshipTypeStats collectRelationshipStatsByFullScan(Session session) {
        var counts = new HashMap<String, Long>();
        var connections = new HashMap<String, List<Connection>>();
        try {
            log.info("Running full relationship scan — this may take a while on large graphs...");
            var startTime = System.currentTimeMillis();
            var rowsByType = session.executeRead(tx -> tx.run("""
                            MATCH (start)-[relationship]->(end)
                            RETURN type(relationship) AS relationshipType,
                                   labels(start) AS startLabels,
                                   labels(end) AS endLabels,
                                   count(*) AS cnt
                            """).list()).stream()
                    .collect(Collectors.groupingBy(row -> row.get("relationshipType").asString()));
            rowsByType.forEach((relationshipType, rows) -> {
                var connectionList = buildConnectionList(rows);
                connections.put(relationshipType, connectionList);
                counts.put(relationshipType, connectionList.stream().mapToLong(Connection::count).sum());
            });
            log.info("Full scan complete: {} distinct types in {} ms", counts.size(), System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            log.warn("Could not collect relationship stats: {}", e.getMessage());
        }
        return new RelationshipTypeStats(counts, connections);
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
