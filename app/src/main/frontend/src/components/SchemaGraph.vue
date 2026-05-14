<!--
  Schema graph canvas built on Vue Flow.

  Each label in the view is a draggable rectangle node (LabelNode).
  Relationship types become directed polyline edges (PolylineEdge).

  Label hierarchy (extendsLabel) can be shown in two modes, toggled by the
  toolbar in the top-left corner:
  - "Edges"  — dashed InheritanceEdge from child to parent (UML open triangle)
  - "Boxes"  — translucent GroupNode container rendered behind child nodes

  Node positions and edge waypoints are persisted to view.layout on every
  change (debounced 600 ms). The layout is loaded from view.layout on mount.
-->
<template>
  <div class="graph-root">
  <VueFlow
    :key="view.name"
    :nodes="flowNodes"
    :edges="flowEdges"
    :node-types="nodeTypes"
    :edge-types="edgeTypes"
    :nodes-connectable="false"
    :edges-updatable="false"
    :snap-to-grid="true"
    :snap-grid="[20, 20]"
    fit-view-on-init
    @nodes-change="onNodesChange"
    @node-drag-start="onNodeDragStart"
    @node-drag="onNodeDrag"
    @node-drag-stop="onNodeDragStop"
    @node-click="onNodeClick"
    @edge-click="onEdgeClick"
    @pane-click="onPaneClick"
    @node-context-menu="onNodeContextMenu"
    @edge-context-menu="onEdgeContextMenu"
  >
    <Background pattern-color="#3a3d60" :gap="24" />
    <Controls position="top-right" :show-interactive="false">
      <ControlButton
        :title="isLayingOut ? 'Laying out…' : 'Auto-layout'"
        :disabled="isLayingOut"
        @click="runAutoLayout"
      >
        <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24"
             fill="none" stroke="currentColor" stroke-width="2.2"
             stroke-linecap="round" stroke-linejoin="round">
          <path d="M21.5 2v6h-6M2.5 22v-6h6"/>
          <path d="M2 11.5a10 10 0 0 1 18.8-4.3M22 12.5a10 10 0 0 1-18.8 4.2"/>
        </svg>
      </ControlButton>
      <select v-model="layoutAlgo" class="algo-select" title="Layout algorithm">
        <option v-for="algo in LAYOUT_ALGOS" :key="algo.value" :value="algo.value">
          {{ algo.label }}
        </option>
      </select>
    </Controls>

    <Panel position="top-left" class="hierarchy-toolbar">
      <span class="toolbar-label">Hierarchy</span>
      <div class="toolbar-toggle">
        <button
          v-for="mode in HIERARCHY_MODES"
          :key="mode.value"
          :class="{ active: hierarchyMode === mode.value }"
          @click="setHierarchyMode(mode.value)"
        >{{ mode.label }}</button>
      </div>
    </Panel>
  </VueFlow>
  <ContextMenu ref="contextMenuRef" :model="contextMenuItems" />
  </div>
</template>

<script setup>
import { ref, computed, watch, provide, markRaw, nextTick } from 'vue'
import { VueFlow, Panel, useVueFlow } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { Controls, ControlButton } from '@vue-flow/controls'
import LabelNode from './LabelNode.vue'
import PolylineEdge from './PolylineEdge.vue'
import InheritanceEdge from './InheritanceEdge.vue'
import GroupNode from './GroupNode.vue'
import { useSchemaStore } from '../stores/schema.js'
import ContextMenu from 'primevue/contextmenu'
import { computeElkLayout } from '../composables/useElkLayout.js'
import { relDisplayName } from '../utils/format.js'

const props = defineProps({
  view: { type: Object, required: true }
})

const emit = defineEmits(['select'])

const store = useSchemaStore()
const nodeTypes = { labelNode: markRaw(LabelNode), groupNode: markRaw(GroupNode) }
const edgeTypes = { polylineEdge: markRaw(PolylineEdge), inheritanceEdge: markRaw(InheritanceEdge) }

