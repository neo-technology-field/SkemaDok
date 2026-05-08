package com.neo4j.skemadok.model;

import java.util.List;

/**
 * Constraint as returned by SHOW CONSTRAINTS.
 * Purely structural — overwritten on every collect or merge, never annotated by user.
 */
public record ConstraintInfo(
        String name,
        String type,
        String entityType,
        List<String> labelsOrTypes,
        List<String> properties
) {
}
