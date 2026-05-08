package com.neo4j.skemadok.model;

import java.util.Map;

/**
 * Download request carrying the target format, per-view PNG images captured by the browser
 * (base64-encoded data URLs), and optional rendering flags.
 */
public record GenerateDownloadRequest(String format, Map<String, String> viewImages, boolean includeDataSource) {}
