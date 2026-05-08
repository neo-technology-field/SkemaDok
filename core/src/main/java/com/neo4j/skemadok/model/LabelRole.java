package com.neo4j.skemadok.model;

/**
 * Detected role of a label in the graph schema, determined by co-occurrence heuristics.
 * Consultants can override this after collection.
 */
public enum LabelRole {
    /**
     * Primary node type with own identity and properties.
     */
    ENTITY,

    /**
     * Classifier label — always co-occurs with one or more entity labels, never alone.
     * Carries no properties of its own beyond what the host entity already has.
     */
    TAG,

    /**
     * Subtype label that extends a base entity with additional type-specific properties.
     * Always co-occurs with the base entity label. Not rendered as a standalone node in the canvas.
     */
    HIERARCHY
}