const HIERARCHY_MODES = [
  { value: 'none',  label: 'None'  },
  { value: 'edges', label: 'Edges' },
  { value: 'boxes', label: 'Boxes' }
]

// ---- Snapshots ---------------------------------------------------------

const savedPositions = ref({})
const localWaypoints = ref({})
const hierarchyMode  = ref('none')
const isLayingOut    = ref(false)

const LAYOUT_ALGOS = [
  { value: 'force',   label: 'Force'   },
  { value: 'layered', label: 'Layered' },
  { value: 'tree',    label: 'Tree'    },
  { value: 'stress',  label: 'Stress'  },
]
const layoutAlgo = ref('force')

const contextMenuRef   = ref(null)
const contextMenuItems = ref([])

watch(() => props.view.name, () => {
  savedPositions.value = Object.assign({}, props.view.layout?.nodes         ?? {})
  localWaypoints.value = Object.assign({}, props.view.layout?.edgeWaypoints ?? {})
  hierarchyMode.value  = props.view.layout?.hierarchyMode ?? 'none'
}, { immediate: true })

provide('localWaypoints', localWaypoints)
provide('setWaypoints', (edgeId, waypoints) => {
  localWaypoints.value[edgeId] = waypoints
  ensureLayout()
  props.view.layout.edgeWaypoints[edgeId] = waypoints
  debouncedSave()
})
function clearRelWaypoints(relName) {
  const prefix = `${relName}--`
  for (const edgeId of Object.keys(localWaypoints.value)) {
    if (edgeId.startsWith(prefix)) delete localWaypoints.value[edgeId]
  }
  ensureLayout()
  for (const edgeId of Object.keys(props.view.layout.edgeWaypoints)) {
    if (edgeId.startsWith(prefix)) delete props.view.layout.edgeWaypoints[edgeId]
  }
  debouncedSave()
}
provide('clearRelWaypoints', clearRelWaypoints)

// ---- Hierarchy mode ------------------------------------------------

function setHierarchyMode(mode) {
  if (mode === 'boxes') {
    // Bootstrap savedPositions from the current None-mode node positions before
    // hierarchyMode changes so GroupNode sizing has positions for all children,
    // even those never dragged.  flowNodes.value still reflects the None-mode layout here.
    for (const node of flowNodes.value) {
      if (!savedPositions.value[node.id]) {
        savedPositions.value[node.id] = { x: node.position.x, y: node.position.y }
      }
    }
  }
  hierarchyMode.value = mode
  ensureLayout()
  props.view.layout.hierarchyMode = mode
  debouncedSave()
}

// Parent → [children] map for labels in this view
const parentChildMap = computed(() => {
  const map = new Map()
  const viewSet = new Set(props.view.labels)
  for (const label of store.labels) {
    const parent = label.extends ?? label.extendsLabel   // handle both JS and JSON key names
    if (parent && viewSet.has(label.name) && viewSet.has(parent)) {
      if (!map.has(parent)) map.set(parent, [])
      map.get(parent).push(label.name)
    }
  }
  return map
})

// ---- Layout init -------------------------------------------------------

function ensureLayout() {
  if (!props.view.layout) {
    props.view.layout = { nodes: {}, edgeWaypoints: {}, zoom: 1.0, panX: 0.0, panY: 0.0 }
  }
  props.view.layout.nodes         = props.view.layout.nodes         ?? {}
  props.view.layout.edgeWaypoints = props.view.layout.edgeWaypoints ?? {}
}

function gridPosition(index, total) {
  const cols = Math.max(1, Math.ceil(Math.sqrt(total)))
  return {
    x: (index % cols) * 220,
    y: Math.floor(index / cols) * 140
  }
}

