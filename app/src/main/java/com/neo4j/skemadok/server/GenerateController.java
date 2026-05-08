package com.neo4j.skemadok.server;

import com.neo4j.skemadok.model.GenerateDownloadRequest;
import com.neo4j.skemadok.model.GeneratePreviewRequest;
import com.neo4j.skemadok.model.PreviewResponse;
import com.neo4j.skemadok.service.GenerateService;
import com.neo4j.skemadok.service.SchemaService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * REST endpoints for the documentation generation workflow.
 * Preview returns copyable text blocks for the selected format.
 * Download assembles the full artefact (with images) and streams it to the browser.
 */
@RestController
@RequestMapping("/api/generate")
public class GenerateController {

    private final SchemaService schemaService;
    private final GenerateService generateService;

    public GenerateController(SchemaService schemaService, GenerateService generateService) {
        this.schemaService = schemaService;
        this.generateService = generateService;
    }

    /**
     * Returns copyable section blocks for the given format and rendering options.
     * No images are involved — the client uses this to populate the preview UI.
     */
    @PostMapping("/preview")
    public PreviewResponse preview(@RequestBody GeneratePreviewRequest request) {
        var doc = schemaService.load();
        return generateService.preview(doc, request.format(), request.includeDataSource());
    }

    /**
     * Generates the full download artefact (text + images) and returns it as a file stream.
     * Multi-file formats (AsciiDoc, Markdown with images) are returned as a zip archive.
     */
    @PostMapping("/download")
    public ResponseEntity<byte[]> download(@RequestBody GenerateDownloadRequest request) throws IOException {
        var doc = schemaService.load();
        var result = generateService.generateDownload(
                doc, request.format(), request.includeDataSource(), request.viewImages());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + result.filename() + "\"")
                .header(HttpHeaders.CONTENT_TYPE, result.contentType())
                .body(result.content());
    }
}
