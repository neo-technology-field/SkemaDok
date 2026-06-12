package com.neo4j.skemadok.bulk;

import com.neo4j.skemadok.generator.FreemarkerRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

/**
 * Writes import shell scripts to the output directory and prints a compact terminal summary.
 *
 * <p>Two scripts are rendered from FreeMarker templates and written to the output directory:
 * {@code import.sh} for a standalone Neo4j install and {@code import-docker.sh} for a
 * Docker-based setup. The terminal summary uses wildcard paths (supported by neo4j-admin 5.x+)
 * so it remains brief regardless of schema size.
 */
class ImportInstructionsPrinter {

    private static final Logger log = LoggerFactory.getLogger(ImportInstructionsPrinter.class);

    private final ExportResult result;
    private final String database;
    private final Path outputDir;
    private final FreemarkerRenderer renderer;

    ImportInstructionsPrinter(ExportResult result, String database,
                              Path outputDir, FreemarkerRenderer renderer) {
        this.result = result;
        this.database = database;
        this.outputDir = outputDir;
        this.renderer = renderer;
    }

    void printAndWriteScripts() throws IOException {
        var absoluteOutput = outputDir.toAbsolutePath().normalize();

        var model = Map.<String, Object>of(
                "database", database,
                "importDir", absoluteOutput.toString()
        );

        var standaloneScript = absoluteOutput.resolve("import.sh");
        var dockerScript = absoluteOutput.resolve("import-docker.sh");

        Files.writeString(standaloneScript, renderer.render("bulk/import-standalone.sh.ftl", model));
        Files.writeString(dockerScript, renderer.render("bulk/import-docker.sh.ftl", model));
        makeExecutable(standaloneScript);
        makeExecutable(dockerScript);

        System.out.printf(
                """

                        Parquet export complete.
                        Output: %s
                          Nodes:         %d files  (%,d rows)
                          Relationships: %d files  (%,d rows)

                        Import scripts written to:
                          %s   (standalone Neo4j install)
                          %s   (Neo4j running in Docker)
                          %s   (constraints + indexes — run after import)

                        Quick reference:

                          neo4j-admin database import full %s \\
                            --overwrite-destination=true \\
                            --input-type=parquet \\
                            --path-pattern-style=glob \\
                            --format=block \\
                            --nodes="%s/nodes/*.parquet" \\
                            --relationships="%s/relationships/*.parquet"
                        """, absoluteOutput,
                result.nodeFiles().size(), result.totalNodeRows(),
                result.relationshipFiles().size(), result.totalRelationshipRows(),
                standaloneScript, dockerScript, result.schemaScript(),
                database, absoluteOutput, absoluteOutput);
    }

    private static void makeExecutable(Path script) {
        try {
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (Exception e) {
            log.debug("Could not set executable bit on {}: {}", script.getFileName(), e.getMessage());
        }
    }

}
