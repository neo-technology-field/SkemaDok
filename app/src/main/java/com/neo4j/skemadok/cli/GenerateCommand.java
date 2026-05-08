package com.neo4j.skemadok.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neo4j.skemadok.generator.DocumentGenerator;
import com.neo4j.skemadok.model.SchemaDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Generates documentation from an enriched schema JSON file.
 */
@Component
@Command(
        name = "generate",
        description = "Generate documentation from an enriched schema JSON file.",
        mixinStandardHelpOptions = true
)
public class GenerateCommand implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(GenerateCommand.class);

    @Option(names = {"-s", "--schema"}, required = true,
            description = "Path to the enriched schema JSON file")
    private Path schemaFile;

    @Option(names = {"-f", "--format"}, defaultValue = "asciidoc",
            description = "Output format: asciidoc, markdown (default: ${DEFAULT-VALUE})")
    private String format;

    @Option(names = {"-o", "--output"},
            description = "Output file path (default: schema-doc.adoc or schema-doc.md)")
    private Path outputFile;

    private final List<DocumentGenerator> generators;
    private final ObjectMapper objectMapper;

    public GenerateCommand(List<DocumentGenerator> generators, ObjectMapper objectMapper) {
        this.generators = generators;
        this.objectMapper = objectMapper;
    }

    @Override
    public Integer call() {
        try {
            SchemaDocument doc = objectMapper.readValue(schemaFile.toFile(), SchemaDocument.class);

            DocumentGenerator generator = generators.stream()
                    .filter(g -> g.getFormat().equalsIgnoreCase(format))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown format '" + format + "'. Supported: " +
                                    generators.stream().map(DocumentGenerator::getFormat).reduce((a, b) -> a + ", " + b).orElse("")
                    ));

            Path target = outputFile != null ? outputFile : Path.of("schema-doc." + generator.getFileExtension());
            generator.generate(doc, target);

            log.info("Documentation written to: {}", target.toAbsolutePath());
            return 0;
        } catch (Exception e) {
            log.error("Generation failed: {}", e.getMessage());
            return 1;
        }
    }
}
