package com.neo4j.skemadok.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * A user-defined subset of the schema for a logical domain area.
 *
 * <p>Labels and relationship types can belong to multiple views. The {@code layout} tracks
 * per-node canvas positions and viewport state so the user's arrangement is preserved
 * across sessions. Nodes added to a view but not yet positioned are auto-placed by the
 * frontend on first render.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ViewDefinition {

    private String name;
    private String description = "";
    private List<String> labels = new ArrayList<>();
    private List<String> relationshipTypes = new ArrayList<>();

    /**
     * Per-node positions and viewport state. Null until the user opens the view.
     */
    private CanvasLayout layout;

    public ViewDefinition() {
    }

    public ViewDefinition(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<String> getLabels() {
        return labels;
    }

    public void setLabels(List<String> labels) {
        this.labels = labels;
    }

    public List<String> getRelationshipTypes() {
        return relationshipTypes;
    }

    public void setRelationshipTypes(List<String> relationshipTypes) {
        this.relationshipTypes = relationshipTypes;
    }

    public CanvasLayout getLayout() {
        return layout;
    }

    public void setLayout(CanvasLayout layout) {
        this.layout = layout;
    }
}
