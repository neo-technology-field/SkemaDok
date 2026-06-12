package com.neo4j.skemadok.seeder;

import com.neo4j.skemadok.model.Connection;
import com.neo4j.skemadok.model.ConstraintInfo;
import com.neo4j.skemadok.model.LabelInfo;
import com.neo4j.skemadok.model.LabelRole;
import com.neo4j.skemadok.model.PropertyInfo;
import com.neo4j.skemadok.model.RelationshipTypeInfo;
import com.neo4j.skemadok.model.SchemaDocument;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end seeding against a real Neo4j instance.
 *
 * <p>Each test recreates a fixture {@link SchemaDocument}, runs the seeder against a
 * TestContainers Neo4j, and asserts that node and relationship counts match the planned
 * targets. Each test calls {@code --drop} first so the container can be shared.
 */
@Testcontainers
class SchemaSeederIT {

    private static final String ADMIN_PASSWORD = "test-password";
    private static final String DATABASE = "neo4j";

    @Container
    static final Neo4jContainer<?> neo4j = new Neo4jContainer<>("neo4j:latest")
            .withAdminPassword(ADMIN_PASSWORD);

    @Test
    void seedsLabelsRelationshipsAndConstraintsAtScaleOne() {
        var doc = simpleSchema();

        var plan = runSeed(doc, defaults().withDrop(true).withScale(1d));

        try (var driver = openDriver(); var session = driver.session()) {
            assertThat(countNodes(session, "Person")).isEqualTo(plan.totalForLabel("Person"));
            assertThat(countNodes(session, "Company")).isEqualTo(plan.totalForLabel("Company"));
            assertThat(countRels(session, "WORKS_AT")).isEqualTo(planRel(plan, "WORKS_AT"));

            // id values are populated via the unique-by-index generator.
            var sampleId = session.run("MATCH (p:Person) RETURN p.id AS id LIMIT 1")
                    .single().get("id").asString();
            assertThat(sampleId).isNotEmpty();

            // Both uniqueness constraints (id, email) from the document were replayed.
            assertThat(constraintNames(session)).contains("person_id_unique", "person_email_unique");

            // Non-id unique property has no duplicates after seeding.
            var emailDuplicates = session.run(
                    "MATCH (p:Person) WITH p.email AS email, count(*) AS c "
                            + "WHERE c > 1 RETURN count(*) AS dups")
                    .single().get("dups").asLong();
            assertThat(emailDuplicates).isZero();

            // __seedId is cleaned up after seeding.
            assertThat(nodesWithSeedId(session)).isZero();

            // The synthetic seed-id index is dropped after seeding.
            assertThat(indexNames(session)).doesNotContain("skemadok_seed_id_Person");
        }
    }

    @Test
    void downScaleProducesProportionallyFewerNodesAndRels() {
        var doc = simpleSchema();

        var plan = runSeed(doc, defaults().withDrop(true).withScale(0.1d));

        try (var driver = openDriver(); var session = driver.session()) {
            assertThat(countNodes(session, "Person")).isEqualTo(plan.totalForLabel("Person"));
            assertThat(countNodes(session, "Company")).isEqualTo(plan.totalForLabel("Company"));
            assertThat(countRels(session, "WORKS_AT")).isEqualTo(planRel(plan, "WORKS_AT"));
        }
    }

    @Test
    void dryRunDoesNotWriteAnything() {
        var doc = simpleSchema();

        try (var driver = openDriver(); var session = driver.session()) {
            session.run("MATCH (n) DETACH DELETE n").consume();
        }
        var seeder = new SchemaSeeder();
        var plan = seeder.plan(doc, defaults().withDryRun(true).withScale(1d));
        assertThat(plan.nodeCreates()).isNotEmpty();

        try (var driver = openDriver(); var session = driver.session()) {
            assertThat(countAllNodes(session)).isZero();
        }
    }

