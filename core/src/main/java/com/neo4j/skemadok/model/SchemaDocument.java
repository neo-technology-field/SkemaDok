package com.neo4j.skemadok.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Root document for the schema JSON file.
 * Produced by the collector, enriched by the user via the UI, consumed by the generator.
 * This file is the handoff artefact between customer and Neo4j user.
 * <p>
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} is intentional: it lets older schema files
 * load successfully when new structural fields have been added, and it silently drops derived
 * fields (like the formerly-serialised {@code parameterized}) that were accidentally written
 * by earlier versions.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SchemaDocument {

    /**
     * Format version for the schema JSON file.
     */
    @JsonProperty("$schema")
    private String schemaVersion = "skemadok/1.0";

    private Instant capturedAt;
    private Instant lastEditedAt;
    private String databaseAddress;
    private String databaseName;
    private String databaseVersion;

    private List<LabelInfo> nodeLabels = new ArrayList<>();
    private List<RelationshipTypeInfo> relationshipTypes = new ArrayList<>();
    private List<IndexInfo> indexes = new ArrayList<>();
    private List<ConstraintInfo> constraints = new ArrayList<>();
    private List<ViewDefinition> views = new ArrayList<>();

    // needed for Jackson
    private SchemaDocument() {
    }

    @JsonCreator
    public SchemaDocument(
            @JsonProperty("databaseAddress") String databaseAddress,
            @JsonProperty("databaseName") String databaseName,
            @JsonProperty("databaseVersion") String databaseVersion,
            @JsonProperty("capturedAt") Instant capturedAt) {
        this.databaseAddress = databaseAddress;
        this.databaseName = databaseName;
        this.databaseVersion = databaseVersion;
        this.capturedAt = capturedAt;
    }

    @JsonProperty("$schema")
    public String getSchemaVersion() {
        return schemaVersion;
    }

    @JsonProperty("$schema")
    public void setSchemaVersion(String schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }

    public Instant getLastEditedAt() {
        return lastEditedAt;
    }

    public void setLastEditedAt(Instant lastEditedAt) {
        this.lastEditedAt = lastEditedAt;
    }

    public String getDatabaseAddress() {
        return databaseAddress;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public String getDatabaseVersion() {
        return databaseVersion;
    }

    public List<LabelInfo> getNodeLabels() {
        return nodeLabels;
    }

    public void setNodeLabels(List<LabelInfo> nodeLabels) {
        this.nodeLabels = nodeLabels;
    }

    public List<RelationshipTypeInfo> getRelationshipTypes() {
        return relationshipTypes;
    }

    public void setRelationshipTypes(List<RelationshipTypeInfo> relationshipTypes) {
        this.relationshipTypes = relationshipTypes;
    }

    public List<IndexInfo> getIndexes() {
        return indexes;
    }

    public void setIndexes(List<IndexInfo> indexes) {
        this.indexes = indexes;
    }

    public List<ConstraintInfo> getConstraints() {
        return constraints;
    }

    public void setConstraints(List<ConstraintInfo> constraints) {
        this.constraints = constraints;
    }

    public List<ViewDefinition> getViews() {
        return views;
    }

    public void setViews(List<ViewDefinition> views) {
        this.views = views;
    }
}
