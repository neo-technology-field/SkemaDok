package com.neo4j.skemadok.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neo4j.skemadok.model.SchemaDocument;
import com.neo4j.skemadok.seeder.SchemaSeeder;
import com.neo4j.skemadok.seeder.SeedOptions;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.GraphDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Materialises a Neo4j database that mirrors the topology of an existing {@code schema.json}.
 */
@Command(
        name = "seed",
        description = "Rebuild a Neo4j database from a captured schema.json (with optional scale factor).",
        mixinStandardHelpOptions = true
)
public class SeedCommand implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(SeedCommand.class);

    @Parameters(index = "0", description = "Path to the schema.json file to replay")
    private Path schemaFile;

    @Option(names = {"-a", "--uri"},
            description = "Neo4j Bolt URI (e.g. bolt://localhost:7687). Required unless --dry-run.")
    private String uri;

    @Option(names = {"-u", "--username"},
            description = "Database username. Required unless --dry-run.")
    private String username;

    @Option(names = {"-p", "--password"},
            description = "Database password. Required unless --dry-run.",
            interactive = true, arity = "0..1", echo = false)
    private char[] password;

    @Option(names = {"-d", "--database"}, required = true,
            description = "Target database name. Required — there is no default.")
    private String database;

    @Option(names = {"-s", "--scale"}, defaultValue = "1.0",
            description = "Multiplier applied to every node and relationship count. "
                    + "Use 0.001 for a small dev slice, 10 to stress-test. (default: ${DEFAULT-VALUE})")
    private double scale;

    @Option(names = "--drop",
            description = "Delete all existing nodes, relationships, constraints and indexes before seeding.")
    private boolean drop;

    @Option(names = "--dry-run",
            description = "Compute scaled targets and exit without touching the database.")
    private boolean dryRun;

    @Option(names = "--skip-indexes",
            description = "Skip replay of non-key indexes (constraints are still applied). "
                    + "Useful when bulk-loading at high scale.")
    private boolean skipIndexes;

    @Option(names = "--seed", defaultValue = "42",
            description = "Random seed for relationship endpoint sampling. (default: ${DEFAULT-VALUE})")
    private long randomSeed;

    private final SchemaSeeder seeder;
    private final ObjectMapper objectMapper;

    public SeedCommand(SchemaSeeder seeder, ObjectMapper objectMapper) {
        this.seeder = seeder;
        this.objectMapper = objectMapper;
    }

    @Override
    public Integer call() {
        log.info("Reading schema from {}", schemaFile.toAbsolutePath());
        try {
            var doc = objectMapper.readValue(schemaFile.toFile(), SchemaDocument.class);
            var options = SeedOptions.defaults()
                    .withDatabase(database)
                    .withScale(scale)
                    .withDrop(drop)
                    .withDryRun(dryRun)
                    .withSkipNonKeyIndexes(skipIndexes)
                    .withRandomSeed(randomSeed);

            if (dryRun) {
                seeder.dryRun(doc, options);
                return 0;
            }

            if (uri == null || username == null || password == null) {
                log.error("--uri, --username and --password are required unless --dry-run is set.");
                return 2;
            }

            try (var driver = GraphDatabase.driver(uri, AuthTokens.basic(username, new String(password)))) {
                driver.verifyConnectivity();
                log.info("Connected to {} (database {}, scale {})", uri, database, scale);
                seeder.seed(driver, doc, options);
            }
            log.info("Seeding complete.");
            return 0;
        } catch (IllegalStateException e) {
            log.error("{}", e.getMessage());
            return 1;
        } catch (Exception e) {
            log.error("Seeding failed: {}", e.getMessage(), e);
            return 1;
        }
    }
}