// Constraint types that enforce uniqueness — displayed with a solid underline.
// Neo4j 5.x uses the NODE_PROPERTY_UNIQUENESS / RELATIONSHIP_PROPERTY_UNIQUENESS names;
// older versions used the shorter UNIQUENESS / RELATIONSHIP_UNIQUENESS forms.
const UNIQUE_CONSTRAINT_TYPES = new Set([
  'NODE_PROPERTY_UNIQUENESS', 'NODE_KEY',
  'RELATIONSHIP_PROPERTY_UNIQUENESS', 'RELATIONSHIP_KEY',
  'UNIQUENESS', 'RELATIONSHIP_UNIQUENESS',
])

/**
 * Annotates an array of property names with constraint/index markers.
 * Returns objects { name, marker } where marker is 'constrained', 'indexed', or null.
 * LOOKUP indexes are skipped — they cover all tokens, not specific properties.
 */
function annotateProps(entityName, propNames) {
  if (!propNames?.length) return []
  const allConstraints = store.constraints
  const allIndexes     = store.indexes
  return propNames.map(name => {
    const hasConstraint = allConstraints.some(c =>
      UNIQUE_CONSTRAINT_TYPES.has(c.type) &&
      c.labelsOrTypes.includes(entityName) &&
      c.properties.includes(name)
    )
    if (hasConstraint) return { name, marker: 'constrained' }
    const hasIndex = allIndexes.some(idx =>
      idx.type !== 'LOOKUP' &&
      idx.state === 'ONLINE' &&
      idx.labelsOrTypes.includes(entityName) &&
      idx.properties.includes(name)
    )
    return { name, marker: hasIndex ? 'indexed' : null }
  })
}

// ---- Flow nodes --------------------------------------------------------

// Node dimensions — updated from drag-stop and from Vue Flow's ResizeObserver via onNodesChange.
const nodeDimensions = ref({})

