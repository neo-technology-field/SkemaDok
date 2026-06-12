package com.neo4j.skemadok.seeder;

import com.neo4j.skemadok.model.ConstraintInfo;

import java.util.List;
import java.util.Locale;

/**
 * Renders the {@code CREATE CONSTRAINT …} Cypher for a captured {@link ConstraintInfo}.
 *
 * <p>The mapping covers the constraint-type strings the collector emits — both the canonical
 * Neo4j 5 names ({@code UNIQUENESS}, {@code NODE_KEY}, …) and the alternative spellings
 * ({@code NODE_UNIQUENESS}, {@code REL_KEY}, …) that show up in older databases. Unknown types
 * return {@code null} so the executor can skip them with a warning rather than crash.
 */
public final class ConstraintDdl {

    private ConstraintDdl() {
    }

    /**
     * @return the {@code CREATE CONSTRAINT …} Cypher for {@code constraint}, or {@code null}
     * when the constraint type is unsupported or the schema entry is incomplete
     * (missing label or properties).
     */
    public static String build(ConstraintInfo constraint) {
        var isNode = "node".equalsIgnoreCase(constraint.entityType());
        var labelOrType = (constraint.labelsOrTypes() == null || constraint.labelsOrTypes().isEmpty())
                ? null
                : constraint.labelsOrTypes().getFirst();
        if (labelOrType == null || constraint.properties() == null || constraint.properties().isEmpty()) {
            return null;
        }
        var propList = constraint.properties().stream()
                .map(p -> (isNode ? "n." : "r.") + "`" + p + "`")
                .toList();
        var pattern = isNode
                ? "(n:`" + labelOrType + "`)"
                : "()-[r:`" + labelOrType + "`]-()";
        var name = "`" + constraint.name() + "`";

        return switch (constraint.type().toUpperCase(Locale.ROOT)) {
            case "UNIQUENESS", "NODE_UNIQUENESS", "RELATIONSHIP_UNIQUENESS", "REL_UNIQUENESS" ->
                    "CREATE CONSTRAINT " + name
                            + " IF NOT EXISTS FOR " + pattern
                            + " REQUIRE " + asSingleOrTuple(propList) + " IS UNIQUE";
            case "NODE_KEY" -> "CREATE CONSTRAINT " + name
                    + " IF NOT EXISTS FOR " + pattern
                    + " REQUIRE " + asSingleOrTuple(propList) + " IS NODE KEY";
            case "RELATIONSHIP_KEY", "REL_KEY" -> "CREATE CONSTRAINT " + name
                    + " IF NOT EXISTS FOR " + pattern
                    + " REQUIRE " + asSingleOrTuple(propList) + " IS RELATIONSHIP KEY";
            case "NODE_PROPERTY_EXISTENCE", "NODE_EXISTENCE", "RELATIONSHIP_PROPERTY_EXISTENCE", "REL_EXISTENCE" ->
                    "CREATE CONSTRAINT " + name
                            + " IF NOT EXISTS FOR " + pattern
                            + " REQUIRE " + propList.getFirst() + " IS NOT NULL";
            default -> null;
        };
    }

    private static String asSingleOrTuple(List<String> propRefs) {
        if (propRefs.size() == 1) {
            return propRefs.getFirst();
        }
        return "(" + String.join(", ", propRefs) + ")";
    }
}
