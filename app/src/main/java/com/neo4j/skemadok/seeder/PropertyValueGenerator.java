package com.neo4j.skemadok.seeder;

import com.neo4j.skemadok.model.PropertyInfo;

/**
 * Strategy for inventing a value for a single property occurrence during seeding.
 *
 * <p>v1 ships a single placeholder implementation; a Faker-backed variant is intended
 * to drop in behind this interface once name-heuristic value generation is needed.
 */
public interface PropertyValueGenerator {

    /**
     * @param ownerName    the label or relationship type that owns the property
     * @param property     the schema description of the property
     * @param index        zero-based ordinal of the entity carrying this property within the
     *                     owner's index space; used to keep generated values deterministic and
     *                     unique-per-label when {@code mustBeUnique} is set
     * @param mustBeUnique true when the property participates in a UNIQUENESS or KEY constraint
     *                     on this owner; the generator is then expected to vary the returned
     *                     value by {@code index} so the resulting batch satisfies the constraint
     * @return a value compatible with the Neo4j driver's Java type mapping, or {@code null}
     * to leave the property unset
     */
    Object generate(String ownerName, PropertyInfo property, long index, boolean mustBeUnique);
}
