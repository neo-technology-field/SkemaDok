package com.neo4j.skemadok.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neo4j.skemadok.model.SchemaDocument;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Thin file-backed service for the schema document.
 * No in-memory document state — every read comes from disk, every write goes to disk.
 */
@Service
public class SchemaService {

    private final ObjectMapper objectMapper;
    private Path filePath;

    public SchemaService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Registers the schema file path at UI startup.
     * Must be called before any request is handled.
     */
    public void setFilePath(Path path) {
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Schema file not found: " + path.toAbsolutePath());
        }
        this.filePath = path;
    }

    /**
     * Returns the filename of the schema file (e.g. {@code schema.json}).
     */
    public String getFileName() {
        assertReady();
        return filePath.getFileName().toString();
    }

    /**
     * Reads the schema document from disk.
     */
    public SchemaDocument load() {
        assertReady();
        try {
            return objectMapper.readValue(filePath.toFile(), SchemaDocument.class);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to read schema document: " + e.getMessage());
        }
    }

    /**
     * Sets lastEditedAt and writes the document to disk.
     */
    public void save(SchemaDocument document) {
        assertReady();
        document.setLastEditedAt(Instant.now());
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(filePath.toFile(), document);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to persist schema document: " + e.getMessage());
        }
    }

    private void assertReady() {
        if (filePath == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Schema not loaded. The UI is still starting up.");
        }
    }
}
