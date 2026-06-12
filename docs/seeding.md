# Seeder design

The seeder materialises a Neo4j database from a captured `schema.json`. Given a schema and a
scale factor it produces a `SeedPlan`, then executes it against a target database. This document
explains the key design decisions.

---

## Why `__seedId`?

To create relationships the seeder needs to pick random endpoint nodes — e.g. "pick a random
`Person`" for each end of a `WORKS_AT` relationship. The obvious Cypher approaches do not scale:

```cypher
-- Full cartesian product before LIMIT: O(|Person| × |Company|) work
MATCH (a:Person), (b:Company) WITH a, b ORDER BY rand() LIMIT 50 CREATE ...

-- Full label scan per relationship
MATCH (a:Person) WITH a ORDER BY rand() LIMIT 1 ...
```

At production scale (hundreds of thousands of nodes) both are unusable.

The seeder instead creates a synthetic dense integer property, `__seedId`, on every node during
creation:

```
Person node 0  → __seedId = 0
Person node 1  → __seedId = 1
...
Person node 99 → __seedId = 99
```

Every node also receives a synthetic `__Seed` label backed by a uniqueness constraint on
`__seedId`. This creates one global index that covers every node regardless of its business
labels. Random endpoint lookup is then an O(1) index seek against that single index:

```cypher
MATCH (n:__Seed {__seedId: 42})   -- single index seek, any label
```

The seeder samples random integers in Java (cheap), assembles them into batches, and sends one
`UNWIND` statement to Neo4j per batch — thousands of relationships in a single round trip with
O(1) cost per endpoint lookup.

Both `__Seed` and `__seedId` are removed at the end of the seed run. They are internal
implementation details and leave no trace in the finished database.

---

## The seedId space and label ranges

Each label does not get its own independent `0..N-1` sequence. Instead, the planner allocates
contiguous slices from one global space. This is necessary to handle **HIERARCHY** correctly.

Consider `Employee extends Person` with 1000 persons total, 300 of them also employees:

```
__seedId   0 ────── 699     700 ───── 999
labels     :Person           :Person :Employee
```

The 300 `Person+Employee` nodes are created with both labels and receive `__seedId` values in
`[700, 1000)`. All nodes also carry `__Seed`, so every lookup goes through the same index:

| Sample from range      | Query                              | Node found                                                     |
|------------------------|------------------------------------|----------------------------------------------------------------|
| `[0, 1000)` Person     | `MATCH (n:__Seed {__seedId: 42})`  | plain-Person node                                              |
| `[0, 1000)` Person     | `MATCH (n:__Seed {__seedId: 750})` | Person+Employee node (correct: an employee IS a person)        |
| `[700, 1000)` Employee | `MATCH (n:__Seed {__seedId: 750})` | same Person+Employee node                                      |
| `[700, 1000)` Employee | `MATCH (n:__Seed {__seedId: 42})`  | plain-Person node — never sampled, 42 is outside `[700, 1000)` |

Sampling a random `Employee` means picking an integer in `[700, 1000)`. Sampling a random
`Person` means picking from `[0, 1000)` — which correctly includes employees.

These integer intervals are what `SeedPlan.labelRanges()` stores as `List<long[]>` (a list of
half-open intervals `[low, high)`). Multiple intervals arise when a label's slice is split across
several `NodeCreate` buckets.

**TAG labels** follow the same pattern. A `VIP` tag applied to the first 100 persons occupies
`[0, 100)` in the Person space. After `SET n:VIP` those nodes carry both `:Person` and `:VIP`,
but the `__Seed` index entry is unchanged. Sampling a random `VIP` node means picking
`__seedId` from `[0, 100)` and looking it up via `__Seed`.

---

## Relationship seeding: mix-and-batch matrix

Naively submitting all relationship creation tasks in parallel causes **write-lock deadlocks**.
Two transactions that both need to lock the same node (e.g. a shared TAG node) will deadlock
if they acquire locks in opposite orders.

