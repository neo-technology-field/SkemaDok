<!--
  Custom edge that renders a straight-segment polyline with user-moveable bend points (waypoints).

  Waypoints are stored in the parent SchemaGraph via provide/inject rather than in Vue Flow's
  edge data, which avoids triggering a full edge re-init on every drag event.

  Start and end points are computed from node bounding-box intersections so lines touch
  the node border cleanly on whichever side faces the other node (or the first/last waypoint).

  The arrowhead is a plain <polygon> computed in script — no SVG <marker>, so it can never
  lose its reference when the edge re-renders.

  Interactions:
  - Drag a waypoint handle to move the bend point
  - Hover the edge and drag a midpoint handle to insert a new waypoint in one motion
  - Double-click a waypoint handle to remove it
-->
<template>
  <g v-if="nodeGeometry" class="polyline-edge">

    <!-- Wide transparent hit area so the user can click the line more easily -->
    <polyline
      :points="svgPoints"
      fill="none"
      stroke="transparent"
      stroke-width="14"
      class="edge-hitarea"
    />

    <!-- Visible polyline — shortened slightly so the arrowhead sits cleanly at the tip -->
    <polyline
      :points="svgPointsShortened"
      fill="none"
      stroke-width="1.5"
      :style="{ stroke: selected ? 'var(--accent-bright)' : 'var(--amber)' }"
    />

    <!-- Arrowhead polygon — always rendered, no marker reference needed -->
    <polygon
      v-if="arrowPolygon"
      :points="arrowPolygon"
      :style="{ fill: selected ? 'var(--accent-bright)' : 'var(--amber)' }"
    />

    <!-- Waypoint handles (draggable; double-click to remove). Hidden for self-loops:
         the badge drag is the only repositioning mechanism there, and showing
         individual handles would let the user break the rectangular shape. -->
    <circle
      v-for="(wp, i) in (isSelfLoop ? [] : waypoints)"
      :key="`wp-${i}`"
      :cx="wp.x"
      :cy="wp.y"
      r="5"
      stroke-width="1.5"
      class="waypoint-handle"
      :style="{ fill: 'var(--bg-elevated)', stroke: selected ? 'var(--accent-bright)' : 'var(--amber)' }"
      @mousedown.stop.prevent="startWaypointDrag(i, $event)"
      @dblclick.stop="removeWaypoint(i)"
    ><title>Drag to move · Double-click to remove</title></circle>

    <!--
      Edge label: rendered before midpoint handles so handles sit on top in SVG z-order.
      Group centred on the mid-segment midpoint, rotated to follow the exact
      segment angle (clamped to avoid upside-down text). The opaque background rect
      "interrupts" the visible line. When displayProperties is non-empty the rect grows
      downward with a divider and one row per property.
    -->
    <g
      v-if="data?.label"
      class="edge-badge"
      :transform="`translate(${labelPos.x}, ${labelPos.y}) rotate(${labelAngle})`"
      :style="isSelfLoop ? { cursor: isDraggingLoop ? 'grabbing' : 'grab' } : {}"
      @pointerdown="onBadgePointerDown"
    >
      <rect
        :x="-labelBgWidth / 2"
        y="-9"
        :width="labelBgWidth"
        :height="labelBgHeight"
        rx="3"
        stroke-width="0.5"
        :style="{ fill: selected ? 'var(--accent-dim)' : 'var(--bg-elevated)', stroke: 'var(--amber)' }"
      />
      <text
        x="0"
        y="0"
        text-anchor="middle"
        dominant-baseline="central"
        font-size="11"
        class="edge-label-text"
        :style="{ fill: selected ? 'var(--accent-bright)' : 'var(--text-secondary)' }"
      >{{ data.label }}</text>
      <template v-if="annotatedDisplayProps.length">
        <line
          :x1="-labelBgWidth / 2 + 4"
          y1="9"
          :x2="labelBgWidth / 2 - 4"
          y2="9"
          stroke-width="0.75"
          :style="{ stroke: 'var(--border-strong)' }"
        />
        <text
          v-for="(prop, i) in annotatedDisplayProps"
          :key="prop.name"
          x="0"
          :y="22 + i * 16"
          text-anchor="middle"
          font-size="10"
          class="edge-label-text"
          :style="{
            fill: 'var(--text-muted)',
            textDecoration: prop.marker === 'constrained' ? 'underline solid' : prop.marker === 'indexed' ? 'underline dotted' : 'none'
          }"
        >{{ prop.name }}</text>
      </template>
    </g>

    <!-- Segment midpoint handles — rendered after the badge so they sit on top.
         pointer-events inactive when edge is not hovered so invisible handles
         never silently block badge clicks. -->
    <circle
      v-for="(mp, i) in (isSelfLoop ? [] : segmentMidpoints)"
      :key="`mp-${i}`"
      :cx="mp.x"
      :cy="mp.y"
      r="5"
      stroke-width="1.5"
      class="midpoint-handle"
      :style="{ fill: 'var(--bg-elevated)', stroke: 'var(--amber)' }"
      @mousedown.stop.prevent="insertAndDrag(i, mp, $event)"
    />
  </g>