const flowNodes = computed(() => {
  const viewLabelSet = new Set(props.view.labels)
  const allViewLabels = store.labels.filter(l => viewLabelSet.has(l.name))

  // TAGs whose taggedEntities overlap with the view are shown as chips on those entity nodes — not standalone.
  const absorbedTags = new Set()
  for (const label of allViewLabels) {
    if (label.role === 'TAG' && label.taggedEntities?.length) {
      if (label.taggedEntities.some(e => viewLabelSet.has(e))) {
        absorbedTags.add(label.name)
      }
    }
  }

  // A TAG chip appears on each of its taggedEntities that is present in the view.
  const chipTagsByEntity = {}
  for (const label of allViewLabels) {
    if (absorbedTags.has(label.name)) {
      for (const entityName of label.taggedEntities) {
        if (viewLabelSet.has(entityName)) {
          ;(chipTagsByEntity[entityName] ??= []).push(label.name)
        }
      }
    }
  }
  for (const chips of Object.values(chipTagsByEntity)) {
    chips.sort()
  }

  const labels = allViewLabels.filter(l => !absorbedTags.has(l.name))

  if (hierarchyMode.value !== 'boxes' || parentChildMap.value.size === 0) {
    return labels.map((label, i) => {
      const saved = savedPositions.value[label.name]
      return {
        id:       label.name,
        type:     'labelNode',
        position: saved ? { x: saved.x, y: saved.y } : gridPosition(i, labels.length),
        data:     {
          label,
          chipTags:             chipTagsByEntity[label.name] ?? [],
          annotatedDisplayProps: annotateProps(label.name, label.displayProperties),
        },
        // Reset width/height — when switching out of boxes mode, Vue Flow keeps
        // the wrapper's previously-applied width/height from the groupNode style.
        style:    { width: 'auto', height: 'auto' }
      }
    })
  }

  // Boxes mode: parent labels become GroupNodes (same id, sized to contain children).
  // Children remain regular LabelNodes at their absolute positions.
  // Relationship edges are rerouted to the GroupNode in flowEdges, so the GroupNode
  // id must match the parent label name — which it does.
  const parentNames = new Set(parentChildMap.value.keys())
  const PAD_L      = 14   // small left margin — child appears left-aligned in the container
  const PAD_R      = 36
  const PAD_T      = 14   // gap between last section and first child
  const PAD_B      = 20
  const HEADER_H   = 38   // approximate rendered height of the header row
  const PROP_ROW_H = 21   // row pitch: 18px height + 3px gap (matches LabelNode CSS)

  return labels.map((label, i) => {
    const saved = savedPositions.value[label.name]

    if (parentNames.has(label.name)) {
      // Extra vertical space above children for the header and any displayProperties.
      const propsH    = (label.displayProperties?.length ?? 0) * PROP_ROW_H
      const topOffset = HEADER_H + propsH + PAD_T

      const childBounds = (parentChildMap.value.get(label.name) ?? [])
        .map(n => {
          const pos  = savedPositions.value[n]
          const dims = nodeDimensions.value[n]
          return pos ? { x: pos.x, y: pos.y, w: dims?.width ?? 160, h: dims?.height ?? 80 } : null
        })
        .filter(Boolean)

      if (childBounds.length > 0) {
        const minX = Math.min(...childBounds.map(p => p.x)) - PAD_L
        const minY = Math.min(...childBounds.map(p => p.y)) - topOffset
        const maxX = Math.max(...childBounds.map(p => p.x + p.w)) + PAD_R
        const maxY = Math.max(...childBounds.map(p => p.y + p.h)) + PAD_B
        const groupWidth  = Math.max(maxX - minX, 200)
        const groupHeight = Math.max(maxY - minY, 80)
        return {
          id:         label.name,
          type:       'groupNode',
          position:   { x: minX, y: minY },
          style:      { width: `${groupWidth}px`, height: `${groupHeight}px` },
          data:       { label, groupWidth, groupHeight, chipTags: chipTagsByEntity[label.name] ?? [], annotatedDisplayProps: annotateProps(label.name, label.displayProperties) },
          zIndex:     0,
          selectable: true,
          draggable:  true
        }
      }
    }

    return {
      id:       label.name,
      type:     'labelNode',
      position: saved ? { x: saved.x, y: saved.y } : gridPosition(i, labels.length),
      data:     { label, chipTags: chipTagsByEntity[label.name] ?? [], annotatedDisplayProps: annotateProps(label.name, label.displayProperties) },
      zIndex:   1001
    }
  })
})

// ---- Flow edges --------------------------------------------------------

