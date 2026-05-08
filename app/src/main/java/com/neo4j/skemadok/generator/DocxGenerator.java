package com.neo4j.skemadok.generator;

import com.neo4j.skemadok.model.*;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.xmlbeans.XmlException;
import org.apache.xmlbeans.XmlOptions;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyles;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Generates a DOCX document with embedded images suitable for direct import
 * into Google Docs or Microsoft Word.
 *
 * <p>Preview returns HTML snippets so the user can paste individual sections directly
 * into an existing Word or Google Docs document via the system clipboard.
 * Labels and relationships are aggregated into single HTML blocks.</p>
 */
@Component
public class DocxGenerator extends AbstractDocumentGenerator {

    @Override
    public String getFormat() {
        return "docx";
    }

    @Override
    public String getFileExtension() {
        return "docx";
    }

    @Override
    public String getContentType() {
        return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    }

    // ---- Preview (HTML for clipboard) --------------------------------------

    /**
     * Overrides the default preview to return HTML snippets rather than format-native text,
     * since DOCX clipboard paste targets (Word, Google Docs) accept HTML better than raw DOCX XML.
     * Labels and relationship types are each aggregated into one copyable block.
     */
    @Override
    public PreviewResponse preview(SchemaDocument doc, GenerateOptions options) {
        var labelMembership = buildEntityViewMembership(doc, true);
        var relMembership = buildEntityViewMembership(doc, false);

        var views = doc.getViews().stream()
                .map(v -> new SectionItem(v.getName(), renderViewBlock(v, null, options)))
                .toList();

        return getPreviewResponse(doc, options, labelMembership, relMembership, views);
    }

    // ---- Download (DOCX file) ----------------------------------------------

    @Override
    public Path generateForDownload(SchemaDocument doc, Map<String, byte[]> viewImages,
                                    Path outputDir, GenerateOptions options) throws IOException {
        var labelMembership = buildEntityViewMembership(doc, true);
        var relMembership = buildEntityViewMembership(doc, false);

        try (var document = new XWPFDocument()) {
            initHeadingStyles(document);
            addHeading(document, doc.getDatabaseName() + " — Schema Documentation", 1);
            addParagraph(document, "Database: " + doc.getDatabaseAddress() + " / " + doc.getDatabaseName());
            addParagraph(document, "Captured: " + formatInstant(doc.getCapturedAt()));

            if (!doc.getViews().isEmpty()) {
                addHeading(document, "Views", 2);
                for (var view : doc.getViews()) {
                    addHeading(document, view.getName(), 3);
                    if (!view.getDescription().isBlank()) {
                        addParagraph(document, view.getDescription());
                    }
                    byte[] img = viewImages.get(view.getName());
                    if (img != null) {
                        addImage(document, img, view.getName());
                    }
                }
            }

            if (!doc.getNodeLabels().isEmpty()) {
                addHeading(document, "Node Labels", 2);
                for (var label : doc.getNodeLabels()) {
                    appendLabelDocx(document, label,
                            labelMembership.getOrDefault(label.getName(), List.of()), options);
                }
            }

            if (!doc.getRelationshipTypes().isEmpty()) {
                addHeading(document, "Relationship Types", 2);
                for (var rel : doc.getRelationshipTypes()) {
                    appendRelDocx(document, rel,
                            relMembership.getOrDefault(rel.getName(), List.of()), options);
                }
            }

            appendConstraintsIndexesDocx(document, doc);

            String fileName = sanitizeFileName(doc.getDatabaseName()) + "-schema.docx";
            Path outputFile = outputDir.resolve(fileName);
            try (var fos = new FileOutputStream(outputFile.toFile())) {
                document.write(fos);
            }
            return outputFile;
        }
    }

    // ---- DOCX block builders -----------------------------------------------

    private void appendLabelDocx(XWPFDocument document, LabelInfo label,
                                 List<String> viewNames, GenerateOptions options) {
        addHeading(document, label.getName(), 3);
        String meta = "Role: " + label.getRole() + " — " + label.getNodeCount() + " nodes";
        if (!viewNames.isEmpty()) {
            meta += "  |  Appears in: " + String.join(", ", viewNames);
        }
        addParagraph(document, meta);
        addRelParagraph(document, options, label.getDataSource(), label.getDescription(), label.getProperties());
    }

    private void appendRelDocx(XWPFDocument document, RelationshipTypeInfo rel,
                               List<String> viewNames, GenerateOptions options) {
        addHeading(document, rel.getName(), 3);
        String meta = rel.getCount() + " relationships";
        if (!viewNames.isEmpty()) {
            meta += "  |  Appears in: " + String.join(", ", viewNames);
        }
        addParagraph(document, meta);
        for (var conn : rel.getConnections()) {
            var start = ":" + String.join(" | :", conn.startLabels());
            var end = ":" + String.join(" | :", conn.endLabels());
            addParagraph(document, "(" + start + ")-[:" + rel.getName() + "]->(" + end + ") — " + conn.count());
        }
        addRelParagraph(document, options, rel.getDataSource(), rel.getDescription(), rel.getProperties());
    }

