package com.neo4j.skemadok.generator;

import com.neo4j.skemadok.model.*;

import java.time.Instant;
import java.util.List;

/**
 * Produces hand-crafted {@link SchemaDocument} instances for generator unit tests.
 * No database or Spring context required.
 */
class SchemaDocumentFixture {

    /**
     * Returns a representative document with one view, two labels, one relationship type,
     * one index, and one constraint — enough to exercise every section of a generated document.
     */
    static SchemaDocument standard() {
        var doc = new SchemaDocument("bolt://localhost:7687", "testdb", "5.26.0",
                Instant.parse("2026-01-15T10:00:00Z"));

        // Two labels
        var propId   = new PropertyInfo("id",   List.of("Long"),   false, "Primary key",   "Core ERP");
        var propName = new PropertyInfo("name",  List.of("String"), true,  "Display name",  "");

        var person = new LabelInfo("Person", 42_000L, List.of("Active"), List.of(propId, propName));
        person.setDescription("Represents an individual.");
        person.setDataSource("Salesforce");

        var active = new LabelInfo("Active", 30_000L, List.of("Person"), List.of());
        active.setDescription("Tag applied to currently active entities.");

        // One relationship type
        var since = new PropertyInfo("since", List.of("Date"), true, "Start date of the employment.", "HR System");

        var worksIn = new RelationshipTypeInfo("WORKS_IN", 5_000L,
                List.of(new Connection(List.of("Person"), List.of("Department"), 5_000L)),
                List.of(since));
        worksIn.setDescription("Links a person to the department they work in.");
        worksIn.setDataSource("HR System");

        // One view
        var view = new ViewDefinition();
        view.setName("HR Domain");
        view.setDescription("Human resources entities.");
        view.setLabels(List.of("Person", "Active"));
        view.setRelationshipTypes(List.of("WORKS_IN"));

        // One index, one constraint
        var index = new IndexInfo("person_id_idx", "RANGE", "NODE", List.of("Person"), List.of("id"), "ONLINE", 0L, "");
        var constraint = new ConstraintInfo("person_id_unique", "UNIQUENESS", "NODE",
                List.of("Person"), List.of("id"));

        doc.setNodeLabels(List.of(person, active));
        doc.setRelationshipTypes(List.of(worksIn));
        doc.setViews(List.of(view));
        doc.setIndexes(List.of(index));
        doc.setConstraints(List.of(constraint));

        return doc;
    }

    /**
     * Returns a document with a single relationship type that has two connection patterns,
     * intentionally ordered low-count-first so tests can verify descending sort.
     */
    static SchemaDocument multiConnection() {
        var doc = new SchemaDocument("bolt://localhost:7687", "testdb", "5.26.0",
                Instant.parse("2026-01-15T10:00:00Z"));

        var reportsTo = new RelationshipTypeInfo("REPORTS_TO", 4_500L,
                List.of(
                        new Connection(List.of("Contractor"), List.of("Manager"), 1_500L),
                        new Connection(List.of("Person"), List.of("Manager"), 3_000L)
                ),
                List.of());

        doc.setNodeLabels(List.of());
        doc.setRelationshipTypes(List.of(reportsTo));
        doc.setViews(List.of());
        doc.setIndexes(List.of());
        doc.setConstraints(List.of());

        return doc;
    }

    /** Returns a minimal document with no views, labels, rels, indexes, or constraints. */
    static SchemaDocument empty() {
        var doc = new SchemaDocument("bolt://localhost:7687", "emptydb", "5.26.0",
                Instant.parse("2026-01-15T10:00:00Z"));
        doc.setNodeLabels(List.of());
        doc.setRelationshipTypes(List.of());
        doc.setViews(List.of());
        doc.setIndexes(List.of());
        doc.setConstraints(List.of());
        return doc;
    }
}
