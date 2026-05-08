package com.neo4j.skemadok.model;

import java.util.List;

/**
 * Index as returned by SHOW INDEXES.
 * Purely structural — overwritten on every collect or merge, never annotated by users.
 */
public record IndexInfo(
        String name,
        String type,
        String entityType,
        List<String> labelsOrTypes,
        List<String> properties,
        String state,
        long readCount,
        String indexConfig
) {
}
