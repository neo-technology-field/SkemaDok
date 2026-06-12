package com.neo4j.skemadok.seeder;

import com.neo4j.skemadok.model.Connection;
import com.neo4j.skemadok.model.ConstraintInfo;
import com.neo4j.skemadok.model.IndexInfo;
import com.neo4j.skemadok.model.LabelInfo;
import com.neo4j.skemadok.model.LabelRole;
import com.neo4j.skemadok.model.RelationshipTypeInfo;
import com.neo4j.skemadok.model.SchemaDocument;
import com.neo4j.skemadok.model.TypeParameter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaSeederPlanTest {

    private final SchemaSeeder seeder = new SchemaSeeder();

    // ---------- scale math (ENTITY-only schemas) ----------

    @Test
    void linearDownScaleAppliedToBothNodesAndRelationships() {
        var doc = newDoc();
        doc.getNodeLabels().add(entity("Person", 1_000));
        doc.getNodeLabels().add(entity("Company", 100));
        doc.getRelationshipTypes().add(rel("WORKS_AT", 500,
                new Connection(List.of("Person"), List.of("Company"), 500)));

        var plan = seeder.plan(doc, SeedOptions.defaults().withScale(0.1d));

        assertEquals(100L, plan.totalForLabel("Person"));
        assertEquals(10L,  plan.totalForLabel("Company"));
        assertEquals(50L,  relTarget(plan, "WORKS_AT"));
    }

    @Test
    void atLeastOneNodeRetainedWhenScaleRoundsToZero() {
        var doc = newDoc();
        doc.getNodeLabels().add(entity("Rare", 3));

        var plan = seeder.plan(doc, SeedOptions.defaults().withScale(0.01d));

        assertEquals(1L, plan.totalForLabel("Rare"));
    }

    @Test
    void connectionTargetCappedAtEndpointProduct() {
        var doc = newDoc();
        doc.getNodeLabels().add(entity("A", 200));
        doc.getNodeLabels().add(entity("B", 200));
        doc.getRelationshipTypes().add(rel("R", 1_000,
                new Connection(List.of("A"), List.of("B"), 1_000)));

        var plan = seeder.plan(doc, SeedOptions.defaults().withScale(0.01d));

        assertEquals(2L, plan.totalForLabel("A"));
        assertEquals(2L, plan.totalForLabel("B"));
        assertEquals(4L, relTarget(plan, "R"));
    }

    @Test
    void upscaleProducesProportionallyMoreNodesAndRels() {
        var doc = newDoc();
        doc.getNodeLabels().add(entity("Person", 100));
        doc.getRelationshipTypes().add(rel("KNOWS", 250,
                new Connection(List.of("Person"), List.of("Person"), 250)));

        var plan = seeder.plan(doc, SeedOptions.defaults().withScale(10d));

        assertEquals(1_000L, plan.totalForLabel("Person"));
        assertEquals(2_500L, relTarget(plan, "KNOWS"));
    }

    @Test
    void parameterisedRelTypesUnrollOneRelPlanPerInstance() {
        var doc = newDoc();
        doc.getNodeLabels().add(entity("Person", 100));
        doc.getNodeLabels().add(entity("Department", 50));
        var instances = List.of("PROMOTED_2024_Q1", "PROMOTED_2024_Q2", "PROMOTED_2024_Q3", "PROMOTED_2024_Q4");
        doc.getRelationshipTypes().add(parameterisedRel("PROMOTED", 400,
                new Connection(List.of("Person"), List.of("Department"), 400), instances));

        var plan = seeder.plan(doc, SeedOptions.defaults().withScale(1d));

        for (var instance : instances) {
            assertEquals(100L, relTarget(plan, instance), "instance=" + instance);
        }
        assertNull(planRelByType(plan, "PROMOTED"), "the group entry itself should not produce a RelPlan");
    }

    @Test
    void connectionToUnknownLabelIsSkipped() {
        var doc = newDoc();
        doc.getNodeLabels().add(entity("Person", 100));
        doc.getRelationshipTypes().add(rel("HAUNTS", 10,
                new Connection(List.of("Ghost"), List.of("Person"), 10)));

        var plan = seeder.plan(doc, SeedOptions.defaults().withScale(1d));

        assertTrue(plan.rels().isEmpty());
    }

    @Test
    void multiLabelConnectionFlattensToFirstLabelEachSide() {
        var doc = newDoc();
        doc.getNodeLabels().add(entity("Person", 100));
        doc.getNodeLabels().add(entity("Holder", 100));
        doc.getNodeLabels().add(entity("Asset", 50));
        doc.getNodeLabels().add(entity("Resource", 50));
        doc.getRelationshipTypes().add(rel("OWNS", 30,
                new Connection(List.of("Person", "Holder"), List.of("Asset", "Resource"), 30)));

        var plan = seeder.plan(doc, SeedOptions.defaults().withScale(1d));

        var planEntry = planRelByType(plan, "OWNS");
        assertEquals("Holder", planEntry.startLabel());
        assertEquals("Asset",  planEntry.endLabel());
    }

    // ---------- HIERARCHY ----------

    @Test
    void hierarchyChildCarvesNodesOutOfParentTotal() {
        var doc = newDoc();
        doc.getNodeLabels().add(entity("Person", 100));
        doc.getNodeLabels().add(hierarchy("Employee", "Person", 40));

        var plan = seeder.plan(doc, SeedOptions.defaults().withScale(1d));

        assertEquals(100L, plan.totalForLabel("Person"), "every node carries Person");
        assertEquals(40L,  plan.totalForLabel("Employee"), "subset of Persons carry Employee too");

        // Two buckets: 40 Person+Employee, 60 plain Person.
        var withSub = bucketForLabels(plan, List.of("Person", "Employee"));
        assertNotNull(withSub);
        assertEquals(40L, withSub.count());

        var plain = bucketForLabels(plan, List.of("Person"));
        assertNotNull(plain);
        assertEquals(60L, plain.count());
    }

    @Test
    void multipleHierarchyChildrenOfSameParent() {
        var doc = newDoc();
        doc.getNodeLabels().add(entity("Person", 1_000));
        doc.getNodeLabels().add(hierarchy("Employee", "Person", 400));
        doc.getNodeLabels().add(hierarchy("Customer", "Person", 200));

        var plan = seeder.plan(doc, SeedOptions.defaults().withScale(1d));

        assertEquals(1_000L, plan.totalForLabel("Person"));
        assertEquals(400L,   plan.totalForLabel("Employee"));
        assertEquals(200L,   plan.totalForLabel("Customer"));
        // Plain Person = 1000 − 400 − 200 = 400.
        assertEquals(400L, bucketForLabels(plan, List.of("Person")).count());
    }

    @Test
    void multiLevelHierarchyChainAppliesEveryAncestorLabel() {
        var doc = newDoc();
        doc.getNodeLabels().add(entity("Person", 1_000));
        doc.getNodeLabels().add(hierarchy("Employee", "Person", 400));
        doc.getNodeLabels().add(hierarchy("Manager", "Employee", 50));

        var plan = seeder.plan(doc, SeedOptions.defaults().withScale(1d));

        // Each label sums to the right total.
        assertEquals(1_000L, plan.totalForLabel("Person"));
        assertEquals(400L,   plan.totalForLabel("Employee"));
        assertEquals(50L,    plan.totalForLabel("Manager"));

        // The deepest bucket carries all three labels.
        var managerBucket = bucketForLabels(plan, List.of("Person", "Employee", "Manager"));
        assertNotNull(managerBucket);
        assertEquals(50L, managerBucket.count());

        // Plain-Employee bucket has Person + Employee but not Manager.
        var plainEmployee = bucketForLabels(plan, List.of("Person", "Employee"));
        assertNotNull(plainEmployee);
        assertEquals(350L, plainEmployee.count());
    }

    @Test
    void hierarchyWithoutExtendsAborts() {
        var doc = newDoc();
        doc.getNodeLabels().add(entity("Person", 100));
        var orphan = new LabelInfo("Orphan", 10, new ArrayList<>(), new ArrayList<>());
        orphan.setRole(LabelRole.HIERARCHY);  // role set, but no extends
        doc.getNodeLabels().add(orphan);

        var ex = assertThrows(IllegalStateException.class,
                () -> seeder.plan(doc, SeedOptions.defaults().withScale(1d)));
        assertTrue(ex.getMessage().contains("Orphan"));
    }

    @Test
    void hierarchyExtendingMissingLabelAborts() {
        var doc = newDoc();
        var dangling = new LabelInfo("Manager", 10, new ArrayList<>(), new ArrayList<>());
        dangling.setRole(LabelRole.HIERARCHY);
        dangling.setExtendsLabel("PersonThatDoesNotExist");
        doc.getNodeLabels().add(dangling);

        var ex = assertThrows(IllegalStateException.class,
                () -> seeder.plan(doc, SeedOptions.defaults().withScale(1d)));
        assertTrue(ex.getMessage().contains("PersonThatDoesNotExist"));
    }

    @Test
    void hierarchyExtendsCycleAborts() {
        var doc = newDoc();
        var a = new LabelInfo("A", 10, new ArrayList<>(), new ArrayList<>());
        a.setRole(LabelRole.HIERARCHY);
        a.setExtendsLabel("B");
        var b = new LabelInfo("B", 10, new ArrayList<>(), new ArrayList<>());
        b.setRole(LabelRole.HIERARCHY);
        b.setExtendsLabel("A");
        doc.getNodeLabels().add(a);
        doc.getNodeLabels().add(b);

        var ex = assertThrows(IllegalStateException.class,
                () -> seeder.plan(doc, SeedOptions.defaults().withScale(1d)));
        assertTrue(ex.getMessage().toLowerCase().contains("cycle"));
    }

    // ---------- TAG ----------

    @Test
    void tagApplicationProducesProportionalHostCounts() {
        var doc = newDoc();
        doc.getNodeLabels().add(entity("Person", 600));
        doc.getNodeLabels().add(entity("Company", 400));
        doc.getNodeLabels().add(tag("VIP", List.of("Person", "Company"), 100));

        var plan = seeder.plan(doc, SeedOptions.defaults().withScale(1d));

        assertEquals(100L, plan.totalForLabel("VIP"));
        assertEquals(1, plan.tagApplies().size());
        var apply = plan.tagApplies().getFirst();
        // 600/1000 of 100 = 60, 400/1000 of 100 = 40.
        assertEquals(60L, apply.hostCounts().get("Person"));
        assertEquals(40L, apply.hostCounts().get("Company"));
    }

    @Test
    void tagApplicationClampsToHostSize() {
        var doc = newDoc();
        doc.getNodeLabels().add(entity("Person", 10));
        doc.getNodeLabels().add(tag("VIP", List.of("Person"), 100));

        var plan = seeder.plan(doc, SeedOptions.defaults().withScale(1d));

        // Tag asks for 100 but only 10 Persons exist; clamp to 10.
        assertEquals(10L, plan.totalForLabel("VIP"));
    }

    @Test
    void tagWithoutTaggedEntitiesAborts() {
        var doc = newDoc();
        doc.getNodeLabels().add(entity("Person", 100));
        // TAG-role label with no taggedEntities — the schema annotation is incomplete.
        var orphan = new LabelInfo("AggregatedBOM", 200, new ArrayList<>(), new ArrayList<>());
        orphan.setRole(LabelRole.TAG);
        doc.getNodeLabels().add(orphan);

        var ex = assertThrows(IllegalStateException.class,
                () -> seeder.plan(doc, SeedOptions.defaults().withScale(1d)));
        assertTrue(ex.getMessage().contains("AggregatedBOM"));
        assertTrue(ex.getMessage().contains("taggedEntities"));
    }

    @Test
    void tagWithEmptyTaggedEntitiesAborts() {
        var doc = newDoc();
        doc.getNodeLabels().add(entity("Person", 100));
        var orphan = new LabelInfo("OrphanTag", 10, new ArrayList<>(), new ArrayList<>());
        orphan.setRole(LabelRole.TAG);
        orphan.setTaggedEntities(new ArrayList<>());
        doc.getNodeLabels().add(orphan);

        var ex = assertThrows(IllegalStateException.class,
                () -> seeder.plan(doc, SeedOptions.defaults().withScale(1d)));
        assertTrue(ex.getMessage().contains("OrphanTag"));
    }

    @Test
    void multipleSchemaErrorsAreReportedTogether() {
        // Both an orphan TAG and a broken HIERARCHY → one combined exception listing both.
        var doc = newDoc();
        var orphanTag = new LabelInfo("OrphanTag", 5, new ArrayList<>(), new ArrayList<>());
        orphanTag.setRole(LabelRole.TAG);
        doc.getNodeLabels().add(orphanTag);

        var brokenHier = new LabelInfo("Manager", 5, new ArrayList<>(), new ArrayList<>());
        brokenHier.setRole(LabelRole.HIERARCHY);
        brokenHier.setExtendsLabel("Ghost");
        doc.getNodeLabels().add(brokenHier);

        var ex = assertThrows(IllegalStateException.class,
                () -> seeder.plan(doc, SeedOptions.defaults().withScale(1d)));
        assertTrue(ex.getMessage().contains("OrphanTag"), "tag error missing");
        assertTrue(ex.getMessage().contains("Ghost"), "hierarchy error missing");
        assertTrue(ex.getMessage().contains("TAG labels missing taggedEntities"),
                "tag section header missing");
        assertTrue(ex.getMessage().contains("HIERARCHY labels with broken extends chain"),
                "hierarchy section header missing");
    }

    // ---------- audit warnings ----------

    @Test
    void entityWithoutUniquenessIsRecordedInWarnings() {
        var doc = newDoc();
        doc.getNodeLabels().add(entity("WithoutConstraint", 50));
        doc.getNodeLabels().add(entity("WithConstraint", 50));
        doc.getConstraints().add(new ConstraintInfo(
                "with_unique", "UNIQUENESS", "node", List.of("WithConstraint"), List.of("id")));

        var plan = seeder.plan(doc, SeedOptions.defaults().withScale(1d));

        assertTrue(plan.entityNoUniquenessWarnings().contains("WithoutConstraint"));
        assertEquals(1, plan.entityNoUniquenessWarnings().size(),
                "WithConstraint should not appear in warnings");
    }

    // ---------- skipped rels ----------

    @Test
    void relationshipWithEmptyEndpointPoolIsRecordedAsSkipped() {
        var doc = newDoc();
        doc.getNodeLabels().add(entity("Person", 10));
        // Connection refs "Ghost" which is not in nodeLabels — labelRanges has nothing for Ghost.
        doc.getRelationshipTypes().add(rel("HAUNTS", 5,
                new Connection(List.of("Ghost"), List.of("Person"), 5)));

        var plan = seeder.plan(doc, SeedOptions.defaults().withScale(1d));

        assertTrue(plan.rels().isEmpty(), "rel should not be planned");
        assertEquals(1, plan.skippedRels().size());
        var skipped = plan.skippedRels().getFirst();
        assertEquals("HAUNTS", skipped.relType());
        assertTrue(skipped.reason().contains("Ghost"),
                "reason should name the missing label: " + skipped.reason());
    }

    // ---------- math helpers ----------

    @Test
    void scaledNodeCountClampsAtOneForPositiveSources() {
        assertEquals(0L,  Scaling.scaledNodeCount(0L,    0.5d));
        assertEquals(1L,  Scaling.scaledNodeCount(2L,    0.001d));
        assertEquals(50L, Scaling.scaledNodeCount(100L,  0.5d));
    }

    @Test
    void saturatingMultiplyAvoidsOverflow() {
        assertEquals(Long.MAX_VALUE, Scaling.saturatingMultiply(Long.MAX_VALUE, 2L));
        assertEquals(0L, Scaling.saturatingMultiply(0L, 1_000_000L));
        assertEquals(12L, Scaling.saturatingMultiply(3L, 4L));
    }

    // ---------- DDL builders ----------

    @Test
    void uniquenessConstraintDdlUsesRequireIsUnique() {
        var c = new ConstraintInfo("c1", "UNIQUENESS", "node", List.of("Person"), List.of("id"));
        assertEquals("CREATE CONSTRAINT `c1` IF NOT EXISTS FOR (n:`Person`) REQUIRE n.`id` IS UNIQUE",
                ConstraintDdl.build(c));
    }

    @Test
    void compositeNodeKeyConstraintRendersTuple() {
        var c = new ConstraintInfo("k1", "NODE_KEY", "node",
                List.of("Person"), List.of("firstName", "lastName"));
        assertEquals("CREATE CONSTRAINT `k1` IF NOT EXISTS FOR (n:`Person`) "
                        + "REQUIRE (n.`firstName`, n.`lastName`) IS NODE KEY",
                ConstraintDdl.build(c));
    }

    @Test
    void rangeIndexDdlOnNodeProperty() {
        var idx = new IndexInfo("i1", "RANGE", "node", List.of("Person"), List.of("email"),
                "ONLINE", 0L, "");
        assertEquals("CREATE RANGE INDEX `i1` IF NOT EXISTS FOR (n:`Person`) ON (n.`email`)",
                IndexDdl.build(idx));
    }

    @Test
    void lookupIndexSkipped() {
        var idx = new IndexInfo("lookup", "LOOKUP", "node", List.of(), List.of(), "ONLINE", 0L, "");
        assertNull(IndexDdl.build(idx));
    }

    // ---------- fixture helpers ----------

    private static SchemaDocument newDoc() {
        return new SchemaDocument("bolt://localhost:7687", "neo4j", "5.26.0", null);
    }

    private static LabelInfo entity(String name, long count) {
        var l = new LabelInfo(name, count, new ArrayList<>(), new ArrayList<>());
        l.setRole(LabelRole.ENTITY);
        return l;
    }

    private static LabelInfo hierarchy(String name, String parent, long count) {
        var l = new LabelInfo(name, count, new ArrayList<>(), new ArrayList<>());
        l.setRole(LabelRole.HIERARCHY);
        l.setExtendsLabel(parent);
        return l;
    }

    private static LabelInfo tag(String name, List<String> taggedEntities, long count) {
        var l = new LabelInfo(name, count, new ArrayList<>(), new ArrayList<>());
        l.setRole(LabelRole.TAG);
        l.setTaggedEntities(new ArrayList<>(taggedEntities));
        return l;
    }

    private static RelationshipTypeInfo rel(String name, long count, Connection connection) {
        return new RelationshipTypeInfo(name, count, new ArrayList<>(List.of(connection)), new ArrayList<>());
    }

    private static RelationshipTypeInfo parameterisedRel(String base, long count, Connection connection,
                                                         List<String> instances) {
        var typeParameters = List.of(new TypeParameter(0, List.of(instances.get(0))));
        return new RelationshipTypeInfo(base, count, new ArrayList<>(List.of(connection)),
                new ArrayList<>(), typeParameters, instances);
    }

    private static long relTarget(SeedPlan plan, String relType) {
        return plan.rels().stream()
                .filter(p -> p.relType().equals(relType))
                .findFirst().orElseThrow().target();
    }

    private static SeedPlan.RelPlan planRelByType(SeedPlan plan, String relType) {
        return plan.rels().stream()
                .filter(p -> p.relType().equals(relType))
                .findFirst().orElse(null);
    }

    private static SeedPlan.NodeCreate bucketForLabels(SeedPlan plan, List<String> labels) {
        return plan.nodeCreates().stream()
                .filter(b -> b.labels().equals(labels))
                .findFirst().orElse(null);
    }
}