</template>

<script setup>
import { computed, inject, ref } from 'vue'
import { useVueFlow } from '@vue-flow/core'

const props = defineProps({
  id:       { type: String,  required: true },
  source:   String,
  target:   String,
  sourceX:  { type: Number, required: true },
  sourceY:  { type: Number, required: true },
  targetX:  { type: Number, required: true },
  targetY:  { type: Number, required: true },
  data:     Object,
  selected: { type: Boolean, default: false }
})

const { screenToFlowCoordinate, findNode } = useVueFlow()

const localWaypoints = inject('localWaypoints')
const setWaypoints   = inject('setWaypoints')

const annotatedDisplayProps = computed(() =>
  props.data?.annotatedDisplayProps
    ?? (props.data?.displayProperties ?? []).map(n => ({ name: n, marker: null }))
)

const waypoints = computed(() => localWaypoints?.value?.[props.id] ?? [])

const isSelfLoop = computed(() => props.source === props.target)

/**
 * Returns the point where the ray from (cx, cy) toward (tx, ty) exits
 * a rectangle centred at (cx, cy) with dimensions w × h.
 */
function bboxEdgePoint(cx, cy, w, h, tx, ty) {
  const dx = tx - cx
  const dy = ty - cy
  if (Math.abs(dx) < 0.01 && Math.abs(dy) < 0.01) return { x: cx, y: cy }
  const hw = w / 2
  const hh = h / 2
  const t = Math.min(
    hw / (Math.abs(dx) || Infinity),
    hh / (Math.abs(dy) || Infinity)
  )
  return { x: cx + dx * t, y: cy + dy * t }
}

// Node geometry (centres + dimensions) — drives all other edge calculations.
// GroupNodes carry pre-computed dimensions in data to avoid waiting for DOM measurement.
const nodeGeometry = computed(() => {
  const srcNode = findNode(props.source)
  const tgtNode = findNode(props.target)
  const sw = srcNode?.data?.groupWidth  ?? srcNode?.dimensions?.width
  const sh = srcNode?.data?.groupHeight ?? srcNode?.dimensions?.height
  const tw = tgtNode?.data?.groupWidth  ?? tgtNode?.dimensions?.width
  const th = tgtNode?.data?.groupHeight ?? tgtNode?.dimensions?.height
  if (!sw || !sh || !tw || !th) return null
  return {
    scx: srcNode.position.x + sw / 2,
    scy: srcNode.position.y + sh / 2,
    sw, sh,
    tcx: tgtNode.position.x + tw / 2,
    tcy: tgtNode.position.y + th / 2,
    tw, th
  }
})

const LOOP_SIDES = ['right', 'bottom', 'left', 'top']

/**
 * Returns four waypoints forming a rectangular self-loop on the given side.
 *
 * The far leg (WP2→WP3) is the middle segment, so labelPos lands at its centre —
 * exactly where the reltype box is rendered. The x/y of that leg is chosen so the
 * box centre aligns with the path, making the path visually thread through the box.
 * The attachment points (WP1 and WP4) share the same x (right/left loops) or y
 * (top/bottom loops) and lie within the label node bounds, which the endpoint
 * logic uses to route start/end to the correct opposite faces of the node.
 */
