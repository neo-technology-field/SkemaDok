package com.neo4j.skemadok.generator;

import com.neo4j.skemadok.model.GenerateOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

class AsciiDocGeneratorTest {

    private AsciiDocGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new AsciiDocGenerator(new FreemarkerRenderer());
    }

    @Test
    void fullDocumentContainsExpectedSectionHeadings() throws IOException {
        var doc = SchemaDocumentFixture.standard();
        var out = Files.createTempFile("test-", ".adoc");
        try {
            generator.generate(doc, out);
            var text = Files.readString(out);

            assertThat(text).contains("== Views");
            assertThat(text).contains("== Node Labels");
            assertThat(text).contains("== Relationship Types");
            assertThat(text).contains("[appendix]");
            assertThat(text).contains("== Indexes");
            assertThat(text).contains("== Constraints");
        } finally {
            Files.deleteIfExists(out);
        }
    }

    @Test
    void fullDocumentContainsEntityNames() throws IOException {
        var doc = SchemaDocumentFixture.standard();
        var out = Files.createTempFile("test-", ".adoc");
        try {
            generator.generate(doc, out);
            var text = Files.readString(out);

            assertThat(text).contains("Person");
            assertThat(text).contains("Active");
            assertThat(text).contains("WORKS_IN");
            assertThat(text).contains("HR Domain");
        } finally {
            Files.deleteIfExists(out);
        }
    }

    @Test
    void propertyTableIncludesDataSourceColumnWhenEnabled() {
        var doc = SchemaDocumentFixture.standard();
        var opts = new GenerateOptions(true);
        var preview = generator.preview(doc, opts);

        assertThat(preview.labelsBlock()).contains("|Data source");
        assertThat(preview.labelsBlock()).contains("Core ERP");
        assertThat(preview.relationsBlock()).contains("HR System");
    }

    @Test
    void propertyTableOmitsDataSourceColumnWhenDisabled() {
        var doc = SchemaDocumentFixture.standard();
        var opts = new GenerateOptions(false);
        var preview = generator.preview(doc, opts);

        assertThat(preview.labelsBlock()).doesNotContain("|Data source");
        assertThat(preview.labelsBlock()).doesNotContain("Core ERP");
    }

    @Test
    void entityLevelDataSourceRenderedWhenEnabled() {
        var doc = SchemaDocumentFixture.standard();
        var opts = new GenerateOptions(true);
        var preview = generator.preview(doc, opts);

        // LabelInfo.dataSource = "Salesforce"
        assertThat(preview.labelsBlock()).contains("Salesforce");
    }

    @Test
    void entityLevelDataSourceOmittedWhenDisabled() {
        var doc = SchemaDocumentFixture.standard();
        var opts = new GenerateOptions(false);
        var preview = generator.preview(doc, opts);

        assertThat(preview.labelsBlock()).doesNotContain("Salesforce");
    }

    @Test
    void previewViewsArePerItem() {
        var doc = SchemaDocumentFixture.standard();
        var preview = generator.preview(doc, GenerateOptions.defaults());

        assertThat(preview.views()).hasSize(1);
        assertThat(preview.views().getFirst().name()).isEqualTo("HR Domain");
        assertThat(preview.views().getFirst().text()).contains("HR Domain");
    }

    @Test
    void emptySchemaProducesValidDocumentWithoutSections() throws IOException {
        var doc = SchemaDocumentFixture.empty();
        var out = Files.createTempFile("test-empty-", ".adoc");
        try {
            generator.generate(doc, out);
            var text = Files.readString(out);

            assertThat(text).doesNotContain("== Node Labels");
            assertThat(text).doesNotContain("== Relationship Types");
            assertThat(text).doesNotContain("== Views");
            // Appendices are always included (may say "no X collected")
            assertThat(text).contains("[appendix]");
        } finally {
            Files.deleteIfExists(out);
        }
    }

    @Test
    void constraintsAndIndexesAppearsInPreview() {
        var doc = SchemaDocumentFixture.standard();
        var preview = generator.preview(doc, GenerateOptions.defaults());

        assertThat(preview.constraintsIndexes()).contains("person_id_idx");
        assertThat(preview.constraintsIndexes()).contains("person_id_unique");
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
}