const flowEdges = computed(() => {
  const viewLabelSet = new Set(props.view.labels)

  // In boxes mode, relationship edges are rerouted to the GroupNode (outer box)
  // rather than the child LabelNode, so the visual terminus is the container border.
  const childToParent = new Map()
  if (hierarchyMode.value === 'boxes') {
    for (const [parent, children] of parentChildMap.value) {
      for (const child of children) {
        childToParent.set(child, parent)
      }
    }
  }

  // Relationship type edges with parallel-offset detection
  const seen = new Set()
  const candidates = []
  for (const relName of props.view.relationshipTypes) {
    const rel = store.relationshipTypes.find(r => r.name === relName)
    if (!rel) continue
    for (const conn of (rel.connections ?? [])) {
      for (const startLabel of conn.startLabels.filter(l => viewLabelSet.has(l))) {
        for (const endLabel of conn.endLabels.filter(l => viewLabelSet.has(l))) {
          const effectiveStart = childToParent.get(startLabel) ?? startLabel
          const effectiveEnd   = childToParent.get(endLabel)   ?? endLabel
          // Skip only when box-mode remapping collapsed two *different* labels onto the same
          // group node. Genuine self-loops (start === end already) must pass through.
          if (effectiveStart === effectiveEnd && startLabel !== endLabel) continue
          const key = `${relName}|${effectiveStart}|${effectiveEnd}`
          if (seen.has(key)) continue
          seen.add(key)
          candidates.push({ relName, displayName: relDisplayName(rel), start: effectiveStart, end: effectiveEnd, displayProperties: rel.displayProperties ?? [], annotatedDisplayProps: annotateProps(relName, rel.displayProperties) })
        }
      }
    }
  }

  const groups = {}
  for (const c of candidates) {
    const sorted = [c.start, c.end].sort()
    const key = sorted.join('||')
    ;(groups[key] ??= { canonical: sorted[0], items: [] }).items.push(c)
  }

  const SPACING = 50
  const edges = []
  for (const { canonical, items: group } of Object.values(groups)) {
    const n = group.length
    group.forEach((c, idx) => {
      const isSelf = c.start === c.end
      let parallelOffset = (!isSelf && n > 1) ? (idx - (n - 1) / 2) * SPACING : 0
      // Reverse-direction edges have their perpendicular vector flipped in
      // virtualWaypoints; negate the scalar so the absolute displacement is
      // symmetric about the centre line regardless of edge direction.
      if (parallelOffset !== 0 && c.start !== canonical) {
        parallelOffset = -parallelOffset
      }
      edges.push({
        id:     `${c.relName}--${c.start}--${c.end}`,
        source: c.start,
        target: c.end,
        type:   'polylineEdge',
        data:   {
          label: c.displayName, parallelOffset,
          selfLoopIndex: isSelf ? idx : undefined,
          displayProperties:    c.displayProperties,
          annotatedDisplayProps: c.annotatedDisplayProps,
        }
      })
    })
  }

  // Inheritance edges (only in 'edges' mode, only when both endpoints are in the view)
  if (hierarchyMode.value === 'edges') {
    for (const [parentName, childNames] of parentChildMap.value) {
      if (!viewLabelSet.has(parentName)) continue
      for (const childName of childNames) {
        if (!viewLabelSet.has(childName)) continue
        edges.push({
          id:     `__extends__${childName}`,
          source: childName,
          target: parentName,
          type:   'inheritanceEdge',
          data:   {}
        })
      }
    }
  }

  return edges
})

// ---- Save --------------------------------------------------------------

let saveTimer = null
function debouncedSave() {
  clearTimeout(saveTimer)
  saveTimer = setTimeout(() => store.markEdited(), 600)
}

function onNodesChange(changes) {
  for (const change of changes) {
    if (change.type === 'dimensions' && change.dimensions) {
      nodeDimensions.value[change.id] = { width: change.dimensions.width, height: change.dimensions.height }
    }
  }
}

// ---- GroupNode drag: children follow in real-time -----------------------

// Snapshot taken at drag-start so onNodeDrag can compute exact deltas.
const groupDragSnapshot = ref(null)

function onNodeDragStart({ node }) {
  if (hierarchyMode.value !== 'boxes' || !parentChildMap.value.has(node.id)) return
  const children = parentChildMap.value.get(node.id) ?? []
  const childPositions = {}
  for (const name of children) {
    const pos = savedPositions.value[name]
    if (pos) childPositions[name] = { x: pos.x, y: pos.y }
  }
  groupDragSnapshot.value = {
    groupId:  node.id,
    naturalX: node.position.x,
    naturalY: node.position.y,
    childPositions
  }
}

function onNodeDrag({ node }) {
  const snap = groupDragSnapshot.value
  if (!snap || snap.groupId !== node.id) return
  const dx = node.position.x - snap.naturalX
  const dy = node.position.y - snap.naturalY
  for (const [name, startPos] of Object.entries(snap.childPositions)) {
    savedPositions.value[name] = { x: Math.round(startPos.x + dx), y: Math.round(startPos.y + dy) }
  }
}

// Snaps the dropped node's centre Y to the nearest other node's centre Y if within this threshold.
const CENTRE_SNAP_PX = 12

