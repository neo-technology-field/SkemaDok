<template>
  <div class="views-layout">

    <ViewListPanel
      :active-view="activeView"
      @select="selectView"
      @deleted="activeView = store.views[0] ?? null"
    />

    <EntityPicker :active-view="activeView" @label-added="onLabelAddedFromPicker" />

    <div
      class="canvas-area"
      @dragover.prevent
      @drop="onCanvasDrop"
    >
      <SchemaGraph
        v-if="activeView"
        ref="graphRef"
        :key="activeView.name"
        :view="activeView"
        @select="onEntitySelect"
      />
      <div v-else class="canvas-hint">
        <span v-if="store.views.length === 0">Create a view to get started</span>
        <span v-else>Select a view</span>
      </div>
    </div>

    <AnnotationPanel
      v-if="selectedEntity"
      :entity="selectedEntity"
      :is-label="selectedIsLabel"
      :reset-routing="relName => graphRef?.clearRelWaypoints(relName)"
      @close="selectedEntity = null"
    />

  </div>
</template>

<script setup>
import { ref, watch, nextTick } from 'vue'
import { useSchemaStore } from '../stores/schema.js'
import ViewListPanel  from '../components/ViewListPanel.vue'
import EntityPicker   from '../components/EntityPicker.vue'
import SchemaGraph    from '../components/SchemaGraph.vue'
import AnnotationPanel from '../components/AnnotationPanel.vue'

const store = useSchemaStore()

const activeView      = ref(store.views[0] ?? null)
const selectedEntity  = ref(null)
const selectedIsLabel = ref(true)
const graphRef        = ref(null)

// Re-sync reactive references when the store document is replaced (e.g. after Restore).
watch(() => store.document, () => {
  if (activeView.value) {
    activeView.value = store.views.find(v => v.name === activeView.value.name) ?? null
  }
  if (selectedEntity.value) {
    const name = selectedEntity.value.name
    selectedEntity.value = selectedIsLabel.value
      ? (store.labels.find(l => l.name === name) ?? null)
      : (store.relationshipTypes.find(r => r.name === name) ?? null)
  }
})

function selectView(view) {
  activeView.value     = view
  selectedEntity.value = null
}

function onEntitySelect(selection) {
  if (!selection) {
    selectedEntity.value = null
    return
  }
  selectedEntity.value  = selection.entity
  selectedIsLabel.value = selection.isLabel
}

function onLabelAddedFromPicker(labelName) {
  const label = store.labels.find(l => l.name === labelName)
  if (label) {
    selectedEntity.value  = label
    selectedIsLabel.value = true
    nextTick(() => graphRef.value?.selectNode(labelName))
  }
}

function onCanvasDrop(event) {
  if (!activeView.value) return
  const raw = event.dataTransfer.getData('text/plain')
  if (!raw) return
  const [type, name] = raw.split(':')
  if (type === 'LABEL') {
    const isNew = !activeView.value.labels.includes(name)
    graphRef.value?.addLabelToView(name)
    if (isNew) {
      // Auto-add rels whose both endpoints are now in the view.
      // labelSet is built after addLabelToView so it includes the new label.
      const labelSet = new Set(activeView.value.labels)
      for (const rel of store.relationshipTypes) {
        if (activeView.value.relationshipTypes.includes(rel.name)) continue
        if ((rel.connections ?? []).some(conn =>
            conn.startLabels.some(startLabel => labelSet.has(startLabel)) &&
            conn.endLabels.some(endLabel => labelSet.has(endLabel)))) {
          activeView.value.relationshipTypes.push(rel.name)
        }
      }
      store.markEdited()
      const label = store.labels.find(l => l.name === name)
      if (label) {
        selectedEntity.value  = label
        selectedIsLabel.value = true
        nextTick(() => graphRef.value?.selectNode(name))
      }
    }
  }
}
</script>

<style scoped>
.views-layout {
  display: flex;
  width: 100%;
  height: 100%;
  overflow: hidden;
  background: var(--bg-base);
}

.canvas-area {
  flex: 1;
  position: relative;
  overflow: hidden;
}

.canvas-hint {
  display: flex;
  height: 100%;
  align-items: center;
  justify-content: center;
  color: var(--text-muted);
  font-size: 14px;
}
</style>