    private void addRelParagraph(XWPFDocument document,
                                 GenerateOptions options,
                                 String dataSource,
                                 String description,
                                 List<PropertyInfo> properties) {
        if (options.includeDataSource() && dataSource != null && !dataSource.isBlank()) {
            addParagraph(document, "Source: " + dataSource);
        }
        if (!description.isBlank()) {
            addParagraph(document, description);
        }
        if (!properties.isEmpty()) {
            addPropertyTable(document, properties, options);
        }
    }

    private void appendConstraintsIndexesDocx(XWPFDocument document, SchemaDocument doc) {
        addHeading(document, "Indexes", 2);
        if (doc.getIndexes().isEmpty()) {
            addParagraph(document, "No indexes collected.");
        } else {
            var headers = List.of("Name", "Type", "Entity", "Labels / Types", "Properties", "Read count", "Options");
            var rows = doc.getIndexes().stream()
                    .map(idx -> List.of(
                            idx.name(), idx.type(), idx.entityType(),
                            String.join(", ", idx.labelsOrTypes()),
                            String.join(", ", idx.properties()),
                            String.valueOf(idx.readCount()),
                            idx.indexConfig() != null ? idx.indexConfig() : ""))
                    .toList();
            addTable(document, headers, rows);
        }

        addHeading(document, "Constraints", 2);
        if (doc.getConstraints().isEmpty()) {
            addParagraph(document, "No constraints collected.");
        } else {
            var headers = List.of("Name", "Type", "Entity", "Labels / Types", "Properties");
            var rows = doc.getConstraints().stream()
                    .map(c -> List.of(c.name(), c.type(), c.entityType(),
                            String.join(", ", c.labelsOrTypes()),
                            String.join(", ", c.properties())))
                    .toList();
            addTable(document, headers, rows);
        }
    }

    private void addPropertyTable(XWPFDocument document, List<PropertyInfo> properties, GenerateOptions options) {
        var headers = options.includeDataSource()
                ? List.of("Property", "Type(s)", "Mandatory", "Description", "Data source")
                : List.of("Property", "Type(s)", "Mandatory", "Description");
        var rows = properties.stream()
                .map(p -> {
                    var row = new java.util.ArrayList<String>();
                    row.add(p.name());
                    row.add(String.join(", ", p.types()));
                    row.add(p.nullable() ? "no" : "yes");
                    row.add(p.description());
                    if (options.includeDataSource()) {
                        row.add(p.dataSource() != null ? p.dataSource() : "");
                    }
                    return (List<String>) row;
                })
                .toList();
        addTable(document, headers, rows);
    }

    // ---- POI helpers -------------------------------------------------------

    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    /**
     * Complete minimal styles XML for the document. addStyle() / CTStyle.Factory.parse()
     * double-wrap the element, so we parse the whole &lt;w:styles&gt; block and replace
     * the document's styles part in one call via setStyles().
     * basedOn + qFormat are required for LibreOffice and Google Docs to recognise headings.
     */
    private static final String STYLES_XML =
            "<w:styles xmlns:w=\"" + W + "\">" +
                    "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\">" +
                    "<w:name w:val=\"Normal\"/>" +
                    "</w:style>" +
                    "<w:style w:type=\"paragraph\" w:styleId=\"Heading1\">" +
                    "<w:name w:val=\"heading 1\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
                    "<w:pPr><w:spacing w:before=\"480\"/></w:pPr>" +
                    "<w:rPr><w:b/><w:sz w:val=\"48\"/><w:szCs w:val=\"48\"/></w:rPr>" +
                    "</w:style>" +
                    "<w:style w:type=\"paragraph\" w:styleId=\"Heading2\">" +
                    "<w:name w:val=\"heading 2\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
                    "<w:pPr><w:spacing w:before=\"360\"/></w:pPr>" +
                    "<w:rPr><w:b/><w:sz w:val=\"36\"/><w:szCs w:val=\"36\"/></w:rPr>" +
                    "</w:style>" +
                    "<w:style w:type=\"paragraph\" w:styleId=\"Heading3\">" +
                    "<w:name w:val=\"heading 3\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
                    "<w:pPr><w:spacing w:before=\"240\"/></w:pPr>" +
                    "<w:rPr><w:b/><w:sz w:val=\"28\"/><w:szCs w:val=\"28\"/></w:rPr>" +
                    "</w:style>" +
                    "</w:styles>";

