package com.neo4j.skemadok.generator;

import com.neo4j.skemadok.model.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Generates AsciiDoc documentation from an enriched schema document.
 *
 * <p>Structure: views first (image + description), then a global label reference,
 * then a global relationship reference, then constraint/index appendices.
 * This avoids duplicating entity tables for labels that appear in multiple views.</p>
 *
 * <p>Document assembly is handled by Freemarker templates under
 * {@code templates/generator/asciidoc/}. Preview renders individual block templates;
 * download renders the full {@code document.ftl}.</p>
 */
@Component
public class AsciiDocGenerator extends AbstractDocumentGenerator {

    private final FreemarkerRenderer renderer;

    public AsciiDocGenerator(FreemarkerRenderer renderer) {
        this.renderer = renderer;
    }

    @Override
    public String getFormat() {
        return "asciidoc";
    }

    @Override
    public String getFileExtension() {
        return "adoc";
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

        String text = renderer.render("asciidoc/document.ftl", buildModel(doc, imagePaths, options));
        String fileName = sanitizeFileName(doc.getDatabaseName()) + "-schema.adoc";
        Path outputFile = outputDir.resolve(fileName);
        Files.writeString(outputFile, text, StandardCharsets.UTF_8);

        return viewImages.isEmpty() ? outputFile : outputDir;
    }

    @Override
    public void generate(SchemaDocument doc, Path outputPath) throws IOException {
        String text = renderer.render("asciidoc/document.ftl",
                buildModel(doc, Map.of(), GenerateOptions.defaults()));
        Files.writeString(outputPath, text, StandardCharsets.UTF_8);
    }

    // ---- Block renderers (called by preview template method in base class) --

    @Override
    protected String renderViewBlock(ViewDefinition view, String imagePath, GenerateOptions options) {
        var model = baseModel(options);
        model.put("view", view);
        model.put("imgPath", imagePath != null ? imagePath : "");
        return renderer.render("asciidoc/view.ftl", model);
    }

    @Override
    protected String renderLabelBlock(LabelInfo label, List<String> viewNames, GenerateOptions options) {
        var model = baseModel(options);
        model.put("label", label);
        model.put("viewNames", viewNames);
        return renderer.render("asciidoc/label.ftl", model);
    }

    @Override
    protected String renderRelBlock(RelationshipTypeInfo rel, List<String> viewNames, GenerateOptions options) {
        var model = baseModel(options);
        model.put("rel", rel);
        model.put("viewNames", viewNames);
        return renderer.render("asciidoc/rel.ftl", model);
    }

    @Override
    protected String renderConstraintsBlock(SchemaDocument doc, GenerateOptions options) {
        var model = baseModel(options);
        model.put("doc", doc);
        return renderer.render("asciidoc/constraints.ftl", model);
    }

}
