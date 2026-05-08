package com.neo4j.skemadok.model;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Metadata for a single node label, combining collected schema data with user annotations.
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
public class LabelInfo {

    private String name;
    private long nodeCount;
    private List<String> coLabels;
    private LabelRole role = LabelRole.ENTITY;
    private List<PropertyInfo> properties = new ArrayList<>();
    private String description = "";
    private String dataSource = "";

    /**
     * The label this one specialises, e.g. "Person" for an "Employee" label.
     * User-provided; null when no hierarchy relationship is known.
     * Serialised as "extends" in JSON.
     */
    @JsonProperty("extends")
    private String extendsLabel;

    /**
     * For TAG-role labels: the entity labels this tag qualifies. Supports multiple entity types,
     * e.g. in RBAC scenarios where one role tag applies across Person, ServiceAccount, and Team.
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> taggedEntities = new ArrayList<>();

    /** CSS hex colour chosen by the user. Null means use the default theme colour. */
    private String color;

    /**
     * Property names selected by the user to be shown inside the label box on the canvas.
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> displayProperties = new ArrayList<>();

    /**
     * Set to true by the merger when the label no longer exists in the database.
     * Null (omitted from JSON) when the label is present.
     */
    private Boolean removed;

    /** For Jackson deserialization. */
    private LabelInfo() {
    }

    public LabelInfo(String name, long nodeCount, List<String> coLabel,  List<PropertyInfo> properties) {
        this.name = name;
        this.nodeCount = nodeCount;
        this.coLabels = coLabel;
        this.properties = properties;
    }

    public String getName() {
        return name;
    }

    public long getNodeCount() {
        return nodeCount;
    }

    public List<String> getCoLabels() {
        return coLabels;
    }

    public LabelRole getRole() {
        return role;
    }

    public void setRole(LabelRole role) {
        this.role = role;
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

    @JsonProperty("extends")
    public String getExtendsLabel() {
        return extendsLabel;
    }

    public void setExtendsLabel(String extendsLabel) {
        this.extendsLabel = extendsLabel;
    }

    public List<String> getTaggedEntities() {
        return taggedEntities;
    }

    public void setTaggedEntities(List<String> taggedEntities) {
        this.taggedEntities = taggedEntities != null ? taggedEntities : new ArrayList<>();
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public List<String> getDisplayProperties() {
        return displayProperties;
    }

    public void setDisplayProperties(List<String> displayProperties) {
        this.displayProperties = displayProperties != null ? displayProperties : Collections.emptyList();
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
}
