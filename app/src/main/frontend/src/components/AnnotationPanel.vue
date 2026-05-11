<template>
  <aside class="annotation-panel">
    <div class="annotation-header">
      <div class="annotation-name">{{ isLabel ? entity.name : relDisplayName(entity) }}</div>
      <div class="annotation-meta">
        <span v-if="isLabel && entity.role && entity.role !== 'ENTITY'" class="chip chip-tag">
          {{ entity.role }}
        </span>
        <span class="entity-count">
          {{ isLabel
            ? formatCount(entity.nodeCount) + ' nodes'
            : formatCount(entity.count) + ' rels'
          }}
        </span>
      </div>
      <Button
        icon="pi pi-times"
        text
        rounded
        size="small"
        class="annotation-close"
        @click="emit('close')"
      />
    </div>

    <div v-if="!isLabel && entity.connections?.length" class="annotation-section">
      <div class="section-label">Connects</div>
      <div class="connectivity">
        <div v-for="(conn, idx) in entity.connections" :key="idx" class="conn-pair">
          <span v-for="label in conn.startLabels" :key="label" class="label-chip">{{ label }}</span>
          <span class="conn-arrow">→</span>
          <span v-for="label in conn.endLabels" :key="label" class="label-chip">{{ label }}</span>
        </div>
      </div>
      <Button
        v-if="resetRouting"
        label="Reset routing"
        icon="pi pi-undo"
        size="small"
        severity="secondary"
        text
        class="reset-routing-btn"
        @click="resetRouting(entity.name)"
      />
    </div>

    <div class="annotation-section">
      <div class="section-label">Description</div>
      <Textarea
        v-model="editDescription"
        placeholder="Describe this entity…"
        autoResize
        rows="3"
        @blur="saveDescription"
      />
    </div>

    <div class="annotation-section">
      <div class="section-label">Data source</div>
      <InputText
        v-model="editDataSource"
        placeholder="e.g. Salesforce, Legacy ERP…"
        size="small"
        fluid
        @blur="saveDataSource"
      />
    </div>

    <div v-if="isLabel" class="annotation-section">
      <div class="section-label">Role</div>
      <Select
        v-model="editRole"
        :options="roleOptions"
        optionLabel="label"
        optionValue="value"
        :optionDisabled="opt => opt.disabled"
        size="small"
        fluid
        @change="saveRole"
      />
      <div v-if="!canBeTag && entity.role !== 'TAG'" class="section-hint">
        Cannot set to TAG — another TAG already uses this label as a tagged entity.
      </div>
    </div>

    <div v-if="isLabel" class="annotation-section">
      <div class="section-label">Subtype of</div>
      <Select
        v-model="editExtends"
        :options="extendsOptions"
        optionLabel="label"
        optionValue="value"
        placeholder="No parent label"
        size="small"
        fluid
        @change="saveExtends"
      />
    </div>

    <div v-if="isLabel && entity.role === 'TAG'" class="annotation-section">
      <div class="section-label">Tagged entities</div>
      <div class="section-hint">Which entity types nodes with this tag belong to</div>
      <MultiSelect
        v-model="editTaggedEntities"
        :options="parentEntityOptions"
        optionLabel="label"
        optionValue="value"
        placeholder="No entity selected"
        size="small"
        fluid
        display="chip"
        @change="saveTaggedEntities"
      />
    </div>

    <div v-if="isLabel" class="annotation-section">
      <div class="section-label">Colour</div>
      <div class="colour-palette">
        <button
          class="palette-clear"
          :class="{ active: !entity.color }"
          title="No colour"
          @click="saveColor(null)"
        />
        <button
          v-for="c in COLOR_PALETTE"
          :key="c"
          class="palette-swatch"
          :style="{ background: c }"
          :class="{ active: entity.color === c }"
          @click="saveColor(c)"
        />
        <label class="palette-custom" title="Custom colour">
          <input
            type="color"
            :value="customColorValue"
            @change="saveColor($event.target.value)"
          />
        </label>
      </div>
    </div>

    <div v-if="entity.properties?.length" class="annotation-section">
      <div class="section-label">Show in graph</div>
      <div class="prop-checks">
        <label
          v-for="prop in entity.properties"
          :key="prop.name"
          class="prop-check-row"
        >
          <input
            type="checkbox"
            :checked="isDisplayed(prop.name)"
            @change="toggleDisplayProperty(prop.name)"
          />
          <span class="prop-check-name">{{ prop.name }}</span>
        </label>
      </div>
    </div>

    <div v-if="entity.properties?.length" class="annotation-section">
      <div class="section-label">Properties</div>
      <div v-for="prop in entity.properties" :key="prop.name" class="prop-row">
        <div class="prop-header">
          <span class="prop-name">{{ prop.name }}</span>
          <span class="prop-type">{{ prop.types?.join(' | ') }}</span>
          <span v-if="!prop.nullable" class="prop-req">req</span>
        </div>
        <InputText
          v-model="prop.description"
          placeholder="Describe property…"
          size="small"
          fluid
          @blur="() => savePropDescription(prop, prop.description)"
        />
      </div>
    </div>
  </aside>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Textarea from 'primevue/textarea'