    @Test
    void hierarchyChildrenCarryEveryAncestorLabelInTheChain() {
        var doc = hierarchySchema();

        runSeed(doc, defaults().withDrop(true).withScale(1d));

        try (var driver = openDriver(); var session = driver.session()) {
            // Total Person count = 100.
            assertThat(countNodes(session, "Person")).isEqualTo(100L);
            // Employee count (subset of Person) = 40.
            assertThat(countNodes(session, "Employee")).isEqualTo(40L);
            // Manager count (subset of Employee) = 10.
            assertThat(countNodes(session, "Manager")).isEqualTo(10L);

            // Every Employee is also a Person.
            var employeesNotPersons = session.run(
                    "MATCH (n:Employee) WHERE NOT n:Person RETURN count(n) AS c")
                    .single().get("c").asLong();
            assertThat(employeesNotPersons).isZero();

            // Every Manager is also an Employee and a Person.
            var managersNotEmployees = session.run(
                    "MATCH (n:Manager) WHERE NOT n:Employee OR NOT n:Person RETURN count(n) AS c")
                    .single().get("c").asLong();
            assertThat(managersNotEmployees).isZero();

            // Sub-label uniqueness constraint enforced (employeeNumber unique across Employees).
            assertThat(constraintNames(session)).contains("employee_number_unique");
            var duplicateEmployeeNumbers = session.run(
                    "MATCH (e:Employee) WITH e.employeeNumber AS n, count(*) AS c "
                            + "WHERE c > 1 RETURN count(*) AS dups")
                    .single().get("dups").asLong();
            assertThat(duplicateEmployeeNumbers).isZero();

            // __seedId removed from every label including Employee and Manager.
            assertThat(nodesWithSeedId(session)).isZero();
        }
    }

    @Test
    void tagsAreAppliedOnTopOfExistingEntities() {
        var doc = tagSchema();

        runSeed(doc, defaults().withDrop(true).withScale(1d));

        try (var driver = openDriver(); var session = driver.session()) {
            assertThat(countNodes(session, "Person")).isEqualTo(50L);
            assertThat(countNodes(session, "VIP")).isEqualTo(10L);

            // Every VIP is also a Person.
            var vipsNotPersons = session.run(
                    "MATCH (n:VIP) WHERE NOT n:Person RETURN count(n) AS c")
                    .single().get("c").asLong();
            assertThat(vipsNotPersons).isZero();

            assertThat(nodesWithSeedId(session)).isZero();
        }
    }

    // ---------- helpers ----------

    private static SeedPlan runSeed(SchemaDocument doc, SeedOptions options) {
        var seeder = new SchemaSeeder();
        try (var driver = openDriver()) {
            return seeder.seed(driver, doc, options);
        }
    }

    private static SeedOptions defaults() {
        return SeedOptions.defaults().withDatabase(DATABASE);
    }

    private static SchemaDocument simpleSchema() {
        var doc = new SchemaDocument(neo4j.getBoltUrl(), DATABASE, "5.x", null);
        var personProps = new ArrayList<>(List.of(
                new PropertyInfo("id", List.of("String"), false),
                new PropertyInfo("email", List.of("String"), false),
                new PropertyInfo("name", List.of("String"), false)
        ));
        var companyProps = new ArrayList<>(List.of(
                new PropertyInfo("id", List.of("String"), false)
        ));
        var person = new LabelInfo("Person", 100, new ArrayList<>(), personProps);
        person.setRole(LabelRole.ENTITY);
        var company = new LabelInfo("Company", 20, new ArrayList<>(), companyProps);
        company.setRole(LabelRole.ENTITY);
        doc.getNodeLabels().add(person);
        doc.getNodeLabels().add(company);

        doc.getRelationshipTypes().add(new RelationshipTypeInfo(
                "WORKS_AT", 50,
                new ArrayList<>(List.of(new Connection(List.of("Person"), List.of("Company"), 50))),
                new ArrayList<>()
        ));

        doc.getConstraints().add(new ConstraintInfo(
                "person_id_unique", "UNIQUENESS", "node", List.of("Person"), List.of("id")));
        // Non-id uniqueness — this is the critical case the user flagged: it must actually be
        // created (not silently swallowed) and seeded values must satisfy it.
        doc.getConstraints().add(new ConstraintInfo(
                "person_email_unique", "UNIQUENESS", "node", List.of("Person"), List.of("email")));
        return doc;
    }

