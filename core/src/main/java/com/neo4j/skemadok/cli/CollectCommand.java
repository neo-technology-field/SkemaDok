package com.neo4j.skemadok.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neo4j.skemadok.collector.RelTypeGrouper;
import com.neo4j.skemadok.collector.SchemaCollector;
import com.neo4j.skemadok.model.RelationshipTypeInfo;
import com.neo4j.skemadok.model.SchemaDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Connects to Neo4j, introspects the schema, and writes the result to a JSON file.
 * The output file is human-readable so the customer can review what is being shared.
 */
@Command(
        name = "collect",
        description = "Connect to Neo4j and collect schema information into a JSON file.",
        mixinStandardHelpOptions = true
)
public class CollectCommand implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(CollectCommand.class);

    @Option(names = {"-H", "--host"}, required = true,
            description = "Neo4j Bolt URI (e.g. bolt://localhost:7687 or neo4j+s://xxxxx.databases.neo4j.io)")
    private String uri;

    @Option(names = {"-u", "--username"}, required = true, description = "Database username")
    private String username;

    @Option(names = {"-p", "--password"}, required = true, description = "Database password",
            interactive = true, arity = "0..1", echo = false)
    private char[] password;

    @Option(names = {"-d", "--database"}, defaultValue = "neo4j",
            description = "Target database name (default: ${DEFAULT-VALUE})")
    private String database;

    @Option(names = {"-o", "--output"}, defaultValue = "schema.json",
            description = "Output file path (default: ${DEFAULT-VALUE})")
    private Path outputFile;

    @Option(names = {"--group-threshold"},
            defaultValue = "" + RelTypeGrouper.DEFAULT_THRESHOLD,
            description = "Minimum number of instances required to collapse a family of "
                    + "parameterised relationship type names (e.g. REL_2024_01, REL_2024_02) "
                    + "into a single canonical entry. Lower values group more aggressively; "
                    + "use 0 to disable grouping entirely. (default: ${DEFAULT-VALUE})")
    private int groupThreshold;

    private final SchemaCollector collector;
    private final ObjectMapper objectMapper;

    public CollectCommand(SchemaCollector collector, ObjectMapper objectMapper) {
        this.collector = collector;
        this.objectMapper = objectMapper;
    }

    @Override
    public Integer call() {
        log.info("Connecting to {} (database: {})…", uri, database);
        try {
            SchemaDocument doc = collector.collect(uri, username, new String(password), database, groupThreshold);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputFile.toFile(), doc);

            long grouped = doc.getRelationshipTypes().stream().filter(RelationshipTypeInfo::isParameterized).count();
            long raw = doc.getRelationshipTypes().stream()
                    .mapToLong(r -> r.isParameterized() ? r.getInstances().size() : 1).sum();

            if (grouped > 0) {
                log.info("Collected: {} labels, {} relationship types ({} groups collapsed from {} raw types, threshold={})",
                        doc.getNodeLabels().size(), doc.getRelationshipTypes().size(), grouped, raw, groupThreshold);
            } else {
                log.info("Collected: {} labels, {} relationship types, {} indexes, {} constraints",
                        doc.getNodeLabels().size(), doc.getRelationshipTypes().size(),
                        doc.getIndexes().size(), doc.getConstraints().size());
            }
            log.info("Output: {}", outputFile.toAbsolutePath());
            return 0;
        } catch (Exception e) {
            log.error("Collection failed: {}", e.getMessage());
            return 1;
        }
    }
}
