package com.neo4j.skemadok.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Describes one variable segment in a parameterised relationship type name.
 *
 * <p>Example: for {@code WORKS_FOR_2024_01}, the base is {@code WORKS_FOR} and there are two
 * parameters — position 0 for the year segment and position 1 for the month segment.
 *
 * <p>{@code description} is the user-provided explanation (e.g. "Fiscal year").
 * {@code exampleValues} are sampled automatically from the actual instance names.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TypeParameter(
        int position,
        String description,
        List<String> exampleValues
) {
    public TypeParameter {
        if (description == null) description = "";
    }

    /** Structural constructor — description defaults to empty string. */
    public TypeParameter(int position, List<String> exampleValues) {
        this(position, "", exampleValues);
    }

    public TypeParameter withDescription(String description) {
        return new TypeParameter(position, description, exampleValues);
    }
}