    private static SchemaDocument hierarchySchema() {
        var doc = new SchemaDocument(neo4j.getBoltUrl(), DATABASE, "5.x", null);

        var personProps = new ArrayList<>(List.of(
                new PropertyInfo("id", List.of("String"), false)
        ));
        var person = new LabelInfo("Person", 100, new ArrayList<>(), personProps);
        person.setRole(LabelRole.ENTITY);

        var employeeProps = new ArrayList<>(List.of(
                new PropertyInfo("employeeNumber", List.of("Long"), false)
        ));
        var employee = new LabelInfo("Employee", 40, new ArrayList<>(), employeeProps);
        employee.setRole(LabelRole.HIERARCHY);
        employee.setExtendsLabel("Person");

        var managerProps = new ArrayList<>(List.of(
                new PropertyInfo("managerCode", List.of("String"), false)
        ));
        var manager = new LabelInfo("Manager", 10, new ArrayList<>(), managerProps);
        manager.setRole(LabelRole.HIERARCHY);
        manager.setExtendsLabel("Employee");

        doc.getNodeLabels().add(person);
        doc.getNodeLabels().add(employee);
        doc.getNodeLabels().add(manager);

        doc.getConstraints().add(new ConstraintInfo(
                "person_id_unique", "UNIQUENESS", "node", List.of("Person"), List.of("id")));
        doc.getConstraints().add(new ConstraintInfo(
                "employee_number_unique", "UNIQUENESS", "node",
                List.of("Employee"), List.of("employeeNumber")));
        return doc;
    }

    private static SchemaDocument tagSchema() {
        var doc = new SchemaDocument(neo4j.getBoltUrl(), DATABASE, "5.x", null);

        var personProps = new ArrayList<>(List.of(
                new PropertyInfo("id", List.of("String"), false)
        ));
        var person = new LabelInfo("Person", 50, new ArrayList<>(), personProps);
        person.setRole(LabelRole.ENTITY);

        var vip = new LabelInfo("VIP", 10, new ArrayList<>(), new ArrayList<>());
        vip.setRole(LabelRole.TAG);
        vip.setTaggedEntities(new ArrayList<>(List.of("Person")));

        doc.getNodeLabels().add(person);
        doc.getNodeLabels().add(vip);

        doc.getConstraints().add(new ConstraintInfo(
                "person_id_unique", "UNIQUENESS", "node", List.of("Person"), List.of("id")));
        return doc;
    }

    private static Driver openDriver() {
        return GraphDatabase.driver(neo4j.getBoltUrl(), AuthTokens.basic("neo4j", ADMIN_PASSWORD));
    }

    private static long countNodes(Session session, String label) {
        return session.run("MATCH (n:`" + label + "`) RETURN count(n) AS c")
                .single().get("c").asLong();
    }

    private static long countAllNodes(Session session) {
        return session.run("MATCH (n) RETURN count(n) AS c").single().get("c").asLong();
    }

    private static long countRels(Session session, String relType) {
        return session.run("MATCH ()-[r:`" + relType + "`]->() RETURN count(r) AS c")
                .single().get("c").asLong();
    }

    private static long nodesWithSeedId(Session session) {
        return session.run("MATCH (n) WHERE n.__seedId IS NOT NULL RETURN count(n) AS c")
                .single().get("c").asLong();
    }

    private static List<String> constraintNames(Session session) {
        return session.run("SHOW CONSTRAINTS YIELD name")
                .list(r -> r.get("name").asString());
    }

    private static List<String> indexNames(Session session) {
        return session.run("SHOW INDEXES YIELD name")
                .list(r -> r.get("name").asString());
    }

    private static long planRel(SeedPlan plan, String relType) {
        return plan.rels().stream()
                .filter(r -> r.relType().equals(relType))
                .findFirst().orElseThrow().target();
    }
}
