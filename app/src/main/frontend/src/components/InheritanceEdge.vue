<!--
  Inheritance edge — rendered for label "extends" relationships.

  Visually distinct from relationship type edges:
  - Dashed stroke
  - Hollow open-triangle arrowhead (UML generalisation style)
  - Muted colour so it reads as structural metadata, not data flow

  Reuses the same bboxEdgePoint geometry as PolylineEdge but without
  waypoints — inheritance edges are always straight lines.
-->
<template>
  <g v-if="nodeGeometry" class="inheritance-edge">
    <line
      :x1="start.x" :y1="start.y"
      :x2="end.x"   :y2="end.y"
      :stroke="selected ? '#93c5fd' : '#6b7db3'"
      stroke-width="1.5"
      stroke-dasharray="6 4"
    />
    <polygon
      v-if="arrowPolygon"
      :points="arrowPolygon"
      fill="none"
      :stroke="selected ? '#93c5fd' : '#6b7db3'"
      stroke-width="1.5"
      stroke-linejoin="round"
    />
  </g>
</template>

<script setup>
import { computed } from 'vue'
import { useVueFlow } from '@vue-flow/core'

const props = defineProps({
  id:       { type: String,  required: true },
  source:   String,
  target:   String,
  sourceX:  { type: Number, required: true },
  sourceY:  { type: Number, required: true },
  targetX:  { type: Number, required: true },
  targetY:  { type: Number, required: true },
  selected: { type: Boolean, default: false }
})

const { findNode } = useVueFlow()

function bboxEdgePoint(cx, cy, w, h, tx, ty) {
  const dx = tx - cx
  const dy = ty - cy
  if (Math.abs(dx) < 0.01 && Math.abs(dy) < 0.01) return { x: cx, y: cy }
  const t = Math.min(
    (w / 2) / (Math.abs(dx) || Infinity),
    (h / 2) / (Math.abs(dy) || Infinity)
  )
  return { x: cx + dx * t, y: cy + dy * t }
}

const nodeGeometry = computed(() => {
  const src = findNode(props.source)
  const tgt = findNode(props.target)
  const sw = src?.data?.groupWidth  ?? src?.dimensions?.width
  const sh = src?.data?.groupHeight ?? src?.dimensions?.height
  const tw = tgt?.data?.groupWidth  ?? tgt?.dimensions?.width
  const th = tgt?.data?.groupHeight ?? tgt?.dimensions?.height
  if (!sw || !sh || !tw || !th) return null
  return {
    scx: src.position.x + sw / 2,
    scy: src.position.y + sh / 2,
    sw, sh,
    tcx: tgt.position.x + tw / 2,
    tcy: tgt.position.y + th / 2,
    tw, th
  }
})

// Overlap-aware endpoints: when the two boxes share an axis range, the edge
// runs level (horizontal or vertical) through the overlap. Falls back to the
// centre-to-centre ray clip when boxes are diagonally separated.
const endpoints = computed(() => {
  const g = nodeGeometry.value
  if (!g) {
    return {
      start: { x: props.sourceX, y: props.sourceY },
      end:   { x: props.targetX, y: props.targetY }
    }
  }
  const sLeft = g.scx - g.sw / 2, sRight = g.scx + g.sw / 2
  const sTop  = g.scy - g.sh / 2, sBot   = g.scy + g.sh / 2
  const tLeft = g.tcx - g.tw / 2, tRight = g.tcx + g.tw / 2
  const tTop  = g.tcy - g.th / 2, tBot   = g.tcy + g.th / 2

  const yOverlap = Math.min(sBot, tBot) - Math.max(sTop, tTop)
  const xOverlap = Math.min(sRight, tRight) - Math.max(sLeft, tLeft)
  const dx = g.tcx - g.scx
  const dy = g.tcy - g.scy

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
    start: bboxEdgePoint(g.scx, g.scy, g.sw, g.sh, g.tcx, g.tcy),
    end:   bboxEdgePoint(g.tcx, g.tcy, g.tw, g.th, g.scx, g.scy)
  }
})

const start = computed(() => endpoints.value.start)
const end   = computed(() => endpoints.value.end)

// Hollow open triangle — UML generalisation arrowhead
const arrowPolygon = computed(() => {
  const s = start.value
  const e = end.value
  const dx = e.x - s.x
  const dy = e.y - s.y
  const len = Math.sqrt(dx * dx + dy * dy)
  if (len < 1) return null
  const nx = dx / len
  const ny = dy / len
  const size  = 12
  const theta = Math.PI / 6   // 30° half-angle → slimmer than filled arrow
  const cos   = Math.cos(theta)
  const sin   = Math.sin(theta)
  const lx = e.x - size * (nx * cos + ny * sin)
  const ly = e.y - size * (ny * cos - nx * sin)
  const rx = e.x - size * (nx * cos - ny * sin)
  const ry = e.y - size * (ny * cos + nx * sin)
  return `${e.x},${e.y} ${lx},${ly} ${rx},${ry}`
})
</script>
