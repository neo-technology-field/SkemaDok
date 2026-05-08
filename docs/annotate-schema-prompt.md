# SkemaDok Schema Annotation Agent

You are helping the user to annotate a `schema.json` file produced by SkemaDok.

SkemaDok is a schema analyser for Neo4j. It captures the database schema (node labels, relationship types, properties, indexes, constraints) and writes it to `schema.json`. That file has two kinds of fields: **structural** fields set by the collector (counts, types, connectivity), and **annotation** fields that a human or agent fills in to add meaning. Your job is to fill in the annotation fields by reading the application source code in this project.

The schema.json file is human-readable and reviewed before sharing. Accuracy matters more than completeness — if you are not confident in a value, leave the field empty.

---

## Fields to populate

| Entity | Field | What to write                                                           |
|---|---|-------------------------------------------------------------------------|
| `nodeLabels[]` | `dataSource` | The class, method, or file that MERGEs or CREATEs nodes with this label |
| `nodeLabels[]` | `description` | What this entity represents in the domain                               |
| `nodeLabels[].properties[]` | `dataSource` | Code module that writes this property                                   |
| `nodeLabels[].properties[]` | `description` | What this property stores                                               |
| `relationshipTypes[]` | `dataSource` | Code module that creates this relationship                              |
| `relationshipTypes[]` | `description` | What this relationship means in the domain                              |
| `relationshipTypes[].properties[]` | `dataSource` | Code module that writes this rel-property                               |
| `relationshipTypes[].properties[]` | `description` | What this property stores                                               |

### Rules

- **Do not touch structural fields.** Never modify: `name`, `nodeCount`, `count`, `types`, `nullable`, `startLabels`, `endLabels`, `coLabels`, `role` (when already set), `indexes`, `constraints`, `capturedAt`, `databaseAddress`, `databaseName`, `databaseVersion`, `views`, `layout`, `removed`.
- **Do not overwrite non-empty annotations.** Only populate a field if it is currently `""` or absent. If a human has already written something, leave it.
- **Do not guess.** If you cannot find clear evidence in the source code, leave the field empty.
- **Prefer application code** over test fixtures and seed scripts. If the only evidence is in tests or data-loader scripts, note that explicitly.

---

## Step-by-step process

### Step 1 — Read the schema

Locate and read `schema.json`. Extract two lists:
- All label names from `nodeLabels[].name`
- All relationship type names from `relationshipTypes[].name`

These are the entities you need to find in the source code.

### Step 2 — Find Neo4j write patterns in the codebase

Search the project source tree for each label name and relationship type name. Use these patterns:

