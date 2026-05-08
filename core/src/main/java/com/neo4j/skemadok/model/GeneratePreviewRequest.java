package com.neo4j.skemadok.model;

/**
 * Preview request from the UI, selecting a format and optional rendering flags.
 */
public record GeneratePreviewRequest(String format, boolean includeDataSource) {}
