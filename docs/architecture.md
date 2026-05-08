# SkemaDok — Architecture

## Contents

1. [Module Structure](#1-module-structure)
2. [Two Executables](#2-two-executables)
3. [The Schema Document](#3-the-schema-document)
4. [Collector Design](#4-collector-design)
5. [Merger Design](#5-merger-design)
6. [Spring Boot Application](#6-spring-boot-application)
7. [REST API](#7-rest-api)
8. [Frontend Integration](#8-frontend-integration)
9. [Build Pipeline](#9-build-pipeline)

---

## 1. Module Structure

This is a Maven multi-module project. Dependencies flow in one direction: `collector` and `app` both depend on `core`; they share no code with each other.

```
skemadok/
├── core/       Domain model, SchemaCollector, SchemaMerger, CollectCommand, MergeCommand
├── collector/  Thin fat JAR (~10 MB): plain main(), no Spring, no web server
└── app/        Full fat JAR (~48 MB): Spring Boot, Tomcat, Vue SPA, document generators
```

`core` has no Spring dependencies. All domain logic lives there so it can be used from both executables without pulling in the framework.

---

## 2. Two Executables

### Collector JAR

`CollectorApplication.main()` instantiates `CollectCommand` and `MergeCommand` directly with `new` and hands them to picocli:

```
CollectorApplication.main()
  → new CommandLine(new CollectorCommand(), factory).execute(args)
```

No Spring context is created. Process exits after the command completes.

### Full App JAR

`SkemaDokApplication.main()` inspects `args` before Spring initialises:

```
SkemaDokApplication.main()
  ├── "ui" in args  → WebApplicationType.SERVLET  (Tomcat starts on :8282)
  └── anything else → WebApplicationType.NONE      (no web server; process exits after command)
```

`SkemaDokApplication.commandLineRunner` builds a picocli `IFactory` that resolves `@Command`-annotated classes as Spring beans (so they can be `@Autowired`) and falls back to picocli's default factory for internal types. The factory must be an anonymous class — `IFactory.create()` is generic and cannot be a lambda.

`AppConfig` registers `CollectCommand` and `MergeCommand` as explicit `@Bean`s. They are implemented once in `core` and carry no Spring annotations; `AppConfig` is the only place they are bound to the container.

---

## 3. The Schema Document

`SchemaDocument` (serialised to `schema.json`) is the handoff artefact between all four subcommands and between the customer and the user.

Fields are either **structural** (overwritten on every `collect` or `merge`) or **annotation** (user-added and preserved across merges). The merge identity key per entity type is `name`.

```json
{
  "capturedAt": "2026-04-24T10:00:00Z",
  "databaseAddress": "bolt://localhost:7687",
  "databaseName": "neo4j",
  "databaseVersion": "5.26.0",

  "nodeLabels": [
    {
      "name": "Person",           // structural — merge key
      "nodeCount": 42000,         // structural
      "role": "ENTITY",           // structural: ENTITY | TAG | HIERARCHY
      "properties": [
        {
          "name": "id",           // structural — merge key
          "types": ["Long"],      // structural
          "nullable": false,      // structural
          "description": ""       // annotation
        }
      ],
      "description": "",          // annotation
      "removed": true             // annotation — only present when true
    }
  ],

  "relationshipTypes": [
    {
      "name": "WORKS_IN",         // structural — merge key
      "count": 5000,              // structural
      "startLabels": ["Person"],  // structural
      "endLabels": ["Department"],// structural
      "properties": [],           // structural + annotation descriptions
      "description": "",          // annotation
      "removed": true             // annotation — only present when true
    }
  ],

  "indexes": [ ... ],             // structural — always replaced by collect/merge

  "constraints": [ ... ],         // structural — always replaced by collect/merge

  "views": [                      // annotation — entirely user-owned; never touched by collect/merge
    {
      "name": "HR Domain",
      "description": "",
      "labels": ["Person", "Department"],
      "relationshipTypes": ["WORKS_IN"],
      "layout": { }               // opaque blob from the Vue frontend
    }
  ]
}
```

A label or relationship type may appear in multiple views. The `removed` flag is the only way a merge marks deletion — entities are never removed from the file so annotations survive temporary disappearances.

---

## 4. Collector Design

`SchemaCollector` uses only native Cypher — no APOC, no Spring Data Neo4j:

| Data | Procedure / statement |
|---|---|
| Label properties | `CALL db.schema.nodeTypeProperties()` |
| Relationship type properties | `CALL db.schema.relTypeProperties()` |
| Label counts + multi-label co-occurrence | single `MATCH (n) UNWIND labels(n)` query |
| Connectivity (start/end labels per rel type) | `CALL db.schema.visualization()` |
| Indexes | `SHOW INDEXES` |
| Constraints | `SHOW CONSTRAINTS` |

`SHOW INDEXES` and `SHOW CONSTRAINTS` require elevated privileges. The collector catches errors and continues with a partial schema rather than aborting.

### Label role detection

A label that never appears in a single-label node combination is automatically classified as `TAG`. The heuristic uses `labelCount` from `db.schema.nodeTypeProperties()` — if no row for a label has `labelCount == 1`, the label always co-occurs with at least one other and is therefore a classification tag. Labels that do appear alone remain `ENTITY`.

---

## 5. Merger Design

`SchemaMerger` receives an existing (annotated) `SchemaDocument` and a fresh snapshot. For each entity type it:

1. Builds a map of existing entities keyed by `name`.
2. Iterates the snapshot entities and either updates the existing record (structural fields only) or creates a new one.
3. Any existing entity not present in the snapshot is flagged `removed: true`. Its annotations and view positions are preserved.
4. Replaces `indexes` and `constraints` entirely (these have no user annotations).
5. Leaves `views` and all annotation fields untouched.

---

## 6. Spring Boot Application

`UiCommand` (a picocli `@Command`) is the entry point for `ui` mode. It:

1. Registers the schema file path in `SchemaService` before the HTTP server starts handling requests.
2. Opens the default browser to `http://localhost:8282`.
3. Keeps the process alive until interrupted.

`SchemaService` holds only the file path. It has no in-memory document state. Every `GET /api/schema` reads from disk; every `PUT /api/schema` writes to disk. This means the file on disk is always the authoritative copy — the frontend holds the working copy in its Pinia store and flushes it on Save.

---

## 7. REST API

`SchemaController` exposes two endpoints under `/api`:

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/schema` | Read full document from disk; returns JSON |
| `PUT` | `/api/schema` | Write full document to disk; sets `lastEditedAt`; returns 204 |

Requests arriving before `UiCommand` has registered the schema file path return `503 Service Unavailable`.

The frontend (`api/schemaApi.js`) is a thin wrapper around `fetch`. Non-2xx responses throw an `Error`; a 204 response returns `null`.

---

## 8. Frontend Integration

The Vue SPA is served as static files from `app/src/main/resources/static/`. The Maven build uses `frontend-maven-plugin` to install Node 20 and run `npm run build` during `generate-resources`; output lands in `static/` and is bundled into the fat JAR automatically.

In development, the Vite dev server on `:5173` proxies `/api` requests to the Spring server on `:8282`. In production, Spring serves both the SPA and the API from the same port.

See [ui-architecture.md](ui-architecture.md) for a detailed description of the frontend.

---

## 9. Build Pipeline

```
mvn package -DskipTests
│
├── core/
│   └── compiler:compile → core classes + domain model
│
├── collector/
│   └── compiler:compile + maven-shade-plugin
│       → skemadok-collector-{version}.jar (~10 MB)
│
└── app/
    ├── frontend-maven-plugin
    │   ├── install Node 20 + npm
    │   └── npm run build → app/src/main/resources/static/
    ├── compiler:compile → Spring Boot + CLI + generators
    └── spring-boot:repackage
        → skemadok-{version}.jar (~48 MB)
```

The fat JARs are self-contained. Java 21 is the minimum for customer distribution, even when the build machine runs a newer JDK.
