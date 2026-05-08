package com.neo4j.skemadok.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persisted canvas state for a view.
 *
 * <p>{@code nodes} maps each label name to its canvas position. Labels present in the view
 * but without an entry here are auto-placed by the frontend on first render.
 *
 * <p>{@code edgeWaypoints} stores bend points per edge, keyed as
 * {@code "RelType--StartLabel--EndLabel"}. Empty when the user has not added any bends.
 *
 * <p>{@code zoom}, {@code panX}, {@code panY} restore the viewport so the user
 * returns to the same position they left.
 */
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
public class CanvasLayout {

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, NodePosition> nodes = new LinkedHashMap<>();

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, List<NodePosition>> edgeWaypoints = new LinkedHashMap<>();

    private double zoom = 1.0;
    private double panX = 0.0;
    private double panY = 0.0;

    /** Hierarchy display mode chosen by the user: "none", "edges", or "boxes". */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String hierarchyMode;

    public CanvasLayout() {
    }

    public Map<String, NodePosition> getNodes() {
        return nodes;
    }

    public void setNodes(Map<String, NodePosition> nodes) {
        this.nodes = nodes;
    }

    public Map<String, List<NodePosition>> getEdgeWaypoints() {
        return edgeWaypoints;
    }

    public void setEdgeWaypoints(Map<String, List<NodePosition>> edgeWaypoints) {
        this.edgeWaypoints = edgeWaypoints;
    }

    public double getZoom() {
        return zoom;
    }

    public void setZoom(double zoom) {
        this.zoom = zoom;
    }

    public double getPanX() {
        return panX;
    }

    public void setPanX(double panX) {
        this.panX = panX;
    }

    public double getPanY() {
        return panY;
    }

    public void setPanY(double panY) {
        this.panY = panY;
    }

    public String getHierarchyMode() {
        return hierarchyMode;
    }

    public void setHierarchyMode(String hierarchyMode) {
        this.hierarchyMode = hierarchyMode;
    }
}