    private static void initHeadingStyles(XWPFDocument document) {
        try {
            XWPFStyles styles = document.getStyles() != null ? document.getStyles() : document.createStyles();
            // setLoadReplaceDocumentElement(null) mirrors what POI does in onDocumentRead():
            // it strips the <w:styles> root so CTStyles holds only the children.
            // commit() then re-adds the <w:styles> wrapper via setSaveSyntheticDocumentElement.
            // Without this, parsing produces CTStyles whose content IS <w:styles>, and commit()
            // wraps it again → <w:styles><w:styles>…</w:styles></w:styles>.
            XmlOptions parseOpts = new XmlOptions().setLoadReplaceDocumentElement(null);
            styles.setStyles(CTStyles.Factory.parse(STYLES_XML, parseOpts));
        } catch (XmlException e) {
            throw new IllegalStateException("Failed to init heading styles", e);
        }
    }

    private void addHeading(XWPFDocument document, String text, int level) {
        XWPFParagraph p = document.createParagraph();
        p.setStyle("Heading" + level);
        XWPFRun run = p.createRun();
        run.setText(text);
    }

    private void addParagraph(XWPFDocument document, String text) {
        XWPFParagraph p = document.createParagraph();
        XWPFRun run = p.createRun();
        run.setText(text);
        run.setFontSize(11);
    }

    private void addTable(XWPFDocument document, List<String> headers, List<List<String>> rows) {
        XWPFTable table = document.createTable(1 + rows.size(), headers.size());
        XWPFTableRow headerRow = table.getRow(0);
        for (int i = 0; i < headers.size(); i++) {
            setCellText(headerRow.getCell(i), headers.get(i), true);
        }
        for (int r = 0; r < rows.size(); r++) {
            XWPFTableRow row = table.getRow(r + 1);
            var cells = rows.get(r);
            for (int c = 0; c < cells.size(); c++) {
                setCellText(row.getCell(c), cells.get(c), false);
            }
        }
        document.createParagraph();
    }

    private void addImage(XWPFDocument document, byte[] imageBytes, String altText) {
        try {
            XWPFParagraph p = document.createParagraph();
            XWPFRun run = p.createRun();
            // 1200×900 source canvas → 5 inches wide, 3.75 inches tall in the document
            int widthEmu = 5 * Units.EMU_PER_INCH;
            int heightEmu = (int) (3.75 * Units.EMU_PER_INCH);
            run.addPicture(new ByteArrayInputStream(imageBytes),
                    XWPFDocument.PICTURE_TYPE_PNG, altText, widthEmu, heightEmu);
        } catch (Exception e) {
            addParagraph(document, "[Image: " + altText + " — could not embed]");
        }
    }

    private void setCellText(XWPFTableCell cell, String text, boolean bold) {
        XWPFParagraph para = cell.getParagraphs().isEmpty() ? cell.addParagraph() : cell.getParagraphs().getFirst();
        XWPFRun run = para.getRuns().isEmpty() ? para.createRun() : para.getRuns().getFirst();
        run.setText(text != null ? text : "");
        run.setBold(bold);
        run.setFontSize(10);
    }

    // ---- HTML preview block builders (clipboard targets) -------------------

    @Override
    protected String renderViewBlock(ViewDefinition view, String imagePath, GenerateOptions options) {
        var sb = new StringBuilder();
        sb.append("<h2>").append(htmlEsc(view.getName())).append("</h2>\n");
        if (!view.getDescription().isBlank()) {
            sb.append("<p>").append(htmlEsc(view.getDescription())).append("</p>\n");
        }
        return sb.toString();
    }

    @Override
    protected String renderLabelBlock(LabelInfo label, List<String> viewNames, GenerateOptions options) {
        var sb = new StringBuilder();
        sb.append("<h3>").append(htmlEsc(label.getName())).append("</h3>\n");
        sb.append("<p>Role: <strong>").append(htmlEsc(label.getRole().toString())).append("</strong> — ")
                .append(label.getNodeCount()).append(" nodes");
        if (!viewNames.isEmpty()) {
            sb.append(" | <em>Appears in: ").append(htmlEsc(String.join(", ", viewNames))).append("</em>");
        }
        sb.append("</p>\n");
        if (options.includeDataSource() && label.getDataSource() != null && !label.getDataSource().isBlank()) {
            sb.append("<p><em>Source: ").append(htmlEsc(label.getDataSource())).append("</em></p>\n");
        }
        if (!label.getDescription().isBlank()) {
            sb.append("<p>").append(htmlEsc(label.getDescription())).append("</p>\n");
        }
        if (!label.getProperties().isEmpty()) {
            sb.append(propertyTableHtml(label.getProperties(), options));
        }
        return sb.toString();
    }

