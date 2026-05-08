package com.neo4j.skemadok.model;

import java.util.List;

/**
 * Copyable documentation sections returned by the preview endpoint.
 * Views are per-item because each has an associated image; labels and relationships are
 * aggregated into single blocks so the user copies a whole section at once.
 */
public record PreviewResponse(
        List<SectionItem> views,
        String labelsBlock,
        String relationsBlock,
        String constraintsIndexes
) {}
