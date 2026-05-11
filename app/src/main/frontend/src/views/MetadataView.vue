<template>
  <div class="metadata-layout">

    <!-- ---- Entity list sidebar ---- -->
    <aside class="entity-sidebar">
      <Listbox
        v-model="selectedItem"
        :options="entityGroups"
        optionLabel="name"
        optionGroupLabel="label"
        optionGroupChildren="items"
        dataKey="name"
        filter
        filterPlaceholder="Filter entities…"
        :filterFields="['name']"
        class="entity-listbox"
      >
        <template #optiongroup="{ option }">
          <span class="group-label">{{ option.label }}</span>
        </template>
        <template #option="{ option }">
          <span class="entity-name">{{ labelNameSet.has(option.name) ? option.name : relDisplayName(option) }}</span>
          <span v-if="labelNameSet.has(option.name) && option.role && option.role !== 'ENTITY'" class="chip chip-tag">{{ option.role }}</span>
          <span v-if="labelNameSet.has(option.name)" class="rel-count" :title="option.nodeCount?.toLocaleString()">{{ formatCount(option.nodeCount) }}</span>
          <span v-else class="rel-count" :title="option.count?.toLocaleString()">{{ formatCount(option.count) }}</span>
        </template>
      </Listbox>
    </aside>

    <!-- ---- Entity detail ---- -->
    <div v-if="selected" class="entity-detail">
      <div class="detail-header">
        <div class="detail-name">{{ selected.isLabel ? selected.entity.name : relDisplayName(selected.entity) }}</div>
        <div class="detail-meta">
          <template v-if="selected.isLabel">
            <span v-if="selected.entity.role && selected.entity.role !== 'ENTITY'" class="chip chip-tag">
              {{ selected.entity.role }}
            </span>
            <span class="meta-stat"><span class="stat-number" :title="selected.entity.nodeCount?.toLocaleString()">{{ formatCount(selected.entity.nodeCount) }}</span> nodes</span>
          </template>
          <template v-else>
            <span class="meta-stat"><span class="stat-number" :title="selected.entity.count?.toLocaleString()">{{ formatCount(selected.entity.count) }}</span> rels</span>
            <div v-if="selected.entity.connections?.length" class="connectivity">
              <div v-for="(conn, idx) in selected.entity.connections" :key="idx" class="conn-pair">
                <span v-for="label in conn.startLabels" :key="label" class="label-chip">{{ label }}</span>
                <span class="conn-arrow">→</span>
                <span v-for="label in conn.endLabels" :key="label" class="label-chip">{{ label }}</span>
              </div>
            </div>
          </template>
        </div>
      </div>

      <div class="detail-section two-col">
        <div class="field-group">
          <div class="section-label">Description</div>
          <Textarea
            v-model="editDescription"
            placeholder="Describe this entity…"
            autoResize
            rows="3"
            @blur="saveDescription"
          />
        </div>
        <div class="field-group">
          <div class="section-label">Data source</div>
          <InputText
            v-model="editDataSource"
            placeholder="e.g. Salesforce, Legacy ERP…"
            @blur="saveDataSource"
          />
        </div>
      </div>

      <!-- Properties table -->
      <div v-if="selected.entity.properties?.length" class="detail-section">
        <div class="section-label">Properties</div>
        <DataTable :value="selected.entity.properties" size="small" stripedRows class="props-table">
          <Column field="name" header="Name">
            <template #body="{ data }">
              <span class="prop-name-cell">{{ data.name }}</span>
            </template>
          </Column>
          <Column header="Type">
            <template #body="{ data }">
              <span class="prop-type-cell">{{ data.types?.join(' | ') }}</span>
            </template>
          </Column>
          <Column header="Req" style="width: 2.5rem; text-align: center">
            <template #body="{ data }">
              <span v-if="!data.nullable" class="req-dot" title="Required">•</span>
            </template>
          </Column>
          <Column header="Description" style="min-width: 10rem">
            <template #body="{ data }">
              <InputText
                v-model="data.description"
                placeholder="Add description…"
                size="small"
                fluid
                @blur="() => savePropDescription(data, data.description)"
              />
            </template>
          </Column>
          <Column header="Data source" style="min-width: 10rem">
            <template #body="{ data }">
              <InputText
                v-model="data.dataSource"
                placeholder="Source system…"
                size="small"
                fluid
                @blur="() => savePropDataSource(data, data.dataSource)"
              />
            </template>
          </Column>
        </DataTable>
      </div>

      <!-- Type parameters (parameterised relationship types) -->
      <div v-if="selected.entity.typeParameters?.length" class="detail-section">
        <div class="section-label">Type parameters</div>
        <div class="type-params-hint">
          Parameterised: <code>{{ relDisplayName(selected.entity) }}</code>.
          Examples: {{ selected.entity.instances?.slice(0, 3).join(', ') }}{{ selected.entity.instances?.length > 3 ? '…' : '' }}
        </div>
        <DataTable :value="selected.entity.typeParameters" size="small" class="props-table" style="margin-top: 10px">
          <Column field="position" header="Pos" style="width: 3rem" />
          <Column header="Variable name" style="min-width: 8rem">
            <template #body="{ data }">
              <InputText
                v-model="data.name"
                :placeholder="'v' + (data.position + 1)"
                size="small"
                fluid
                @blur="() => saveParamName(data, data.name)"
              />
            </template>
          </Column>
          <Column header="Example values">
            <template #body="{ data }">
              <span class="prop-type-cell">{{ data.exampleValues?.slice(0, 4).join(', ') }}</span>
            </template>
          </Column>
          <Column header="Meaning">
            <template #body="{ data }">
              <InputText
                v-model="data.description"
                placeholder="Describe this parameter…"
                size="small"
                fluid
                @blur="() => saveParamDescription(data, data.description)"
              />
            </template>
          </Column>
        </DataTable>
      </div>
    </div>

    <div v-else class="detail-empty">
      Select a label or relationship type
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import Listbox from 'primevue/listbox'
import InputText from 'primevue/inputtext'
import Textarea from 'primevue/textarea'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import { useSchemaStore } from '../stores/schema.js'
import { formatCount, relDisplayName } from '../utils/format.js'

