# SkemaDok — UI Architecture

## Contents

1. [Technology Stack](#1-technology-stack)
2. [Application Bootstrap](#2-application-bootstrap)
3. [Routing](#3-routing)
4. [State Management](#4-state-management)
5. [Views](#5-views)
   - 5.1 MetadataView
   - 5.2 CanvasView
   - 5.3 GenerateView
6. [Graph Component](#6-graph-component)
   - 6.1 Vue Flow Basics
   - 6.2 TAG Chip Absorption
   - 6.3 Self-Loop Layout
   - 6.4 Hierarchy Modes
   - 6.5 Layout Persistence
   - 6.6 LabelNode Size Calculation
   - 6.7 Centre-Snap on Drag
   - 6.8 GroupNode Size Calculation
   - 6.9 Parallel Edge Offset
   - 6.10 Edge Waypoints
   - 6.11 Auto-Layout (ELK)
7. [Node Components](#7-node-components)
8. [Edge Components](#8-edge-components)
   - 8.1 PolylineEdge — Rendering Pipeline
   - 8.2 Self-Loop Rendering
   - 8.3 InheritanceEdge
9. [CSS Architecture](#9-css-architecture)
10. [Number Formatting](#10-number-formatting)

---

## 1. Technology Stack

Frontend source lives under `app/src/main/frontend/`.

| Package | Version | Role |
|---|---|---|
| `vue` | 3.4 | UI framework |
| `vue-router` | 4.3 | Client-side routing |
| `pinia` | 2.2 | State management |
| `@vue-flow/core` | 1.48 | Graph canvas (nodes, edges, pan/zoom) |
| `@vue-flow/background` | 1.3 | Dot/grid background on canvas |
| `@vue-flow/controls` | 1.1 | Zoom in/out/fit controls |
| `primevue` | 4.5 | UI component library (inputs, tables, listboxes) |
| `@primevue/themes` | 4.5 | PrimeVue theming (Lara base + Neo4j override) |
| `primeicons` | 7.0 | Icon set |
| `html-to-image` | — | Off-screen canvas capture to PNG data URL |
| `elkjs` | — | Eclipse Layout Kernel (pure-JS port); ELK auto-layout |
| `vite` | — | Build tool and dev server |
| `@vitejs/plugin-vue` | — | Vue SFC transform |

The dev server runs on `:5173` and proxies `/api` to the Spring server on `:8282`. In 'production' the built SPA is served directly by Spring from `app/src/main/resources/static/`. See [architecture.md](architecture.md) for the backend and build details.

---

## 2. Application Bootstrap

`main.js` wires everything together in this order:

```
createApp(App)
  ├── createPinia()               state store
  ├── createRouter()              vue-router
  ├── app.use(PrimeVue, {         component library
  │     theme: {
  │       preset: Neo4jPreset,    Lara + Neo4j teal overrides
  │       options: {
  │         darkModeSelector: '[data-theme="dark"]'
  │       }
  │     }
  │   })
  └── app.mount('#app')
```

### PrimeVue Theme Override

PrimeVue uses the **Lara** preset as a base. `main.js` overrides the `primary` colour scale with Neo4j teal:

| Token | Light | Dark |
|---|---|---|
| `primary.200` | `#9de8ec` | used for interactive text |
| `primary.600` | `#0A6190` | used for interactive text |
| `primary.950` | `#052d4a` | darkest |

Dark mode is activated by setting `data-theme="dark"` on `<html>`. This is set/read from `localStorage['skemadok-theme']` (defaults to dark).

---

## 3. Routing

```
/                 → redirect to /views
/metadata         → MetadataView.vue
/views            → CanvasView.vue
/generate         → GenerateView.vue
```

`App.vue` renders the topbar (title, DB info, nav links, save/restore, theme toggle) and a `<RouterView>` for the active route.

---

## 4. State Management

The Pinia store (`stores/schema.js`) holds the full schema document in memory. It is the single source of truth for the frontend.

```
useSchemaStore()
│
├── State
│   ├── document        full SchemaDocument object (labels, rels, views, metadata)
│   ├── loading         boolean, true while fetch in progress
│   ├── error           string | null
│   └── dirty           true when unsaved changes exist
│
├── Computed
│   ├── labels          document.nodeLabels ?? []
│   ├── relationshipTypes  document.relationshipTypes ?? []
│   └── views           document.views ?? []
│
└── Actions
    ├── loadSchema()     GET /schema → set document, dirty = false
    ├── saveSchema()     PUT /schema with full document → dirty = false
    ├── reloadSchema()   calls loadSchema() (re-reads from disk, discards local changes)
    └── markEdited()     sets dirty = true
```

### Mutation Flow

```
Vue component
    │  user interaction (blur, click, drag)
    ▼
mutate store.document directly
    │  (components hold references into the reactive document object)
    ▼
markEdited()
    │  dirty = true → Save button enabled
    ▼
user presses Save
    ▼
PUT /api/schema  { full document }
    │
    ▼
Spring writes to disk → 204
    │
dirty = false
```

All annotation edits (descriptions, colours, data sources, etc.) mutate the Pinia `document` object in place — no API call is made until the user presses Save. Layout changes (node positions, waypoints) go through a 600 ms debounce before calling `store.markEdited()`.

---

## 5. Views

### 5.1 MetadataView (`/metadata`)

Two-panel layout for browsing and editing entity metadata.

```
┌──────────────────────────────────────────────────────────────────┐
│  TOPBAR                                                          │
├─────────────────┬────────────────────────────────────────────────┤
│                 │                                                │
│  Sidebar 260px  │  Detail panel (flex: 1)                       │
│                 │                                                │
│  Filtered       │  Entity name  [role badge]  42k nodes          │
│  Listbox        │  ─────────────────────────────────────         │
│                 │  Description                                   │
│  ▸ LABELS       │  ┌─────────────────────────────────────┐      │
│    Agreement    │  │                                     │      │
│    Author  42k  │  └─────────────────────────────────────┘      │
│    ...          │                                                │
│  ▸ RELS         │  Data source  [________________]              │
│    ABOUT   145  │                                                │
│    ...          │  Properties                                    │
│                 │  ┌──────────┬───────┬──┬───────────┬────────┐ │
│                 │  │ name     │ type  │* │ description│ source │ │
│                 │  │ id       │ Long  │● │            │        │ │
│                 │  └──────────┴───────┴──┴───────────┴────────┘ │
└─────────────────┴────────────────────────────────────────────────┘
```

All edits mutate the Pinia store on `blur` and set `dirty = true`. The properties DataTable is PrimeVue's `<DataTable>` with inline `<InputText>` cells.

---

### 5.2 CanvasView (`/views`)

Four-panel layout. `CanvasView.vue` is a thin shell (~80 lines) that delegates to four subcomponents:

| Subcomponent | Panel | Responsibility |
|---|---|---|
| `ViewListPanel.vue` | Views 180px | View list, create, delete |
| `EntityPicker.vue` | Entity Picker 200px | Draggable label/rel picker |
| `SchemaGraph.vue` | Canvas flex:1 | Vue Flow graph, layout, persistence |
| `AnnotationPanel.vue` | Annotation 270px | Entity metadata editor |

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  TOPBAR                                                                      │
├──────────┬──────────────┬──────────────────────────────┬────────────────────┤
│          │              │                              │                    │
│  Views   │  Entity      │  Canvas (flex: 1)            │  Annotation        │
│  180px   │  Picker      │                              │  270px             │
│          │  200px       │  SchemaGraph                 │  (when selected)   │
│  test 7L │              │                              │                    │
│  test1 3L│  Labels Rels │  [graph nodes & edges]       │  Person            │
│  hier1 4L│              │                              │  ENTITY  104 nodes │
│          │  Filter…     │                              │  ──────────────    │
│  [+ New] │  ──────────  │                              │  Colour            │
│          │  Agreement   │                              │  ● ● ● ● …         │
│          │    1.2M      │                              │  Description       │
│          │  Author 42k  │                              │  └─textarea─┘      │
│          │  ...         │                              │  Extends  [▼]      │
│          │              │                              │  Show props        │
│          │  ─Rels─────  │                              │  ☑ id              │
│          │  ABOUT  145  │                              │  ☐ name            │
│          │  ...         │                              │                    │
│  [Delete]│              │                              │                    │
└──────────┴──────────────┴──────────────────────────────┴────────────────────┘
```

Labels can be dragged from the picker onto the canvas. Toggling a label automatically adds/removes relationship types where both endpoints are present in the view (`autoSelectRels` / `autoDeselectRels`).

---

### 5.3 GenerateView (`/generate`)

Full-page scrollable view that provides a live preview of generated documentation and lets the user copy sections or download the full document as a single file.

**Format selector** — four output formats:

| ID | Label | Notes |
|---|---|---|
| `asciidoc` | AsciiDoc | Renders with Asciidoctor; converts to HTML or PDF |
| `markdown` | Markdown | GitHub-flavoured; Confluence/Notion compatible |
| `html` | HTML | Self-contained file; open in browser or import into Google Docs |
| `docx` | DOCX | Microsoft Word / Google Docs |

**Options** — two checkboxes:
- *Include data source* — adds a data-source column to property tables
- *Capture in light mode* — renders diagram images with a light background regardless of the active UI theme

**Download button** — sends `POST /api/generate/download` with the full document, format, and view images (PNG data URLs). The server assembles the output package (a zip for AsciiDoc/Markdown containing the document + `views/` PNG directory; a self-contained `.html` or `.docx` otherwise) and returns a binary download.

**Preview sections** (AsciiDoc and Markdown only) — loaded from `POST /api/generate/preview`, which returns a JSON object with string fields:
- `views` — array of per-view objects (name, rendered text)
- `labelsBlock` — full Node Labels section as a single string
- `relationsBlock` — Relationship Types section
- `constraintsIndexes` — Constraints & Indexes section

Each section is expandable (`<details>`) with a pre-formatted `<pre>` block and a copy-to-clipboard button.

**Views section** — each view is rendered as a card:

```
┌── View card ─────────────────────────────────────────────────────────┐
│  HR Domain                                    [Copy]                 │
│  ─────────────────────────────────────────────────────────────────   │
│  image::views/hr-domain.png[HR Domain]        [Copy directive]       │
│  ─────────────────────────────────────────────────────────────────   │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │  [rendered diagram image]                                    │   │
│  └──────────────────────────────────────────────────────────────┘   │
│  [Copy image]                                                        │
└──────────────────────────────────────────────────────────────────────┘
```

The image directive is format-specific:
- AsciiDoc: `image::views/{slug}.png[{viewName}]`
- Markdown: `![{viewName}](views/{slug}.png)`

The slug mirrors the Java `sanitizeFileName()` logic: lowercase, non-alphanumeric runs → hyphen, leading/trailing hyphens trimmed.

**Image capture** — the view mounts one offscreen `SchemaGraph` instance per view (positioned far left via `position: fixed; left: -10000px`). Vue Flow's `ResizeObserver` fires for off-screen fixed elements, so layout is computed normally. After a 1.5 s warmup (`setTimeout`), `captureAllImages()` iterates through all views and calls `captureContainerAsPng(el, theme)` from `utils/captureView.js`. The `captureLight` option is re-applied on toggle without remounting.

**Scrolling** — the Generate route toggles `page-scrollable` on `<html>` via `onMounted`/`onUnmounted`. This breaks the fixed-height layout chain (`html → body → #app → .app-shell → .app-content`) so the browser window scrollbar handles overflow rather than an inner div.

---

## 6. Graph Component

`SchemaGraph.vue` is the most complex component. It wraps Vue Flow and manages layout, hierarchy, TAG chip absorption, self-loop distribution, and persistence.

### 6.1 Vue Flow Basics

Vue Flow renders a directed graph of nodes and edges on an SVG/HTML canvas with pan and zoom. Nodes are positioned absolutely by `{x, y}` in **flow coordinates** (not screen pixels). The viewport transform (zoom + pan) maps flow coordinates to screen coordinates.

```
Screen pixel = (flow coordinate × zoom) + pan offset
```

Vue Flow accepts two arrays: `nodes` and `edges`. These are computed properties in `SchemaGraph.vue` — they are rebuilt whenever the view changes or layout state changes.

### 6.2 TAG Chip Absorption

Labels with `role === 'TAG'` that have a `parentEntity` annotation and whose parent is present in the current view are rendered as chips *inside* the parent node rather than as standalone graph nodes.

```
SchemaGraph computes two derived maps when building nodes:

absorbedTags (Set<string>)
  — names of TAGs that have a parentEntity in the current view's label list

chipTagsByEntity (Map<string, string[]>)
  — parentEntity → [TAG names to render as chips]
```

Absorbed TAGs are excluded from the `labels` array passed to Vue Flow, so no standalone node is created for them. Each parent `LabelNode` or `GroupNode` receives a `chipTags` array via `node.data`; `LabelNode.vue` renders these as coloured chips in a `<div class="tag-chips">` row above the property list.

The chip row is only visible when `chipTags.length > 0`.

---

### 6.3 Self-Loop Layout

When a relationship type has the same label at both start and end, `SchemaGraph` detects it as a self-loop and assigns it a `selfLoopIndex` (0-based, incremented per node). Multiple self-loops on the same node are distributed to different sides:

```
selfLoopIndex  →  side
0              →  right
1              →  bottom
2              →  left
3              →  top
4+             →  cycles back to right, etc.
```

`SchemaGraph` also maintains a `selfLoopNodes` set and adds `LOOP_EXTENT` extra horizontal clearance to the auto-layout bounding box so the rectangular self-loop path does not overlap adjacent nodes.

See §8.2 (Self-Loop Rendering) for how `PolylineEdge` turns the index into actual waypoints.

---

### 6.4 Hierarchy Modes

Three modes are toggled via buttons in the graph toolbar:

```
HIERARCHY  [None]  [Edges]  [Boxes]
```

| Mode | Constant | LabelNode | GroupNode | InheritanceEdge |
|---|---|---|---|---|
| None | `'none'` | all labels | — | — |
| Edges | `'edges'` | all labels | — | parent → child (dashed) |
| Boxes | `'boxes'` | child labels | parent labels | — |

Hierarchy is determined by the `extends` annotation on a label. A label with `extends: 'Person'` is a child of `Person`.

### 6.5 Layout Persistence

Node positions, edge waypoints, zoom, pan, and hierarchy mode are all stored in `view.layout`:

```json
{
  "nodes": {
    "Person":   { "x": 400, "y": 200 },
    "Employee": { "x": 250, "y": 80  }
  },
  "edgeWaypoints": {
    "WORKS_AT--Employee--Department": [
      { "x": 360, "y": 150 }
    ]
  },
  "zoom": 0.85,
  "panX": 120,
  "panY": 60,
  "hierarchyMode": "boxes"
}
```

`{x, y}` is the **top-left corner** of the node box in Vue Flow's coordinate system. Node dimensions are not persisted — they are measured from the rendered DOM after mount via `ResizeObserver`. Derived values (centre, bounds) are computed at runtime.

**Stale wrapper dimensions:** Vue Flow keeps an inline `style="width: Xpx; height: Ypx"` on the node's HTML wrapper. When a node switches type (e.g. `groupNode` → `labelNode` after leaving Boxes mode), Vue Flow does not clear these inline styles. The fix is to include `style: { width: 'auto', height: 'auto' }` in every `labelNode` definition so Vue Flow overwrites the stale values.

On mount the component reads `view.layout` and restores all positions. After drag-stop a **600 ms debounce** calls `store.markEdited()`. Nothing reaches disk until Save.

### 6.6 LabelNode Size Calculation

`LabelNode.vue` has no explicit width/height set in JavaScript. Vue Flow measures the rendered DOM element after mount using a `ResizeObserver`. These measured dimensions are stored in `nodeDimensions` in `SchemaGraph.vue`.

```
┌──────────────────────────────────────────────────┐
│  node-header   padding: 8px 10px 8px 12px        │ ← 40px minimum height
│  [name·········sep···count]                       │
├──────────────────────────────────────────────────┤
│  node-props    padding: 6px 10px 8px 12px        │
│  * property-name                                  │ ← 18px + 3px gap per row
│  * property-name                                  │
└──────────────────────────────────────────────────┘
```

```
node height = 40px (header)
            + (N_props × 21px)     [18px row + 3px gap]
            + 14px (props padding)  [only if props section present]

node width  = max(160px, content width)   [CSS min-width]
            ≈ 160px for most labels
```

**Example:**

```
No display properties:    height = 40px,  width ≈ 160px
2 display properties:     height = 40 + (2×21) + 14 = 96px
4 display properties:     height = 40 + (4×21) + 14 = 138px
```

### 6.7 Centre-Snap on Drag

When a node is dropped, it snaps horizontally to the nearest other node's X-axis if the gap is ≤ 12 px.

```
              Dropped node centre-X
                      │
         ◄────────────┼────────────►
              12px    │    12px
                      │
         If another node's centre-X falls in this range,
         snap X to that node's centre-X.
```

This keeps horizontal columns aligned without a grid.

### 6.8 GroupNode Size Calculation

In **Boxes** mode, parent labels become `GroupNode` containers. Their size must enclose all child label nodes with padding.

Constants:

```
PAD_L    = 14px    left inner padding
PAD_R    = 36px    right inner padding (extra for scroll affordance)
PAD_T    = 14px    top inner padding (above first child)
PAD_B    = 20px    bottom inner padding
HEADER_H = 38px    group node header (name + count row)
PROP_ROW_H = 21px  height per display-property row (18px + 3px gap)
```

Children are positioned **relative to the group node's inner content area** (below the header):

```
┌─ GroupNode ──────────────────────────────────────────┐
│  node-header  (HEADER_H = 38px)                      │
│  * property   (N × PROP_ROW_H)                       │
├──────────────────────────────────────────────────────┤  ← content top
│  PAD_T (14px)                                        │
│  ┌─ child LabelNode ─────────────┐                   │
│  └───────────────────────────────┘                   │
│  PAD_B (20px)                                        │
└──────────────────────────────────────────────────────┘
```

Children's positions in `view.layout.nodes` are stored in the **group's local coordinate system**. Vue Flow's parent-node feature places them within the group.

**Group size computation:**

```
topOffset    = HEADER_H + (N_parent_props × PROP_ROW_H) + PAD_T

For each child node at position (cx, cy) with size (cw, ch):
  minX = min(cx)
  minY = min(cy)
  maxX = max(cx + cw)
  maxY = max(cy + ch)

groupWidth  = max(maxX - minX + PAD_L + PAD_R,  200px)
groupHeight = max(maxY - minY + PAD_T + PAD_B,   80px) + topOffset
```

### 6.9 Parallel Edge Offset

When multiple relationship types connect the same pair of labels, edges are fanned out perpendicularly:

```
  Person ─────────────────────────────── Department
          WORKS_AT  (offset  0)
         ─────────────────────────────
          MANAGES   (offset +50px)
         ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─
          REPORTS_TO (offset -50px)
```

The offset is applied perpendicular to the source→target direction. For edges without manual waypoints, two **virtual waypoints** are auto-generated at 1/3 and 2/3 along the centre-to-centre axis:

```
direction vector:  d = (Tx-Sx, Ty-Sy),  |d|
perpendicular:     p = (-dy/|d|, dx/|d|) × offset

wp1 = (Sx,Sy) + d×(1/3) + p
wp2 = (Sx,Sy) + d×(2/3) + p
```

For edges with manual waypoints, no virtual waypoints are added — the user's waypoints are used as-is.

Edge IDs encode the parallel pair: `${relName}--${startLabel}--${endLabel}`

### 6.10 Edge Waypoints

`PolylineEdge.vue` renders edges as polylines. Users can:

- **Drag a waypoint** — move it freely
- **Double-click a waypoint** — remove it
- **Hover a segment midpoint handle** — drag to insert a new waypoint

All coordinates are converted from screen pixels to flow coordinates using Vue Flow's `screenToFlowCoordinate()`. Waypoints are stored in `localWaypoints` (a `Map` keyed by edge ID) in `SchemaGraph.vue`, not in the edge `data` object — this avoids triggering Vue Flow's edge re-initialisation on every drag tick.

On drag-stop (600 ms debounce), `localWaypoints` is written into `view.layout.edgeWaypoints`.

**Hitarea and z-order constraints:**

`pointer-events: visiblePainted` ignores strokes with `stroke="transparent"`. The invisible hitarea line must use `pointer-events: stroke`:

```css
.edge-hitarea { pointer-events: stroke; }
```

Midpoint insert handles must be rendered **after** the label badge `<g>` in SVG order (SVG z-order is render order):

```
<polyline class="edge-hitarea" .../>
<polyline class="edge-line" .../>
<polygon  class="edge-arrow" .../>
<g class="edge-label">  ← badge </g>
<circle   class="midpoint-handle" .../>   ← always after badge
```

Midpoint handles use conditional `pointer-events` so they only catch events when the parent edge is hovered:

```css
.midpoint-handle           { pointer-events: none; }
.polyline-edge:hover
  .midpoint-handle         { pointer-events: all;  }
```

### 6.11 Auto-Layout (ELK)

`SchemaGraph.vue` has a one-shot **Auto-layout** button in the Vue Flow Controls widget. It runs ELK over all nodes in the current view and writes computed positions back into `savedPositions` / `view.layout.nodes`.

The ELK wrapper lives in `composables/useElkLayout.js`:

```js
computeElkLayout(nodes, edges, dimensions, algorithm = 'force')
  → Promise<{ [nodeId]: { x: number, y: number } }>
```

The ELK instance (`new ELK()`) is module-level — created once at import time and reused.

**Available algorithms:**

| ID | ELK algorithm | Best for |
|---|---|---|
| `force` | `force` (300 iterations) | Cyclic graphs; balanced, organic layout |
| `layered` | `layered`, direction RIGHT | DAGs and heavily hierarchical schemas |
| `stress` | `stress` | Small graphs where edge lengths matter |
| `tree` | `mrtree` DFS | Pure tree structures |

**Edge routing is disabled** (`elk.edgeRouting: NONE`). ELK computes only node positions; `PolylineEdge` handles all edge routing.

**Self-loops** are stripped before submission (ELK cannot route them).

**Waypoints are cleared** after auto-layout because existing manual waypoints were placed relative to the old positions:

```js
localWaypoints.value = {}
props.view.layout.edgeWaypoints = {}
```

`fitView` is called after the next Vue tick so the viewport snaps to show all repositioned nodes.

---

## 7. Node Components

### 7.1 LabelNode.vue

Used for individual entity nodes in all hierarchy modes.

```
┌──────────────────────────────────────────┐
│  ████  NodeName    [ENTITY]  ┊  1.2M    │  ← node-header
├──────────────────────────────────────────┤
│  [Status] [Active]                       │  ← tag-chips (if any TAGs absorbed)
├──────────────────────────────────────────┤
│  * id                                    │  ← node-props
│  * name                                  │    (only if displayProperties set)
└──────────────────────────────────────────┘
```

- Left border: 4px solid accent (blue-violet by default, or overridden by `label.color`)
- If `label.color` set: full border 1.5px in that colour (no accent left strip)
- If `role === 'TAG'` (standalone): dashed border, violet tint
- Text colour adapts to background luminance (see §7.3)

**TAG chips** — when absorbed TAGs are associated with this node (see §6.2), a `<div class="tag-chips">` row is rendered between the header and the properties. Each chip is a `<span class="tag-chip">` with the TAG label name. On coloured nodes the chip background adapts to the node's background luminance.

Invisible `<Handle>` elements on all four sides allow edges to attach from any direction (`opacity: 0; pointer-events: none`).

### 7.2 GroupNode.vue

Used only in **Boxes** hierarchy mode, for parent labels.

Structurally similar to `LabelNode` but:
- `width: 100%; height: 100%` — sized entirely by Vue Flow (set from the computed group size)
- No fixed left border stripe — full border using `label.color` or default
- Border colour for very dark colours is lightened (§7.3)
- `display: flex; flex-direction: column` so child nodes float above it

### 7.3 Colour Adaption

Both components apply the same luminance formula:

```
lum = 0.299 × R + 0.587 × G + 0.114 × B      (ITU-R BT.601)
      where R, G, B ∈ [0, 1]

lum > 0.5  →  dark text (#111827), muted (#4b5563), dark separator
lum ≤ 0.5  →  light text (#f3f4f6), muted (#9ca3af), light separator
```

`GroupNode` additionally corrects very dark border colours (luminance < 0.18) by mixing toward white:

```
mix = 0.6
corrected_channel = channel × (1 - mix) + 255 × mix
```

---

## 8. Edge Components

### 8.1 PolylineEdge.vue — Rendering Pipeline

`PolylineEdge.vue` renders relationship type edges as sequences of straight line segments (entirely in SVG). The pipeline has seven stages:

```
Stage 1  Node geometry          centres + dimensions from Vue Flow
Stage 2  Virtual waypoints      auto-generated for parallel pairs (no user waypoints yet)
Stage 3  allPoints              full ordered sequence: start, [waypoints], end
Stage 4  Border intersection    start/end snapped from node centres to node borders
Stage 5  Line shortening        last segment trimmed 2 px so arrowhead tip does not overlap node HTML
Stage 6  Arrowhead polygon      triangle computed from trimmed tip + penultimate point
Stage 7  Label placement        midpoint of middle segment, rotated to segment angle
```

---

#### Stage 1 — Node Geometry

`nodeGeometry` resolves both endpoint nodes from Vue Flow via `findNode()`. For each node it reads:

| Source | Width | Height |
|---|---|---|
| `GroupNode` | `node.data.groupWidth` | `node.data.groupHeight` |
| `LabelNode` | `node.dimensions.width` | `node.dimensions.height` |

`GroupNode` dimensions are pre-computed by `SchemaGraph` and stored in `node.data` because they are set as a CSS `style` string — Vue Flow does not measure them the same way it measures `LabelNode`.

If either dimension is missing (node not yet measured), `nodeGeometry` returns `null` and the edge falls back to the raw `sourceX/sourceY/targetX/targetY` provided by Vue Flow. **This is a known source of flicker on first render.**

```
scx = node.position.x + sw / 2     (source centre X)
scy = node.position.y + sh / 2     (source centre Y)
tcx = node.position.x + tw / 2     (target centre X)
tcy = node.position.y + th / 2     (target centre Y)
```

---

#### Stage 2 — Virtual Waypoints (Parallel Edges)

`virtualWaypoints` computes two points at ⅓ and ⅔ along the centre-to-centre axis, both displaced perpendicularly by `parallelOffset`:

```
dx = tcx − scx,   dy = tcy − scy,   len = √(dx²+dy²)

perpendicular (right-hand, y-axis down):
  px = +dy / len
  py = −dx / len

wp1 = (scx + dx/3  + px×offset,  scy + dy/3  + py×offset)
wp2 = (scx + dx×⅔ + px×offset,  scy + dy×⅔ + py×offset)
```

Virtual waypoints are **ignored once the user has placed manual waypoints** on the same edge.

---

#### Stage 3 — allPoints Assembly

```
if user waypoints present:     [start, ...userWaypoints, end]
else if virtualWaypoints:      [start, wp1, wp2, end]
else:                          [start, end]
```

`start` and `end` are the border intersection points (Stage 4).

---

#### Stage 4 — Border Intersection (`bboxEdgePoint`)

`bboxEdgePoint` computes where the ray from centre `(cx, cy)` toward a target point `(tx, ty)` exits the rectangle of size `w × h` centred at `(cx, cy)`:

```
hw = w / 2,   hh = h / 2

t = min(hw / |dx|,  hh / |dy|)

exit point = (cx + dx×t,  cy + dy×t)
```

**Overlap-aware routing (no waypoints):** When two bounding boxes share a vertical range (y-overlap > 0) and the nodes are side by side (`|dx| ≥ |dy|`), the edge is routed **horizontally** through the overlap midpoint:

```
y = (max(sTop, tTop) + min(sBot, tBot)) / 2
start = (sRight or sLeft, y)
end   = (tLeft  or tRight, y)
```

Symmetrically, when boxes share a horizontal range and are stacked, the edge routes **vertically**. Diagonal clip is the fallback for nodes separated with no axis overlap.

---

#### Stage 5 — Line Shortening

`trimmedPoints` shortens the last segment by 2 px toward the penultimate point to prevent the arrowhead tip from appearing inside the HTML node element:

```
dx = last.x − prev.x,   dy = last.y − prev.y,   len = √(dx²+dy²)

if len > 12:
  trimmedEnd = last − 2 × (dx/len, dy/len)
```

---

#### Stage 6 — Arrowhead

The arrowhead is a plain `<polygon>` (no SVG `<marker>`, which can lose its reference on re-render):

```
tip   = trimmedPoints[last]
prev  = trimmedPoints[last−1]

size  = 10 px
theta = 36° (π/5)   →  half-angle of the arrowhead cone

left  base point:  tip − size × (nx·cos θ + ny·sin θ,  ny·cos θ − nx·sin θ)
right base point:  tip − size × (nx·cos θ − ny·sin θ,  ny·cos θ + nx·sin θ)
```

---

#### Stage 7 — Label Placement

The label is placed at the midpoint of the **middle segment** of `allPoints`:

```
mid segment index = floor((len(allPoints) − 1) / 2)
labelPos = midpoint of allPoints[mid] → allPoints[mid+1]
```

The label is rendered as an SVG `<g>` with `rotate(angle)` clamped to `(−90°, 90°]` so text never appears upside-down. An opaque background `<rect>` (`width = label.length × 7.5 + 12 px, height = 18 px`) visually breaks the polyline at the label position.

---

#### Full Pipeline Diagram

```
                 Vue Flow node objects
                        │
                  nodeGeometry
                        │
         ┌──────────────┴──────────────┐
         │                             │
   virtualWaypoints              user waypoints
   (parallel offset,              (from localWaypoints
    1/3 and 2/3 points)            injected Map)
         │                             │
         └──────────────┬──────────────┘
                        │
                   allPoints
           [bboxEdgePoint(start), ...wps, bboxEdgePoint(end)]
                        │
                        │  trim last 2 px
                        │
                  trimmedPoints
                 /              \
        visible polyline      arrowhead polygon
                 \              /
                  SVG rendered
                        │
                  labelPos (mid segment midpoint)
                  labelAngle (clamped to ±90°)
                        │
                  SVG label + background rect
```

---

### 8.2 Self-Loop Rendering

When `source === target`, `PolylineEdge` enters self-loop mode. `loopWaypointsForSide()` generates **four waypoints** forming a rectangular path around one side of the node.

**Waypoint layout for a right-side loop:**

```
     WP1 ──────────────── WP2
      │                    │
      │   ┌─ NodeName ─┐   │   ← reltype label rendered at WP1→WP2 midpoint
      │   │            │   │
      │   └────────────┘   │
     WP4 ──────────────── WP3
```

Gap sizes adapt to the label box width and the node dimensions:

```
GAP_V  = max(20, labelBgWidth / 2 − nodeHalfHeight + STUB)
GAP_H  = 20
STUB   = 20
```

**Dragging** — individual waypoint handles are hidden for self-loops. The user drags the loop by its reltype badge. On mouse-up, `nearestSide()` determines which side the badge was released over, then regenerates all four waypoints for that side.

---

### 8.3 InheritanceEdge.vue

Renders `extends` hierarchy edges as dashed lines with a UML hollow-triangle arrowhead at the parent end. Used only in **Edges** hierarchy mode.

```
  Child ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ △ Parent
```

Endpoint geometry uses the same overlap-aware routing as `PolylineEdge` (§8.1 Stage 4). The component guards on `v-if="nodeGeometry"` — the edge is not rendered until both nodes are measured.

The arrowhead tip sits **at the node border** with no inward trim (the hollow triangle has no HTML to be occluded by). Half-angle 30° for a slimmer UML style. Stroke: `stroke-dasharray="6 4"`.

---

## 9. CSS Architecture

### 9.1 File Layout

All paths below are relative to `app/src/main/frontend/src/`.

| File | Scope |
|---|---|
| `style.css` | Global: resets, custom properties, shared classes, Vue Flow overrides, `html.page-scrollable` scrolling override |
| `LabelNode.vue <style scoped>` | Node box styles, TAG chip styles |
| `GroupNode.vue <style scoped>` | Group container styles |
| `PolylineEdge.vue <style scoped>` | Edge SVG styles |
| `InheritanceEdge.vue <style scoped>` | Dashed edge styles |
| `MetadataView.vue <style scoped>` | Two-panel metadata layout |
| `CanvasView.vue <style scoped>` | Flex layout for the four panels |
| `ViewListPanel.vue <style scoped>` | View list sidebar |
| `EntityPicker.vue <style scoped>` | Draggable entity picker sidebar |
| `AnnotationPanel.vue <style scoped>` | Entity annotation editor panel |
| `App.vue <style scoped>` | Topbar and app shell |
| `GenerateView.vue <style scoped>` | Generate page layout, view cards, image preview, directive row |

No CSS framework is active. Do not add one without confirmation. If new components are needed, **Tailwind CSS v4** is the agreed direction (its `@theme {}` block maps onto the existing CSS variable approach).

**`html.page-scrollable` override** — the Generate view adds this class to `<html>` on mount and removes it on unmount. It breaks the fixed-height chain that the rest of the app relies on (canvas needs `overflow: hidden`; generate needs browser-level scrolling):

```css
html.page-scrollable,
html.page-scrollable body,
html.page-scrollable #app    { height: auto; min-height: 100%; }
html.page-scrollable .app-shell  { height: auto; min-height: 100%; }
html.page-scrollable .app-content { overflow: visible; }
```

### 9.2 Custom Property System

All colours, spacing, and typography are defined as CSS custom properties on `:root` (light) and overridden on `[data-theme="dark"]`.

**Backgrounds:**

| Variable | Light | Dark |
|---|---|---|
| `--bg-base` | `#ffffff` | `#0d1117` |
| `--bg-surface` | `#f6f8fa` | `#161b22` |
| `--bg-elevated` | `#e4e9ef` | `#21262d` |
| `--bg-hover` | `#e4e9ef` | `#21262d` |
| `--bg-active` | `#d0d7de` | `#2d333b` |

**Text:**

| Variable | Light | Dark |
|---|---|---|
| `--text-primary` | `#1f2328` | `#e6edf3` |
| `--text-secondary` | `#42484e` | `#8d96a0` |
| `--text-muted` | `#6e7781` | `#656d76` |

**Accent (Neo4j teal):**

| Variable | Light | Dark |
|---|---|---|
| `--accent` | `#0A6190` | `#00CDD7` |
| `--accent-bright` | `#1589c3` | `#a5b4fc` |
| `--accent-dim` | `rgba(26,143,195,0.15)` | `rgba(129,140,248,0.15)` |

**Secondary palettes:**

| Variable | Usage |
|---|---|
| `--amber`, `--amber-dim`, `--amber-border` | warnings, caution states |
| `--violet`, `--violet-dim`, `--violet-border` | TAG role badges |
| `--danger`, `--danger-dim` | destructive actions |

**Typography:**

| Variable | Value |
|---|---|
| `--font-sans` | system-ui, -apple-system, Segoe UI, sans-serif |
| `--font-mono` | 'JetBrains Mono', 'Cascadia Code', 'Fira Code', monospace |

### 9.3 Theme Switching

```
App.vue  toggleTheme()
  │
  ├── document.documentElement.dataset.theme = 'dark' | 'light'
  │     → CSS [data-theme="dark"] selector activates
  │
  └── localStorage['skemadok-theme'] = value
        → read on next mount, defaults to 'dark'
```

PrimeVue picks up the same selector because `darkModeSelector` is configured to match it in `main.js`.

### 9.4 Vue Flow Overrides

Vue Flow ships with its own stylesheet. `style.css` overrides the relevant parts to match the theme:

```css
.vue-flow                       → background: var(--bg-base)
.vue-flow__controls             → themed background, border, rounded
.vue-flow__controls-button      → themed text and hover colours
.vue-flow__selection            → accent-dim fill, accent border
.vue-flow__background           → matches --bg-base
```

---

## 10. Number Formatting

All node and relationship counts are formatted by `utils/format.js`:

```
n < 1 000                    → "842"    exact
1 000 ≤ n < 10 000           → "1.2k"   one decimal
10 000 ≤ n < 999 500         → "42k"    integer
999 500 ≤ n < 10 000 000     → "1M"     promotes unit cleanly
10 000 000 ≤ n < 999 500 000 → "10M" / "100M"
n ≥ 999 500 000              → "1.5B"   etc.
```

The upper boundary of each integer band is `999 500` (not `1 000 000`) to prevent `Math.round` overflowing into the next unit (`999 999 → 1000k` would be wrong).

The raw number is always available on the element's `title` attribute for hover.

`formatCount` is imported by: `LabelNode.vue`, `GroupNode.vue`, `MetadataView.vue`, `ViewListPanel.vue`, `AnnotationPanel.vue`.
