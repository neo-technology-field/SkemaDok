package com.neo4j.skemadok.generator;

import com.neo4j.skemadok.model.*;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Base class for all document generators.
 *
 * <p>Provides shared utility methods and a template-method implementation of {@link #preview}
 * that is reused by text-based formats (AsciiDoc, Markdown). HTML and DOCX override
 * {@code preview} entirely because their preview semantics differ.</p>
 */
abstract class AbstractDocumentGenerator implements DocumentGenerator {

    // ---- Template method: preview -------------------------------------------

    /**
     * Builds the preview response by calling the abstract render methods once per entity.
     * Labels and relationships are concatenated into single blocks.
     * Subclasses that cannot use this structure (HTML, DOCX) must override this method.
     */
    @Override
    public PreviewResponse preview(SchemaDocument doc, GenerateOptions options) {
        var labelMembership = buildEntityViewMembership(doc, true);
        var relMembership = buildEntityViewMembership(doc, false);

        // Use the canonical image path even in preview so the copy text contains
        // a complete, usable include directive — not just the bare heading.
        var views = doc.getViews().stream()
                .map(v -> new SectionItem(v.getName(),
                        renderViewBlock(v, "views/" + sanitizeFileName(v.getName()) + ".png", options)))
                .toList();

        return getPreviewResponse(doc, options, labelMembership, relMembership, views);
    }

    protected @NonNull PreviewResponse getPreviewResponse(SchemaDocument doc,
                                                          GenerateOptions options,
                                                          Map<String, List<String>> labelMembership,
                                                          Map<String, List<String>> relMembership,
                                                          List<SectionItem> views) {
        var labelsBlock = doc.getNodeLabels().stream()
                .map(l -> renderLabelBlock(l, labelMembership.getOrDefault(l.getName(), List.of()), options))
                .collect(java.util.stream.Collectors.joining());

        var relationsBlock = doc.getRelationshipTypes().stream()
                .map(r -> renderRelBlock(r, relMembership.getOrDefault(r.getName(), List.of()), options))
                .collect(java.util.stream.Collectors.joining());

        return new PreviewResponse(views, labelsBlock, relationsBlock, renderConstraintsBlock(doc, options));
    }

    // ---- Abstract render methods (implemented by each format subclass) -------

    /**
     * Renders a single view section.
     *
     * @param view      the view to render
     * @param imagePath relative path to the view image, or {@code null} for preview (no images)
     * @param options   rendering options
     */
    protected abstract String renderViewBlock(ViewDefinition view, String imagePath, GenerateOptions options);

    /**
     * Renders a single label section including its property table.
     *
     * @param label     label metadata
     * @param viewNames view names this label appears in (for membership annotation)
     * @param options   rendering options
     */
    protected abstract String renderLabelBlock(LabelInfo label, List<String> viewNames, GenerateOptions options);

    /**
     * Renders a single relationship type section including its property table.
     *
     * @param rel       relationship type metadata
     * @param viewNames view names this relationship appears in
     * @param options   rendering options
     */
    protected abstract String renderRelBlock(RelationshipTypeInfo rel, List<String> viewNames, GenerateOptions options);

    /**
     * Renders the constraints and indexes appendix section.
     */
    protected abstract String renderConstraintsBlock(SchemaDocument doc, GenerateOptions options);

    // ---- Shared utilities ---------------------------------------------------

    /**
     * Builds a map from entity name to the list of view names that include it.
     *
     * @param forLabels {@code true} to index label membership, {@code false} for relationship types
     */
    static Map<String, List<String>> buildEntityViewMembership(SchemaDocument doc, boolean forLabels) {
        Map<String, List<String>> membership = new LinkedHashMap<>();
        for (var view : doc.getViews()) {
            var names = forLabels ? view.getLabels() : view.getRelationshipTypes();
            for (var name : names) {
                membership.computeIfAbsent(name, k -> new ArrayList<>()).add(view.getName());
            }
        }
        return membership;
    }

    /**
     * Writes each view image to a {@code views/} subdirectory under {@code outputDir}
     * and returns a map from view name to its relative path.
     * Returns an empty map when no images are provided.
     */
    protected static Map<String, String> writeViewImages(Map<String, byte[]> viewImages, Path outputDir) throws IOException {
        if (viewImages.isEmpty()) {
            return Map.of();
        }
        Path viewsDir = outputDir.resolve("views");
        Files.createDirectories(viewsDir);
        Map<String, String> imagePaths = new LinkedHashMap<>();
        for (var entry : viewImages.entrySet()) {
            String slug = sanitizeFileName(entry.getKey());
            Files.write(viewsDir.resolve(slug + ".png"), entry.getValue());
            imagePaths.put(entry.getKey(), "views/" + slug + ".png");
        }
        return imagePaths;
    }

    /**
     * Converts a display name to a safe filename slug.
     * Used for image filenames and the main output file.
     */
    static String sanitizeFileName(String name) {
        return name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
    }

    private static final DateTimeFormatter DISPLAY_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    /**
     * Formats a capture timestamp for display in generated documents.
     */
    static String formatInstant(Instant instant) {
        return instant != null ? DISPLAY_FORMAT.format(instant) : "";
    }


    protected Map<String, Object> buildModel(SchemaDocument doc, Map<String, String> imagePaths,
                                           GenerateOptions options) {
        var model = baseModel(options);
        model.put("doc", doc);
        model.put("capturedAt", formatInstant(doc.getCapturedAt()));
        model.put("imagePaths", imagePaths);
        model.put("labelMembership", buildEntityViewMembership(doc, true));
        model.put("relMembership", buildEntityViewMembership(doc, false));
        return model;
    }

    /**
     * Base model carrying options flags shared by every template.
     */
    protected Map<String, Object> baseModel(GenerateOptions options) {
        Map<String, Object> model = new HashMap<>();
        model.put("includeDataSource", options.includeDataSource());
        return model;
    }

    /**
     * Escapes a string for safe inclusion in HTML content.
     * Returns an empty string for {@code null} input.
     */
    static String htmlEsc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