function loopWaypointsForSide(side, cx, cy, w, h) {
  const STUB  = 20                      // minimum clearance between box edge and path corner
  const lbw   = labelBgWidth.value      // reltype box width (= vertical extent when rotated 90°)
  const lbh   = labelBgHeight.value     // reltype box height
  const halfW = w / 2, halfH = h / 2
  const ax = w * 0.25, ay = h * 0.25   // attachment offset from centre

  switch (side) {
    case 'right': {
      // Vertical far leg: rotated box occupies lbw px vertically — GAP_V must give STUB clearance above/below.
      const GAP_V = Math.max(20, lbw / 2 - halfH + STUB)
      const GAP_H = 20
      const rx = cx + halfW + GAP_H + lbw / 2
      return [
        { x: cx + ax, y: cy - halfH - GAP_V },
        { x: rx,      y: cy - halfH - GAP_V },
        { x: rx,      y: cy + halfH + GAP_V },
        { x: cx + ax, y: cy + halfH + GAP_V },
      ]
    }
    case 'left': {
      const GAP_V = Math.max(20, lbw / 2 - halfH + STUB)
      const GAP_H = 20
      const rx = cx - halfW - GAP_H - lbw / 2
      return [
        { x: cx - ax, y: cy + halfH + GAP_V },
        { x: rx,      y: cy + halfH + GAP_V },
        { x: rx,      y: cy - halfH - GAP_V },
        { x: cx - ax, y: cy - halfH - GAP_V },
      ]
    }
    case 'top': {
      // Horizontal far leg: unrotated box occupies lbw px horizontally — GAP_H gives STUB clearance left/right.
      const GAP_H = Math.max(20, lbw / 2 - halfW + STUB)
      const GAP_V = 20
      const ry = cy - halfH - GAP_V - lbh / 2
      return [
        { x: cx - halfW - GAP_H, y: cy - ay },
        { x: cx - halfW - GAP_H, y: ry      },
        { x: cx + halfW + GAP_H, y: ry      },
        { x: cx + halfW + GAP_H, y: cy - ay },
      ]
    }
    case 'bottom': {
      const GAP_H = Math.max(20, lbw / 2 - halfW + STUB)
      const GAP_V = 20
      const ry = cy + halfH + GAP_V + lbh / 2
      return [
        { x: cx + halfW + GAP_H, y: cy + ay },
        { x: cx + halfW + GAP_H, y: ry      },
        { x: cx - halfW - GAP_H, y: ry      },
        { x: cx - halfW - GAP_H, y: cy + ay },
      ]
    }
  }
}

/** Returns which side of the node (at cx, cy) the point (px, py) is closest to. */
function nearestSide(cx, cy, px, py) {
  return Math.abs(px - cx) >= Math.abs(py - cy)
    ? (px >= cx ? 'right' : 'left')
    : (py >= cy ? 'bottom' : 'top')
}

/**
 * Virtual waypoints injected automatically when no user waypoints exist.
 *
 * Self-loop: four waypoints forming a rectangular loop on the side determined by
 * selfLoopIndex (0=right, 1=bottom, 2=left, 3=top, cycling). Multiple self-loops
 * on the same node are spread to different sides. The user can override by dragging
 * a waypoint — that writes real waypoints, which take precedence over virtual ones.
 *
 * Parallel edges: two waypoints offset perpendicularly so bundled edges fan out and
 * their labels sit on a centred, horizontal segment.
 *
 * Manual waypoints always take precedence over virtual ones.
 */
const virtualWaypoints = computed(() => {
  if (isSelfLoop.value) {
    if (waypoints.value.length > 0) return null
    const geo = nodeGeometry.value
    if (!geo) return null
    const side = LOOP_SIDES[(props.data?.selfLoopIndex ?? 0) % 4]
    return loopWaypointsForSide(side, geo.scx, geo.scy, geo.sw, geo.sh)
  }
  const offset = props.data?.parallelOffset ?? 0
  if (offset === 0 || waypoints.value.length > 0) return null
  const geo = nodeGeometry.value
  if (!geo) return null
  const dx = geo.tcx - geo.scx
  const dy = geo.tcy - geo.scy
  const len = Math.sqrt(dx * dx + dy * dy)
  if (len < 1) return null
  // Right-hand perpendicular in screen space (y-axis points down)
  const px =  dy / len
  const py = -dx / len
  const ox = px * offset
  const oy = py * offset
  return [
    { x: geo.scx + dx / 3 + ox, y: geo.scy + dy / 3 + oy },
    { x: geo.scx + dx * 2 / 3 + ox, y: geo.scy + dy * 2 / 3 + oy }
  ]
})

