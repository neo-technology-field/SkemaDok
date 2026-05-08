<template>
  <aside class="view-list-panel">
    <div class="panel-header" style="display: flex; align-items: center; justify-content: space-between;">
      Views
      <Button icon="pi pi-plus" text rounded size="small" @click="startCreate" />
    </div>

    <div v-if="creating" class="create-form">
      <InputText
        ref="createInput"
        v-model="newViewName"
        placeholder="View name"
        size="small"
        fluid
        @keyup.enter="confirmCreate"
        @keyup.escape="creating = false"
      />
      <div style="display: flex; gap: 6px; margin-top: 6px;">
        <Button label="Create" size="small" @click="confirmCreate" />
        <Button label="Cancel" text size="small" @click="creating = false" />
      </div>
    </div>

    <div class="scroll-list">
      <div
        v-for="view in store.views"
        :key="view.name"
        class="list-item"
        :class="{ selected: activeView?.name === view.name }"
        @click="emit('select', view)"
        @dblclick.stop="startRename(view)"
      >
        <template v-if="renamingView === view">
          <InputText
            ref="renameInput"
            v-model="renameValue"
            size="small"
            class="rename-input"
            @keyup.enter="confirmRename"
            @keyup.escape="cancelRename"
            @blur="confirmRename"
            @click.stop
          />
        </template>
        <template v-else>
          <span class="list-item-label">{{ view.name }}</span>
          <span class="list-item-meta">{{ view.labels.length }}L</span>
        </template>
      </div>
      <div v-if="store.views.length === 0" class="empty-hint">
        No views yet.
      </div>
    </div>

    <div v-if="activeView" class="view-actions">
      <Button label="Edit description" outlined size="small" fluid @click="openDialog" />
      <Button label="Delete view" severity="danger" outlined size="small" fluid @click="deleteActiveView" />
    </div>

    <Dialog
      v-model:visible="dialogVisible"
      :header="activeView?.name"
      :modal="false"
      :draggable="true"
      :keepInViewport="true"
      style="width: 600px"
    >
      <Textarea
        v-model="editDescription"
        placeholder="Describe this view…"
        autoResize
        rows="12"
        style="width: 100%"
      />
      <template #footer>
        <Button label="Cancel" text size="small" @click="cancelDialog" />
        <Button label="Save" size="small" @click="saveDescription" />
      </template>
    </Dialog>
  </aside>
</template>

<script setup>
import { ref, nextTick, watch } from 'vue'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Textarea from 'primevue/textarea'
import Dialog from 'primevue/dialog'
import { useSchemaStore } from '../stores/schema.js'

const props = defineProps({
  activeView: { type: Object, default: null }
})

const emit = defineEmits(['select', 'deleted'])

const store = useSchemaStore()

const creating    = ref(false)
const newViewName = ref('')
const createInput = ref(null)

const renamingView = ref(null)
const renameValue  = ref('')
const renameInput  = ref(null)

const dialogVisible   = ref(false)
const editDescription = ref('')

watch(() => props.activeView, () => {
  dialogVisible.value = false
})

function openDialog() {
  editDescription.value = props.activeView?.description ?? ''
  dialogVisible.value = true
}

function saveDescription() {
  if (props.activeView) {
    props.activeView.description = editDescription.value
    store.markEdited()
  }
  dialogVisible.value = false
}

function cancelDialog() {
  dialogVisible.value = false
}

async function startCreate() {
  creating.value = true
  newViewName.value = ''
  await nextTick()
  createInput.value?.$el?.focus()
}

function confirmCreate() {
  const name = newViewName.value.trim()
  if (!name) return
  const created = { name, description: '', labels: [], relationshipTypes: [] }
  store.document.views.push(created)
  store.markEdited()
  creating.value = false
  emit('select', created)
}

async function startRename(view) {
  renamingView.value = view
  renameValue.value  = view.name
  await nextTick()
  // renameInput is an array when used inside v-for
  const el = Array.isArray(renameInput.value) ? renameInput.value[0] : renameInput.value
  el?.$el?.focus()
  el?.$el?.select()
}

function confirmRename() {
  if (!renamingView.value) return
  const newName = renameValue.value.trim()
  const isDuplicate = newName !== renamingView.value.name &&
    store.views.some(v => v.name === newName)
  if (newName && !isDuplicate) {
    renamingView.value.name = newName
    store.markEdited()
  }
  renamingView.value = null
}

function cancelRename() {
  renamingView.value = null
}

function deleteActiveView() {
  if (!props.activeView) return
  if (!confirm(`Delete view "${props.activeView.name}"?`)) return
  store.document.views = store.document.views.filter(v => v.name !== props.activeView.name)
  store.markEdited()
  emit('deleted')
}
</script>

<style scoped>
.view-list-panel {
  width: 180px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  border-right: 1px solid var(--border);
  background: var(--bg-surface);
  overflow: hidden;
}

.view-list-panel .panel-header {
  flex-shrink: 0;
}

.create-form {
  padding: 10px 12px;
  border-bottom: 1px solid var(--border);
  flex-shrink: 0;
}

.view-actions {
  padding: 8px;
  border-top: 1px solid var(--border);
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.rename-input {
  flex: 1;
  font-size: 12px;
  height: 24px;
  padding: 2px 6px;
  min-width: 0;
}

.rename-input :deep(input) {
  font-size: 12px;
  font-family: var(--font-sans);
  height: 24px;
  padding: 2px 6px;
}

.empty-hint {
  padding: 16px 12px;
  color: var(--text-muted);
  font-size: 12px;
  text-align: center;
}
</style>