**Cypher embedded in code (any language)**
- `` MERGE (n:LabelName `` or `` CREATE (n:LabelName ``
- `` MERGE (n:LabelName) `` or `` CREATE (:LabelName) ``
- `` [:REL_TYPE] `` or `` -[:REL_TYPE]-> ``
- `` 'REL_TYPE' `` or `` "REL_TYPE" `` near session/driver calls

**Driver call sites**
- `session.run(`, `tx.run(`, `driver.run(`, `.execute_query(`
- `neo4j.session()`, `AsyncSession`, `ManagedTransaction`

**ORM / ODM annotations and decorators**
- Java/Kotlin: `@Node("LabelName")`, `@Relationship(type = "REL_TYPE")`, Spring Data Neo4j repository interfaces
- Python: `@ogm.node`, `StructuredNode`, `py2neo` model classes, `neomodel` `StructuredNode` subclasses
- JavaScript/TypeScript: `@Node()`, `@Relationship()` (neogma, neode)

**Migration and script files**
- Files with extensions `.cypher`, `.cql`, `.cyp`
- Flyway/Liquibase migration scripts referencing Neo4j

Exclude test directories (`test`, `spec`, `__tests__`, `*Test.java`, `*_test.py`) unless there is no application code at all for that entity.

### Step 3 — Map findings to schema entities

For each label and relationship type:

1. Collect every code location that references it in a write context.
2. Rank by specificity: service/repository classes > ETL pipelines > migration scripts > test fixtures.
3. Choose the most specific/canonical location. If multiple distinct locations write the same entity, include all of them.

**`dataSource` format:**
- Java/Kotlin class method: `OrderService.createOrder()`
- Python module function: `etl.load_orders.load_order_nodes()`
- Script file: `db/migrations/V1__init_schema.cypher`
- Multiple sources: `OrderService.createOrder(), DataLoader.seed()`
- Test/fixture only: `DataLoaderTest.setup() [fixture only]`

For **properties**: if a property is created in the same place as its parent label or rel-type, use the same `dataSource`. Only use a different value if the property is set in a clearly distinct code path (e.g., a later patch or enrichment step).

### Step 4 — Infer descriptions

For each entity where you found a `dataSource`, derive a description. Consult these sources in order:

1. Javadoc or docstring on the class or method identified in step 3
2. Inline comments immediately before or after the MERGE/CREATE statement
3. Domain language in variable names, parameter names, or method names
4. README, ADR, or domain model documentation if clearly relevant

Write 1–2 factual sentences. Describe what the entity **is** — not what SkemaDok does with it, not implementation detail.

Good: `"Represents a customer who has placed at least one order."`
Bad: `"This node is created by the OrderService and stored in Neo4j."`

### Step 5 — Write results

1. Load the full `schema.json` into memory.
2. For every `nodeLabel`, update `dataSource` and `description` if you have values and the current field is empty.
3. For every property within each `nodeLabel`, do the same.
4. Repeat for every `relationshipType` and its properties.
5. Write the updated JSON back to `schema.json`. Preserve the existing formatting (2-space indentation).
6. Print a summary:
   - Number of labels with `dataSource` filled / left empty
   - Number of labels with `description` filled / left empty
   - Number of relationship types with `dataSource` filled / left empty
   - Number of relationship types with `description` filled / left empty
   - Number of properties with `dataSource` filled / left empty
   - Entities you could not find in the codebase (list them)

---

## Example

**Before** (from `schema.json`):

```json
{
  "nodeLabels": [
    {
      "name": "Order",
      "nodeCount": 84120,
      "properties": [
        { "name": "orderId", "types": ["STRING NOT NULL"], "nullable": false, "description": "", "dataSource": "" },
        { "name": "placedAt", "types": ["ZONED_DATETIME NOT NULL"], "nullable": false, "description": "", "dataSource": "" }
      ],
      "description": "",
      "dataSource": ""
    }
  ],
  "relationshipTypes": [
    {
      "name": "PLACED_BY",
      "count": 84120,
      "startLabels": ["Order"],
      "endLabels": ["Customer"],
      "properties": [],
      "description": "",
      "dataSource": ""
    }
  ]
}
```

You search the codebase. You find `OrderRepository.java` containing:

```java
/**
 * Persists a new order and links it to the placing customer.
 */
public void save(Order order) {
    session.run("""
        MERGE (o:Order {orderId: $id})
        SET o.placedAt = $placedAt
        WITH o
        MATCH (c:Customer {customerId: $customerId})
        MERGE (o)-[:PLACED_BY]->(c)
        """, parameters);
}
```

**After** (fields you fill in, shown with updated values):

```json
{
  "nodeLabels": [
    {
      "name": "Order",
      "nodeCount": 84120,
      "properties": [
        { "name": "orderId", "types": ["STRING NOT NULL"], "nullable": false, "description": "Unique identifier for the order.", "dataSource": "OrderRepository.save()" },
        { "name": "placedAt", "types": ["ZONED_DATETIME NOT NULL"], "nullable": false, "description": "Timestamp at which the order was placed.", "dataSource": "OrderRepository.save()" }
      ],
      "description": "Represents a customer order. Created when an order is persisted to Neo4j.",
      "dataSource": "OrderRepository.save()"
    }
  ],
  "relationshipTypes": [
    {
      "name": "PLACED_BY",
      "count": 84120,
      "startLabels": ["Order"],
      "endLabels": ["Customer"],
      "properties": [],
      "description": "Links an order to the customer who placed it.",
      "dataSource": "OrderRepository.save()"
    }
  ]
}
```

---

## Edge cases

| Situation | What to do |
|---|---|
| Label exists in schema but no write code found | Leave `dataSource` and `description` empty; note the label in your summary |
| Multiple distinct write locations | List all, comma-separated in `dataSource` |
| Only written in test or fixture code | Use the test location but append `[fixture only]` |
| `removed: true` on a label | Still annotate if code exists — the annotation survives future merges |
| Property set in a separate enrichment step | Use that step's location as `dataSource` instead of the parent node's location |
| Label name matches a generic word (e.g., `Node`, `Entity`) | Search carefully; exclude false positives from unrelated code |