/**
 * Computes start/end points on node borders.
 * Direction respects user waypoints first, then the auto virtual midpoint.
 * Without waypoints, an overlap-aware fallback prefers level horizontal or
 * vertical edges when the two node bounding boxes share an axis range — this
 * lets the user level an edge by moving boxes so their extents overlap.
 */
const edgeEndpoints = computed(() => {
  const geo = nodeGeometry.value
  if (!geo) {
    return {
      start: { x: props.sourceX, y: props.sourceY },
      end:   { x: props.targetX, y: props.targetY }
    }
  }
  const { scx, scy, sw, sh, tcx, tcy, tw, th } = geo
  const vwp = virtualWaypoints.value

  if (waypoints.value.length > 0 || vwp) {
    // Self-loops always use orthogonal border attachment: identify which side the arc
    // is on from WP1's position relative to the node bounding box, then clamp the
    // start/end to the matching border. Applies to both virtual and user waypoints so
    // dragging the badge never produces diagonal exit segments.
    if (isSelfLoop.value) {
      const pts    = waypoints.value.length > 0 ? waypoints.value : vwp
      const first  = pts[0]
      const last   = pts[pts.length - 1]
      const top    = scy - sh / 2
      const bottom = scy + sh / 2
      const right  = scx + sw / 2
      const left   = scx - sw / 2

      // 4-waypoint rectangular format: WP1 and WP4 share x (right/left loops) or y
      // (top/bottom loops), and that coordinate falls inside the node bounds.
      // Route start/end to the correct opposite faces of the node.
      const sameX = Math.abs(first.x - last.x) < 1
      const sameY = Math.abs(first.y - last.y) < 1
      if (sameX && first.x > left && first.x < right) {
        return first.y < scy
          ? { start: { x: first.x, y: top    }, end: { x: last.x, y: bottom } }  // right loop
          : { start: { x: first.x, y: bottom }, end: { x: last.x, y: top    } }  // left loop
      }
      if (sameY && first.y > top && first.y < bottom) {
        return first.x < scx
          ? { start: { x: left,  y: first.y }, end: { x: right, y: last.y } }  // top loop
          : { start: { x: right, y: first.y }, end: { x: left,  y: last.y } }  // bottom loop
      }

      // Legacy 2-waypoint fallback for old user-saved layouts
      if (Math.abs(first.x - last.x) < Math.abs(first.y - last.y)) {
        return first.x > scx
          ? { start: { x: right, y: first.y }, end: { x: right, y: last.y  } }
          : { start: { x: left,  y: first.y }, end: { x: left,  y: last.y  } }
      } else {
        return first.y < scy
          ? { start: { x: first.x, y: top    }, end: { x: last.x, y: top    } }
          : { start: { x: first.x, y: bottom }, end: { x: last.x, y: bottom } }
      }
    }
    const firstPt = waypoints.value[0]  ?? vwp[0]
    const lastPt  = waypoints.value[waypoints.value.length - 1] ?? vwp[vwp.length - 1]
    return {
      start: bboxEdgePoint(scx, scy, sw, sh, firstPt.x, firstPt.y),
      end:   bboxEdgePoint(tcx, tcy, tw, th, lastPt.x,  lastPt.y)
    }
  }

  const sLeft = scx - sw / 2, sRight = scx + sw / 2
  const sTop  = scy - sh / 2, sBot   = scy + sh / 2
  const tLeft = tcx - tw / 2, tRight = tcx + tw / 2
  const tTop  = tcy - th / 2, tBot   = tcy + th / 2

  const yOverlap = Math.min(sBot, tBot) - Math.max(sTop, tTop)
  const xOverlap = Math.min(sRight, tRight) - Math.max(sLeft, tLeft)
  const dx = tcx - scx
  const dy = tcy - scy

  if (yOverlap > 0 && Math.abs(dx) >= Math.abs(dy)) {
    const y = (Math.max(sTop, tTop) + Math.min(sBot, tBot)) / 2
    const sx = dx >= 0 ? sRight : sLeft
    const ex = dx >= 0 ? tLeft  : tRight
    return { start: { x: sx, y }, end: { x: ex, y } }
  }

  if (xOverlap > 0 && Math.abs(dy) > Math.abs(dx)) {
    const x = (Math.max(sLeft, tLeft) + Math.min(sRight, tRight)) / 2
    const sy = dy >= 0 ? sBot : sTop
    const ey = dy >= 0 ? tTop : tBot
    return { start: { x, y: sy }, end: { x, y: ey } }
  }

  return {
    start: bboxEdgePoint(scx, scy, sw, sh, tcx, tcy),
    end:   bboxEdgePoint(tcx, tcy, tw, th, scx, scy)
  }
})

