package com.neo4j.skemadok.model;

/**
 * The assembled output of a single documentation generation run — content bytes,
 * suggested download filename, and the MIME type for the HTTP response.
 */
public record GenerationResult(byte[] content, String filename, String contentType) {
}
