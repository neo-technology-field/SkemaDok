package com.neo4j.skemadok.service;

import com.neo4j.skemadok.generator.DocumentGenerator;
import com.neo4j.skemadok.model.GenerateOptions;
import com.neo4j.skemadok.model.GenerationResult;
import com.neo4j.skemadok.model.PreviewResponse;
import com.neo4j.skemadok.model.SchemaDocument;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Orchestrates documentation generation — generator lookup, image decoding, temp-directory
 * lifecycle, and ZIP assembly.
 */
@Service
public class GenerateService {

    private final List<DocumentGenerator> generators;

    public GenerateService(List<DocumentGenerator> generators) {
        this.generators = generators;
    }

    /**
     * Produces the preview blocks for the requested format.
     *
     * @param doc               schema document to render
     * @param format            format identifier (case-insensitive)
     * @param includeDataSource whether to include data-source annotations in output
     */
    public PreviewResponse preview(SchemaDocument doc, String format, boolean includeDataSource) {
        var options = new GenerateOptions(includeDataSource);
        return findGenerator(format).preview(doc, options);
    }

    /**
     * Generates the full download artefact and returns its content with metadata.
     * Multi-file formats are zipped transparently.
     *
     * @param doc               schema document to render
     * @param format            format identifier (case-insensitive)
     * @param includeDataSource whether to include data-source annotations in output
     * @param rawImages         data-URL-encoded PNGs keyed by view name; null or empty means no images
     */
    public GenerationResult generateDownload(SchemaDocument doc, String format,
                                             boolean includeDataSource,
                                             Map<String, String> rawImages) throws IOException {
        var generator = findGenerator(format);
        var options = new GenerateOptions(includeDataSource);
        var viewImages = decodeImages(rawImages);

        var tempDir = Files.createTempDirectory("skemadok-generate-");
        try {
            var result = generator.generateForDownload(doc, viewImages, tempDir, options);

            byte[] content;
            String filename;
            String contentType;

            if (Files.isDirectory(result)) {
                content = zipDirectory(result);
                filename = doc.getDatabaseName() + "-schema.zip";
                contentType = "application/zip";
            } else {
                content = Files.readAllBytes(result);
                filename = result.getFileName().toString();
                contentType = generator.getContentType();
            }

            return new GenerationResult(content, filename, contentType);
        } finally {
            deleteDirectory(tempDir);
        }
    }

    // ---- Private helpers ---------------------------------------------------

    private DocumentGenerator findGenerator(String format) {
        return generators.stream()
                .filter(g -> g.getFormat().equalsIgnoreCase(format))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Unknown format: " + format));
    }

    private Map<String, byte[]> decodeImages(Map<String, String> rawImages) {
        if (rawImages == null || rawImages.isEmpty()) {
            return Map.of();
        }
        Map<String, byte[]> decoded = new LinkedHashMap<>();
        for (var entry : rawImages.entrySet()) {
            String dataUrl = entry.getValue();
            int comma = dataUrl.indexOf(',');
            String base64 = comma >= 0 ? dataUrl.substring(comma + 1) : dataUrl;
            decoded.put(entry.getKey(), Base64.getDecoder().decode(base64));
        }
        return decoded;
    }

    private byte[] zipDirectory(Path dir) throws IOException {
        var byteOutStream = new ByteArrayOutputStream();
        try (var zos = new ZipOutputStream(byteOutStream)) {
            Files.walk(dir)
                    .filter(p -> !Files.isDirectory(p))
                    .forEach(file -> {
                        try {
                            zos.putNextEntry(new ZipEntry(dir.relativize(file).toString()));
                            Files.copy(file, zos);
                            zos.closeEntry();
                        } catch (IOException e) {
                            throw new RuntimeException("Failed to add file to zip: " + file, e);
                        }
                    });
        }
        return byteOutStream.toByteArray();
    }

    private void deleteDirectory(Path dir) {
        try {
            Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }
}