const allPoints = computed(() => {
  const { start, end } = edgeEndpoints.value
  if (waypoints.value.length > 0)    return [start, ...waypoints.value, end]
  if (virtualWaypoints.value)        return [start, ...virtualWaypoints.value, end]
  return [start, end]
})

const svgPoints = computed(() =>
  allPoints.value.map(p => `${p.x},${p.y}`).join(' ')
)

// Points with the final segment shortened by 9 px so neither the line nor the
// arrowhead tip lands inside the target node's HTML element (which Vue Flow
// renders above the SVG layer). Both the visible line and the arrowhead tip
// use this same endpoint so there is no gap between line-end and arrowhead.
const trimmedPoints = computed(() => {
  const pts = allPoints.value
  if (pts.length < 2) return pts
  const last = pts[pts.length - 1]
  const prev = pts[pts.length - 2]
  const dx = last.x - prev.x
  const dy = last.y - prev.y
  const len = Math.sqrt(dx * dx + dy * dy)
  if (len <= 12) return pts
  return [...pts.slice(0, -1), {
    x: last.x - dx * 2 / len,
    y: last.y - dy * 2 / len
  }]
})

const svgPointsShortened = computed(() =>
  trimmedPoints.value.map(p => `${p.x},${p.y}`).join(' ')
)

// Arrowhead polygon — tip matches the trimmed line endpoint so the arrowhead
// is always in SVG space and never occluded by the target node.
const arrowPolygon = computed(() => {
  const pts = trimmedPoints.value
  if (pts.length < 2) return null
  const tip  = pts[pts.length - 1]
  const prev = pts[pts.length - 2]
  const dx = tip.x - prev.x
  const dy = tip.y - prev.y
  const len = Math.sqrt(dx * dx + dy * dy)
  if (len < 1) return null
  const nx = dx / len
  const ny = dy / len
  const size  = 10
  const theta = Math.PI / 5  // 36° half-angle
  const cos   = Math.cos(theta)
  const sin   = Math.sin(theta)
  const lx = tip.x - size * (nx * cos + ny * sin)
  const ly = tip.y - size * (ny * cos - nx * sin)
  const rx = tip.x - size * (nx * cos - ny * sin)
  const ry = tip.y - size * (ny * cos + nx * sin)
  return `${tip.x},${tip.y} ${lx},${ly} ${rx},${ry}`
})

// Label midpoint of the middle segment
const labelPos = computed(() => {
  const pts = allPoints.value
  if (pts.length < 2) return { x: 0, y: 0 }
  const mid = Math.floor((pts.length - 1) / 2)
  const a = pts[mid]
  const b = pts[mid + 1]
  return { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 }
})

const labelBgWidth = computed(() => {
  const nameWidth = (props.data?.label?.length ?? 4) * 7.5 + 12
  const propWidth = annotatedDisplayProps.value.length > 0
    ? Math.max(...annotatedDisplayProps.value.map(p => p.name.length * 6.5 + 12))
    : 0
  return Math.max(nameWidth, propWidth)
})

const labelBgHeight = computed(() => {
  const n = annotatedDisplayProps.value.length
  return n > 0 ? 18 + n * 16 + 4 : 18
})

