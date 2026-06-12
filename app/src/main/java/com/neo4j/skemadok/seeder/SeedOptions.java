package com.neo4j.skemadok.seeder;

/**
 * Knobs for {@link SchemaSeeder#seed}. Scale is the only mandatory dial; the rest
 * are convenience switches that gate destructive or expensive steps.
 *
 * <p>{@code skipNonKeyIndexes} matters at large scales — index population during
 * a bulk load is the slowest single step. {@code dryRun} computes scaled targets
 * and returns without touching the database, so a user can sanity-check the
 * expected row counts before committing.
 */
public record SeedOptions(
        String database,
        double scale,
        boolean drop,
        boolean dryRun,
        boolean skipNonKeyIndexes,
        long randomSeed
) {

    public static SeedOptions defaults() {
        return new SeedOptions("neo4j", 1.0d, false, false, false, 42L);
    }

    public SeedOptions withDatabase(String database) {
        return new SeedOptions(database, scale, drop, dryRun, skipNonKeyIndexes, randomSeed);
    }

    public SeedOptions withScale(double scale) {
        return new SeedOptions(database, scale, drop, dryRun, skipNonKeyIndexes, randomSeed);
    }

    public SeedOptions withDrop(boolean drop) {
        return new SeedOptions(database, scale, drop, dryRun, skipNonKeyIndexes, randomSeed);
    }

    public SeedOptions withDryRun(boolean dryRun) {
        return new SeedOptions(database, scale, drop, dryRun, skipNonKeyIndexes, randomSeed);
    }

    public SeedOptions withSkipNonKeyIndexes(boolean skipNonKeyIndexes) {
        return new SeedOptions(database, scale, drop, dryRun, skipNonKeyIndexes, randomSeed);
    }

    public SeedOptions withRandomSeed(long randomSeed) {
        return new SeedOptions(database, scale, drop, dryRun, skipNonKeyIndexes, randomSeed);
    }
}