const store = useSchemaStore()

const selected        = ref(null)   // { key, entity, isLabel }
const editDescription = ref('')
const editDataSource  = ref('')

const labelNameSet = computed(() => new Set(store.labels.map(l => l.name)))

const entityGroups = computed(() => [
  { label: 'Labels',        items: store.labels },
  { label: 'Relationships', items: store.relationshipTypes }
])

const selectedItem = ref(null)

watch(selectedItem, (item) => {
  if (!item) {
    selected.value = null
    return
  }
  const isLabel = labelNameSet.value.has(item.name)
  selected.value = {
    key: (isLabel ? 'l:' : 'r:') + item.name,
    entity: item,
    isLabel
  }
  editDescription.value = item.description ?? ''
  editDataSource.value  = item.dataSource  ?? ''
})

function saveDescription() {
  if (!selected.value) return
  selected.value.entity.description = editDescription.value
  store.markEdited()
}

function saveDataSource() {
  if (!selected.value) return
  selected.value.entity.dataSource = editDataSource.value
  store.markEdited()
}

function savePropDescription(prop, description) {
  if (!selected.value) return
  prop.description = description
  store.markEdited()
}

function savePropDataSource(prop, dataSource) {
  if (!selected.value) return
  prop.dataSource = dataSource
  store.markEdited()
}

function saveParamName(param, name) {
  param.name = name || ('v' + (param.position + 1))
  store.markEdited()
}

function saveParamDescription(param, description) {
  param.description = description
  store.markEdited()
}
</script>

<style scoped>
.metadata-layout {
  display: flex;
  width: 100%;
  height: 100%;
  overflow: hidden;
  background: var(--bg-base);
}

/* ---- Sidebar with Listbox ---- */

.entity-sidebar {
  width: 260px;
  flex-shrink: 0;
  border-right: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.entity-listbox {
  flex: 1;
  overflow: hidden;
  width: 100%;
  border: none;
  border-radius: 0;
  background: var(--bg-surface);
  display: flex;
  flex-direction: column;
}

.entity-listbox :deep(.p-listbox-header) {
  padding: 10px;
  border-bottom: 1px solid var(--border);
  border-radius: 0;
  background: var(--bg-surface);
}

.entity-listbox :deep(.p-listbox-list-container) {
  flex: 1;
  overflow-y: auto;
  max-height: none !important;
}

.entity-listbox :deep(.p-listbox-list) {
  padding: 0;
}

.entity-listbox :deep(.p-listbox-option-group) {
  padding: 7px 12px 4px;
  background: var(--bg-elevated);
  border-top: 3px solid var(--border-strong);
}

.entity-listbox :deep(.p-listbox-option) {
  border-radius: 0;
  border-bottom: 1px solid var(--border);
  padding: 7px 12px;
  gap: 8px;
}

.entity-listbox :deep(.p-listbox-option:last-child) {
  border-bottom: none;
}

.group-label {
  font-size: 10px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: var(--text-secondary);
}

.entity-name {
  flex: 1;
  font-size: 13px;
  font-weight: 600;
  font-family: var(--font-mono);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rel-count {
  font-size: 11px;
  color: var(--text-secondary);
  font-family: var(--font-mono);
  flex-shrink: 0;
}

/* ---- Detail ---- */

.entity-detail {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
}

.detail-empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-muted);
  font-size: 14px;
}

.detail-header {
  padding: 20px 24px 16px;
  border-bottom: 1px solid var(--border);
  background: var(--bg-surface);
  flex-shrink: 0;
}

.detail-name {
  font-size: 20px;
  font-weight: 700;
  color: var(--text-primary);
  font-family: var(--font-mono);
  margin-bottom: 8px;
}

.detail-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.meta-stat {
  font-size: 12px;
  color: var(--text-secondary);
  font-family: var(--font-mono);
}

.stat-number {
  color: var(--text-secondary);
  font-weight: 600;
}

.connectivity {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.conn-pair {
  display: flex;
  align-items: center;
  gap: 5px;
  flex-wrap: wrap;
}

.conn-arrow {
  color: var(--text-muted);
  font-size: 12px;
}

.detail-section {
  padding: 18px 24px;
  border-bottom: 1px solid var(--border-strong);
}

.detail-section:last-child {
  border-bottom: none;
}

.detail-section .section-label {
  margin-bottom: 10px;
}

.detail-section.two-col {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
}

.field-group {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

/* ---- Properties table ---- */

.prop-name-cell {
  font-family: var(--font-mono);
  font-weight: 700;
  font-size: 13px;
  color: var(--text-primary);
  white-space: nowrap;
}

.prop-type-cell {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-secondary);
  white-space: nowrap;
}

.req-dot {
  color: var(--text-primary);
  font-size: 18px;
}

.type-params-hint {
  font-size: 12px;
  color: var(--text-secondary);
  line-height: 1.5;
  padding: 8px 10px;
  background: var(--bg-elevated);
  border-radius: 4px;
  border: 1px solid var(--border);
  font-family: var(--font-mono);
}
</style>
