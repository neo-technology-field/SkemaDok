package com.neo4j.skemadok.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Describes one variable segment in a parameterised relationship type name.
 *
 * <p>Example: for {@code WORKS_FOR_2024_01}, the base is {@code WORKS_FOR} and there are two
 * parameters — position 0 for the year segment and position 1 for the month segment.
 *
 * <p>{@code name} is the user-provided variable identifier (e.g. "year", "quarter"); defaults
 * to {@code v1}, {@code v2}, etc.
 * {@code description} is a free-text explanation (e.g. "Fiscal year").
 * {@code exampleValues} are sampled automatically from the actual instance names.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TypeParameter(
        int position,
        String name,
        String description,
        List<String> exampleValues
) {
    public TypeParameter {
        if (name == null || name.isBlank()) {
            name = "v" + (position + 1);
        }
        if (description == null) {
            description = "";
        }
    }

    /** Structural constructor — name defaults to vN, description defaults to empty string. */
    public TypeParameter(int position, List<String> exampleValues) {
        this(position, "v" + (position + 1), "", exampleValues);
    }

    /**
     * Jackson deserialisation entry point. Defaults {@code name} to the positional default
     * when absent, so old {@code schema.json} files without the field round-trip cleanly.
     */
    @JsonCreator
    public static TypeParameter fromJson(
            @JsonProperty("position") int position,
            @JsonProperty("name") String name,
            @JsonProperty("description") String description,
            @JsonProperty("exampleValues") List<String> exampleValues
    ) {
        return new TypeParameter(
                position,
                name,
                description != null ? description : "",
                exampleValues != null ? exampleValues : List.of()
        );
    }

    public TypeParameter withName(String name) {
        return new TypeParameter(position, name, description, exampleValues);
    }

    public TypeParameter withDescription(String description) {
        return new TypeParameter(position, name, description, exampleValues);
    }
}
