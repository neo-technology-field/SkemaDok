package com.neo4j.skemadok.collector;

import com.neo4j.skemadok.model.Connection;
import com.neo4j.skemadok.model.PropertyInfo;
import com.neo4j.skemadok.model.RelationshipTypeInfo;
import com.neo4j.skemadok.model.TypeParameter;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Detects parameterised relationship type groups in a set of type names and provides
 * access to the analysis results.
 *
 * <h2>The problem</h2>
 * Some schemas encode runtime metadata directly in the relationship type name, e.g.
 * {@code WORKS_FOR_2024_01}, {@code WORKS_FOR_2024_02}, {@code WORKS_FOR_2025_01}.
 * This inflates the schema and obscures structure. These should appear as a single
 * {@code WORKS_FOR} entry with named parameter slots.
 *
 * <h2>Detection rule</h2>
 * A type name is split on {@code _}. Candidate groups are identified by frequency: if at least
 * {@code threshold} names share the same {@code _}-delimited prefix, that prefix triggers a group.
 * Within each candidate group, names are further bucketed by total segment count. Inside each
 * segment-count bucket the stable base is the <em>longest common segment prefix</em> — the
 * leftmost consecutive positions where every member agrees. The first position where any member
 * diverges is where the variable part begins. This means {@code COMP_HAS_COSTS_FOR_PROD_1267}
 * and {@code COMP_HAS_COSTS_FOR_PROD_2345} produce base {@code COMP_HAS_COSTS_FOR_PROD}, not
 * the shorter frequency-detected prefix {@code COMP}.
 *
 * <p>The threshold is configurable via {@code --group-threshold} on the CLI; 10 is the
 * default because schemas with &gt;65k relationship types are not uncommon and a low
 * threshold produces an enormous number of false groups.
 */
public class RelTypeGrouper {

    public static final int DEFAULT_THRESHOLD = 10;

    private final Map<String, List<String>> groups;
    private final List<String> ungroupedNames;

    /**
     * Analyses {@code allTypeNames}, detecting parameterised groups at or above
     * {@code minGroupSize}. Results are immediately available via {@link #getGroups()}
     * and {@link #getUngroupedNames()}.
     */
    public RelTypeGrouper(List<String> allTypeNames, int minGroupSize) {
        this.groups = Collections.unmodifiableMap(detect(allTypeNames, minGroupSize));
        var groupedNames = groups.values().stream().flatMap(List::stream).collect(Collectors.toSet());
        this.ungroupedNames = allTypeNames.stream()
                .filter(name -> !groupedNames.contains(name))
                .toList();
    }

    /**
     * Detected parameterised groups: stable base name → sorted list of member type names.
     * Groups with fewer than {@code minGroupSize} members are absent; their names appear in
     * {@link #getUngroupedNames()} instead.
     */
    public Map<String, List<String>> getGroups() {
        return groups;
    }

    /**
     * Type names that did not qualify for any group and should be collected individually.
     */
    public List<String> getUngroupedNames() {
        return ungroupedNames;
    }

    /**
     * Builds a grouped {@link RelationshipTypeInfo} from precomputed stats.
     * Type-parameter slots and instance names are derived from {@code memberNames} alone.
     */
    static RelationshipTypeInfo buildGrouped(
            String base,
            List<String> memberNames,
            List<PropertyInfo> mergedProperties,
            long totalCount,
            List<Connection> connections) {

        int baseSegCount = base.split("_").length;
        int varCount = memberNames.stream()
                .mapToInt(n -> n.split("_").length)
                .max().orElse(baseSegCount) - baseSegCount;

        var typeParams = new ArrayList<TypeParameter>();
        for (int i = 0; i < varCount; i++) {
            final int segIdx = baseSegCount + i;
            var examples = memberNames.stream()
                    .filter(name -> name.split("_").length > segIdx)
                    .map(name -> name.split("_")[segIdx])
                    .distinct().sorted().limit(5)
                    .toList();
            typeParams.add(new TypeParameter(i, examples));
        }

        return new RelationshipTypeInfo(
                base, totalCount, connections,
                List.copyOf(mergedProperties),
                typeParams,
                memberNames.stream().sorted().toList()
        );
    }

    /**
     * Returns the longest segment-by-segment common prefix of all names in the list.
     * Segments are {@code _}-delimited. Names are assumed to share the same segment count
     * (callers enforce this by bucketing on segment count before invoking).
     *
     * <p>The result is the stable base: all leading positions where every name agrees.
     * The first position where any name diverges marks where variable slots begin.
     */
    static String longestCommonSegmentPrefix(List<String> names) {
        if (names.isEmpty()) {
            return "";
        }
        var parts = names.stream().map(n -> n.split("_")).toList();
        var reference = parts.getFirst();
        int stableLen = 0;
        for (int col = 0; col < reference.length; col++) {
            final int c = col;
            if (parts.stream().allMatch(p -> p.length > c && p[c].equals(reference[c]))) {
                stableLen = col + 1;
            } else {
                break;
            }
        }
        return String.join("_", Arrays.copyOf(reference, stableLen));
    }

    private Map<String, List<String>> detect(List<String> names, int minGroupSize) {
        var prefixCount = buildPrefixCounts(names);

        var initialBuckets = new LinkedHashMap<String, List<String>>();
        for (var name : names) {
            var shortPrefix = shortestQualifyingPrefix(name, prefixCount, minGroupSize);
            if (shortPrefix != null) {
                var key = shortPrefix + ":" + name.split("_").length;
                initialBuckets.computeIfAbsent(key, k -> new ArrayList<>()).add(name);
            }
        }

        var result = new LinkedHashMap<String, List<String>>();
        for (var entry : initialBuckets.entrySet()) {
            var members = entry.getValue();
            if (members.size() >= minGroupSize) {
                var sortedMembers = members.stream().sorted().toList();
                var actualBase = longestCommonSegmentPrefix(sortedMembers);
                result.merge(actualBase, new ArrayList<>(sortedMembers), (a, b) -> {
                    var merged = new ArrayList<>(a);
                    merged.addAll(b);
                    return merged;
                });
            }
        }
        return result;
    }

    /**
     * For each name, emits every leading sub-sequence of its {@code _}-delimited segments
     * (excluding the full name itself) and counts how many names share each one.
     * For example, {@code WORKS_FOR_2024} contributes to the counts for {@code WORKS} and
     * {@code WORKS_FOR}, but not for {@code WORKS_FOR_2024}.
     */
    private Map<String, Integer> buildPrefixCounts(Collection<String> names) {
        var counts = new HashMap<String, Integer>();
        for (var name : names) {
            var parts = name.split("_");
            for (int i = 1; i < parts.length; i++) {
                counts.merge(String.join("_", Arrays.copyOf(parts, i)), 1, Integer::sum);
            }
        }
        return counts;
    }

    /**
     * Returns the shortest proper {@code _}-prefix of {@code name} whose count meets
     * {@code minGroupSize}, or {@code null} if no such prefix exists.
     *
     * <p>Shortest-first is intentional: using the longest qualifying prefix would split
     * what should be a single parameterised type (e.g. {@code REL}) into narrower sub-groups
     * (e.g. {@code REL_2024}, {@code REL_2025}).
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
}