function onNodeDragStop({ node }) {
  ensureLayout()

  if (hierarchyMode.value === 'boxes' && parentChildMap.value.has(node.id)) {
    const snap = groupDragSnapshot.value
    if (snap && snap.groupId === node.id) {
      const dx = node.position.x - snap.naturalX
      const dy = node.position.y - snap.naturalY
      for (const [name, startPos] of Object.entries(snap.childPositions)) {
        const newPos = { x: Math.round(startPos.x + dx), y: Math.round(startPos.y + dy) }
        props.view.layout.nodes[name] = newPos
        savedPositions.value[name]    = newPos
      }
    } else {
      // No snapshot (drag started before boxes mode was active) — commit current state.
      for (const name of parentChildMap.value.get(node.id) ?? []) {
        const pos = savedPositions.value[name]
        if (pos) props.view.layout.nodes[name] = pos
      }
    }
    groupDragSnapshot.value = null
    debouncedSave()
    return
  }

  // LabelNode drag: record position, then centre-snap if close to another node's centre Y.
  const nodeH = node.dimensions?.height
  let pos = { x: node.position.x, y: node.position.y }

  if (nodeH) {
    const centreY = pos.y + nodeH / 2
    for (const [otherId, otherSaved] of Object.entries(savedPositions.value)) {
      if (otherId === node.id) continue
      const otherH = nodeDimensions.value[otherId]?.height
      if (!otherH) continue
      const otherCentreY = otherSaved.y + otherH / 2
      if (Math.abs(centreY - otherCentreY) <= CENTRE_SNAP_PX) {
        pos = { x: pos.x, y: Math.round(otherCentreY - nodeH / 2) }
        break
      }
    }
  }

  props.view.layout.nodes[node.id] = pos
  savedPositions.value[node.id] = pos
  if (node.dimensions) {
    nodeDimensions.value[node.id] = { width: node.dimensions.width, height: node.dimensions.height }
  }
  debouncedSave()
}

// ---- External label addition -------------------------------------------

function findPlacement(labelName) {
  const EST_W = 240, EST_H = 60, MARGIN = 30, LOOP_EXTENT = 150

  // Identify nodes that carry a self-loop in this view
  const selfLoopNodes = new Set()
  for (const relName of props.view.relationshipTypes) {
    const rel = store.relationshipTypes.find(r => r.name === relName)
    if (!rel) continue
    for (const l of rel.startLabels) {
      if (rel.endLabels.includes(l) && props.view.labels.includes(l)) selfLoopNodes.add(l)
    }
  }

  // Build occupied bounding boxes: existing nodes + self-loop extents + stored waypoints
  const occupied = []
  for (const [id, pos] of Object.entries(savedPositions.value)) {
    const w = nodeDimensions.value[id]?.width  ?? 220
    const h = nodeDimensions.value[id]?.height ?? 50
    occupied.push({ x1: pos.x - MARGIN, y1: pos.y - MARGIN, x2: pos.x + w + MARGIN, y2: pos.y + h + MARGIN })
    if (selfLoopNodes.has(id)) {
      occupied.push({
        x1: pos.x - LOOP_EXTENT, y1: pos.y - LOOP_EXTENT,
        x2: pos.x + w + LOOP_EXTENT, y2: pos.y + h + LOOP_EXTENT,
      })
    }
  }
  for (const wps of Object.values(props.view.layout?.edgeWaypoints ?? {})) {
    for (const wp of wps) occupied.push({ x1: wp.x - 40, y1: wp.y - 40, x2: wp.x + 40, y2: wp.y + 40 })
  }

  // Prefer y near schema-adjacent labels already on the canvas
  const relatedPositions = []
  for (const rel of store.relationshipTypes) {
    const peers = rel.startLabels.includes(labelName) ? rel.endLabels
                : rel.endLabels.includes(labelName)   ? rel.startLabels : []
    for (const peer of peers) {
      const p = savedPositions.value[peer]
      if (p && peer !== labelName) relatedPositions.push(p)
    }
  }

  const positions = Object.values(savedPositions.value)
  const refPositions = relatedPositions.length > 0 ? relatedPositions : positions
  const startY = refPositions.length > 0
    ? Math.round(refPositions.reduce((s, p) => s + p.y, 0) / refPositions.length / 20) * 20
    : 0

  // Start x beyond the rightmost occupied content (including self-loop extents and waypoints)
  let maxRight = 0
  for (const [id, pos] of Object.entries(savedPositions.value)) {
    const w = nodeDimensions.value[id]?.width ?? 220
    maxRight = Math.max(maxRight, pos.x + w + (selfLoopNodes.has(id) ? LOOP_EXTENT : 0))
  }
  for (const wps of Object.values(props.view.layout?.edgeWaypoints ?? {})) {
    for (const wp of wps) maxRight = Math.max(maxRight, wp.x + 40)
  }

  const startX = positions.length > 0 ? maxRight + MARGIN : 0

  function overlaps(x, y) {
    return occupied.some(b => x < b.x2 && x + EST_W > b.x1 && y < b.y2 && y + EST_H > b.y1)
  }

  for (const dy of [0, -140, 140, -280, 280, -420, 420]) {
    for (const dx of [0, 280, 560, 840]) {
      const x = startX + dx, y = startY + dy
      if (!overlaps(x, y)) return { x: Math.round(x), y: Math.round(y) }
    }
  }
  return { x: Math.round(maxRight + 300), y: Math.round(startY) }
}

