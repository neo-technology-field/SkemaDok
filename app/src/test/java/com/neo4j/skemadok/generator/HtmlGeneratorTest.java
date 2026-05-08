package com.neo4j.skemadok.generator;

import com.neo4j.skemadok.model.GenerateOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

class HtmlGeneratorTest {

    private HtmlGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new HtmlGenerator(new FreemarkerRenderer());
    }

    @Test
    void previewReturnsEmptyResponse() {
        var doc = SchemaDocumentFixture.standard();
        var preview = generator.preview(doc, GenerateOptions.defaults());

        assertThat(preview.views()).isEmpty();
        assertThat(preview.labelsBlock()).isEmpty();
        assertThat(preview.relationsBlock()).isEmpty();
        assertThat(preview.constraintsIndexes()).isEmpty();
    }

    @Test
    void generatedFileIsValidHtml() throws IOException {
        var doc = SchemaDocumentFixture.standard();
        var dir = Files.createTempDirectory("html-gen-test-");
        try {
            var result = generator.generateForDownload(doc, java.util.Map.of(), dir, GenerateOptions.defaults());
            var text = Files.readString(result);

            assertThat(text).contains("<!DOCTYPE html>");
            assertThat(text).contains("<table>");
            assertThat(text).contains("Person");
            assertThat(text).contains("WORKS_IN");
        } finally {
            Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException ignored) {}
            });
        }
    }

    @Test
    void dataSourceColumnAppearsWhenEnabled() throws IOException {
        var doc = SchemaDocumentFixture.standard();
        var dir = Files.createTempDirectory("html-ds-test-");
        try {
            var result = generator.generateForDownload(doc, java.util.Map.of(), dir, new GenerateOptions(true));
            var text = Files.readString(result);

            assertThat(text).contains("Data source");
            assertThat(text).contains("Core ERP");
        } finally {
            Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException ignored) {}
            });
        }
    }

    @Test
    void dataSourceColumnAbsentWhenDisabled() throws IOException {
        var doc = SchemaDocumentFixture.standard();
        var dir = Files.createTempDirectory("html-nods-test-");
        try {
            var result = generator.generateForDownload(doc, java.util.Map.of(), dir, new GenerateOptions(false));
            var text = Files.readString(result);

            assertThat(text).doesNotContain("Data source");
            assertThat(text).doesNotContain("Core ERP");
        } finally {
            Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException ignored) {}
            });
        }
    }
}