The seeder uses the mix-and-batch matrix technique to eliminate deadlocks by construction:

1. Split the start-node seedId space into N equal buckets.
2. Split the end-node seedId space into N equal buckets.
3. Build an N×N cell matrix where cell (i, j) creates relationships from start-bucket[i] to
   end-bucket[j].
4. Assign each cell to diagonal stripe `(j - i + N) % N`.
5. Within any stripe, no two cells share a start **or** end bucket, so parallel tasks within
   a stripe cannot contend for write locks on either endpoint.
6. Process stripes sequentially; within each stripe run all cells in parallel.

Each cell runs on its own Java virtual thread with its own `Session`. Within each cell, UNWIND
batches execute as sequential auto-commit transactions — no `IN CONCURRENT TRANSACTIONS`. A
single auto-commit transaction cannot deadlock with itself, and cells in the same stripe cannot
deadlock with each other because their node sets are guaranteed disjoint.

Each UNWIND statement includes `WITH a, b, row ORDER BY id(a), id(b)` before the `CREATE`. With
Neo4j's block format, node and relationship data are co-located in the same block, so ordering by
`id()` on both endpoints gives sequential block access when writing — reducing page faults for
dense relationship types where the relationship store exceeds the page cache.

```
N = 4, stripes for regular (non-self-loop) rel type:

     end:  0   1   2   3
start: 0 [ S0  S1  S2  S3 ]
       1 [ S3  S0  S1  S2 ]
       2 [ S2  S3  S0  S1 ]
       3 [ S1  S2  S3  S0 ]

S0 cells run in parallel, then S1, then S2, then S3.
```

**Self-loops** (startLabel == endLabel) need special handling. Mirror cells (i, j) and (j, i)
would appear in the same stripe and deadlock each other because both try to lock buckets i and j
in opposite orders. The matrix is folded to the upper triangular (cells where i ≤ j only);
the same `(j - i + N) % N` stripe assignment remains conflict-free.

Each cell's target count is distributed round-robin across all live cells so no cell gets more
than one extra relationship relative to any other.

Effective N is capped at `min(MATRIX_N, startPoolSize, endPoolSize)` to prevent empty
partitions when a label has fewer nodes than MATRIX_N.

---

## Execution pipeline

| Phase               | What happens                                                                          |
|---------------------|---------------------------------------------------------------------------------------|
| Drop / empty check  | Clear the target database or fail if non-empty                                        |
| `__Seed` constraint | Create uniqueness constraint on `__Seed.__seedId`; await index build                  |
| Constraints         | Replay schema constraints before writing nodes so violations surface early            |
| Nodes               | Batch-create nodes per `NodeCreate` bucket; every node receives `__Seed` + `__seedId` |
| Tags                | Apply tag labels via range MATCH on `__Seed` index                                    |
| Relationships       | Matrix/stripe parallel creation; virtual thread per cell, sequential UNWIND batches   |
| Cleanup             | Remove `__Seed` label and `__seedId` property from all nodes                          |
| Schema indexes      | Replay schema indexes from the captured schema                                        |
| Drop constraint     | Drop `__seed_node_id` uniqueness constraint                                           |

---

## Key constants

| Constant           | Default | Meaning                                                           |
|--------------------|---------|-------------------------------------------------------------------|
| `NODE_BATCH`       | 20 000  | Rows per `UNWIND` for node creation                               |
| `NODE_INNER_BATCH` | 2 000   | `IN CONCURRENT TRANSACTIONS` batch size within node creation      |
| `TAG_BATCH`        | 10 000  | `IN CONCURRENT TRANSACTIONS` batch size for tag application       |
| `REL_BATCH`        | 10 000  | Rows per `UNWIND` per cell; one auto-commit transaction per batch |
| `DROP_BATCH`       | 10 000  | `IN TRANSACTIONS` batch size for `__Seed` cleanup                 |
| `MATRIX_N`         | 8       | Matrix dimension; controls both partition count and parallelism   |
