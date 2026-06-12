package com.neo4j.skemadok.bulk;

import com.neo4j.skemadok.generator.FreemarkerRenderer;
import com.neo4j.skemadok.model.SchemaDocument;
import com.neo4j.skemadok.seeder.SeedOptions;
import com.neo4j.skemadok.seeder.SeedPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Orchestrates export of a {@link SeedPlan} to Parquet files suitable for
 * {@code neo4j-admin database import full}.
 *
 * <p>Delegates node writing to {@link NodeParquetWriter} and relationship writing to
 * {@link RelationshipParquetWriter}. Also writes {@code schema.cypher} with the constraints
 * and indexes to apply after the import. Returns an {@link ExportResult} that the CLI command
 * passes to {@link ImportInstructionsPrinter} to emit the post-run copy-paste commands.
 */
public class BulkParquetExporter {

    private static final Logger log = LoggerFactory.getLogger(BulkParquetExporter.class);

    private final FreemarkerRenderer renderer;

    public BulkParquetExporter(FreemarkerRenderer renderer) {
        this.renderer = renderer;
    }

    /**
     * Writes all node and relationship Parquet files to {@code outputDir/nodes/} and
     * {@code outputDir/relationships/} respectively.
     */
    public ExportResult export(SchemaDocument schema, SeedPlan plan, SeedOptions options, Path outputDir)
            throws IOException {
        var nodesDir = outputDir.resolve("nodes");
        var relsDir = outputDir.resolve("relationships");
        Files.createDirectories(nodesDir);
        Files.createDirectories(relsDir);
        log.info("Exporting Parquet files to {}", outputDir.toAbsolutePath());

        var nodeFiles = new NodeParquetWriter(schema, plan).writeAll(nodesDir);
        var relFiles = new RelationshipParquetWriter(schema, plan, options).writeAll(relsDir);
        var schemaScript = new SchemaCypherWriter(schema).write(outputDir);
        log.info("Schema DDL written → {}", schemaScript.getFileName());

        return new ExportResult(nodeFiles, relFiles, schemaScript);
    }

    /**
     * Writes import shell scripts to the output directory and prints a compact terminal summary.
     */
    public void printInstructions(ExportResult result, String database, Path outputDir)
            throws IOException {
        new ImportInstructionsPrinter(result, database, outputDir, renderer)
                .printAndWriteScripts();
    }
}