    @Override
    protected String renderRelBlock(RelationshipTypeInfo rel, List<String> viewNames, GenerateOptions options) {
        var sb = new StringBuilder();
        sb.append("<h3>").append(htmlEsc(rel.getName())).append("</h3>\n");
        sb.append("<p>").append(rel.getCount()).append(" relationships");
        if (!viewNames.isEmpty()) {
            sb.append(" | <em>Appears in: ").append(htmlEsc(String.join(", ", viewNames))).append("</em>");
        }
        sb.append("</p>\n");
        if (!rel.getConnections().isEmpty()) {
            sb.append("<ul>\n");
            for (var conn : rel.getConnections()) {
                var start = ":" + String.join(" | :", conn.startLabels());
                var end = ":" + String.join(" | :", conn.endLabels());
                sb.append("<li><code>(").append(htmlEsc(start)).append(")-[:")
                        .append(htmlEsc(rel.getName())).append("]->(").append(htmlEsc(end)).append(")</code>")
                        .append(" — ").append(conn.count()).append("</li>\n");
            }
            sb.append("</ul>\n");
        }
        if (options.includeDataSource() && rel.getDataSource() != null && !rel.getDataSource().isBlank()) {
            sb.append("<p><em>Source: ").append(htmlEsc(rel.getDataSource())).append("</em></p>\n");
        }
        if (!rel.getDescription().isBlank()) {
            sb.append("<p>").append(htmlEsc(rel.getDescription())).append("</p>\n");
        }
        if (!rel.getProperties().isEmpty()) {
            sb.append(propertyTableHtml(rel.getProperties(), options));
        }
        return sb.toString();
    }

    @Override
    protected String renderConstraintsBlock(SchemaDocument doc, GenerateOptions options) {
        var sb = new StringBuilder();
        sb.append("<h2>Indexes</h2>\n");
        if (doc.getIndexes().isEmpty()) {
            sb.append("<p>No indexes collected.</p>\n");
        } else {
            sb.append("<table><thead><tr><th>Name</th><th>Type</th><th>Entity</th><th>Labels / Types</th><th>Properties</th></tr></thead><tbody>\n");
            for (var idx : doc.getIndexes()) {
                sb.append("<tr><td><code>").append(htmlEsc(idx.name())).append("</code></td>")
                        .append("<td>").append(htmlEsc(idx.type())).append("</td>")
                        .append("<td>").append(htmlEsc(idx.entityType())).append("</td>")
                        .append("<td>").append(htmlEsc(String.join(", ", idx.labelsOrTypes()))).append("</td>")
                        .append("<td>").append(htmlEsc(String.join(", ", idx.properties()))).append("</td></tr>\n");
            }
            sb.append("</tbody></table>\n");
        }
        sb.append("<h2>Constraints</h2>\n");
        if (doc.getConstraints().isEmpty()) {
            sb.append("<p>No constraints collected.</p>\n");
        } else {
            sb.append("<table><thead><tr><th>Name</th><th>Type</th><th>Entity</th><th>Labels / Types</th><th>Properties</th></tr></thead><tbody>\n");
            for (var c : doc.getConstraints()) {
                sb.append("<tr><td><code>").append(htmlEsc(c.name())).append("</code></td>")
                        .append("<td>").append(htmlEsc(c.type())).append("</td>")
                        .append("<td>").append(htmlEsc(c.entityType())).append("</td>")
                        .append("<td>").append(htmlEsc(String.join(", ", c.labelsOrTypes()))).append("</td>")
                        .append("<td>").append(htmlEsc(String.join(", ", c.properties()))).append("</td></tr>\n");
            }
            sb.append("</tbody></table>\n");
        }
        return sb.toString();
    }

    private String propertyTableHtml(List<PropertyInfo> properties, GenerateOptions options) {
        var sb = new StringBuilder();
        sb.append("<table><thead><tr><th>Property</th><th>Type(s)</th><th>Mandatory</th><th>Description</th>");
        if (options.includeDataSource()) {
            sb.append("<th>Data source</th>");
        }
        sb.append("</tr></thead><tbody>\n");
        for (var p : properties) {
            sb.append("<tr><td><code>").append(htmlEsc(p.name())).append("</code></td>")
                    .append("<td>").append(htmlEsc(String.join(", ", p.types()))).append("</td>")
                    .append("<td>").append(p.nullable() ? "no" : "yes").append("</td>")
                    .append("<td>").append(htmlEsc(p.description())).append("</td>");
            if (options.includeDataSource()) {
                sb.append("<td>").append(htmlEsc(p.dataSource() != null ? p.dataSource() : "")).append("</td>");
            }
            sb.append("</tr>\n");
        }
        sb.append("</tbody></table>\n");
        return sb.toString();
    }
}
