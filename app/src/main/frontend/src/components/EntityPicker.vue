<template>
  <aside class="picker-panel" :class="{ disabled: !activeView }">
    <Tabs v-model:value="pickerTab" class="picker-tabs">
      <TabList>
        <Tab value="labels">Labels</Tab>
        <Tab value="rels">Rels</Tab>
      </TabList>
    </Tabs>

    <div class="picker-search">
      <InputText v-model="pickerSearch" placeholder="Filter…" size="small" fluid />
    </div>

    <div class="scroll-list picker-list">
      <template v-if="pickerTab === 'labels'">
        <div
          v-for="label in filteredLabels"
          :key="label.name"
          class="picker-item"
          :class="{ 'in-view': activeView?.labels.includes(label.name) }"
          :draggable="!!activeView"
          @dragstart="onLabelDragStart($event, label.name)"
          @click="toggleLabel(label.name)"
        >
          <span class="picker-name">{{ label.name }}</span>
          <span v-if="label.role && label.role !== 'ENTITY'" class="chip chip-tag">{{ label.role }}</span>
          <span class="picker-count" :title="label.nodeCount?.toLocaleString()">{{ formatCount(label.nodeCount) }}</span>
        </div>
        <div v-if="filteredLabels.length === 0" class="empty-hint">No labels</div>
      </template>

      <template v-else>
        <div
          v-for="rel in filteredRels"
          :key="rel.name"
          class="picker-item"
          :class="{ 'in-view': activeView?.relationshipTypes.includes(rel.name) }"
          @click="toggleRelationship(rel.name)"
        >
          <span class="picker-name">{{ relDisplayName(rel) }}</span>
          <span class="picker-count" :title="rel.count?.toLocaleString()">{{ formatCount(rel.count) }}</span>
        </div>
        <div v-if="filteredRels.length === 0" class="empty-hint">No relationships</div>
      </template>
    </div>
  </aside>
</template>

<script setup>
import { ref, computed } from 'vue'
import InputText from 'primevue/inputtext'
import Tabs from 'primevue/tabs'
import TabList from 'primevue/tablist'
import Tab from 'primevue/tab'
import { useSchemaStore } from '../stores/schema.js'
import { formatCount, relDisplayName } from '../utils/format.js'

const props = defineProps({
  activeView: { type: Object, default: null }
})

const emit = defineEmits(['label-added'])

const store = useSchemaStore()

const pickerTab    = ref('labels')
const pickerSearch = ref('')

const filteredLabels = computed(() => {
  const q = pickerSearch.value.toLowerCase()
  return store.labels.filter(l => !q || l.name.toLowerCase().includes(q))
})

const filteredRels = computed(() => {
  const q = pickerSearch.value.toLowerCase()
  return store.relationshipTypes.filter(r => !q || r.name.toLowerCase().includes(q))
})

function toggleLabel(labelName) {
  if (!props.activeView) return
  const idx = props.activeView.labels.indexOf(labelName)
  if (idx === -1) {
    props.activeView.labels.push(labelName)
    autoSelectRels()
    emit('label-added', labelName)
  } else {
    props.activeView.labels.splice(idx, 1)
    autoDeselectRels()
  }
  store.markEdited()
}

function toggleRelationship(relName) {
  if (!props.activeView) return
  const idx = props.activeView.relationshipTypes.indexOf(relName)
  if (idx === -1) {
    props.activeView.relationshipTypes.push(relName)
    const rel = store.relationshipTypes.find(r => r.name === relName)
    if (rel) {
      for (const conn of (rel.connections ?? [])) {
        for (const labelName of [...conn.startLabels, ...conn.endLabels]) {
          if (!props.activeView.labels.includes(labelName)) {
            props.activeView.labels.push(labelName)
          }
        }
      }
    }
  } else {
    props.activeView.relationshipTypes.splice(idx, 1)
  }
  store.markEdited()
}

function autoSelectRels() {
  const labelSet = new Set(props.activeView.labels)
  for (const rel of store.relationshipTypes) {
    if (props.activeView.relationshipTypes.includes(rel.name)) continue
    if ((rel.connections ?? []).some(conn =>
        conn.startLabels.some(startLabel => labelSet.has(startLabel)) &&
        conn.endLabels.some(endLabel => labelSet.has(endLabel)))) {
      props.activeView.relationshipTypes.push(rel.name)
    }
  }
}

function autoDeselectRels() {
  const labelSet = new Set(props.activeView.labels)
  props.activeView.relationshipTypes = props.activeView.relationshipTypes.filter(relName => {
    const rel = store.relationshipTypes.find(r => r.name === relName)
    return rel && (rel.connections ?? []).some(conn =>
        conn.startLabels.some(startLabel => labelSet.has(startLabel)) &&
        conn.endLabels.some(endLabel => labelSet.has(endLabel)))
  })
}

function onLabelDragStart(event, labelName) {
  event.dataTransfer.setData('text/plain', 'LABEL:' + labelName)
  event.dataTransfer.effectAllowed = 'copy'
}
</script>

<style scoped>
.picker-panel.disabled {
  opacity: 0.45;
  pointer-events: none;
}

.picker-panel {
  width: 200px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  border-right: 1px solid var(--border);
  background: var(--bg-surface);
  overflow: hidden;
}

.picker-tabs {
  flex-shrink: 0;
}

.picker-tabs :deep(.p-tablist) {
  background: var(--bg-surface);
  border-bottom: 1px solid var(--border);
}

.picker-tabs :deep(.p-tab) {
  flex: 1;
  justify-content: center;
  font-size: 12px;
  padding: 8px 4px;
}

.picker-search {
  padding: 8px;
  border-bottom: 1px solid var(--border);
  flex-shrink: 0;
}

.picker-list {
  flex: 1;
  overflow-y: auto;
}

.picker-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 7px 10px;
  cursor: pointer;
  border-bottom: 1px solid var(--border);
  transition: background 0.1s;
}

.picker-item:last-child {
  border-bottom: none;
}

.picker-item:hover {
  background: var(--bg-hover);
}

.picker-item.in-view {
  background: var(--bg-active);
}

.picker-item.in-view .picker-name {
  color: var(--text-primary);
}

.picker-item[draggable="true"] {
  cursor: grab;
}

.picker-item[draggable="true"]:active {
  cursor: grabbing;
}

.picker-name {
  flex: 1;
  font-size: 12px;
  font-weight: 600;
  font-family: var(--font-mono);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text-secondary);
}

.picker-count {
  font-size: 11px;
  color: var(--text-secondary);
  font-family: var(--font-mono);
}

.empty-hint {
  padding: 16px 12px;
  color: var(--text-muted);
  font-size: 12px;
  text-align: center;
}
</style>
