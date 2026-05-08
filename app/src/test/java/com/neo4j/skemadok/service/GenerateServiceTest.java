package com.neo4j.skemadok.service;

import com.neo4j.skemadok.generator.DocumentGenerator;
import com.neo4j.skemadok.model.GenerateOptions;
import com.neo4j.skemadok.model.PreviewResponse;
import com.neo4j.skemadok.model.SchemaDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GenerateServiceTest {

    @Mock
    private DocumentGenerator generator;

    private GenerateService service;
    private SchemaDocument doc;

    @BeforeEach
    void setUp() {
        service = new GenerateService(List.of(generator));
        when(generator.getFormat()).thenReturn("testformat");

        doc = new SchemaDocument("bolt://localhost:7687", "testdb", "5.26.0",
                Instant.parse("2026-01-15T10:00:00Z"));
    }

    // ---- preview -----------------------------------------------------------

    @Test
    void preview_delegatesToGenerator() {
        var expected = new PreviewResponse(List.of(), "", "", "");
        when(generator.preview(eq(doc), any())).thenReturn(expected);

        var result = service.preview(doc, "testformat", false);

        assertThat(result).isSameAs(expected);
        verify(generator).preview(eq(doc), any());
    }

    @Test
    void preview_forwardsIncludeDataSourceFlag() {
        ArgumentCaptor<GenerateOptions> optionsCaptor = ArgumentCaptor.forClass(GenerateOptions.class);
        when(generator.preview(any(), optionsCaptor.capture())).thenReturn(new PreviewResponse(List.of(), "", "", ""));

        service.preview(doc, "testformat", true);

        assertThat(optionsCaptor.getValue().includeDataSource()).isTrue();
    }

    @Test
    void preview_unknownFormat_throwsBadRequest() {
        assertThatThrownBy(() -> service.preview(doc, "unknown", false))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ---- generateDownload — single file ------------------------------------

    @Test
    void generateDownload_singleFile_returnsBytesAndFilename() throws IOException {
        byte[] fileContent = "doc content".getBytes();

        when(generator.getContentType()).thenReturn("text/plain");
        when(generator.generateForDownload(any(), any(), any(), any())).thenAnswer(invocation -> {
            Path tempDir = invocation.getArgument(2);
            Path output = tempDir.resolve("schema-doc.txt");
            Files.write(output, fileContent);
            return output;
        });

        var result = service.generateDownload(doc, "testformat", false, Map.of());

        assertThat(result.filename()).isEqualTo("schema-doc.txt");
        assertThat(result.contentType()).isEqualTo("text/plain");
        assertThat(result.content()).isEqualTo(fileContent);
    }

    // ---- generateDownload — directory → zip --------------------------------

    @Test
    void generateDownload_directory_returnsZip() throws IOException {
        when(generator.generateForDownload(any(), any(), any(), any())).thenAnswer(invocation -> {
            Path tempDir = invocation.getArgument(2);
            Path subDir = tempDir.resolve("output");
            Files.createDirectory(subDir);
            Files.writeString(subDir.resolve("document.adoc"), "= Title");
            Files.createDirectory(subDir.resolve("views"));
            Files.write(subDir.resolve("views").resolve("hr-domain.png"), new byte[]{1, 2, 3});
            return subDir;
        });

        var result = service.generateDownload(doc, "testformat", false, Map.of());

        assertThat(result.filename()).isEqualTo("testdb-schema.zip");
        assertThat(result.contentType()).isEqualTo("application/zip");

        // Verify the zip contains both expected entries
        var entries = zipEntryNames(result.content());
        assertThat(entries).containsExactlyInAnyOrder("document.adoc", "views/hr-domain.png");
    }

    // ---- generateDownload — image decoding ---------------------------------

    @Test
    void generateDownload_stripsDataUrlPrefixBeforeDecoding() throws IOException {
        byte[] pngBytes = {(byte) 0x89, 'P', 'N', 'G'};
        String dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(pngBytes);

        when(generator.generateForDownload(any(), any(), any(), any())).thenAnswer(invocation -> {
            Path tempDir = invocation.getArgument(2);
            Path output = tempDir.resolve("out.txt");
            Files.writeString(output, "");
            return output;
        });

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, byte[]>> imagesCaptor = ArgumentCaptor.forClass(Map.class);
        when(generator.getContentType()).thenReturn("text/plain");

        service.generateDownload(doc, "testformat", false, Map.of("HR Domain", dataUrl));

        verify(generator).generateForDownload(any(), imagesCaptor.capture(), any(), any());
        assertThat(imagesCaptor.getValue().get("HR Domain")).isEqualTo(pngBytes);
    }

    @Test
    void generateDownload_nullImages_passesEmptyMap() throws IOException {
        when(generator.generateForDownload(any(), any(), any(), any())).thenAnswer(invocation -> {
            Path tempDir = invocation.getArgument(2);
            Path output = tempDir.resolve("out.txt");
            Files.writeString(output, "");
            return output;
        });
        when(generator.getContentType()).thenReturn("text/plain");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, byte[]>> imagesCaptor = ArgumentCaptor.forClass(Map.class);

        service.generateDownload(doc, "testformat", false, null);

        verify(generator).generateForDownload(any(), imagesCaptor.capture(), any(), any());
        assertThat(imagesCaptor.getValue()).isEmpty();
    }

    // ---- generateDownload — unknown format ---------------------------------

    @Test
    void generateDownload_unknownFormat_throwsBadRequest() {
        assertThatThrownBy(() -> service.generateDownload(doc, "unknown", false, Map.of()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ---- Helpers -----------------------------------------------------------

    private List<String> zipEntryNames(byte[] zipBytes) throws IOException {
        var names = new java.util.ArrayList<String>();
        try (var zis = new ZipInputStream(new java.io.ByteArrayInputStream(zipBytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                names.add(entry.getName());
            }
        }
        return names;
    }
}
