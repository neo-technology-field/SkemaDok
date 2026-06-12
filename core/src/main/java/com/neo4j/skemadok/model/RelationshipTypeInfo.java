package com.neo4j.skemadok.model;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Metadata for a single relationship type.
 * Start/end labels come from db.schema.visualization(), which reflects the schema graph.
 * The {@code name} field is the stable merge key.
 */
@JsonAutoDetect(
        fieldVisibility    = JsonAutoDetect.Visibility.ANY,
        getterVisibility   = JsonAutoDetect.Visibility.NONE,
        setterVisibility   = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE
)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class RelationshipTypeInfo {

    private String name;
    private long count;
    /** Directed label pairs for this relationship type. Source of truth for connectivity. */
    private List<Connection> connections = new ArrayList<>();
    private List<PropertyInfo> properties = new ArrayList<>();
    private String description = "";

    /** Source system or integration that creates this relationship type. User-provided annotation. */
    private String dataSource = "";

    /**
     * Set to true by the merger when the relationship type no longer exists.
     * Null (omitted from JSON) when present.
     */
    private Boolean removed;

    /**
     * Non-null when this entry represents a family of relationship types that follow a
     * parameterised naming convention (e.g. {@code WORKS_FOR_2024_01}).
     */
    private List<TypeParameter> typeParameters;

    /** The raw relationship type names collapsed into this parameterised entry. Null for non-parameterised types. */
    private List<String> instances;

    /**
     * Property names selected by the user to be shown on the edge label in the canvas.
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> displayProperties = new ArrayList<>();

    /** For Jackson deserialization. */
    private RelationshipTypeInfo() {
    }

    /** Constructor for plain (non-parameterised) relationship types. */
    public RelationshipTypeInfo(
            String name, long count,
            List<Connection> connections,
            List<PropertyInfo> properties) {
        this.name = name;
        this.count = count;
        this.connections = connections;
        this.properties = properties;
    }

    /** Constructor for parameterised relationship type families. */
    public RelationshipTypeInfo(
            String name, long count,
            List<Connection> connections,
            List<PropertyInfo> properties,
            List<TypeParameter> typeParameters, List<String> instances) {
        this(name, count, connections, properties);
        this.typeParameters = typeParameters;
        this.instances = instances;
    }

    public String getName() {
        return name;
    }

    public long getCount() {
        return count;
    }

    /** Returns connections sorted by count descending so the most frequent paths appear first in generated docs. */
    public List<Connection> getConnections() {
        return connections.stream()
                .sorted(Comparator.comparingLong(Connection::count).reversed())
                .toList();
    }

    public List<PropertyInfo> getProperties() {
        return properties;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getDataSource() {
        return dataSource;
    }

    public void setDataSource(String dataSource) {
        this.dataSource = dataSource;
    }

    public Boolean getRemoved() {
        return removed;
    }

    public void setRemoved(Boolean removed) {
        this.removed = removed;
    }

    @JsonIgnore
    public boolean isRemoved() {
        return Boolean.TRUE.equals(removed);
    }

    public List<TypeParameter> getTypeParameters() {
        return typeParameters;
    }

    public List<String> getInstances() {
        return instances;
    }

    @JsonIgnore
    public boolean isParameterized() {
        return typeParameters != null;
    }

    /**
     * Human-readable display name that encodes the variable slots.
     * Plain types return {@code name} unchanged.
     * Parameterised types return e.g. {@code WORKS_FOR_{year}_{quarter}}.
     */
    @JsonIgnore
    public String getDisplayName() {
        if (!isParameterized()) {
            return name;
        }
        // Group names carry a trailing _ convention to avoid collision with plain types
        var baseName = name.endsWith("_") ? name.substring(0, name.length() - 1) : name;
        return baseName + typeParameters.stream()
                .map(p -> "_{" + p.name() + "}")
                .collect(Collectors.joining());
    }

    public List<String> getDisplayProperties() {
        return displayProperties;
    }

    public void setDisplayProperties(List<String> displayProperties) {
        this.displayProperties = displayProperties != null ? displayProperties : Collections.emptyList();
    }
}