// Exact segment angle clamped to (-90°, 90°] — text never appears upside-down
const labelAngle = computed(() => {
  const pts = allPoints.value
  if (pts.length < 2) return 0
  const mid = Math.floor((pts.length - 1) / 2)
  const dx = pts[mid + 1].x - pts[mid].x
  const dy = pts[mid + 1].y - pts[mid].y
  let angle = Math.atan2(dy, dx) * 180 / Math.PI
  if (angle > 90)  angle -= 180
  if (angle < -90) angle += 180
  return angle
})

// Midpoints of each segment, used as insertion handles
const segmentMidpoints = computed(() => {
  const pts = allPoints.value
  const result = []
  for (let i = 0; i < pts.length - 1; i++) {
    result.push({
      x: (pts[i].x + pts[i + 1].x) / 2,
      y: (pts[i].y + pts[i + 1].y) / 2
    })
  }
  return result
})

function insertAndDrag(segmentIndex, position, event) {
  const updated = [...waypoints.value]
  updated.splice(segmentIndex, 0, { x: position.x, y: position.y })
  setWaypoints?.(props.id, updated)
  startWaypointDrag(segmentIndex, event)
}

function removeWaypoint(index) {
  const updated = [...waypoints.value]
  updated.splice(index, 1)
  setWaypoints?.(props.id, updated)
}

function startWaypointDrag(index, event) {
  event.preventDefault()

  function onMouseMove(e) {
    const pos = screenToFlowCoordinate({ x: e.clientX, y: e.clientY })
    const updated = [...waypoints.value]
    updated[index] = { x: pos.x, y: pos.y }
    setWaypoints?.(props.id, updated)
  }

  function onMouseUp() {
    window.removeEventListener('mousemove', onMouseMove)
    window.removeEventListener('mouseup', onMouseUp)
  }

  window.addEventListener('mousemove', onMouseMove)
  window.addEventListener('mouseup', onMouseUp)
}

const isDraggingLoop = ref(false)

/**
 * Intercepts pointerdown on the badge for self-loops. Uses pointer events (not
 * mouse events) so it fires before Vue Flow's canvas-pan handler, which also
 * uses pointerdown.
 */
function onBadgePointerDown(event) {
  if (!isSelfLoop.value) return
  event.stopPropagation()
  startLoopDrag(event)
}

/**
 * Drag handler for the self-loop badge. Updates the arc live as the mouse
 * moves so the user gets immediate feedback. On release the waypoints are
 * persisted (overriding the virtual defaults).
 *
 * Uses window mouse listeners — the same pattern as startWaypointDrag — so
 * events are reliably received regardless of which SVG element is under the
 * cursor during the drag.
 *
 * To reset to auto-distributed positioning, click "Reset routing" in the
 * annotation panel, or double-click each waypoint handle to remove it.
 */
function startLoopDrag(event) {
  isDraggingLoop.value = true

  function computeAndApply(e) {
    const geo = nodeGeometry.value
    if (!geo) return
    const flowPos = screenToFlowCoordinate({ x: e.clientX, y: e.clientY })
    const side = nearestSide(geo.scx, geo.scy, flowPos.x, flowPos.y)
    setWaypoints?.(props.id, loopWaypointsForSide(side, geo.scx, geo.scy, geo.sw, geo.sh))
  }

  function onMouseMove(e) {
    computeAndApply(e)
  }

  function onMouseUp(e) {
    window.removeEventListener('mousemove', onMouseMove)
    window.removeEventListener('mouseup', onMouseUp)
    isDraggingLoop.value = false
    computeAndApply(e)
  }

  window.addEventListener('mousemove', onMouseMove)
  window.addEventListener('mouseup', onMouseUp)
}
</script>

<style scoped>
.midpoint-handle {
  opacity: 0;
  cursor: crosshair;
  pointer-events: none;
  transition: opacity 0.15s;
}

.polyline-edge:hover .midpoint-handle {
  opacity: 1;
  pointer-events: all;
}

.waypoint-handle {
  cursor: move;
  pointer-events: all;
}

.edge-hitarea {
  cursor: pointer;
  pointer-events: stroke;
}

.edge-badge {
  pointer-events: all;
}

.edge-label-text {
  pointer-events: none;
  user-select: none;
  font-family: var(--font-mono, monospace);
}
</style>
