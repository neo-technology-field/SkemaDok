package com.neo4j.skemadok.model;

import java.util.List;

/**
 * One connectivity pattern for a relationship type: the specific combination of start label set
 * and end label set observed in the graph, with the number of relationships matching that pattern.
 *
 * <p>Label lists are sorted on construction so equality is independent of query result ordering.
 * Rows from the database are kept separate — {@code ["A","B"] → ["E"]} and {@code ["A"] → ["E"]}
 * are distinct patterns even though both involve label A.
 */
public record Connection(List<String> startLabels, List<String> endLabels, long count) {

    public Connection {
        startLabels = startLabels.stream().sorted().toList();
        endLabels = endLabels.stream().sorted().toList();
    }
}