async function runAutoLayout() {
  if (isLayingOut.value || props.view.labels.length < 2) return
  isLayingOut.value = true
  try {
    const layoutNodes = props.view.labels.map(l => ({ id: l }))

    const layoutEdges = []
    for (const relName of props.view.relationshipTypes) {
      const rel = store.relationshipTypes.find(r => r.name === relName)
      if (!rel) continue
      for (const conn of (rel.connections ?? [])) {
        for (const startLabel of conn.startLabels) {
          for (const endLabel of conn.endLabels) {
            if (props.view.labels.includes(startLabel) && props.view.labels.includes(endLabel)) {
              layoutEdges.push({ id: `${relName}__${startLabel}__${endLabel}`, source: startLabel, target: endLabel })
            }
          }
        }
      }
    }

    const newPositions = await computeElkLayout(layoutNodes, layoutEdges, nodeDimensions.value, layoutAlgo.value)

    ensureLayout()
    for (const [id, pos] of Object.entries(newPositions)) {
      savedPositions.value[id]       = pos
      props.view.layout.nodes[id]    = pos
    }

    // Waypoints are stale once nodes have moved; let PolylineEdge recompute straight routes.
    localWaypoints.value             = {}
    props.view.layout.edgeWaypoints  = {}

    debouncedSave()
    await nextTick()
    fitView({ padding: 0.15 })
  } finally {
    isLayingOut.value = false
  }
}

function addLabelToView(labelName) {
  if (props.view.labels.includes(labelName)) return
  ensureLayout()
  if (!savedPositions.value[labelName]) {
    const pos = findPlacement(labelName)
    savedPositions.value[labelName]    = pos
    props.view.layout.nodes[labelName] = pos
  }
  props.view.labels.push(labelName)
  debouncedSave()
}

const { findNode, getNodes, fitView } = useVueFlow()

function selectNode(labelName) {
  for (const n of getNodes.value) {
    n.selected = false
  }
  const node = findNode(labelName)
  if (node) node.selected = true
}

defineExpose({ addLabelToView, clearRelWaypoints, selectNode })

// ---- Selection ---------------------------------------------------------

function onNodeClick({ node }) {
  const label = store.labels.find(l => l.name === node.id)
  if (label) emit('select', { entity: label, isLabel: true })
}

