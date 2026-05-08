package com.neo4j.skemadok.collector;

import com.neo4j.skemadok.model.Connection;
import com.neo4j.skemadok.model.RelationshipTypeInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the parameterised relationship type grouping logic.
 * No Neo4j connection required.
 */
class RelTypeGrouperTest {

    private final RelTypeGrouper grouper = new RelTypeGrouper();

    // ---- group — threshold --------------------------------------------------

    @Test
    void defaultThresholdIsTen() {
        assertEquals(10, RelTypeGrouper.DEFAULT_THRESHOLD);
    }

    @Test
    void familyBelowThresholdPassesThroughAsIndividualTypes() {
        var types = List.of(rel("REL_2024"), rel("REL_2025"), rel("REL_2026"));
        var result = grouper.group(types, 5);
        assertEquals(3, result.size());
        assertTrue(result.stream().noneMatch(RelationshipTypeInfo::isParameterized));
    }

    @Test
    void familyAtThresholdIsGrouped() {
        var types = List.of(rel("REL_2024"), rel("REL_2025"), rel("REL_2026"),
                rel("REL_2027"), rel("REL_2028"));
        var result = grouper.group(types, 5);
        assertEquals(1, result.size());
        assertEquals("REL", result.getFirst().getName());
        assertTrue(result.getFirst().isParameterized());
    }

    @Test
    void thresholdZeroGroupsEvenPairs() {
        var types = List.of(rel("FOO_2024"), rel("FOO_2025"));
        var result = grouper.group(types, 0);
        assertEquals(1, result.size());
        assertEquals("FOO", result.getFirst().getName());
        assertTrue(result.getFirst().isParameterized());
    }

    // ---- group — no parameterisation ----------------------------------------

    @Test
    void regularTypesPassThroughUnchanged() {
        var types = List.of(rel("KNOWS"), rel("WORKS_FOR"), rel("FOLLOWS"));
        var result = grouper.group(types, 2);
        assertEquals(3, result.size());
        var names = result.stream().map(RelationshipTypeInfo::getName).toList();
        assertTrue(names.containsAll(List.of("FOLLOWS", "KNOWS", "WORKS_FOR")));
    }

    @Test
    void singleCandidateIsNotGrouped() {
        var types = List.of(rel("WORKS_FOR_2024"), rel("KNOWS"));
        var result = grouper.group(types, 2);
        assertEquals(2, result.size());
        assertTrue(result.stream().noneMatch(RelationshipTypeInfo::isParameterized));
    }

    // ---- group — grouping ---------------------------------------------------

    @Test
    void twoInstancesAreGroupedUnderBaseName() {
        var types = List.of(rel("WORKS_FOR_2024"), rel("WORKS_FOR_2025"));
        var result = grouper.group(types, 2);
        assertEquals(1, result.size());
        var grouped = result.getFirst();
        assertEquals("WORKS", grouped.getName());
        assertTrue(grouped.isParameterized());
    }

    @Test
    void baseIsShortestQualifyingPrefix() {
        // WORKS is the shortest qualifying prefix — it has count ≥ threshold, so it wins over WORKS_FOR
        var types = List.of(rel("WORKS_FOR_122"), rel("WORKS_FOR_233"));
        var result = grouper.group(types, 2);
        assertEquals(1, result.size());
        assertEquals("WORKS", result.getFirst().getName());
    }

    @Test
    void digitInFirstSegmentDoesNotPreventGrouping() {
        // CODE2HELL contains a digit but that is irrelevant — frequency is the only signal
        var types = List.of(
                rel("CODE2HELL_202604_AB"), rel("CODE2HELL_202604_BC"),
                rel("CODE2HELL_202605_AB"), rel("CODE2HELL_202605_BC"),
                rel("CODE2HELL_202606_AB"), rel("CODE2HELL_202606_BC"),
                rel("CODE2HELL_202607_AB"), rel("CODE2HELL_202607_BC"),
                rel("CODE2HELL_202608_AB"), rel("CODE2HELL_202608_BC")
        );
        var result = grouper.group(types, 10);
        assertEquals(1, result.size());
        assertEquals("CODE2HELL", result.getFirst().getName());
        assertTrue(result.getFirst().isParameterized());
        assertEquals(2, result.getFirst().getTypeParameters().size());
    }

