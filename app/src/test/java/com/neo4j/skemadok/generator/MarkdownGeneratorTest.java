package com.neo4j.skemadok.generator;

import com.neo4j.skemadok.model.GenerateOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownGeneratorTest {

    private MarkdownGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new MarkdownGenerator(new FreemarkerRenderer());
    }

    @Test
    void fullDocumentContainsExpectedSectionHeadings() throws IOException {
        var doc = SchemaDocumentFixture.standard();
        var out = Files.createTempFile("test-", ".md");
        try {
            generator.generate(doc, out);
            var text = Files.readString(out);

            assertThat(text).contains("## Views");
            assertThat(text).contains("## Node Labels");
            assertThat(text).contains("## Relationship Types");
            assertThat(text).contains("## Indexes");
            assertThat(text).contains("## Constraints");
        } finally {
            Files.deleteIfExists(out);
        }
    }

    @Test
    void fullDocumentContainsEntityNames() throws IOException {
        var doc = SchemaDocumentFixture.standard();
        var out = Files.createTempFile("test-", ".md");
        try {
            generator.generate(doc, out);
            var text = Files.readString(out);

            assertThat(text).contains("Person");
            assertThat(text).contains("Active");
            assertThat(text).contains("WORKS_IN");
        } finally {
            Files.deleteIfExists(out);
        }
    }

    @Test
    void propertyTableUsesMarkdownPipeSyntax() throws IOException {
        var doc = SchemaDocumentFixture.standard();
        var out = Files.createTempFile("test-", ".md");
        try {
            generator.generate(doc, out);
            var text = Files.readString(out);

            // Markdown tables use pipe syntax
            assertThat(text).contains("| Property |");
            assertThat(text).contains("|---|");
        } finally {
            Files.deleteIfExists(out);
        }
    }

    @Test
    void propertyTableIncludesDataSourceColumnWhenEnabled() {
        var doc = SchemaDocumentFixture.standard();
        var preview = generator.preview(doc, new GenerateOptions(true));

        assertThat(preview.labelsBlock()).contains("| Data source |");
        assertThat(preview.labelsBlock()).contains("Core ERP");
    }

    @Test
    void propertyTableOmitsDataSourceColumnWhenDisabled() {
        var doc = SchemaDocumentFixture.standard();
        var preview = generator.preview(doc, new GenerateOptions(false));

        assertThat(preview.labelsBlock()).doesNotContain("Data source");
        assertThat(preview.labelsBlock()).doesNotContain("Core ERP");
    }

    @Test
    void relConnectivityRendersPerPairNotFlattened() {
        var doc = SchemaDocumentFixture.multiConnection();
        var preview = generator.preview(doc, GenerateOptions.defaults());

        assertThat(preview.relationsBlock()).contains("(:Person)-[:REPORTS_TO]->(:Manager)`");
        assertThat(preview.relationsBlock()).contains("(:Contractor)-[:REPORTS_TO]->(:Manager)`");
        // Old smashed format must not appear
        assertThat(preview.relationsBlock()).doesNotContain("(:Contractor | :Person)");
        assertThat(preview.relationsBlock()).doesNotContain("(:Person | :Contractor)");
    }

    @Test
    void relConnectivitySortedByCountDescending() {
        var doc = SchemaDocumentFixture.multiConnection();
        var preview = generator.preview(doc, GenerateOptions.defaults());

        var text = preview.relationsBlock();
        // Person (3000) must appear before Contractor (1500)
        assertThat(text.indexOf("(:Person)-[:REPORTS_TO]")).isLessThan(text.indexOf("(:Contractor)-[:REPORTS_TO]"));
    }

    @Test
    void emptySchemaProducesValidDocumentWithoutEntitySections() throws IOException {
        var doc = SchemaDocumentFixture.empty();
        var out = Files.createTempFile("test-empty-", ".md");
        try {
            generator.generate(doc, out);
            var text = Files.readString(out);

            assertThat(text).doesNotContain("## Node Labels");
            assertThat(text).doesNotContain("## Relationship Types");
            assertThat(text).doesNotContain("## Views");
        } finally {
            Files.deleteIfExists(out);
        }
    }
}