import Select from 'primevue/select'
import MultiSelect from 'primevue/multiselect'
import { useSchemaStore } from '../stores/schema.js'
import { formatCount, relDisplayName } from '../utils/format.js'

const props = defineProps({
  entity:       { type: Object,   required: true },
  isLabel:      { type: Boolean,  required: true },
  resetRouting: { type: Function, default: null }
})

const emit = defineEmits(['close'])

const store = useSchemaStore()

const COLOR_PALETTE = [
  '#2d6a4f', '#1d4e89', '#6b3d1e', '#6a1e6b',
  '#6b6b1e', '#1e6b6b', '#6b1e1e', '#3d4a5c',
  '#1e4a6b', '#4a3d6b'
]

const editDescription    = ref('')
const editDataSource     = ref('')
const editRole           = ref('ENTITY')
const editExtends        = ref(null)
const editTaggedEntities = ref([])

watch(() => props.entity, (entity) => {
  editDescription.value    = entity.description    ?? ''
  editDataSource.value     = entity.dataSource     ?? ''
  editRole.value           = entity.role           ?? 'ENTITY'
  editExtends.value        = entity.extends        ?? null
  editTaggedEntities.value = entity.taggedEntities ?? []
}, { immediate: true })

// A label referenced in another TAG's taggedEntities must remain ENTITY —
// promoting it to TAG would mean a TAG points at another TAG, which is semantically invalid.
const canBeTag = computed(() =>
  store.labels.every(l => !(l.role === 'TAG' && (l.taggedEntities ?? []).includes(props.entity.name)))
)

const roleOptions = computed(() => [
  { label: 'Entity',    value: 'ENTITY' },
  { label: 'Tag',       value: 'TAG',       disabled: !canBeTag.value && props.entity.role !== 'TAG' },
  { label: 'Hierarchy', value: 'HIERARCHY' },
])

const extendsOptions = computed(() => [
  { label: '— none —', value: null },
  ...store.labels
    .filter(l => l.name !== props.entity.name)
    .map(l => ({ label: l.name, value: l.name }))
])

const parentEntityOptions = computed(() => {
  const coLabelSet = new Set(props.entity.coLabels ?? [])
  return store.labels
    .filter(l =>
      l.name !== props.entity.name &&
      (!l.role || l.role === 'ENTITY') &&
      !l.removed &&
      (coLabelSet.size === 0 || coLabelSet.has(l.name))
    )
    .map(l => ({ label: l.name, value: l.name }))
})

const customColorValue = computed(() => {
  const c = props.entity.color
  return (c && !COLOR_PALETTE.includes(c)) ? c : '#888888'
})

function saveDescription() {
  props.entity.description = editDescription.value
  store.markEdited()
}

function saveDataSource() {
  props.entity.dataSource = editDataSource.value
  store.markEdited()
}

function saveRole() {
  if (!props.isLabel) return
  props.entity.role = editRole.value
  if (editRole.value !== 'TAG') {
    props.entity.taggedEntities = []
    editTaggedEntities.value = []
  }
  store.markEdited()
}

function saveExtends() {
  if (!props.isLabel) return
  props.entity.extends = editExtends.value
  store.markEdited()
}

function saveTaggedEntities() {
  if (!props.isLabel) return
  // TODO: once taggedEntities is populated there is no way to clear it back to an empty list via
  //  the MultiSelect — deselecting all still calls this with the previous value. Add a "clear"
  //  affordance or replace the MultiSelect with something that exposes a remove-all action.
  props.entity.taggedEntities = editTaggedEntities.value
  store.markEdited()
}

function saveColor(color) {
  if (!props.isLabel) return
  props.entity.color = color
  store.markEdited()
}

function savePropDescription(prop, description) {
  prop.description = description
  store.markEdited()
}

