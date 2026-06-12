package com.neo4j.skemadok.seeder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * Renders the upfront "Seed plan" breakdown that runs before any writes (and before any
 * relationship-creation runtime logs). One INFO log line carries the full multisection
 * report — NODES, RELATIONSHIPS, SUMMARY — so the user can verify counts and spot warnings
 * before committing to a long-running seed.
 *
 * <p>The reporter consumes only the {@link SeedPlan} (plus {@link SeedOptions} for display
 * config). Every fact it renders — per-label role / extends / taggedEntities, parameterised
 * grouping of relationships — is pre-computed by the planner onto the plan, so the reporter
 * never reaches back into the {@link com.neo4j.skemadok.model.SchemaDocument}.
 */
final class PlanReporter {

    private static final Logger log = LoggerFactory.getLogger(PlanReporter.class);

    private final SeedPlan plan;
    private final SeedOptions options;

    PlanReporter(SeedPlan plan, SeedOptions options) {
        this.plan = plan;
        this.options = options;
    }

    /**
     * Emits the breakdown as a single multi-line INFO log entry.
     */
    void logBreakdown() {
        var nl = System.lineSeparator();
        var sb = new StringBuilder();
        sb.append("Seed plan (scale=").append(options.scale())
                .append(", database=").append(options.database());
        if (options.dryRun()) {
            sb.append(", DRY RUN");
        }
        sb.append("):").append(nl).append(nl);

        renderNodes(sb, nl);
        sb.append(nl);
        renderRelationships(sb, nl);
        sb.append(nl);
        renderSummary(sb, nl);

        log.info("{}", sb);
    }

    /**
     * One row per non-removed label. ENTITY rows tagged with {@code WARN: no uniqueness
     * constraint} when applicable; HIERARCHY rows show {@code (extends X)}; TAG rows show
     * {@code (tags [hosts])}.
     */
    private void renderNodes(StringBuilder sb, String nl) {
        sb.append("NODES").append(nl);
        var warnings = Set.copyOf(plan.entityNoUniquenessWarnings());
        for (var summary : plan.labelSummaries()) {
            sb.append(String.format("  %-30s %-10s %,12d",
                    summary.name(), summary.role(), summary.count()));
            switch (summary.role()) {
                case ENTITY -> {
                    if (warnings.contains(summary.name())) {
                        sb.append("   WARN: no uniqueness constraint");
                    }
                }
                case HIERARCHY -> sb.append("   (extends ").append(summary.extendsLabel()).append(")");
                case TAG -> sb.append("   (tags ").append(summary.taggedEntities()).append(")");
            }
            sb.append(nl);
        }
    }

    /**
     * One row per logical relationship group, matching the runtime INFO-line granularity.
     * Skipped rels are listed below the planned ones so the user can see what didn't fit.
     */
    private void renderRelationships(StringBuilder sb, String nl) {
        sb.append("RELATIONSHIPS").append(nl);
        for (var group : plan.relGroups()) {
            renderRelGroup(sb, group);
        }
        for (var skipped : plan.skippedRels()) {
            sb.append(String.format("  %-36s   SKIPPED: %s%n", skipped.relType(), skipped.reason()));
        }
    }

    private static void renderRelGroup(StringBuilder sb, SeedPlan.RelGroup group) {
        if (group.parameterised() && group.plans().size() > 1) {
            sb.append(String.format("  %-36s %,12d across %,d instances    %s -> %s%n",
                    group.logicalType() + "_{...}",
                    group.totalCount(), group.plans().size(),
                    group.startLabel(), group.endLabel()));
        } else {
            sb.append(String.format("  %-36s %,12d                         %s -> %s%n",
                    group.logicalType(), group.totalCount(),
                    group.startLabel(), group.endLabel()));
        }
    }

    /**
     * Footer counts so the user can cross-check the breakdown against the schema and against
     * post-seed {@code MATCH (n) RETURN labels(n), count(*)} queries.
     */
    private void renderSummary(StringBuilder sb, String nl) {
        sb.append("SUMMARY").append(nl);
        long nodeCreatesTotal = plan.nodeCreates().stream().mapToLong(SeedPlan.NodeCreate::count).sum();
        long tagsAppliedTotal = plan.tagApplies().stream()
                .mapToLong(t -> t.hostCounts().values().stream().mapToLong(Long::longValue).sum())
                .sum();
        long relsTotal = plan.rels().stream().mapToLong(SeedPlan.RelPlan::target).sum();

        sb.append(String.format("  Labels in schema      : %d (%d ENTITY/HIERARCHY buckets, %d TAG applications)%n",
                plan.labelSummaries().size(), plan.nodeCreates().size(), plan.tagApplies().size()));
        sb.append(String.format("  Nodes to create       : %,d%n", nodeCreatesTotal));
        sb.append(String.format("  Tag labellings        : %,d%n", tagsAppliedTotal));
        sb.append(String.format("  Relationships planned : %,d (%d logical types, %d skipped)%n",
                relsTotal, plan.relGroups().size(), plan.skippedRels().size()));
        if (!plan.entityNoUniquenessWarnings().isEmpty()) {
            sb.append(String.format("  ENTITY labels without uniqueness constraint: %d%n",
                    plan.entityNoUniquenessWarnings().size()));
        }
    }
}
