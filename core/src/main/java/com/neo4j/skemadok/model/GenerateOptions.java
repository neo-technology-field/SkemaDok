package com.neo4j.skemadok.model;

/**
 * Controls which optional annotation fields are rendered in generated documentation.
 * Passed from the UI request through the generator chain so templates can make consistent decisions.
 */
public record GenerateOptions(boolean includeDataSource) {

    /** All optional sections enabled — used by the CLI path which has no interactive toggles. */
    public static GenerateOptions defaults() {
        return new GenerateOptions(true);
    }
}