function isDisplayed(propName) {
  return (props.entity.displayProperties ?? []).includes(propName)
}

function toggleDisplayProperty(propName) {
  const current = [...(props.entity.displayProperties ?? [])]
  const idx = current.indexOf(propName)
  if (idx === -1) {
    current.push(propName)
  } else {
    current.splice(idx, 1)
  }
  props.entity.displayProperties = current
  store.markEdited()
}
</script>

<style scoped>
.annotation-panel {
  width: 270px;
  flex-shrink: 0;
  border-left: 1px solid var(--border);
  background: var(--bg-surface);
  overflow-y: auto;
  display: flex;
  flex-direction: column;
}

.annotation-header {
  padding: 14px 16px 12px;
  border-bottom: 1px solid var(--border);
  position: relative;
  flex-shrink: 0;
}

.annotation-name {
  font-size: 15px;
  font-weight: 700;
  color: var(--text-primary);
  font-family: var(--font-mono);
  margin-bottom: 6px;
  padding-right: 28px;
}

.annotation-meta {
  display: flex;
  align-items: center;
  gap: 8px;
}

.entity-count {
  font-size: 11px;
  color: var(--text-secondary);
  font-family: var(--font-mono);
}

.annotation-close {
  position: absolute;
  top: 8px;
  right: 8px;
}

.annotation-section {
  padding: 14px 16px;
  border-bottom: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.section-hint {
  font-size: 11px;
  color: var(--text-muted);
  line-height: 1.4;
  margin-top: -4px;
}

.annotation-section:last-child {
  border-bottom: none;
}

.connectivity {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.conn-pair {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 5px;
}

.conn-arrow {
  color: var(--text-muted);
  font-size: 12px;
}

.reset-routing-btn {
  align-self: flex-start;
  font-size: 11px;
  padding: 2px 4px;
  margin-top: 2px;
  color: var(--text-muted) !important;
}

.prop-row {
  display: flex;
  flex-direction: column;
  gap: 5px;
  margin-bottom: 10px;
}

.prop-row:last-child {
  margin-bottom: 0;
}

.prop-header {
  display: flex;
  align-items: center;
  gap: 6px;
}

.prop-name {
  font-size: 12px;
  font-weight: 600;
  font-family: var(--font-mono);
  color: var(--text-primary);
}

.prop-type {
  font-size: 11px;
  color: var(--text-secondary);
  font-family: var(--font-mono);
}

.prop-req {
  font-size: 9px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  padding: 1px 4px;
  border-radius: 3px;
  background: var(--danger-dim);
  color: var(--danger);
  font-family: var(--font-mono);
}

.colour-palette {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}

.palette-swatch,
.palette-clear {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  border: 2px solid transparent;
  cursor: pointer;
  padding: 0;
  flex-shrink: 0;
  transition: border-color 0.1s, transform 0.1s;
}

.palette-swatch:hover {
  transform: scale(1.2);
}

.palette-swatch.active {
  border-color: #fff;
  transform: scale(1.15);
}

.palette-clear {
  background: transparent;
  border-color: var(--border-strong, #4a4e6e);
  position: relative;
}

.palette-clear::after {
  content: '×';
  position: absolute;
  inset: 0;
  text-align: center;
  line-height: 18px;
  font-size: 13px;
  color: var(--text-muted);
}

.palette-clear:hover {
  border-color: var(--danger);
  transform: scale(1.15);
}

.palette-clear.active {
  border-color: var(--accent-bright, #a5b4fc);
}

.palette-custom {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  border: 2px dashed var(--border-strong, #52569e);
  cursor: pointer;
  overflow: hidden;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: border-color 0.1s, transform 0.1s;
}

.palette-custom:hover {
  border-color: var(--text-secondary);
  transform: scale(1.15);
}

.palette-custom input[type="color"] {
  opacity: 0;
  width: 100%;
  height: 100%;
  cursor: pointer;
  padding: 0;
  border: none;
}

.prop-checks {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.prop-check-row {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  user-select: none;
}

.prop-check-row input[type="checkbox"] {
  width: 13px;
  height: 13px;
  flex-shrink: 0;
  cursor: pointer;
  accent-color: var(--accent-bright, #a5b4fc);
}

.prop-check-name {
  font-size: 12px;
  font-family: var(--font-mono, monospace);
  color: var(--text-secondary);
}

.prop-check-row:hover .prop-check-name {
  color: var(--text-primary);
}
</style>
