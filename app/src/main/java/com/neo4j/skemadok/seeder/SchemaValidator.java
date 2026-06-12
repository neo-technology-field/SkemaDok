package com.neo4j.skemadok.seeder;

import com.neo4j.skemadok.model.LabelInfo;
import com.neo4j.skemadok.model.LabelRole;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pre-seed structural validation of a {@link com.neo4j.skemadok.model.SchemaDocument}.
 *
 * <p>Two checks, both treated as hard errors because either makes seeding impossible:
 * <ul>
 *   <li><b>HIERARCHY chain</b> — every HIERARCHY label's {@code extends} chain must terminate
 *       at an ENTITY label. {@code null} extends, missing/removed targets, TAG roots, and
 *       cycles all abort.</li>
 *   <li><b>TAG annotation</b> — every non-removed TAG label must declare its
 *       {@code taggedEntities}. Without it the seeder has no host to tag.</li>
 * </ul>
 *
 * <p>Errors from both checks are collected and reported together so the user can fix the JSON
 * in one pass.
 */
final class SchemaValidator {

    private final Map<String, LabelInfo> labelsByName;

    SchemaValidator(Map<String, LabelInfo> labelsByName) {
        this.labelsByName = labelsByName;
    }

    /**
     * Throws {@link IllegalStateException} with a combined offender list if either hierarchy
     * chains or tag annotations are inconsistent. Silent on success.
     */
    void validate() {
        var hierarchyErrors = invalidHierarchyChains();
        var orphanTags = orphanTags();
        if (hierarchyErrors.isEmpty() && orphanTags.isEmpty()) {
            return;
        }
        throw new IllegalStateException(buildMessage(orphanTags, hierarchyErrors));
    }

    private List<String> invalidHierarchyChains() {
        return labelsByName.values().stream()
                .filter(l -> !l.isRemoved())
                .filter(l -> l.getRole() == LabelRole.HIERARCHY)
                .map(this::walkChain)
                .filter(Objects::nonNull)
                .toList();
    }

    private List<LabelInfo> orphanTags() {
        return labelsByName.values().stream()
                .filter(l -> !l.isRemoved())
                .filter(l -> l.getRole() == LabelRole.TAG)
                .filter(l -> l.getTaggedEntities() == null || l.getTaggedEntities().isEmpty())
                .toList();
    }

    /**
     * Walks the {@code extends} chain from {@code start} upwards. Returns an error message if
     * the chain is broken (null/missing target, TAG root, cycle), or {@code null} when the
     * chain terminates cleanly at an ENTITY label.
     */
    private String walkChain(LabelInfo start) {
        var visited = new LinkedHashSet<String>();
        var current = start;
        while (true) {
            if (!visited.add(current.getName())) {
                return "HIERARCHY label '" + start.getName() + "' has a cycle in its extends chain: "
                        + String.join(" -> ", visited) + " -> " + current.getName();
            }
            if (current.getRole() == LabelRole.ENTITY) {
                return null;
            }
            if (current.getRole() == LabelRole.TAG) {
                return "HIERARCHY label '" + start.getName()
                        + "' extends chain ends at TAG label '" + current.getName()
                        + "'; expected an ENTITY root";
            }
            var parentName = current.getExtendsLabel();
            if (parentName == null) {
                return "HIERARCHY label '" + current.getName()
                        + "' has no 'extends' target (encountered while resolving '"
                        + start.getName() + "')";
            }
            var parent = labelsByName.get(parentName);
            if (parent == null || parent.isRemoved()) {
                return "HIERARCHY label '" + current.getName() + "' extends '" + parentName
                        + "' which does not exist or is removed";
            }
            current = parent;
        }
    }

    private static String buildMessage(List<LabelInfo> orphanTags, List<String> hierarchyErrors) {
        var sb = new StringBuilder("Schema annotations are incomplete — cannot seed:\n");
        if (!orphanTags.isEmpty()) {
            sb.append("  TAG labels missing taggedEntities:\n");
            for (var tag : orphanTags) {
                sb.append("    - ").append(tag.getName())
                        .append(" (").append(tag.getNodeCount()).append(" nodes)");
                var coLabels = tag.getCoLabels();
                if (coLabels != null && !coLabels.isEmpty()) {
                    sb.append("  co-labels=").append(coLabels);
                }
                sb.append("\n");
            }
        }
        if (!hierarchyErrors.isEmpty()) {
            sb.append("  HIERARCHY labels with broken extends chain:\n");
            for (var error : hierarchyErrors) {
                sb.append("    - ").append(error).append("\n");
            }
        }
        sb.append("Either populate the missing annotations, or re-classify these labels.");
        return sb.toString();
    }
}
