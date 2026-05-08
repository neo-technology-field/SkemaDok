<template>
  <div
    class="label-node"
    :class="{ 'is-tag': data.label.role === 'TAG', 'is-selected': selected, 'is-colored': !!data.label.color }"
    :style="colorStyle"
  >
    <div v-if="chipTags.length" class="tag-chips">
      <span v-for="tag in chipTags" :key="tag" class="tag-chip">{{ tag }}</span>
    </div>

    <div class="node-header">
      <span class="node-name">{{ data.label.name }}</span>
      <span v-if="data.label.role && data.label.role !== 'ENTITY' && data.label.role !== 'HIERARCHY'" class="role-badge" :class="data.label.role?.toLowerCase()">
        {{ data.label.role }}
      </span>
      <span class="header-sep" />
      <span class="node-count" :title="data.label.nodeCount?.toLocaleString()">{{ formatCount(data.label.nodeCount) }}</span>
    </div>

    <div v-if="displayProps.length" class="node-props">
      <div v-for="prop in displayProps" :key="prop.name" class="node-prop">
        <span class="prop-star">*</span>
        <span class="prop-name" :class="prop.marker">{{ prop.name }}</span>
      </div>
    </div>

    <!-- Invisible handles on all four sides so edges can connect from any direction -->
    <Handle id="t" type="target" :position="Position.Top"    class="node-handle" />
    <Handle id="r" type="source" :position="Position.Right"  class="node-handle" />
    <Handle id="b" type="source" :position="Position.Bottom" class="node-handle" />
    <Handle id="l" type="target" :position="Position.Left"   class="node-handle" />
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { Handle, Position } from '@vue-flow/core'
import { formatCount } from '../utils/format.js'

const props = defineProps({
  data:     { type: Object,  required: true },
  selected: { type: Boolean, default: false }
})

const displayProps = computed(() =>
  props.data.annotatedDisplayProps
    ?? (props.data.label.displayProperties ?? []).map(n => ({ name: n, marker: null }))
)
const chipTags     = computed(() => props.data.chipTags ?? [])


function luminance(hex) {
  const r = parseInt(hex.slice(1, 3), 16) / 255
  const g = parseInt(hex.slice(3, 5), 16) / 255
  const b = parseInt(hex.slice(5, 7), 16) / 255
  return 0.299 * r + 0.587 * g + 0.114 * b
}

const colorStyle = computed(() => {
  const color = props.data.label.color
  if (!color) return {}
  const lum = luminance(color)
  // Switch all theme-dependent vars based on background luminance so chips and
  // borders remain readable regardless of which custom colour is chosen.
  return {
    backgroundColor:   color,
    '--text-primary':  lum > 0.5 ? '#111827' : '#f3f4f6',
    '--text-muted':    lum > 0.5 ? '#4b5563' : '#9ca3af',
    '--sep-color':     lum > 0.5 ? 'rgba(0,0,0,0.25)' : 'rgba(255,255,255,0.25)',
    '--violet':        lum > 0.5 ? '#6639ba' : '#c084fc',
    '--violet-dim':    lum > 0.5 ? 'rgba(102,57,186,0.18)' : 'rgba(192,132,252,0.18)',
    '--violet-border': lum > 0.5 ? 'rgba(102,57,186,0.55)' : 'rgba(192,132,252,0.55)',
  }
})
</script>

<style scoped>
.tag-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  padding: 5px 10px 4px 12px;
  border-bottom: 1px solid var(--violet-border, rgba(192, 132, 252, 0.38));
  background: var(--violet-dim, rgba(192, 132, 252, 0.08));
}

.label-node.is-colored .tag-chips {
  padding-left: 10px;
}

.tag-chip {
  font-size: 9px;
  font-weight: 700;
  font-family: var(--font-mono, monospace);
  letter-spacing: 0.05em;
  color: var(--violet, #c084fc);
  border: 1px solid var(--violet-border, rgba(192, 132, 252, 0.38));
  border-radius: 4px;
  padding: 1px 5px;
  white-space: nowrap;
}

.label-node {
  min-width: 160px;
  max-width: 260px;
  min-height: 40px;
  border-radius: 6px;
  background: var(--bg-elevated, #2d2f4a);
  border: 1.5px solid var(--border-strong, #52569e);
  border-left: 4px solid var(--accent-bright, #a5b4fc);
  cursor: default;
  user-select: none;
  position: relative;
  transition: border-color 0.15s, box-shadow 0.15s;
  overflow: hidden;
}

.label-node.is-colored {
  border-left-width: 1.5px;
  border-left-color: var(--border-strong, #52569e);
}

.label-node.is-selected {
  border-color: var(--accent-bright, #a5b4fc);
  box-shadow: 0 0 0 3px rgba(129, 140, 248, 0.25);
}

.label-node.is-tag {
  border-style: dashed;
  border-color: var(--violet, #c084fc);
}

.label-node.is-tag.is-selected {
  border-color: var(--violet, #c084fc);
  box-shadow: 0 0 0 3px rgba(192, 132, 252, 0.25);
}

.node-header {
  display: flex;
  align-items: center;
  padding: 8px 10px 8px 12px;
}

.label-node.is-colored .node-header {
  padding-left: 10px;
}

.node-name {
  font-size: 13px;
  font-weight: 700;
  color: var(--text-primary, #eaeefa);
  font-family: var(--font-mono, monospace);
  letter-spacing: -0.01em;
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.role-badge {
  font-size: 9px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  padding: 1px 5px;
  border-radius: 3px;
  font-family: var(--font-mono, monospace);
  margin-right: 4px;
  flex-shrink: 0;
}

.role-badge.tag {
  background: var(--violet-dim, rgba(192, 132, 252, 0.14));
  color: var(--violet, #c084fc);
}

.header-sep {
  width: 0;
  align-self: stretch;
  border-left: 1px dashed var(--sep-color, var(--border-strong, #52569e));
  margin: 0 8px;
  flex-shrink: 0;
}

.node-count {
  font-size: 11px;
  color: var(--text-muted, #545880);
  font-family: var(--font-mono, monospace);
  flex-shrink: 0;
}

.node-props {
  border-top: 1px solid var(--sep-color, var(--border-strong, #52569e));
  padding: 6px 10px 8px 12px;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.label-node.is-colored .node-props {
  padding-left: 10px;
}

.node-prop {
  display: flex;
  align-items: center;
  gap: 5px;
  height: 18px;
}

.prop-star {
  font-size: 10px;
  color: var(--text-muted, #545880);
  font-family: var(--font-mono, monospace);
  flex-shrink: 0;
}

.prop-name {
  font-size: 11px;
  color: var(--text-primary, #eaeefa);
  font-family: var(--font-mono, monospace);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.prop-name.constrained {
  text-decoration: underline solid;
  text-underline-offset: 2px;
}

.prop-name.indexed {
  text-decoration: underline dotted;
  text-underline-offset: 2px;
}

:deep(.node-handle) {
  opacity: 0;
  pointer-events: none;
  width: 8px;
  height: 8px;
}
</style>
