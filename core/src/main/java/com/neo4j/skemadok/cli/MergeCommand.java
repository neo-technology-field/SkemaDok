package com.neo4j.skemadok.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neo4j.skemadok.merge.SchemaMerger;
import com.neo4j.skemadok.model.LabelInfo;
import com.neo4j.skemadok.model.RelationshipTypeInfo;
import com.neo4j.skemadok.model.SchemaDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Merges a fresh schema snapshot schema JSON into an existing annotated schema file.
 * New labels and relationships are added; removed ones are flagged but not deleted;
 * user annotations (descriptions, views) are preserved.
 */
@Command(
        name = "merge",
        description = "Merge a new schema snapshot into an existing annotated schema file.",
        mixinStandardHelpOptions = true
)
public class MergeCommand implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(MergeCommand.class);

    @Parameters(index = "0", description = "Existing annotated schema file (target of merge)")
    private Path existingFile;

    @Parameters(index = "1", description = "New schema snapshot to merge in")
    private Path newFile;

    @Option(names = {"-o", "--output"},
            description = "Output file (default: overwrites the existing file)")
    private Path outputFile;

    private final SchemaMerger merger;
    private final ObjectMapper objectMapper;

    public MergeCommand(SchemaMerger merger, ObjectMapper objectMapper) {
        this.merger = merger;
        this.objectMapper = objectMapper;
    }

    @Override
    public Integer call() {
        try {
            SchemaDocument existing = objectMapper.readValue(existingFile.toFile(), SchemaDocument.class);
            SchemaDocument newDoc = objectMapper.readValue(newFile.toFile(), SchemaDocument.class);
            SchemaDocument merged = merger.merge(existing, newDoc);

            Path target = outputFile != null ? outputFile : existingFile;
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), merged);

            long removedLabels = merged.getNodeLabels().stream().filter(LabelInfo::isRemoved).count();
            long removedRels = merged.getRelationshipTypes().stream().filter(RelationshipTypeInfo::isRemoved).count();

            log.info("Merged: {} labels, {} relationship types",
                    merged.getNodeLabels().size(), merged.getRelationshipTypes().size());
            if (removedLabels > 0 || removedRels > 0) {
                log.warn("{} label(s) and {} relationship type(s) no longer exist " +
                        "(flagged with removed:true, not deleted)", removedLabels, removedRels);
            }
            log.info("Output: {}", target.toAbsolutePath());
            return 0;
        } catch (Exception e) {
            log.error("Merge failed: {}", e.getMessage());
            return 1;
        }
    }
}