function onEdgeClick({ edge }) {
  if (edge.id.startsWith('__extends__')) return
  const rel = store.relationshipTypes.find(r => r.name === edge.data?.label)
  if (rel) emit('select', { entity: rel, isLabel: false })
}

function onPaneClick() {
  emit('select', null)
}

// ---- Context menu ------------------------------------------------------

function onNodeContextMenu({ node, event }) {
  event.preventDefault()
  const labelName = node.id
  contextMenuItems.value = [
    {
      label: 'Delete',
      command: () => {
        const idx = props.view.labels.indexOf(labelName)
        if (idx !== -1) {
          props.view.labels.splice(idx, 1)
          emit('select', null)
          debouncedSave()
        }
      }
    },
    {
      label: 'Add Related Relationships',
      command: () => {
        for (const r of store.relationshipTypes) {
          if (props.view.relationshipTypes.includes(r.name)) continue
          if ((r.connections ?? []).some(conn =>
              conn.startLabels.includes(labelName) || conn.endLabels.includes(labelName))) {
            props.view.relationshipTypes.push(r.name)
          }
        }
        debouncedSave()
      }
    }
  ]
  contextMenuRef.value.show(event)
}

function onEdgeContextMenu({ edge, event }) {
  if (edge.id.startsWith('__extends__')) return
  event.preventDefault()
  const relName = edge.data?.label
  contextMenuItems.value = [
    {
      label: 'Delete',
      command: () => {
        const idx = props.view.relationshipTypes.indexOf(relName)
        if (idx !== -1) {
          props.view.relationshipTypes.splice(idx, 1)
          emit('select', null)
          debouncedSave()
        }
      }
    },
    {
      label: 'Add Related Labels',
      command: () => {
        const rel = store.relationshipTypes.find(r => r.name === relName)
        if (rel) {
          for (const conn of (rel.connections ?? [])) {
            for (const labelName of [...conn.startLabels, ...conn.endLabels]) {
              addLabelToView(labelName)
            }
          }
        }
      }
    }
  ]
  contextMenuRef.value.show(event)
}

</script>

<style scoped>
.hierarchy-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  background: var(--bg-elevated, #2d2f4a);
  border: 1px solid var(--border-strong, #52569e);
  border-radius: 6px;
  padding: 5px 8px;
  pointer-events: all;
}

.toolbar-label {
  font-size: 10px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.07em;
  color: var(--text-muted, #545880);
  white-space: nowrap;
}

.toolbar-toggle {
  display: flex;
  border-radius: 4px;
  overflow: hidden;
  border: 1px solid var(--border-strong, #52569e);
}

.toolbar-toggle button {
  padding: 3px 9px;
  font-size: 11px;
  font-weight: 600;
  background: transparent;
  color: var(--text-secondary, #8b90c0);
  border: none;
  border-right: 1px solid var(--border-strong, #52569e);
  cursor: pointer;
  font-family: var(--font-sans, sans-serif);
  transition: background 0.1s, color 0.1s;
}

.toolbar-toggle button:last-child {
  border-right: none;
}

.toolbar-toggle button:hover {
  background: var(--bg-hover, #383b5e);
  color: var(--text-primary, #eaeefa);
}

.toolbar-toggle button.active {
  background: var(--accent-bright, #a5b4fc);
  color: #1a1c35;
}

.algo-select {
  width: 100%;
  padding: 4px 2px;
  background: var(--vf-controls-bg, #1e1e2e);
  color: var(--vf-controls-color, #d4d4d8);
  border: none;
  border-top: 1px solid var(--vf-controls-border, #3f3f50);
  font-size: 10px;
  font-family: var(--font-sans, sans-serif);
  cursor: pointer;
  text-align: center;
}

.algo-select:focus {
  outline: none;
}
.graph-root {
  width: 100%;
  height: 100%;
  position: relative;
}
</style>
