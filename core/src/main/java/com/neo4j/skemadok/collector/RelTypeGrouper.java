package com.neo4j.skemadok.collector;

import com.neo4j.skemadok.model.Connection;
import com.neo4j.skemadok.model.PropertyInfo;
import com.neo4j.skemadok.model.RelationshipTypeInfo;
import com.neo4j.skemadok.model.TypeParameter;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Detects and collapses parameterised relationship type names into grouped entries.
 *
 * <h2>The problem</h2>
 * Some schemas encode runtime metadata directly in the relationship type name, e.g.
 * {@code WORKS_FOR_2024_01}, {@code WORKS_FOR_2024_02}, {@code WORKS_FOR_2025_01}.
 * This inflates the schema and obscures structure. These should appear as a single
 * {@code WORKS_FOR} entry with named parameter slots.
 *
 * <h2>Detection rule</h2>
 * A type name is split on {@code _}. The base is determined entirely by frequency:
 * if at least {@code threshold} names share the same {@code _}-delimited prefix, that
 * prefix is a base candidate. The longest qualifying prefix wins. Character content
 * (digits vs letters) plays no role — the variation across instances is the only signal.
 *
 * <p>The threshold is configurable via {@code --group-threshold} on the CLI; 10 is the
 * default because schemas with &gt;65k relationship types are not uncommon and a low
 * threshold produces an enormous number of false groups.
 */
public class RelTypeGrouper {

    public static final int DEFAULT_THRESHOLD = 10;

    /**
     * Key for grouping connections by label sets, excluding count from equality.
     */
    private record ConnectionKey(List<String> startLabels, List<String> endLabels) {
    }

    /**
     * Groups raw relationship types, returning a new list where parameterised families
     * at or above {@code minGroupSize} are replaced by a single grouped entry.
     * Types below the threshold pass through unchanged. The result is sorted by name.
     *
     * @param rawTypes     relationship types as returned by the collector
     * @param minGroupSize minimum number of instances required to collapse a family
     */
    List<RelationshipTypeInfo> group(List<RelationshipTypeInfo> rawTypes, int minGroupSize) {
        var prefixCount = buildPrefixFrequencies(rawTypes.stream()
                .map(RelationshipTypeInfo::getName).toList());

        var groups = new LinkedHashMap<String, List<RelationshipTypeInfo>>();
        var ungroupable = new ArrayList<RelationshipTypeInfo>();

        for (var rt : rawTypes) {
            var base = shortestQualifyingPrefix(rt.getName(), prefixCount, minGroupSize);
            if (base == null) {
                ungroupable.add(rt);
            } else {
                var key = base + ":" + rt.getName().split("_").length;
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(rt);
            }
        }

        var result = new ArrayList<>(ungroupable);
        for (var entry : groups.entrySet()) {
            var members = entry.getValue();
            var base = entry.getKey().substring(0, entry.getKey().lastIndexOf(':'));
            if (members.size() < minGroupSize) {
                result.addAll(members);
            } else {
                result.add(buildGrouped(base, members));
            }
        }

        result.sort(Comparator.comparing(RelationshipTypeInfo::getName));
        return result;
    }

    /**
     * Returns {@code true} if at least one parameterised family at or above {@code minGroupSize}
     * can be formed from the given type names, using the same detection logic as {@link #group}.
     *
     * <p>Used by the collector to decide between per-type count-store queries (fast, but N
     * round trips) and a full relationship scan (one query, necessary when group members are
     * numerous enough to make N round trips impractical).
     *
     * @param names        relationship type names, as returned by {@code db.schema.relTypeProperties()}
     * @param minGroupSize the grouping threshold; mirrors the value passed to {@link #group}
     */
    boolean detectsGroups(Collection<String> names, int minGroupSize) {
        var prefixCount = buildPrefixFrequencies(names);
        return prefixCount.values().stream().anyMatch(count -> count >= minGroupSize);
    }

    /**
     * Returns the detected parameterised families and their variant counts.
     * Keys are the base names (e.g. {@code WORKS_FOR}); values are the number of concrete
     * type names that map to that family. Only families at or above {@code minGroupSize} are returned.
     *
     * <p>Used for logging — one info line per family before the full scan starts, so the user can
     * see which types triggered the expensive scan path.
     *
     * @param names        relationship type names from {@code db.schema.relTypeProperties()}
     * @param minGroupSize the grouping threshold; mirrors the value passed to {@link #group}
     */
    Map<String, Integer> detectedGroupSizes(Collection<String> names, int minGroupSize) {
        var prefixCount = buildPrefixFrequencies(names);
        var groupCounts = new LinkedHashMap<String, Integer>();
        for (var name : names) {
            var base = shortestQualifyingPrefix(name, prefixCount, minGroupSize);
            if (base != null) {
                groupCounts.merge(base, 1, Integer::sum);
            }
        }
        return groupCounts.entrySet().stream()
                .filter(e -> e.getValue() >= minGroupSize)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
    }

