package com.neo4j.skemadok.generator;

import com.neo4j.skemadok.model.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates a self-contained HTML document with all view images embedded as base64.
 * The output is a single file suitable for browser viewing, printing to PDF,
 * or importing directly into Google Docs.
 *
 * <p>HTML is a download-only format — {@link #preview} returns an empty response.
 * Document assembly delegates to {@code templates/generator/html/document.ftl}.</p>
 */
@Component
public class HtmlGenerator extends AbstractDocumentGenerator {

    private final FreemarkerRenderer renderer;

    public HtmlGenerator(FreemarkerRenderer renderer) {
        this.renderer = renderer;
    }

    @Override
    public String getFormat() {
        return "html";
    }

    @Override
    public String getFileExtension() {
        return "html";
    }

    @Override
    public String getContentType() {
        return "text/html;charset=UTF-8";
    }

    /**
     * HTML is download-only; the preview UI shows a static explanation instead.
     */
    @Override
    public PreviewResponse preview(SchemaDocument doc, GenerateOptions options) {
        return new PreviewResponse(List.of(), "", "", "");
    }

    @Override
    public Path generateForDownload(SchemaDocument doc, Map<String, byte[]> viewImages,
                                    Path outputDir, GenerateOptions options) throws IOException {
        Map<String, String> base64Images = new HashMap<>();
        for (var entry : viewImages.entrySet()) {
            base64Images.put(entry.getKey(), Base64.getEncoder().encodeToString(entry.getValue()));
        }

        var model = new HashMap<String, Object>();
        model.put("doc", doc);
        model.put("capturedAt", formatInstant(doc.getCapturedAt()));
        model.put("imagePaths", base64Images);
        model.put("labelMembership", buildEntityViewMembership(doc, true));
        model.put("relMembership", buildEntityViewMembership(doc, false));
        model.put("includeDataSource", options.includeDataSource());

        String html = renderer.render("html/document.ftl", model);
        String fileName = sanitizeFileName(doc.getDatabaseName()) + "-schema.html";
        Path outputFile = outputDir.resolve(fileName);
        Files.writeString(outputFile, html, StandardCharsets.UTF_8);
        return outputFile;
    }

    @Override
    public void generate(SchemaDocument doc, Path outputPath) throws IOException {
        var model = new HashMap<String, Object>();
        model.put("doc", doc);
        model.put("capturedAt", formatInstant(doc.getCapturedAt()));
        model.put("imagePaths", Map.of());
        model.put("labelMembership", buildEntityViewMembership(doc, true));
        model.put("relMembership", buildEntityViewMembership(doc, false));
        model.put("includeDataSource", true);

        Files.writeString(outputPath, renderer.render("html/document.ftl", model), StandardCharsets.UTF_8);
    }

    // These are never called (preview is overridden to return empty), but must be implemented.

    @Override
    protected String renderViewBlock(ViewDefinition view, String imagePath, GenerateOptions options) {
        return "";
    }

    @Override
    protected String renderLabelBlock(LabelInfo label, List<String> viewNames, GenerateOptions options) {
        return "";
    }

    @Override
    protected String renderRelBlock(RelationshipTypeInfo rel, List<String> viewNames, GenerateOptions options) {
        return "";
    }

    @Override
    protected String renderConstraintsBlock(SchemaDocument doc, GenerateOptions options) {
        return "";
    }
}
