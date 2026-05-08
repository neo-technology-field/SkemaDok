package com.neo4j.skemadok.model;

/**
 * Canvas position for a single node (label or relationship type) in a view.
 * Coordinates are in the canvas coordinate space — framework-agnostic.
 */
public record NodePosition(double x, double y) {
}
