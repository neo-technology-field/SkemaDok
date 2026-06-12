package com.neo4j.skemadok.seeder;

import com.neo4j.skemadok.model.ConstraintInfo;
import com.neo4j.skemadok.model.LabelInfo;
import com.neo4j.skemadok.model.LabelRole;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Immutable view over a schema's constraint list, surfacing the two questions the planner and
 * executor repeatedly ask:
 * <ul>
 *   <li>"Which properties on owner X must I generate unique values for?" — used by the value
 *       generator to satisfy UNIQUENESS / KEY constraints during node and relationship
 *       creation.</li>
 *   <li>"Which ENTITY labels declare no uniqueness/key constraint at all?" — used to surface
 *       audit warnings in the breakdown so the user can spot under-annotated labels.</li>
 * </ul>
 *
 * <p>Property-uniqueness lookup includes both node and relationship constraints; the
 * entity-without-uniqueness audit considers only ENTITY-role node labels (a NODE_KEY or
 * UNIQUENESS counts; existence-only constraints do not).
 */
public record UniquenessConstraints(
        Map<String, Set<String>> uniquePropsByOwner,
        Set<String> entityLabelsWithUniqueness) {

    /** Builds the view from a schema's raw constraint list (may be {@code null} or empty). */
    public static UniquenessConstraints from(List<ConstraintInfo> constraints) {
        var propsByOwner = new HashMap<String, Set<String>>();
        var entitiesWithUniqueness = new HashSet<String>();
        if (constraints == null) {
            return new UniquenessConstraints(Map.copyOf(propsByOwner), Set.copyOf(entitiesWithUniqueness));
        }
        for (var c : constraints) {
            var type = c.type() == null ? "" : c.type().toUpperCase(Locale.ROOT);
            if (c.labelsOrTypes() == null || c.labelsOrTypes().isEmpty()) {
                continue;
            }
            var owner = c.labelsOrTypes().getFirst();
            if (isEntityUniquenessOrKey(type)) {
                entitiesWithUniqueness.add(owner);
            }
            if (isAnyUniquenessOrKey(type)
                    && c.properties() != null && !c.properties().isEmpty()) {
                propsByOwner.computeIfAbsent(owner, k -> new HashSet<>()).addAll(c.properties());
            }
        }
        return new UniquenessConstraints(Map.copyOf(propsByOwner), Set.copyOf(entitiesWithUniqueness));
    }

    /** Properties on {@code owner} that must receive unique-per-index values during seeding. */
    public Set<String> uniquePropertiesOf(String owner) {
        return uniquePropsByOwner.getOrDefault(owner, Set.of());
    }

    /**
     * Names of non-removed ENTITY labels in {@code entities} that lack any UNIQUENESS /
     * NODE_KEY constraint. Order matches the input collection so the breakdown can render
     * them deterministically.
     */
    List<String> entitiesWithoutUniqueness(Collection<LabelInfo> entities) {
        return entities.stream()
                .filter(l -> !l.isRemoved())
                .filter(l -> l.getRole() == LabelRole.ENTITY)
                .map(LabelInfo::getName)
                .filter(name -> !entityLabelsWithUniqueness.contains(name))
                .toList();
    }

    private static boolean isEntityUniquenessOrKey(String type) {
        return switch (type) {
            case "UNIQUENESS", "NODE_UNIQUENESS", "NODE_KEY" -> true;
            default -> false;
        };
    }

    private static boolean isAnyUniquenessOrKey(String type) {
        return switch (type) {
            case "UNIQUENESS", "NODE_UNIQUENESS",
                 "RELATIONSHIP_UNIQUENESS", "REL_UNIQUENESS",
                 "NODE_KEY",
                 "RELATIONSHIP_KEY", "REL_KEY" -> true;
            default -> false;
        };
    }
}
