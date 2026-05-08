package com.neo4j.skemadok.model;

/**
 * A named, format-ready content block returned by the preview endpoint.
 * The text is in format-native syntax (AsciiDoc, Markdown) or HTML depending
 * on the requested format — the frontend copies it directly to the clipboard.
 */
public record SectionItem(String name, String text) {}
