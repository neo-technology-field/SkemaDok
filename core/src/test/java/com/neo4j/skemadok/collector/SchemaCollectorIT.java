package com.neo4j.skemadok.collector;

import com.neo4j.skemadok.model.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for SchemaCollector against a real Neo4j instance.
 *
 * <h2>Schema patterns covered</h2>
 * <ul>
 *   <li><b>Deduplication</b> — property lists and startLabel/endLabel lists must not repeat.
 *   <li><b>Nullable inference</b> — {@code nullable} is derived from single-label rows only;
 *       properties seen exclusively in multi-label combinations are conservatively nullable.
 *   <li><b>Boolean / tag labels</b> — labels like {@code :Featured}, {@code :Draft} that are
 *       always applied alongside a base entity label and carry no independent properties.
 *   <li><b>RBAC role labels</b> — labels like {@code :Admin}, {@code :Editor}, {@code :Viewer}
 *       applied to account nodes; no independent properties, always co-occur.
 *   <li><b>Auto-TAG detection</b> — labels that never appear in a single-label node combination
 *       are automatically classified as {@link LabelRole#TAG}.
 *   <li><b>Hierarchy labels</b> — {@code :Category} forming a self-referential tree; remain
 *       unclassified (ENTITY) because hierarchy detection is deferred.
 * </ul>
 *
 * <h2>Alphabetical ordering constraint</h2>
 * {@code db.schema.nodeTypeProperties()} attributes a combined node's properties to
 * {@code nodeLabels[0]}, the alphabetically first label in the combination. For role-detection
 * and nullable tests to work correctly, the base entity label must come before the tag labels
 * alphabetically so the entity gets the properties and the tags appear property-free.
 * <ul>
 *   <li>{@code Article} (A) &lt; {@code Draft} (D) &lt; {@code Featured} (F) &lt; {@code Published} (P)
 *   <li>{@code Account} (Ac) &lt; {@code Admin} (Ad) &lt; {@code Editor} (E) &lt; {@code Viewer} (V)
 *   <li>{@code Widget} (W) &lt; {@code XSpecial} (X) — Widget receives all properties in the combo
 * </ul>
 *
 * <h2>Article and Account each have one pure single-label instance</h2>
 * All tag labels (Draft/Featured/Published/Admin/Editor/Viewer/XSpecial) are created directly
 * with multi-label CREATE statements so they never appear alone. The entity labels (Article,
 * Account) always have at least one pure single-label node so they are NOT auto-tagged.
 */
@Testcontainers
class SchemaCollectorIT {

    private static final String ADMIN_PASSWORD = "test-password";

    @Container
    static final Neo4jContainer<?> neo4j = new Neo4jContainer<>("neo4j:latest")
            .withAdminPassword(ADMIN_PASSWORD);

    @BeforeAll
    static void loadTestData() {
        try (Driver driver = GraphDatabase.driver(neo4j.getBoltUrl(),
                AuthTokens.basic("neo4j", ADMIN_PASSWORD));
             Session session = driver.session()) {

            // ---- Core entities -------------------------------------------------
            // Pure single-label Person nodes to keep the schema clean for connectivity tests.
            session.run("CREATE (:Person {name: 'Alice', age: 30})");
            session.run("CREATE (:Person {name: 'Bob',   age: 45})");
            session.run("CREATE (:Person {name: 'Carol', age: 50})");
            session.run("CREATE (:Organization {name: 'Neo4j'})");
            session.run("CREATE (:Company      {name: 'Acme', revenue: 5000000})");

            // Multi-label Person nodes — E(mployee) < P(erson) alphabetically, so Employee
            // is nodeLabels[0] and gets the properties, keeping Person's property count stable.
            session.run("CREATE (:Person:Employee {name: 'Dave', age: 28})");
            session.run("CREATE (:Person:Manager  {name: 'Eve',  age: 42})");

            // ---- Connectivity: two endpoint patterns for WORKS_FOR ---------------
            // Alice → Organization and Bob → Company create two distinct schema patterns for
            // WORKS_FOR. Without deduplication, Person would appear twice in startLabels.
            session.run("MATCH (p:Person {name:'Alice'}), (o:Organization) CREATE (p)-[:WORKS_FOR]->(o)");
            session.run("MATCH (p:Person {name:'Bob'}),  (c:Company)      CREATE (p)-[:WORKS_FOR]->(c)");

            // ---- Boolean / TAG labels ------------------------------------------
            // Tags are created directly with multi-label CREATE so they never appear alone.
            // One pure Article node (Template) ensures Article itself is NOT auto-tagged.
            session.run("CREATE (:Article:Featured  {title: 'Advanced Neo4j',   views: 250})");
            session.run("CREATE (:Article:Draft      {title: 'Work in progress', views:   0})");
            session.run("CREATE (:Article:Published  {title: 'Intro to Graphs',  views: 100})");
            session.run("CREATE (:Article            {title: 'Template',         views:  50})");

            // ---- RBAC role labels ----------------------------------------------
            // Account (Ac) < Admin (Ad) < Editor (E) < Viewer (V) alphabetically.
            // Properties (accountId, email) are always attributed to Account → role labels
            // appear property-free. One pure Account node (A4) keeps Account from being auto-tagged.
            session.run("CREATE (:Account:Admin  {accountId: 'A1', email: 'admin@neo4j.com'})");
            session.run("CREATE (:Account:Editor {accountId: 'A2', email: 'ed@neo4j.com'})");
            session.run("CREATE (:Account:Viewer {accountId: 'A3', email: 'guest@neo4j.com'})");
            session.run("CREATE (:Account        {accountId: 'A4', email: 'svc@neo4j.com'})");

            // ---- Nullable / deduplication scenario ----------------------------
            // Widget (W) < XSpecial (X) alphabetically, so Widget is nodeLabels[0] in the combo
            // and receives all properties. 'discount' is absent on one XSpecial node, making it
            // nullable — it has no single-label row, only a multi-label row with mandatory=false.
            // 'name' and 'price' appear in both single-label and multi-label rows, always mandatory.
            session.run("CREATE (:Widget           {name: 'Basic',   price:  9.99})");
            session.run("CREATE (:Widget:XSpecial  {name: 'Premium', price: 19.99, discount: 0.10})");
            session.run("CREATE (:Widget:XSpecial  {name: 'Deluxe',  price: 29.99})");

            // ---- Hierarchy: Category tree --------------------------------------
            // Single-label nodes with multiple properties and SUBCATEGORY_OF relationships.
            // The collector does not yet detect hierarchy — these remain unclassified (ENTITY) in v1.
            session.run("CREATE (:Category {name: 'Electronics', level: 1})");
            session.run("CREATE (:Category {name: 'Phones',      level: 2, parent: 'Electronics'})");
            session.run("CREATE (:Category {name: 'Laptops',     level: 2, parent: 'Electronics'})");
            session.run("""
                    MATCH (p:Category {level:1}), (c:Category)
                    WHERE c.parent = p.name
                    CREATE (c)-[:SUBCATEGORY_OF]->(p)
                    """);

            // ---- Property-free label: appears alone with no properties --------
            // CREATE (:Pending) yields nodeLabels=['Pending'], propertyName=null in
            // db.schema.nodeTypeProperties() — the case the collector must not drop.
            session.run("CREATE (:Pending)");

            // ---- Constraint (causes extra rows in nodeTypeProperties) -----------
            session.run("CREATE CONSTRAINT person_name_unique FOR (p:Person) REQUIRE p.name IS UNIQUE");
        }
    }

    // =========================================================================
    // Deduplication
    // =========================================================================

    @Nested
    class Deduplication {

        @Test
        void labelPropertiesContainNoDuplicates() {
            var doc = collect();
            for (var label : doc.getNodeLabels()) {
                var names = label.getProperties().stream().map(PropertyInfo::name).toList();
                assertEquals(new HashSet<>(names).size(), names.size(),
                        "Label '%s' has duplicate properties: %s".formatted(label.getName(), names));
            }
        }

        @Test
        void personNameAppearsExactlyOnce() {
            // Pure :Person nodes exist alongside :Person:Employee and :Person:Manager.
            // Without deduplication, 'name' would appear multiple times for Person.
            var person = findLabel(collect(), "Person");
            var count = person.getProperties().stream().filter(p -> "name".equals(p.name())).count();
            assertEquals(1, count, "Property 'name' should appear exactly once for Person");
        }

        @Test
        void connectionLabelListsContainNoDuplicatesWithinEachPair() {
            for (var rel : collect().getRelationshipTypes()) {
                for (var conn : rel.getConnections()) {
                    var starts = conn.startLabels();
                    assertEquals(new HashSet<>(starts).size(), starts.size(),
                            "Rel '%s' has duplicate startLabels in connection: %s".formatted(rel.getName(), starts));
                    var ends = conn.endLabels();
                    assertEquals(new HashSet<>(ends).size(), ends.size(),
                            "Rel '%s' has duplicate endLabels in connection: %s".formatted(rel.getName(), ends));
                }
            }
        }

        @Test
        void connectionLabelListsAreSortedAlphabetically() {
            for (var rel : collect().getRelationshipTypes()) {
                for (var conn : rel.getConnections()) {
                    assertSorted(conn.startLabels(),
                            "startLabels in connection for '%s' not sorted".formatted(rel.getName()));
                    assertSorted(conn.endLabels(),
                            "endLabels in connection for '%s' not sorted".formatted(rel.getName()));
                }
            }
        }

        @Test
        void worksForConnectsPersonToBothOrganizationAndCompany() {
            var worksFor = findRel(collect(), "WORKS_FOR");
            var connections = worksFor.getConnections();
            assertTrue(connections.stream().anyMatch(conn ->
                    conn.startLabels().equals(List.of("Person")) &&
                    conn.endLabels().equals(List.of("Company")) &&
                    conn.count() > 0));
            assertTrue(connections.stream().anyMatch(conn ->
                    conn.startLabels().equals(List.of("Person")) &&
                    conn.endLabels().equals(List.of("Organization")) &&
                    conn.count() > 0));
        }
    }

    // =========================================================================
    // Nullable properties
    // =========================================================================

    @Nested
    class NullableProperties {

        @Test
        void propertyMandatoryInSingleLabelContextIsNotNullable() {
            // Widget appears alone (pure :Widget node) with name and price always present.
            // The single-label row says mandatory=true → nullable=false.
            var widget = findLabel(collect(), "Widget");
            assertFalse(findProperty(widget, "name").nullable(),
                    "Widget.name is on all Widget nodes (single-label mandatory=true) — must not be nullable");
            assertFalse(findProperty(widget, "price").nullable(),
                    "Widget.price is on all Widget nodes — must not be nullable");
        }

        @Test
        void propertyOnlyInMultiLabelContextIsNullable() {
            // Widget.discount appears only in the [Widget,XSpecial] combination and is absent
            // on one of those nodes → mandatory=false. No single-label row exists for discount,
            // so the collector cannot confirm it is required → must be nullable.
            var widget = findLabel(collect(), "Widget");
            assertTrue(findProperty(widget, "discount").nullable(),
                    "Widget.discount is absent on some Widget+XSpecial nodes — must be nullable");
        }

        @Test
        void personNameIsNotNullable() {
            // Pure :Person nodes always have 'name' → single-label mandatory=true.
            var person = findLabel(collect(), "Person");
            assertFalse(findProperty(person, "name").nullable(),
                    "Person.name is on all single-label Person nodes — must not be nullable");
        }
    }

    // =========================================================================
    // Auto-TAG role detection
    // =========================================================================

    @Nested
    class RoleDetection {

        @Test
        void labelsNeverAppearsAloneAreAutoTagged() {
            var doc = collect();
            for (var name : List.of("Draft", "Featured", "Published",
                                    "Admin", "Editor", "Viewer", "XSpecial")) {
                assertEquals(LabelRole.TAG, findLabel(doc, name).getRole(),
                        "'%s' never appears alone — should be auto-detected as TAG".formatted(name));
            }
        }

        @Test
        void labelsAppearsAloneAreNotAutoTagged() {
            var doc = collect();
            for (var name : List.of("Article", "Account", "Person", "Widget", "Category")) {
                assertNotEquals(LabelRole.TAG, findLabel(doc, name).getRole(),
                        "'%s' appears alone — must NOT be auto-detected as TAG".formatted(name));
            }
        }

        @Test
        void propertyFreeLabelAppearsAloneIsNotAutoTagged() {
            // :Pending appears alone with no properties — must not be classified as TAG.
            assertNotEquals(LabelRole.TAG, findLabel(collect(), "Pending").getRole());
        }
    }

    // =========================================================================
    // Infrastructure
    // =========================================================================

    @Nested
    class Infrastructure {

        @Test
        void indexesAreCollectedWithoutError() {
            var doc = collect();
            assertNotNull(doc.getIndexes());
            // LOOKUP indexes have NULL labelsOrTypes/properties — verify no NPE
            assertFalse(doc.getIndexes().isEmpty(),
                    "At least the backing index for the uniqueness constraint should be present");
        }

        @Test
        void databaseVersionIsPopulated() {
            var version = collect().getDatabaseVersion();
            assertNotNull(version);
            assertNotEquals("unknown", version, "Database version should resolve from dbms.components(); got 'unknown'");
        }
    }

    // =========================================================================
    // Property-free labels
    // =========================================================================

    @Nested
    class PropertyFreeLabels {

        @Test
        void propertyFreeLabelIsCollected() {
            // :Pending nodes have no properties, so db.schema.nodeTypeProperties() yields a
            // null-propertyName row. The collector must not drop property-free labels.
            var label = findLabel(collect(), "Pending");
            assertTrue(label.getProperties().isEmpty(),
                    "Property-free label 'Pending' should be collected with an empty property list");
        }
    }

    // =========================================================================

    private SchemaDocument collect() {
        // Threshold=2 keeps the test data unaffected while still exercising the grouping path.
        return new SchemaCollector().collect(neo4j.getBoltUrl(), "neo4j", ADMIN_PASSWORD, "neo4j", 2);
    }

    private LabelInfo findLabel(SchemaDocument doc, String name) {
        return doc.getNodeLabels().stream()
                .filter(l -> name.equals(l.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Label '%s' not found".formatted(name)));
    }

    private PropertyInfo findProperty(LabelInfo label, String name) {
        return label.getProperties().stream()
                .filter(p -> name.equals(p.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Property '%s' not found on label '%s'".formatted(name, label.getName())));
    }

    private RelationshipTypeInfo findRel(SchemaDocument doc, String name) {
        return doc.getRelationshipTypes().stream()
                .filter(r -> name.equals(r.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Relationship type '%s' not found".formatted(name)));
    }

    private void assertSorted(java.util.List<String> list, String message) {
        var copy = new ArrayList<>(list);
        Collections.sort(copy);
        assertEquals(copy, list, message);
    }
}
