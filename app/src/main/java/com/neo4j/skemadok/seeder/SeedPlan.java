package com.neo4j.skemadok.seeder;

import com.neo4j.skemadok.model.LabelRole;

import java.util.List;
import java.util.Map;

/**
 * Computed scaled targets for one invocation of {@link SchemaSeeder}.
 *
 * <p>Built by {@link SchemaSeeder#plan} without any database interaction so the same record
 * powers the {@code --dry-run} table, the unit tests for cardinality math, and the executor.
 *
 * <p>The plan respects {@link com.neo4j.skemadok.model.LabelRole}:
 * <ul>
 *   <li>ENTITY and HIERARCHY labels create nodes — emitted as {@link NodeCreate} entries. A
 *       HIERARCHY chain {@code [Person, Employee, Manager]} produces a {@code NodeCreate} whose
 *       {@code labels} contains every label in the chain so each seeded node physically carries
 *       all of them.</li>
 *   <li>TAG labels do not create nodes — they are emitted as {@link TagApply} entries that mark
 *       the {@code count} first existing host-entity nodes with the tag label.</li>
 * </ul>
 *
 * <p>{@link #labelRanges} records, per label, the contiguous slices of {@code __seedId} space the
 * label occupies. Relationship endpoint sampling reads this map to pick join keys uniformly at
 * random across whatever subset of an entity's pool a label actually covers (a TAG slice, a
 * HIERARCHY sub-range, or the full ENTITY range).
 */
public record SeedPlan(
        List<NodeCreate> nodeCreates,
        List<TagApply> tagApplies,
        List<RelPlan> rels,
        Map<String, List<long[]>> labelRanges,
        List<SkippedRel> skippedRels,
        List<String> entityNoUniquenessWarnings,
        List<LabelSummary> labelSummaries,
        List<RelGroup> relGroups) {

    /**
     * Total number of seeded nodes that carry {@code label} — ENTITY pool, HIERARCHY sub-range,
     * or TAG slice, depending on the label's role. Reads from {@link #labelRanges} which the
     * planner populates uniformly for all three roles.
     */
    public long totalForLabel(String label) {
        var ranges = labelRanges.get(label);
        if (ranges == null) {
            return 0L;
        }
        return ranges.stream().mapToLong(r -> r[1] - r[0]).sum();
    }

    /**
     * One bucket of nodes to create. All nodes in the bucket share the same set of labels (a
     * hierarchy chain root-to-leaf) and occupy contiguous {@code __seedId} values starting at
     * {@link #seedIdStart}.
     *
     * @param labels            root-to-leaf label chain — index 0 is the ENTITY root, last entry
     *                          is the most specific HIERARCHY (or the root again for a plain
     *                          bucket)
     * @param count             number of nodes to create
     * @param seedIdStart       first {@code __seedId} value used by this bucket
     * @param rangeStartByLabel for each label in {@code labels}, the start of that label's full
     *                          range across the plan. Used to derive the per-label
     *                          {@code index} passed to the value generator
     */
    public record NodeCreate(
            List<String> labels,
            long count,
            long seedIdStart,
            Map<String, Long> rangeStartByLabel) {

        /**
         * Returns the {@code index} to pass to the value generator for properties owned by
         * {@code label}, when generating the {@code position}-th node of this bucket (0-based).
         */
        public long indexForLabel(String label, long position) {
            var start = rangeStartByLabel.get(label);
            if (start == null) {
                throw new IllegalArgumentException("label not in this bucket: " + label);
            }
            return seedIdStart + position - start;
        }
    }

    /**
     * One TAG-role label to apply on top of existing entity nodes.
     *
     * @param tagLabel       the TAG label name
     * @param hostCounts     for each host ENTITY label, how many of its nodes (the first {@code n}
     *                       under {@code ORDER BY __seedId}) should receive the tag. The sum of
     *                       values equals the scaled TAG count
     * @param hostRangeStart for each host ENTITY label, the start of the host's {@code __seedId}
     *                       range. Used to compute the slice [start, start + hostCounts.get(host))
     *                       that the tag covers — fed back into {@link #labelRanges}
     */
    public record TagApply(
            String tagLabel,
            Map<String, Long> hostCounts,
            Map<String, Long> hostRangeStart) {
    }

    /**
     * One actual relationship type to seed. For grouped/parameterised entries this is unrolled —
     * each {@code instance} (e.g. {@code PROMOTED_2024_Q1}) gets its own {@code RelPlan}.
     */
    public record RelPlan(String relType, String startLabel, String endLabel, long target) {
    }

    /**
     * One relationship that {@link SchemaSeeder#plan} could not schedule, recorded so the upfront
     * breakdown can show the user why something is missing rather than silently dropping it.
     *
     * @param relType    the schema relationship type name (group base for parameterised types)
     * @param startLabel representative start label as picked by the planner, or {@code null}
     * @param endLabel   representative end label as picked by the planner, or {@code null}
     * @param reason     human-readable explanation, e.g. {@code "end-side label 'ERZ' has no seeded nodes"}
     */
    public record SkippedRel(String relType, String startLabel, String endLabel, String reason) {
    }

    /**
     * Per-label summary captured at planning time so the reporter can render the NODES table
     * without reaching back into the {@link com.neo4j.skemadok.model.SchemaDocument}. One entry
     * per non-removed label, in schema order.
     *
     * @param name           label name
     * @param role           ENTITY / HIERARCHY / TAG
     * @param count          planned number of nodes carrying this label after scaling
     * @param extendsLabel   the HIERARCHY parent label name, or {@code null} for other roles
     * @param taggedEntities the TAG's host entity labels, or an empty list for other roles
     */
    public record LabelSummary(
            String name,
            LabelRole role,
            long count,
            String extendsLabel,
            List<String> taggedEntities) {
    }

    /**
     * One logical relationship group — the planning-time analogue of the runtime grouping the
     * executor used to do on the fly. A parameterised relationship type collapses all of its
     * instances into one group; a standalone type produces a one-element group.
     *
     * @param logicalType   group base for parameterised types, plain type name otherwise
     * @param startLabel    representative start label shared by every plan in the group
     * @param endLabel      representative end label shared by every plan in the group
     * @param totalCount    sum of {@code plans.target()}
     * @param parameterised whether {@code plans} are instances of a parameterised family
     * @param plans         the RelPlans that make up the group; size > 1 only when {@code parameterised}
     */
    public record RelGroup(
            String logicalType,
            String startLabel,
            String endLabel,
            long totalCount,
            boolean parameterised,
            List<RelPlan> plans) {
    }
}
