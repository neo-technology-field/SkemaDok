package com.neo4j.skemadok.seeder;

/**
 * Scalar math for translating raw source counts into seeded counts under a {@code --scale}
 * multiplier. Kept separate from {@link SchemaSeeder} so the math is testable in isolation
 * and reusable across the planner and any future tooling that needs the same scaling rules.
 */
final class Scaling {

    private Scaling() {
    }

    /**
     * Scales a node-source count, rounding to the nearest integer. Floors at 1 for any positive
     * source so a sparsely-populated label still produces at least one seeded node — without
     * this floor a very small scale would silently drop labels and the rels that depend on
     * them.
     */
    static long scaledNodeCount(long source, double scale) {
        if (source <= 0L) {
            return 0L;
        }
        return Math.max(1L, Math.round(source * scale));
    }

    /**
     * Scales a relationship-source count. Same floor-at-1 rationale as
     * {@link #scaledNodeCount(long, double)}; the per-pair capacity cap is applied by the
     * planner separately.
     */
    static long scaledRelCount(long source, double scale) {
        if (source <= 0L) {
            return 0L;
        }
        var scaled = Math.round(source * scale);
        return Math.max(1L, scaled);
    }

    /**
     * Long multiplication that saturates at {@link Long#MAX_VALUE} instead of overflowing.
     * Used to compute the maximum number of relationships that fit between two node pools
     * without ever wrapping to a negative.
     */
    static long saturatingMultiply(long a, long b) {
        if (a == 0L || b == 0L) {
            return 0L;
        }
        if (a > Long.MAX_VALUE / b) {
            return Long.MAX_VALUE;
        }
        return a * b;
    }
}
