package com.neo4j.skemadok.merge;

import com.neo4j.skemadok.model.ConstraintInfo;
import com.neo4j.skemadok.model.IndexInfo;
import com.neo4j.skemadok.model.LabelInfo;
import com.neo4j.skemadok.model.RelationshipTypeInfo;
import com.neo4j.skemadok.model.SchemaDocument;
import com.neo4j.skemadok.model.ViewDefinition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for schema merge logic.
 * No Neo4j connection required — all inputs are constructed in-memory.
 */
class SchemaMergerTest {

    private final SchemaMerger merger = new SchemaMerger();

    // ---- document-level fields ----------------------------------------------

    @Test
    void structuralHeaderFieldsComeFromIncoming() {
        var existing = doc("bolt://old", "old-db", "4.4.0");
        var incoming = doc("bolt://new", "new-db", "5.26.0");
        var merged = merger.merge(existing, incoming);

        assertEquals("bolt://new", merged.getDatabaseAddress());
        assertEquals("new-db", merged.getDatabaseName());
        assertEquals("5.26.0", merged.getDatabaseVersion());
    }

    @Test
    void capturedAtComesFromIncoming() {
        var now = Instant.now();
        var existing = doc();
        var incoming = new SchemaDocument("", "", "", now);
        assertEquals(now, merger.merge(existing, incoming).getCapturedAt());
    }

    @Test
    void indexesAndConstraintsAreAlwaysReplacedByIncoming() {
        var existing = new SchemaDocument("", "", "", Instant.EPOCH);
        existing.setIndexes(List.of(new IndexInfo("old_idx", "RANGE", "node", List.of("A"), List.of("id"), "ONLINE", 0L, "")));
        existing.setConstraints(List.of(new ConstraintInfo("old_con", "UNIQUENESS", "node", List.of("A"), List.of("id"))));
        var incoming = new SchemaDocument("", "", "", Instant.EPOCH);
        incoming.setIndexes(List.of(new IndexInfo("new_idx", "RANGE", "node", List.of("B"), List.of("id"), "ONLINE", 0L, "")));

        var merged = merger.merge(existing, incoming);
        assertEquals(1, merged.getIndexes().size());
        assertEquals("new_idx", merged.getIndexes().getFirst().name());
        assertTrue(merged.getConstraints().isEmpty());
    }

    @Test
    void viewsAreAlwaysPreservedFromExisting() {
        var view = new ViewDefinition("HR");
        var existing = new SchemaDocument("", "", "", Instant.EPOCH);
        existing.setViews(List.of(view));

        var merged = merger.merge(existing, doc());
        assertEquals(1, merged.getViews().size());
        assertEquals("HR", merged.getViews().getFirst().getName());
    }

    // ---- label merging ------------------------------------------------------

    @Test
    void newLabelFromIncomingIsAdded() {
        var merged = merger.merge(doc(), docWithLabels(label("Person", 100)));
        assertEquals(1, merged.getNodeLabels().size());
        assertEquals("Person", merged.getNodeLabels().getFirst().getName());
    }

    @Test
    void labelNodeCountIsUpdatedFromIncoming() {
        var old = label("Person", 100);
        old.setDescription("important");
        var merged = merger.merge(
                docWithLabels(old),
                docWithLabels(label("Person", 9999))
        );
        var person = merged.getNodeLabels().getFirst();
        assertEquals(9999, person.getNodeCount());
        assertEquals("important", person.getDescription());
    }

    @Test
    void labelAnnotationsArePreservedAcrossMerge() {
        var old = label("Person", 100);
        old.setDescription("main entity");
        old.setDataSource("CRM");
        var person = merger.merge(docWithLabels(old), docWithLabels(label("Person", 200)))
                .getNodeLabels().getFirst();
        assertEquals("main entity", person.getDescription());
        assertEquals("CRM", person.getDataSource());
    }

    @Test
    void removedLabelIsFlaggedWhenAbsentFromIncoming() {
        var merged = merger.merge(docWithLabels(label("Person", 100)), doc());
        assertTrue(merged.getNodeLabels().getFirst().isRemoved());
    }

    @Test
    void previouslyRemovedLabelIsPreservedInSubsequentMerge() {
        var step1 = merger.merge(docWithLabels(label("Person", 100)), doc());
        var step2 = merger.merge(step1, doc());
        assertEquals(1, step2.getNodeLabels().size());
        assertTrue(step2.getNodeLabels().getFirst().isRemoved());
    }

    @Test
    void removedLabelComesBackWhenPresentInIncoming() {
        var removed = label("Person", 50);
        removed.setRemoved(true);
        removed.setDescription("comes back");
        var person = merger.merge(docWithLabels(removed), docWithLabels(label("Person", 200)))
                .getNodeLabels().getFirst();
        assertFalse(person.isRemoved());
        assertEquals(200, person.getNodeCount());
        assertEquals("comes back", person.getDescription());
    }

    // ---- relationship type merging ------------------------------------------

    @Test
    void relTypeCountIsUpdatedFromIncoming() {
        var old = rel("KNOWS", 10);
        old.setDescription("social");
        var merged = merger.merge(docWithRels(old), docWithRels(rel("KNOWS", 9999)));
        var knows = merged.getRelationshipTypes().getFirst();
        assertEquals(9999, knows.getCount());
        assertEquals("social", knows.getDescription());
    }

    @Test
    void removedRelTypeIsFlaggedWhenAbsentFromIncoming() {
        var merged = merger.merge(docWithRels(rel("KNOWS", 10)), doc());
        assertTrue(merged.getRelationshipTypes().getFirst().isRemoved());
    }

    @Test
    void previouslyRemovedRelTypeIsPreservedInSubsequentMerge() {
        var step1 = merger.merge(docWithRels(rel("KNOWS", 10)), doc());
        var step2 = merger.merge(step1, doc());
        assertEquals(1, step2.getRelationshipTypes().size());
        assertTrue(step2.getRelationshipTypes().getFirst().isRemoved());
    }

    // ---- helpers ------------------------------------------------------------

    private SchemaDocument doc() {
        return new SchemaDocument("", "", "", Instant.EPOCH);
    }

    private SchemaDocument doc(String address, String name, String version) {
        return new SchemaDocument(address, name, version, Instant.EPOCH);
    }

    private SchemaDocument docWithLabels(LabelInfo... labels) {
        var doc = new SchemaDocument("", "", "", Instant.EPOCH);
        doc.setNodeLabels(new ArrayList<>(List.of(labels)));
        return doc;
    }

    private SchemaDocument docWithRels(RelationshipTypeInfo... rels) {
        var doc = new SchemaDocument("", "", "", Instant.EPOCH);
        doc.setRelationshipTypes(new ArrayList<>(List.of(rels)));
        return doc;
    }

    private LabelInfo label(String name, long count) {
        return new LabelInfo(name, count, List.of(), new ArrayList<>());
    }

    private RelationshipTypeInfo rel(String name, long count) {
        return new RelationshipTypeInfo(name, count, List.of(), new ArrayList<>());
    }
}
