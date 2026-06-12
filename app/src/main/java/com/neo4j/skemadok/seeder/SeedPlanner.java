package com.neo4j.skemadok.seeder;

import com.neo4j.skemadok.model.LabelInfo;
import com.neo4j.skemadok.model.LabelRole;
import com.neo4j.skemadok.model.RelationshipTypeInfo;
import com.neo4j.skemadok.model.SchemaDocument;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Single-use planner that turns a {@link SchemaDocument} plus a {@link SeedOptions} scale into a
 * {@link SeedPlan}. Construction performs no work beyond indexing the labels; {@link #plan()}
 * runs the full validation → allocation → tag-application → relationship-planning pipeline.
 *
 * <p>The planner keeps the per-pass accumulators ({@code nodeCreates}, {@code labelRanges},
 * {@code skippedRels}) and the indexed label map as instance fields so the recursive
 * {@code allocate} helper and its siblings do not need to pass them as out-parameters. As a
 * consequence the planner is <b>not reusable</b> — one instance per {@code plan()} call.
 */
final class SeedPlanner {

    private static final Logger log = LoggerFactory.getLogger(SeedPlanner.class);

    private final SchemaDocument schema;
    private final double scale;
    private final Map<String, LabelInfo> labelsByName;

    private final List<SeedPlan.NodeCreate> nodeCreates = new ArrayList<>();
    private final Map<String, List<long[]>> labelRanges = new LinkedHashMap<>();
    private final List<SeedPlan.SkippedRel> skippedRels = new ArrayList<>();

    SeedPlanner(SchemaDocument schema, double scale) {
        this.schema = schema;
        this.scale = scale;
        this.labelsByName = indexLabels(schema.getNodeLabels());
    }

    /**
     * Runs the full planning pipeline. Throws {@link IllegalStateException} via
     * {@link SchemaValidator} when the schema annotations are inconsistent — the caller is
     * expected to surface that message to the user and exit non-zero.
     */
    SeedPlan plan() {
        new SchemaValidator(labelsByName).validate();

        var uniqueness = UniquenessConstraints.from(schema.getConstraints());
        var entityNoUniquenessWarnings = uniqueness.entitiesWithoutUniqueness(labelsByName.values());

        var seedIdStart = 0L;
        for (var label : schema.getNodeLabels()) {
            if (label.isRemoved()) {
                continue;
            }
            if (label.getRole() != LabelRole.ENTITY) {
                continue;
            }
            var rootScaled = Scaling.scaledNodeCount(label.getNodeCount(), scale);
            if (rootScaled <= 0) {
                continue;
            }
            allocate(List.of(label.getName()), new LinkedHashMap<>(), rootScaled, seedIdStart);
            seedIdStart += rootScaled;
        }

        var tagApplies = planTagApplies();
        var rels = planRels();

        var labelSummaries = buildLabelSummaries();
        var relGroups = buildRelGroups(rels);

        return new SeedPlan(
                List.copyOf(nodeCreates),
                List.copyOf(tagApplies),
                List.copyOf(rels),
                Map.copyOf(labelRanges),
                List.copyOf(skippedRels),
                List.copyOf(entityNoUniquenessWarnings),
                List.copyOf(labelSummaries),
                List.copyOf(relGroups));
    }

    /**
     * Builds one {@link SeedPlan.LabelSummary} per non-removed label, in schema order. The
     * reporter renders the NODES table directly from these so it does not need to keep a
     * reference to the {@link SchemaDocument}.
     */
    private List<SeedPlan.LabelSummary> buildLabelSummaries() {
        var summaries = new ArrayList<SeedPlan.LabelSummary>();
        for (var label : schema.getNodeLabels()) {
            if (label.isRemoved()) {
                continue;
            }
            var count = totalForLabel(label.getName());
            var hosts = label.getTaggedEntities();
            summaries.add(new SeedPlan.LabelSummary(
                    label.getName(),
                    label.getRole(),
                    count,
                    label.getExtendsLabel(),
                    hosts == null ? List.of() : List.copyOf(hosts)));
        }
        return summaries;
    }

    /**
     * Groups RelPlans by (logical type, start, end) so the reporter and the executor can iterate
     * one entry per logical type rather than re-grouping the flat list at the call site.
     * Parameterised types fold their instances into a single group; standalone types produce
     * one group per type.
     */
    private List<SeedPlan.RelGroup> buildRelGroups(List<SeedPlan.RelPlan> relPlans) {
        var relByDisplayName = getStringRelationshipTypeInfoMap(schema.getRelationshipTypes());
        var bucketed = new LinkedHashMap<GroupKey, List<SeedPlan.RelPlan>>();
        var parameterised = new HashMap<GroupKey, Boolean>();
        for (var rp : relPlans) {
            var info = relByDisplayName.get(rp.relType());
            var isParameterised = info != null && info.isParameterized();
            var logicalType = isParameterised ? info.getName() : rp.relType();
            var key = new GroupKey(logicalType, rp.startLabel(), rp.endLabel());
            bucketed.computeIfAbsent(key, k -> new ArrayList<>()).add(rp);
            parameterised.putIfAbsent(key, isParameterised);
        }
        var groups = new ArrayList<SeedPlan.RelGroup>(bucketed.size());
        for (var entry : bucketed.entrySet()) {
            var key = entry.getKey();
            var plans = entry.getValue();
            var totalCount = plans.stream().mapToLong(SeedPlan.RelPlan::target).sum();
            groups.add(new SeedPlan.RelGroup(
                    key.logicalType(), key.startLabel(), key.endLabel(),
                    totalCount, parameterised.get(key), List.copyOf(plans)));
        }
        return groups;
    }

    private long totalForLabel(String label) {
        var ranges = labelRanges.get(label);
        if (ranges == null) {
            return 0L;
        }
        return ranges.stream().mapToLong(r -> r[1] - r[0]).sum();
    }

    @NonNull
    static Map<String, RelationshipTypeInfo> getStringRelationshipTypeInfoMap(List<RelationshipTypeInfo> rels) {
        var map = new HashMap<String, RelationshipTypeInfo>(rels.size() * 2);
        for (var rel : rels) {
            map.put(rel.getName(), rel);
            if (rel.isParameterized() && rel.getInstances() != null) {
                for (var instance : rel.getInstances()) {
                    map.putIfAbsent(instance, rel);
                }
            }
        }
        return map;
    }

    private record GroupKey(String logicalType, String startLabel, String endLabel) {
    }

    /**
     * Depth-first allocator: recurses into every HIERARCHY child of {@code chain}'s tail label,
     * carves their scaled counts out of the parent's allotment (deepest-first), and emits one
     * {@link SeedPlan.NodeCreate} bucket per concrete label combination. The accumulators on
     * {@code this} carry the result back to {@link #plan()}.
     */
    private void allocate(List<String> chain,
                          Map<String, Long> rangeStartByLabel,
                          long scaledForLabel,
                          long seedIdStart) {
        var label = chain.getLast();
        var localStarts = new LinkedHashMap<>(rangeStartByLabel);
        localStarts.put(label, seedIdStart);
        labelRanges.computeIfAbsent(label, k -> new ArrayList<>())
                .add(new long[]{seedIdStart, seedIdStart + scaledForLabel});

        var childScaled = scaledChildrenOf(label);
        long childrenSum = childScaled.values().stream().mapToLong(Long::longValue).sum();
        if (childrenSum > scaledForLabel) {
            log.warn("HIERARCHY children of '{}' sum to {} but parent total is {}; clamping plain remainder to 0",
                    label, childrenSum, scaledForLabel);
        }
        var plain = Math.max(0L, scaledForLabel - childrenSum);

        var pos = seedIdStart;
        for (var entry : childScaled.entrySet()) {
            var childCount = entry.getValue();
            if (childCount <= 0) {
                continue;
            }
            var subChain = new ArrayList<>(chain);
            subChain.add(entry.getKey());
            allocate(subChain, localStarts, childCount, pos);
            pos += childCount;
        }
        if (plain > 0) {
            nodeCreates.add(new SeedPlan.NodeCreate(
                    List.copyOf(chain), plain, pos, Map.copyOf(localStarts)));
        }
    }

    /**
     * Returns {@code (childLabelName → scaled count)} for every non-removed HIERARCHY label
     * whose {@code extends} points at {@code parent}. Order matches the schema's
     * {@code nodeLabels} list so the DFS produces deterministic bucket emission.
     */
    private Map<String, Long> scaledChildrenOf(String parent) {
        var result = new LinkedHashMap<String, Long>();
        for (var label : labelsByName.values()) {
            if (label.isRemoved()) {
                continue;
            }
            if (label.getRole() != LabelRole.HIERARCHY) {
                continue;
            }
            if (!parent.equals(label.getExtendsLabel())) {
                continue;
            }
            result.put(label.getName(), Scaling.scaledNodeCount(label.getNodeCount(), scale));
        }
        return result;
    }

    /**
     * Walks every TAG-role label and produces one {@link SeedPlan.TagApply} per tag that has a
     * non-empty proportional split across its host entities. Also appends the resulting
     * {@code __seedId} slice to {@link #labelRanges} so relationship sampling on the TAG label
     * picks join keys from exactly the tagged subset.
     */
    private List<SeedPlan.TagApply> planTagApplies() {
        var tagApplies = new ArrayList<SeedPlan.TagApply>();
        for (var label : schema.getNodeLabels()) {
            if (label.isRemoved()) {
                continue;
            }
            if (label.getRole() != LabelRole.TAG) {
                continue;
            }
            var scaledTag = Scaling.scaledNodeCount(label.getNodeCount(), scale);
            if (scaledTag <= 0) {
                continue;
            }
            var hostCounts = computeHostCounts(label, scaledTag);
            if (hostCounts.isEmpty()) {
                continue;
            }
            var hostStarts = recordTagRanges(label.getName(), hostCounts);
            tagApplies.add(new SeedPlan.TagApply(label.getName(),
                    Map.copyOf(hostCounts), Map.copyOf(hostStarts)));
        }
        return tagApplies;
    }

    /**
     * For each host the tag covers, looks up the host's first {@code __seedId} range start and
     * records a {@code [start, start + hostCount)} slice under the tag label. The returned
     * map is the {@code hostRangeStart} block stored on the resulting {@link SeedPlan.TagApply}.
     */
    private Map<String, Long> recordTagRanges(String tagLabel, Map<String, Long> hostCounts) {
        var hostStarts = new LinkedHashMap<String, Long>();
        for (var entry : hostCounts.entrySet()) {
            var hostRanges = labelRanges.get(entry.getKey());
            if (hostRanges == null || hostRanges.isEmpty()) {
                continue;
            }
            var firstStart = hostRanges.getFirst()[0];
            hostStarts.put(entry.getKey(), firstStart);
            labelRanges.computeIfAbsent(tagLabel, k -> new ArrayList<>())
                    .add(new long[]{firstStart, firstStart + entry.getValue()});
        }
        return hostStarts;
    }

    /**
     * Distributes {@code scaledTagCount} across the tag's valid ENTITY hosts proportionally to
     * each host's scaled size. Hosts that are unknown, removed, non-ENTITY, or empty are
     * dropped with a warning. Returns an empty map if no usable host remains.
     */
    private Map<String, Long> computeHostCounts(LabelInfo tag, long scaledTagCount) {
        var hosts = tag.getTaggedEntities();
        if (hosts == null || hosts.isEmpty()) {
            // Defensive: SchemaValidator should have aborted before we reach this point.
            return Map.of();
        }
        var validHostScaled = collectValidHosts(tag, hosts);
        if (validHostScaled.isEmpty()) {
            return Map.of();
        }
        long totalHostSize = validHostScaled.values().stream().mapToLong(Long::longValue).sum();
        return splitProportionally(Math.min(scaledTagCount, totalHostSize), validHostScaled, totalHostSize);
    }

    /**
     * Filters {@code hosts} to the ones that exist, are non-removed ENTITY labels, and have a
     * positive scaled count. Logs a warning for every host dropped so the user can spot
     * mis-annotated tags.
     */
    private Map<String, Long> collectValidHosts(LabelInfo tag, List<String> hosts) {
        var result = new LinkedHashMap<String, Long>();
        for (var host : hosts) {
            var hostInfo = labelsByName.get(host);
            if (hostInfo == null || hostInfo.isRemoved()) {
                log.warn("TAG '{}' references unknown or removed host '{}'; skipping host",
                        tag.getName(), host);
                continue;
            }
            if (hostInfo.getRole() != LabelRole.ENTITY) {
                log.warn("TAG '{}' references non-ENTITY host '{}' (role={}); skipping host",
                        tag.getName(), host, hostInfo.getRole());
                continue;
            }
            var hostScaled = Scaling.scaledNodeCount(hostInfo.getNodeCount(), scale);
            if (hostScaled <= 0) {
                continue;
            }
            result.put(host, hostScaled);
        }
        return result;
    }

    /**
     * Splits {@code total} across hosts in proportion to their scaled sizes. The final host
     * absorbs any rounding residual so the per-host shares always sum to {@code total}.
     */
    private static Map<String, Long> splitProportionally(long total,
                                                         Map<String, Long> hostScaled,
                                                         long totalHostSize) {
        var result = new LinkedHashMap<String, Long>();
        long allocated = 0;
        var hostList = new ArrayList<>(hostScaled.keySet());
        for (var i = 0; i < hostList.size(); i++) {
            var host = hostList.get(i);
            var hostSize = hostScaled.get(host);
            long share = (i == hostList.size() - 1)
                    ? total - allocated
                    : Math.round((double) total * hostSize / totalHostSize);
            share = Math.min(share, hostSize);
            share = Math.max(share, 0);
            result.put(host, share);
            allocated += share;
        }
        return result;
    }

    /**
     * Walks every relationship type, unrolls parameterised groups into one {@link SeedPlan.RelPlan}
     * per instance, caps each plan at the available {@code startPool × endPool} capacity, and
     * records every skipped rel into {@link #skippedRels} with the reason so the breakdown can
     * surface it.
     */
    private List<SeedPlan.RelPlan> planRels() {
        var rels = new ArrayList<SeedPlan.RelPlan>();
        for (var rel : schema.getRelationshipTypes()) {
            if (rel.isRemoved()) {
                continue;
            }
            var instances = rel.isParameterized() && rel.getInstances() != null && !rel.getInstances().isEmpty()
                    ? rel.getInstances()
                    : List.of(rel.getName());
            var perInstanceScale = scale / instances.size();
            for (var connection : rel.getConnections()) {
                planConnection(rel.getName(), instances, perInstanceScale,
                        connection.startLabels(), connection.endLabels(), connection.count(), rels);
            }
        }
        return rels;
    }

    /**
     * Plans (or skips) the rels for one connection — a single {@code (startLabels, endLabels)}
     * pair on a single relationship type. Picks the representative label for each side, looks
     * up the pool size from {@link #labelRanges}, caps the target at the pair's capacity, and
     * emits one {@link SeedPlan.RelPlan} per instance.
     */
    private void planConnection(String typeName,
                                List<String> instances,
                                double perInstanceScale,
                                List<String> startLabels,
                                List<String> endLabels,
                                long sourceCount,
                                List<SeedPlan.RelPlan> output) {
        var startLabel = representativeLabel(startLabels);
        var endLabel = representativeLabel(endLabels);
        if (startLabel == null || endLabel == null) {
            skippedRels.add(new SeedPlan.SkippedRel(typeName, startLabel, endLabel,
                    "connection has no usable representative label"));
            return;
        }
        var startPool = totalRangeSize(labelRanges.get(startLabel));
        var endPool = totalRangeSize(labelRanges.get(endLabel));
        if (startPool == 0 || endPool == 0) {
            var missing = startPool == 0 ? startLabel : endLabel;
            var side = startPool == 0 ? "start" : "end";
            skippedRels.add(new SeedPlan.SkippedRel(typeName, startLabel, endLabel,
                    side + "-side label '" + missing + "' has no seeded nodes"));
            return;
        }
        var capacity = Scaling.saturatingMultiply(startPool, endPool);
        for (var instance : instances) {
            var target = Math.min(Scaling.scaledRelCount(sourceCount, perInstanceScale), capacity);
            if (target == 0) {
                continue;
            }
            output.add(new SeedPlan.RelPlan(instance, startLabel, endLabel, target));
        }
    }

    private static String representativeLabel(List<String> labels) {
        if (labels == null || labels.isEmpty()) {
            return null;
        }
        return labels.getFirst();
    }

    /**
     * Sums the sizes of a list of half-open intervals {@code [low, high)}, returning the total
     * number of {@code __seedId} values covered. Used to determine pool sizes for matrix
     * partitioning and per-cell relationship count distribution.
     */
    private static long totalRangeSize(List<long[]> ranges) {
        if (ranges == null) {
            return 0L;
        }
        return ranges.stream().mapToLong(r -> r[1] - r[0]).sum();
    }

    private static Map<String, LabelInfo> indexLabels(List<LabelInfo> labels) {
        var map = new LinkedHashMap<String, LabelInfo>(labels.size() * 2);
        for (var label : labels) {
            map.put(label.getName(), label);
        }
        return map;
    }
}
