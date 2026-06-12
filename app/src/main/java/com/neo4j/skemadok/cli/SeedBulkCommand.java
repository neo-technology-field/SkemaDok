package com.neo4j.skemadok.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neo4j.skemadok.bulk.BulkParquetExporter;
import com.neo4j.skemadok.model.SchemaDocument;
import com.neo4j.skemadok.seeder.SchemaSeeder;
import com.neo4j.skemadok.seeder.SeedOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Exports Parquet files from a captured schema.json that can be loaded via
 * {@code neo4j-admin database import full}. Useful for seeding large databases without the
 * round-trip overhead of the Bolt protocol.
 *
 * <p>At the end of a successful run, exact import commands are printed to stdout — one variant
 * for bare-metal neo4j-admin and one for a Docker-based setup — so the user can copy-paste
 * without consulting the documentation.
 *
 * <p>No live database connection is required; the command operates entirely on local files.
 */
@Command(
        name = "seed-bulk",
        description = "Export Parquet files from a schema.json for neo4j-admin database import full.",
        mixinStandardHelpOptions = true
)
public class SeedBulkCommand implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(SeedBulkCommand.class);

    @Parameters(index = "0", description = "Path to the schema.json file to replay")
    private Path schemaFile;

    @Option(names = {"--output-dir", "-o"}, defaultValue = "./neo4j-import",
            description = "Directory to write Parquet files into. Created if absent. (default: ${DEFAULT-VALUE})")
    private Path outputDir;

    @Option(names = {"-s", "--scale"}, defaultValue = "1.0",
            description = "Multiplier applied to every node and relationship count. "
                    + "Use 0.001 for a small dev slice, 10 to stress-test. (default: ${DEFAULT-VALUE})")
    private double scale;

    @Option(names = "--seed", defaultValue = "42",
            description = "Random seed for relationship endpoint sampling. (default: ${DEFAULT-VALUE})")
    private long randomSeed;

    @Option(names = "--dry-run",
            description = "Print the computed plan without writing any files.")
    private boolean dryRun;

    @Option(names = {"-d", "--database"}, defaultValue = "neo4j",
            description = "Database name used in the generated import scripts. (default: ${DEFAULT-VALUE})")
    private String database;

    private final SchemaSeeder seeder;
    private final BulkParquetExporter exporter;
    private final ObjectMapper objectMapper;

    public SeedBulkCommand(SchemaSeeder seeder, BulkParquetExporter exporter, ObjectMapper objectMapper) {
        this.seeder = seeder;
        this.exporter = exporter;
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
                    .withDryRun(dryRun)
                    .withSkipNonKeyIndexes(true)
                    .withRandomSeed(randomSeed);

            if (dryRun) {
                seeder.dryRun(doc, options);
                log.info("Dry run complete — no files written.");
                return 0;
            }

            var plan = seeder.planAndReport(doc, options);
            var result = exporter.export(doc, plan, options, outputDir);
            exporter.printInstructions(result, database, outputDir);
            return 0;
        } catch (IllegalStateException e) {
            log.error("{}", e.getMessage());
            return 1;
        } catch (Exception e) {
            log.error("Bulk export failed: {}", e.getMessage(), e);
            return 1;
        }
    }
}