    @Test
    void groupedTypeHasCorrectInstanceList() {
        var types = List.of(rel("REL_2024_01"), rel("REL_2024_02"), rel("REL_2025_01"));
        var grouped = grouper.group(types, 2).getFirst();
        assertEquals(List.of("REL_2024_01", "REL_2024_02", "REL_2025_01"), grouped.getInstances());
    }

    @Test
    void groupedTypeHasCorrectParameterCount() {
        var types = List.of(rel("EDGE_2024_01"), rel("EDGE_2024_02"));
        var grouped = grouper.group(types, 2).getFirst();
        assertEquals(2, grouped.getTypeParameters().size());
    }

    @Test
    void exampleValuesAreSampledFromInstances() {
        var types = List.of(rel("EDGE_2024_Q1"), rel("EDGE_2024_Q2"), rel("EDGE_2025_Q1"));
        var grouped = grouper.group(types, 2).getFirst();
        var param0 = grouped.getTypeParameters().getFirst();
        var param1 = grouped.getTypeParameters().get(1);
        assertTrue(param0.exampleValues().containsAll(List.of("2024", "2025")));
        assertTrue(param1.exampleValues().containsAll(List.of("Q1", "Q2")));
    }

    @Test
    void countsAreSummedAcrossInstances() {
        var a = new RelationshipTypeInfo("FOO_2024", 100, List.of(), new java.util.ArrayList<>());
        var b = new RelationshipTypeInfo("FOO_2025", 200, List.of(), new java.util.ArrayList<>());
        var grouped = grouper.group(List.of(a, b), 2).getFirst();
        assertEquals(300, grouped.getCount());
    }

    @Test
    void connectionsFromAllInstancesAreCombined() {
        var a = new RelationshipTypeInfo("EDGE_2024", 0,
                List.of(new Connection(List.of("Person"), List.of("Organization"), 10)),
                new java.util.ArrayList<>());
        var b = new RelationshipTypeInfo("EDGE_2025", 0,
                List.of(new Connection(List.of("Person"), List.of("Company"), 5)),
                new java.util.ArrayList<>());

        var grouped = grouper.group(List.of(a, b), 2).getFirst();
        var connections = grouped.getConnections();
        // Sorted by count descending: Organization (10) before Company (5)
        assertEquals(2, connections.size());
        assertEquals(List.of("Person"), connections.getFirst().startLabels());
        assertEquals(List.of("Organization"), connections.getFirst().endLabels());
        assertEquals(List.of("Person"), connections.getLast().startLabels());
        assertEquals(List.of("Company"), connections.getLast().endLabels());
    }

    @Test
    void instancesWithDifferentSegmentCountsAreNotGroupedTogether() {
        // REL_2024 (2 vars) vs REL_2024_01 (3 vars) — different total segment counts stay separate
        var types = List.of(rel("REL_2024"), rel("REL_2025"), rel("REL_2024_01"), rel("REL_2025_01"));
        var result = grouper.group(types, 2);
        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(RelationshipTypeInfo::isParameterized));
    }

    @Test
    void resultIsSortedAlphabetically() {
        var types = List.of(
                rel("ZEBRA_2024"), rel("ZEBRA_2025"),
                rel("APPLE_2024"), rel("APPLE_2025")
        );
        var result = grouper.group(types, 2);
        assertEquals("APPLE", result.getFirst().getName());
        assertEquals("ZEBRA", result.get(1).getName());
    }

    // ---- detectsGroups ------------------------------------------------------

    @Test
    void detectsGroupsReturnsTrueWhenFamilyMeetsThreshold() {
        var names = List.of("REL_2024", "REL_2025", "REL_2026");
        assertTrue(grouper.detectsGroups(names, 3));
        assertFalse(grouper.detectsGroups(names, 4));
    }

    @Test
    void detectsGroupsHandlesDigitsAnywhereInName() {
        var names = List.of(
                "CODE2HELL_202604_AB", "CODE2HELL_202604_BC", "CODE2HELL_202605_AB",
                "CODE2HELL_202605_BC", "CODE2HELL_202606_AB"
        );
        assertTrue(grouper.detectsGroups(names, 5));
        assertFalse(grouper.detectsGroups(names, 6));
    }

    // ---- helpers ------------------------------------------------------------

    private RelationshipTypeInfo rel(String name) {
        return new RelationshipTypeInfo(name, 0, List.of(), new java.util.ArrayList<>());
    }
}
