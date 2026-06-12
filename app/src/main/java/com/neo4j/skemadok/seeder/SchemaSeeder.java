package com.neo4j.skemadok.seeder;

import com.neo4j.skemadok.model.SchemaDocument;
import org.neo4j.driver.Driver;

/**
 * Entry point for materialising a Neo4j database from a captured {@link SchemaDocument}.
 *
 * <p>The class is a thin orchestrator over three single-responsibility collaborators:
 * <ul>
 *   <li>{@link SeedPlanner} — validates the schema and computes the {@link SeedPlan}.</li>
 *   <li>{@link PlanReporter} — emits the upfront breakdown so the user can verify the plan.</li>
 *   <li>{@link SeedExecutor} — runs the plan against a live driver in the right phase order.</li>
 * </ul>
 *
 * <p>Construction takes only what is needed to wire the seeder into its environment — the
 * property-value generator strategy. Everything else flows through the per-call API.
 */
public class SchemaSeeder {

    private final PropertyValueGenerator valueGenerator;

    public SchemaSeeder() {
        this(new PlaceholderValueGenerator());
    }

    public SchemaSeeder(PropertyValueGenerator valueGenerator) {
        this.valueGenerator = valueGenerator;
    }

    /**
     * Builds a {@link SeedPlan} without touching the database. Throws {@link IllegalStateException}
     * when the schema's HIERARCHY chains or TAG annotations are inconsistent.
     */
    public SeedPlan plan(SchemaDocument schema, SeedOptions options) {
        return new SeedPlanner(schema, options.scale()).plan();
    }

    /**
     * Builds the plan and logs the upfront breakdown, then returns the plan. Useful when the
     * caller needs to act on the plan further — e.g., to drive a Parquet export rather than
     * executing against a live database.
     */
    public SeedPlan planAndReport(SchemaDocument schema, SeedOptions options) {
        var plan = plan(schema, options);
        new PlanReporter(plan, options).logBreakdown();
        return plan;
    }

    /**
     * Builds the plan and prints the breakdown without touching the database. Convenient for
     * {@code --dry-run} from the CLI; uses no driver.
     */
    public void dryRun(SchemaDocument schema, SeedOptions options) {
        var plan = plan(schema, options);
        new PlanReporter(plan, options).logBreakdown();
    }

    /**
     * Builds the plan, prints the breakdown, and (unless {@link SeedOptions#dryRun()} is set)
     * executes the plan against {@code driver}.
     */
    public SeedPlan seed(Driver driver, SchemaDocument schema, SeedOptions options) {
        var plan = plan(schema, options);
        new PlanReporter(plan, options).logBreakdown();
        if (options.dryRun()) {
            return plan;
        }
        new SeedExecutor(driver, valueGenerator, options).execute(schema, plan);
        return plan;
    }
}
