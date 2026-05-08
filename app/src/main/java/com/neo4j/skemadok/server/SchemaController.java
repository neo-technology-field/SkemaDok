package com.neo4j.skemadok.server;

import com.neo4j.skemadok.model.SchemaDocument;
import com.neo4j.skemadok.service.SchemaService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * REST API for the Vue frontend.
 * Two endpoints: read the document from disk, or replace it entirely.
 * All annotation editing happens in the browser; the server has no in-memory state.
 */
@RestController
@RequestMapping("/api")
public class SchemaController {

    private final SchemaService schemaService;

    public SchemaController(SchemaService schemaService) {
        this.schemaService = schemaService;
    }

    @GetMapping("/schema")
    public SchemaDocument getSchema() {
        return schemaService.load();
    }

    @GetMapping("/schema/filename")
    public String getSchemaFilename() {
        return schemaService.getFileName();
    }

    @PutMapping("/schema")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveSchema(@RequestBody SchemaDocument document) {
        schemaService.save(document);
    }
}
