package com.neo4j.skemadok.generator;

import com.neo4j.skemadok.model.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates GitHub-flavoured Markdown documentation from an enriched schema document.
 *
 * <p>Structure mirrors {@link AsciiDocGenerator}: views first, then global label
 * and relationship sections, then constraints/indexes.
 * Document assembly delegates to Freemarker templates under
 * {@code templates/generator/markdown/}.</p>
 */
@Component
public class MarkdownGenerator extends AbstractDocumentGenerator {

    private final FreemarkerRenderer renderer;

    public MarkdownGenerator(FreemarkerRenderer renderer) {
        this.renderer = renderer;
    }

    @Override
    public String getFormat() {
        return "markdown";
    }

    @Override
    public String getFileExtension() {
        return "md";
    }

    @Override
    public String getContentType() {
        return "text/plain;charset=UTF-8";
    }

    // ---- Download ----------------------------------------------------------

    @Override
    public Path generateForDownload(SchemaDocument doc, Map<String, byte[]> viewImages,
                                    Path outputDir, GenerateOptions options) throws IOException {
        var imagePaths = writeViewImages(viewImages, outputDir);

        String text = renderer.render("markdown/document.ftl", buildModel(doc, imagePaths, options));
        String fileName = sanitizeFileName(doc.getDatabaseName()) + "-schema.md";
        Path outputFile = outputDir.resolve(fileName);
        Files.writeString(outputFile, text, StandardCharsets.UTF_8);

        return viewImages.isEmpty() ? outputFile : outputDir;
    }

    @Override
    public void generate(SchemaDocument doc, Path outputPath) throws IOException {
        String text = renderer.render("markdown/document.ftl",
                buildModel(doc, Map.of(), GenerateOptions.defaults()));
        Files.writeString(outputPath, text, StandardCharsets.UTF_8);
    }

    // ---- Block renderers (called by preview template method in base class) --

    @Override
    protected String renderViewBlock(ViewDefinition view, String imagePath, GenerateOptions options) {
        var model = baseModel(options);
        model.put("view", view);
        model.put("imgPath", imagePath != null ? imagePath : "");
        return renderer.render("markdown/view.ftl", model);
    }

    @Override
    protected String renderLabelBlock(LabelInfo label, List<String> viewNames, GenerateOptions options) {
        var model = baseModel(options);
        model.put("label", label);
        model.put("viewNames", viewNames);
        return renderer.render("markdown/label.ftl", model);
    }

    @Override
    protected String renderRelBlock(RelationshipTypeInfo rel, List<String> viewNames, GenerateOptions options) {
        var model = baseModel(options);
        model.put("rel", rel);
        model.put("viewNames", viewNames);
        return renderer.render("markdown/rel.ftl", model);
    }

    @Override
    protected String renderConstraintsBlock(SchemaDocument doc, GenerateOptions options) {
        var model = baseModel(options);
        model.put("doc", doc);
        return renderer.render("markdown/constraints.ftl", model);
    }

    // ---- Model helpers -----------------------------------------------------

}
