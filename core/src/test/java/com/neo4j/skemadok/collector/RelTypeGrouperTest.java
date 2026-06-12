package com.neo4j.skemadok.collector;

import com.neo4j.skemadok.model.Connection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the parameterised relationship type grouping logic.
 * No Neo4j connection required.
 */
class RelTypeGrouperTest {

    // ---- default threshold --------------------------------------------------

    @Test
    void defaultThresholdIsTen() {
        assertEquals(10, RelTypeGrouper.DEFAULT_THRESHOLD);
    }

    // ---- constructor: threshold behaviour -----------------------------------

    @Test
    void belowThresholdNamesAreUngrouped() {
        var names = List.of("REL_2024", "REL_2025", "REL_2026");
        var grouper = new RelTypeGrouper(names, 5);
        assertTrue(grouper.getGroups().isEmpty());
        assertEquals(3, grouper.getUngroupedNames().size());
    }

    @Test
    void atThresholdNamesAreGrouped() {
        var names = List.of("REL_2024", "REL_2025", "REL_2026", "REL_2027", "REL_2028");
        var grouper = new RelTypeGrouper(names, 5);
        assertEquals(Set.of("REL"), grouper.getGroups().keySet());
    }

    @Test
    void plainTypesWithNoSharedPrefixAreAllUngrouped() {
        var names = List.of("KNOWS", "WORKS_FOR", "FOLLOWS");
        var grouper = new RelTypeGrouper(names, 2);
        assertTrue(grouper.getGroups().isEmpty());
        assertEquals(3, grouper.getUngroupedNames().size());
    }

    @Test
    void baseIsLongestCommonSegmentPrefix() {
        // The variable part starts at the year; WORKS_FOR is the stable base
        var names = List.of("WORKS_FOR_2024", "WORKS_FOR_2025");
        var grouper = new RelTypeGrouper(names, 2);
        assertTrue(grouper.getGroups().containsKey("WORKS_FOR"));
        assertFalse(grouper.getGroups().containsKey("WORKS"));
    }

    @Test
    void differentSegmentCountsProduceSeparateGroups() {
        // 2-seg variants and 3-seg variants must not be merged
        var names = new ArrayList<String>();
        for (int i = 0; i < 5; i++) names.add("REL_" + i);
        for (int i = 0; i < 5; i++) names.add("REL_A_" + i);
        var grouper = new RelTypeGrouper(names, 5);
        assertEquals(2, grouper.getGroups().size());
    }

    @Test
    void groupMembersAreListed() {
        var names = List.of("REL_2024_01", "REL_2024_02", "REL_2025_01");
        var grouper = new RelTypeGrouper(names, 2);
        var members = grouper.getGroups().get("REL");
        assertNotNull(members);
        assertEquals(3, members.size());
        assertTrue(members.containsAll(names));
    }

    @Test
    void digitInSegmentDoesNotPreventGrouping() {
        var names = new ArrayList<String>();
        for (int i = 0; i < 10; i++) names.add("CODE2HELL_2026" + String.format("%02d", i) + "_AB");
        var grouper = new RelTypeGrouper(names, 10);
        assertTrue(grouper.getGroups().containsKey("CODE2HELL"));
    }

    @Test
    void twoDistinctGroupsDetectedWhenStablePartsAreDifferent() {
        var names = new ArrayList<String>();
        for (int i = 0; i < 5; i++) names.add("REL_TYPE_A_STABLE_" + i);
        for (int i = 5; i < 10; i++) names.add("REL_TYPE_A_STABLE_" + i);
        for (int i = 0; i < 5; i++) names.add("REL_TYPE_B_STABLE_ODD_" + i);
        for (int i = 0; i < 5; i++) names.add("REL_TYPE_B_STABLE_EVEN_" + i);
        names.add("REL_TYPE_PLAIN");
        var grouper = new RelTypeGrouper(names, 10);
        assertEquals(Set.of("REL_TYPE_A_STABLE", "REL_TYPE_B_STABLE"), grouper.getGroups().keySet());
        assertTrue(grouper.getUngroupedNames().contains("REL_TYPE_PLAIN"));
    }

    @Test
    void subThresholdSegmentBucketGoesToUngrouped() {
        var names = new ArrayList<String>();
        for (int i = 0; i < 10; i++) names.add("FOO_BAR_" + i);
        names.add("FOO_OTHER");
        var grouper = new RelTypeGrouper(names, 10);
        assertEquals(Set.of("FOO_BAR"), grouper.getGroups().keySet());
        assertTrue(grouper.getUngroupedNames().contains("FOO_OTHER"));
    }

    // ---- buildGrouped (static) ----------------------------------------------

    @Test
    void buildGroupedSetsNameAndCount() {
        var result = RelTypeGrouper.buildGrouped("ORDER",
                List.of("ORDER_1", "ORDER_2", "ORDER_3"), List.of(), 350L, List.of());
        assertEquals("ORDER", result.getName());
        assertEquals(350L, result.getCount());
    }

    @Test
    void buildGroupedSortsInstances() {
        var result = RelTypeGrouper.buildGrouped("ORDER",
                List.of("ORDER_3", "ORDER_1", "ORDER_2"), List.of(), 0L, List.of());
        assertEquals(List.of("ORDER_1", "ORDER_2", "ORDER_3"), result.getInstances());
    }

    @Test
    void buildGroupedDerivesTypeParametersFromMemberNames() {
        var result = RelTypeGrouper.buildGrouped("ORDER",
                List.of("ORDER_2024_Q1", "ORDER_2024_Q2", "ORDER_2025_Q1"),
                List.of(), 0L, List.of());
        assertEquals(2, result.getTypeParameters().size());
        assertTrue(result.getTypeParameters().getFirst().exampleValues().containsAll(List.of("2024", "2025")));
        assertTrue(result.getTypeParameters().get(1).exampleValues().containsAll(List.of("Q1", "Q2")));
    }

    @Test
    void buildGroupedSetsConnections() {
        var connections = List.of(new Connection(List.of("Customer"), List.of("Product"), 350L));
        var result = RelTypeGrouper.buildGrouped("ORDER",
                List.of("ORDER_1"), List.of(), 350L, connections);
        assertEquals(connections, result.getConnections());
    }

    // ---- longestCommonSegmentPrefix (static) --------------------------------

    @Test
    void longestCommonPrefixOfHomogeneousGroup() {
        var names = List.of("WORKS_FOR_2024", "WORKS_FOR_2025", "WORKS_FOR_2026");
        assertEquals("WORKS_FOR", RelTypeGrouper.longestCommonSegmentPrefix(names));
    }

    @Test
    void longestCommonPrefixWhenFirstSegmentAlsoVaries() {
        var names = List.of("REL_2024_01", "REL_2025_01");
        assertEquals("REL", RelTypeGrouper.longestCommonSegmentPrefix(names));
    }

    @Test
    void longestCommonPrefixOfEmptyListIsEmpty() {
        assertEquals("", RelTypeGrouper.longestCommonSegmentPrefix(List.of()));
    }
}