    /**
     * Counts how many names in the collection have each possible proper {@code _}-prefix.
     * A prefix is "proper" when it has fewer segments than the name it is a prefix of.
     */
    private Map<String, Integer> buildPrefixFrequencies(Collection<String> names) {
        var freq = new HashMap<String, Integer>();
        for (var name : names) {
            var parts = name.split("_");
            for (int i = 1; i < parts.length; i++) {
                freq.merge(String.join("_", Arrays.copyOf(parts, i)), 1, Integer::sum);
            }
        }
        return freq;
    }

    /**
     * Returns the shortest proper {@code _}-prefix of {@code name} whose frequency meets
     * {@code minGroupSize}, or {@code null} if no such prefix exists.
     *
     * <p>Shortest-first is intentional: using the longest qualifying prefix would assign
     * sub-prefixes (e.g. {@code REL_2024}) to a narrower group than the enclosing family
     * ({@code REL}), splitting what should be a single parameterised type into several.
     */
    private String shortestQualifyingPrefix(String name, Map<String, Integer> prefixCount, int minGroupSize) {
        var parts = name.split("_");
        for (int i = 1; i < parts.length; i++) {
            var prefix = String.join("_", Arrays.copyOf(parts, i));
            if (prefixCount.getOrDefault(prefix, 0) >= minGroupSize) {
                return prefix;
            }
        }
        return null;
    }

    /**
     * Builds a grouped entry from precomputed stats. Used by the collector when
     * counts and connections come from a group-level graph scan rather than from
     * per-individual-type stats. Type-parameter slots and instance names are derived
     * from {@code memberNames} alone.
     */
    RelationshipTypeInfo buildGrouped(
            String base,
            List<String> memberNames,
            List<PropertyInfo> mergedProperties,
            long totalCount,
            List<Connection> connections) {

        int baseSegCount = base.split("_").length;
        int varCount = memberNames.getFirst().split("_").length - baseSegCount;

        var typeParams = new ArrayList<TypeParameter>();
        for (int i = 0; i < varCount; i++) {
            final int segIdx = baseSegCount + i;
            var examples = memberNames.stream()
                    .map(name -> name.split("_")[segIdx])
                    .distinct().sorted().limit(5)
                    .toList();
            typeParams.add(new TypeParameter(i, examples));
        }

        return new RelationshipTypeInfo(
                base, totalCount, connections,
                new ArrayList<>(mergedProperties),
                typeParams,
                memberNames.stream().sorted().toList()
        );
    }

    /**
     * Builds a grouped entry from per-individual-type {@link RelationshipTypeInfo} objects.
     * Merges counts, connections, and properties across all members, then delegates to
     * {@link #buildGrouped(String, List, List, long, List)} for name-based structure.
     * Used by {@link #group} when per-type stats are already available.
     */
    private RelationshipTypeInfo buildGrouped(String base, List<RelationshipTypeInfo> members) {
        long totalCount = members.stream().mapToLong(RelationshipTypeInfo::getCount).sum();

        // Merge connections from all group members. Connection equality includes count, so we
        // group by (startLabels, endLabels) key and sum counts to avoid duplicates across instances.
        var mergedConnectionCounts = new LinkedHashMap<ConnectionKey, Long>();
        for (var member : members) {
            for (var conn : member.getConnections()) {
                var key = new ConnectionKey(conn.startLabels(), conn.endLabels());
                mergedConnectionCounts.merge(key, conn.count(), Long::sum);
            }
        }
        var mergedConnections = mergedConnectionCounts.entrySet().stream()
                .map(entry -> new Connection(entry.getKey().startLabels(), entry.getKey().endLabels(), entry.getValue()))
                .sorted(Comparator.comparing((Connection conn) -> conn.startLabels().toString())
                        .thenComparing(conn -> conn.endLabels().toString()))
                .toList();

        var mergedProps = new LinkedHashMap<String, PropertyInfo>();
        for (var rt : members) {
            for (var p : rt.getProperties()) {
                mergedProps.merge(p.name(), p,
                        (existing, incoming) -> incoming.nullable() ? existing : existing.withNullable(false));
            }
        }

        return buildGrouped(
                base,
                members.stream().map(RelationshipTypeInfo::getName).toList(),
                new ArrayList<>(mergedProps.values()),
                totalCount,
                mergedConnections
        );
    }
}
