package com.neo4j.skemadok.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A property as observed on a label or relationship type.
 * Types come from db.schema.nodeTypeProperties / db.schema.relTypeProperties.
 * Multiple types indicate schema drift (same property name, different value types across nodes).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record PropertyInfo(
        String name,
        List<String> types,
        boolean nullable,
        String description,
        String dataSource
) {
    public PropertyInfo {
        if (description == null) description = "";
        if (dataSource == null) dataSource = "";
    }

    /** Structural constructor — annotation fields default to empty strings. */
    public PropertyInfo(String name, List<String> types, boolean nullable) {
        this(name, types, nullable, "", "");
    }

    public PropertyInfo withNullable(boolean nullable) {
        return new PropertyInfo(name, types, nullable, description, dataSource);
    }

    public PropertyInfo withAnnotations(String description, String dataSource) {
        return new PropertyInfo(name, types, nullable, description, dataSource);
    }
}
