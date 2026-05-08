package com.neo4j.skemadok.generator;

import com.neo4j.skemadok.model.GenerateOptions;
import com.neo4j.skemadok.model.PreviewResponse;
import com.neo4j.skemadok.model.SchemaDocument;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Generates documentation from an enriched schema document in a specific output format.
 * Implementations are Spring beans collected by the controller and selected by format string.
 */
public interface DocumentGenerator {

    /**
     * Format identifier matched against the {@code --format} CLI option and API requests.
     */
    String getFormat();

    /**
     * File extension for the primary output file, without leading dot.
     */
    String getFileExtension();

    /**
     * MIME content type for the primary output file (not the zip wrapper).
     */
    String getContentType();

    /**
     * Returns copyable section blocks for the given schema, with no images.
     * Views are returned per-item; labels and relationships are each aggregated into one block.
     * HTML format returns an empty response — it is download-only.
     */
    PreviewResponse preview(SchemaDocument doc, GenerateOptions options);

    /**
     * Generates the download artefact into the given output directory.
     *
     * <p>Single-file formats (DOCX, HTML) write one file and return its path.
     * Multi-file formats (AsciiDoc, Markdown with images) write a directory tree
     * and return the directory root — the caller zips it.</p>
     *
     * @param doc        enriched schema document
     * @param viewImages base64-decoded PNG bytes keyed by view name; empty map means no images
     * @param outputDir  temporary directory to write into
     * @param options    rendering options (e.g. whether to include data source annotations)
     * @return path of the primary output file, or the output directory for multi-file formats
     */
    Path generateForDownload(SchemaDocument doc, Map<String, byte[]> viewImages,
                             Path outputDir, GenerateOptions options) throws IOException;

    /**
     * CLI path: generates to a single file with no image support and default options.
     * Multi-file formats write the file into the given path's parent directory.
     */
    default void generate(SchemaDocument doc, Path outputPath) throws IOException {
        generateForDownload(doc, Map.of(), outputPath.getParent(), GenerateOptions.defaults());
    }
}
