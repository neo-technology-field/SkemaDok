package com.neo4j.skemadok.seeder;

import com.neo4j.skemadok.model.IndexInfo;

import java.util.Locale;

/**
 * Renders the {@code CREATE … INDEX …} Cypher for a captured {@link IndexInfo}.
 *
 * <p>{@code LOOKUP} indexes are auto-managed by Neo4j and never produce DDL; vector / fulltext
 * / text / point indexes use bespoke syntax that v1 of the seeder does not attempt — those
 * return {@code null} and are skipped with a debug log by the executor.
 */
public final class IndexDdl {

    private IndexDdl() {
    }

    /**
     * @return the {@code CREATE [RANGE] INDEX …} Cypher for {@code index}, or {@code null} when
     * the type is auto-managed, bespoke (vector/fulltext/text/point), or the schema
     * entry is incomplete.
     */
    public static String build(IndexInfo index) {
        var type = index.type() == null ? "" : index.type().toUpperCase(Locale.ROOT);
        if (type.equals("LOOKUP")) {
            return null;
        }
        if (type.equals("VECTOR") || type.equals("FULLTEXT") || type.equals("TEXT") || type.equals("POINT")) {
            return null;
        }
        if (index.labelsOrTypes() == null || index.labelsOrTypes().isEmpty()) {
            return null;
        }
        if (index.properties() == null || index.properties().isEmpty()) {
            return null;
        }

        var isNode = "node".equalsIgnoreCase(index.entityType());
        var labelOrType = index.labelsOrTypes().getFirst();
        var pattern = isNode
                ? "(n:`" + labelOrType + "`)"
                : "()-[r:`" + labelOrType + "`]-()";
        var propList = index.properties().stream()
                .map(p -> (isNode ? "n." : "r.") + "`" + p + "`")
                .toList();
        var keyword = type.equals("RANGE") ? "RANGE INDEX" : "INDEX";
        return "CREATE " + keyword + " `" + index.name() + "` IF NOT EXISTS FOR " + pattern
                + " ON (" + String.join(", ", propList) + ")";
    }
}
