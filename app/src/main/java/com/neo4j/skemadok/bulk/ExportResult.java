package com.neo4j.skemadok.bulk;

import java.nio.file.Path;
import java.util.List;

/**
 * Files and row counts produced by a single {@link BulkParquetExporter#export} run.
 */
public record ExportResult(
        List<WrittenFile> nodeFiles,
        List<WrittenFile> relationshipFiles,
        Path schemaScript
) {
    /** A single Parquet file that was written during export. */
    public record WrittenFile(Path path, long rowCount) {}

    public long totalNodeRows() {
        return nodeFiles.stream().mapToLong(WrittenFile::rowCount).sum();
    }

    public long totalRelationshipRows() {
        return relationshipFiles.stream().mapToLong(WrittenFile::rowCount).sum();
    }
}
