<template>
  <div class="generate-layout">
    <div class="generate-main">

      <!-- Format selector -->
      <section class="panel">
        <h2>Export Format</h2>
        <div class="format-options">
          <label v-for="fmt in formats" :key="fmt.id" class="format-option">
            <RadioButton v-model="selectedFormat" :value="fmt.id" :inputId="fmt.id" />
            <span class="fmt-label">{{ fmt.label }}</span>
            <span class="fmt-hint">{{ fmt.hint }}</span>
          </label>
        </div>
      </section>

      <!-- Options -->
      <section class="panel options-panel">
        <label class="option-row">
          <input type="checkbox" v-model="includeDataSource" class="option-checkbox" />
          <span class="option-label">Include data source</span>
          <span class="option-hint">Adds a "Data source" column to property tables and shows entity source annotations</span>
        </label>
        <label class="option-row">
          <input type="checkbox" v-model="captureLight" class="option-checkbox" />
          <span class="option-label">Capture in light mode</span>
          <span class="option-hint">Renders diagram images with a light background regardless of the current UI theme</span>
        </label>
      </section>

      <!-- Download -->
      <section class="panel action-panel">
        <Button
          :label="downloading ? downloadProgress : 'Download'"
          :loading="downloading"
          :disabled="!graphsReady || store.views.length === 0"
          @click="downloadAll"
        />
        <span v-if="!graphsReady && store.views.length > 0" class="hint-text">
          Preparing view renders…
        </span>
        <span v-else-if="store.views.length === 0" class="hint-text">
          No views defined — create views in the Views tab first.
        </span>
      </section>

      <!-- Preview sections -->
      <template v-if="!isHtmlFormat">
        <template v-if="loadingPreview">
          <section class="panel"><p class="hint-text">Loading sections…</p></section>
        </template>
        <template v-else-if="preview">

          <!-- Views — per-view because each has an associated image -->
          <section v-if="preview.views.length > 0" class="panel section-panel">
            <h3>Views</h3>
            <div v-for="item in preview.views" :key="'view-'+item.name" class="view-card">

              <!-- Header: name + copy-text button -->
              <div class="view-card-header">
                <span class="section-name">{{ item.name }}</span>
                <button class="copy-btn" @click="copyText(item.text, 'view-'+item.name)">
                  {{ copiedKey === 'view-'+item.name ? 'Copied!' : copyLabel }}
                </button>
              </div>

              <!-- Image directive (AsciiDoc/Markdown only) -->
              <div v-if="!isHtmlFormat && !isDocxFormat && hasLayout(item.name)" class="directive-row">
                <code class="directive-text">{{ imageDirective(item.name) }}</code>
                <button class="copy-btn" @click="copyText(imageDirective(item.name), 'dir-'+item.name)">
                  {{ copiedKey === 'dir-'+item.name ? 'Copied!' : 'Copy directive' }}
                </button>
              </div>

              <!-- Image preview -->
              <div class="image-preview">
                <template v-if="hasLayout(item.name)">
                  <img v-if="capturedImages[item.name]" :src="capturedImages[item.name]"
                       :alt="item.name" class="view-image" />
                  <p v-else class="hint-text">Capturing…</p>
                </template>
                <p v-else class="hint-text">No layout saved — open in the canvas editor first.</p>
                <button
                  class="copy-btn"
                  :disabled="!hasLayout(item.name) || !graphsReady"
                  :title="hasLayout(item.name) ? 'Copy image to clipboard' : 'No layout saved — open in the canvas editor first'"
                  @click="copyImage(item.name)"
                >
                  {{ copiedKey === 'img-'+item.name ? 'Copied!' : 'Copy image' }}
                </button>
              </div>

            </div>
          </section>

          <!-- Node Labels — single collapsible block -->
          <section v-if="preview.labelsBlock" class="panel section-panel">
            <div class="block-header">
              <h3>Node Labels</h3>
              <button class="copy-btn" @click="copyText(preview.labelsBlock, 'labels')">
                {{ copiedKey === 'labels' ? 'Copied!' : copyLabel }}
              </button>
            </div>
            <details class="source-details">
              <summary class="source-summary">Show source</summary>
              <pre class="source-block">{{ preview.labelsBlock }}</pre>
            </details>
          </section>

          <!-- Relationship Types — single collapsible block -->
          <section v-if="preview.relationsBlock" class="panel section-panel">
            <div class="block-header">
              <h3>Relationship Types</h3>
              <button class="copy-btn" @click="copyText(preview.relationsBlock, 'rels')">
                {{ copiedKey === 'rels' ? 'Copied!' : copyLabel }}
              </button>
            </div>
            <details class="source-details">
              <summary class="source-summary">Show source</summary>
              <pre class="source-block">{{ preview.relationsBlock }}</pre>
            </details>
          </section>

          <!-- Constraints & Indexes — single collapsible block -->
          <section v-if="preview.constraintsIndexes" class="panel section-panel">
            <div class="block-header">
              <h3>Constraints &amp; Indexes</h3>
              <button class="copy-btn" @click="copyText(preview.constraintsIndexes, 'ci')">
                {{ copiedKey === 'ci' ? 'Copied!' : copyLabel }}
              </button>
            </div>
            <details class="source-details">
              <summary class="source-summary">Show source</summary>
              <pre class="source-block">{{ preview.constraintsIndexes }}</pre>
            </details>
          </section>

        </template>
      </template>

      <section v-else class="panel">
        <p class="hint-text">HTML output is a single self-contained file. Use the Download button above.</p>
      </section>

    </div>

    <!-- Offscreen SchemaGraph instances for image capture.
         Fixed position far left ensures Vue Flow's ResizeObserver fires. -->
    <div class="capture-area">
      <div
        v-for="view in store.views"
        :key="view.name"
        class="capture-container"
        :ref="el => { if (el) captureRefs[view.name] = el }"
      >
        <SchemaGraph :view="view" />
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import Button from 'primevue/button'
import RadioButton from 'primevue/radiobutton'
import SchemaGraph from '../components/SchemaGraph.vue'
import { useSchemaStore } from '../stores/schema.js'
import { captureContainerAsPng } from '../utils/captureView.js'

const store = useSchemaStore()

const selectedFormat    = ref('asciidoc')
const includeDataSource = ref(true)
const captureLight      = ref(true)
const preview           = ref(null)
const loadingPreview    = ref(false)
const downloading       = ref(false)
const downloadProgress  = ref('')
const captureRefs       = ref({})
const graphsReady       = ref(false)
const copiedKey         = ref(null)
const capturedImages    = ref({})
const capturingImages   = ref(false)

const formats = [
  { id: 'asciidoc', label: 'AsciiDoc', hint: 'Renders with Asciidoctor; converts to HTML or PDF' },
  { id: 'markdown', label: 'Markdown', hint: 'GitHub-flavoured; paste into Confluence, Notion, etc.' },
  { id: 'html',     label: 'HTML',     hint: 'Self-contained file; open in browser or import into Google Docs' },
  { id: 'docx',     label: 'DOCX',     hint: 'Microsoft Word / Google Docs — import or paste sections directly' },
]

const isHtmlFormat = computed(() => selectedFormat.value === 'html')
const isDocxFormat = computed(() => selectedFormat.value === 'docx')
const copyLabel    = computed(() => isDocxFormat.value ? 'Copy (HTML)' : 'Copy')

watch([selectedFormat, includeDataSource], loadPreview, { immediate: true })

watch(graphsReady, ready => { if (ready) captureAllImages() })
watch(captureLight, () => { if (graphsReady.value) captureAllImages() })

onMounted(() => {
  document.documentElement.classList.add('page-scrollable')
  setTimeout(() => { graphsReady.value = true }, 1500)
})

onUnmounted(() => {
  document.documentElement.classList.remove('page-scrollable')
})

async function loadPreview() {
  if (store.dirty) {
    await store.saveSchema()
  }
  loadingPreview.value = true
  preview.value = null
  try {
    const res = await fetch('/api/generate/preview', {
      method:  'POST',
      headers: { 'Content-Type': 'application/json' },
      body:    JSON.stringify({
        format: selectedFormat.value,
        includeDataSource: includeDataSource.value,
      }),
    })
    if (!res.ok) throw new Error('Preview failed: ' + res.status)
    preview.value = await res.json()
  } catch (e) {
    console.error('Preview error:', e)
  } finally {
    loadingPreview.value = false
  }
}

function sanitizeFileName(name) {
  return name.toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
}

function imageDirective(viewName) {
  const slug = sanitizeFileName(viewName)
  const path = `views/${slug}.png`
  return selectedFormat.value === 'asciidoc'
    ? `image::${path}[${viewName}]`
    : `![${viewName}](${path})`
}

async function captureAllImages() {
  if (!graphsReady.value) return
  capturingImages.value = true
  capturedImages.value = {}
  const theme = captureLight.value ? 'light' : 'dark'
  for (const view of store.views) {
    if (!hasLayout(view.name)) continue
    const el = captureRefs.value[view.name]
    if (el) capturedImages.value[view.name] = await captureContainerAsPng(el, theme)
  }
  capturingImages.value = false
}

function hasLayout(viewName) {
  const view = store.views.find(v => v.name === viewName)
  return !!(view?.layout?.nodes && Object.keys(view.layout.nodes).length > 0)
}

async function copyText(text, key) {
  try {
    if (isDocxFormat.value) {
      await navigator.clipboard.write([
        new ClipboardItem({ 'text/html': new Blob([text], { type: 'text/html' }) }),
      ])
    } else {
      await navigator.clipboard.writeText(text)
    }
    showCopied(key)
  } catch (e) {
    console.error('Clipboard write failed:', e)
  }
}

async function copyImage(viewName) {
  const el = captureRefs.value[viewName]
  if (!el) return
  try {
    const theme   = captureLight.value ? 'light' : 'dark'
    const dataUrl = await captureContainerAsPng(el, theme)
    const res     = await fetch(dataUrl)
    const blob    = await res.blob()
    await navigator.clipboard.write([new ClipboardItem({ 'image/png': blob })])
    showCopied('img-' + viewName)
  } catch (e) {
    console.error('Image copy failed:', e)
  }
}

function showCopied(key) {
  copiedKey.value = key
  setTimeout(() => { copiedKey.value = null }, 1500)
}

async function downloadAll() {
  downloading.value = true
  if (store.dirty) {
    await store.saveSchema()
  }
  const viewImages  = {}
  try {
    const theme = captureLight.value ? 'light' : 'dark'
    for (const view of store.views) {
      downloadProgress.value = `Capturing ${view.name}…`
      const el = captureRefs.value[view.name]
      if (el) {
        viewImages[view.name] = await captureContainerAsPng(el, theme)
      }
    }

    downloadProgress.value = 'Assembling…'
    const res = await fetch('/api/generate/download', {
      method:  'POST',
      headers: { 'Content-Type': 'application/json' },
      body:    JSON.stringify({
        format: selectedFormat.value,
        viewImages,
        includeDataSource: includeDataSource.value,
      }),
    })
    if (!res.ok) throw new Error('Download failed: ' + res.status)

    const blob        = await res.blob()
    const url         = URL.createObjectURL(blob)
    const disposition = res.headers.get('Content-Disposition') ?? ''
    const match       = disposition.match(/filename="(.+)"/)
    const filename    = match ? match[1] : 'schema-doc'

    const a  = document.createElement('a')
    a.href   = url
    a.download = filename
    a.click()
    URL.revokeObjectURL(url)

  } catch (e) {
    console.error('Download error:', e)
  } finally {
    downloading.value      = false
    downloadProgress.value = ''
  }
}
</script>

<style scoped>
.generate-layout {
  display: flex;
  width: 100%;
  background: var(--bg-base);
}

.generate-main {
  flex: 1;
  max-width: 780px;
  margin: 0 auto;
  padding: 36px 32px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

h2 {
  font-size: 14px;
  font-weight: 700;
  color: var(--text-primary);
  margin-bottom: 12px;
}

h3 {
  font-size: 11px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: var(--text-muted);
  margin: 0;
}

.panel {
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 20px;
}

.action-panel {
  display: flex;
  align-items: center;
  gap: 16px;
}

.hint-text {
  font-size: 12px;
  color: var(--text-muted);
  font-style: italic;
}

/* Format selector */
.format-options {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.format-option {
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
}

.fmt-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-secondary);
  min-width: 80px;
}

.fmt-hint {
  font-size: 12px;
  color: var(--text-muted);
}

/* Options panel */
.options-panel {
  padding: 14px 20px;
}

.option-row {
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
}

.option-checkbox {
  width: 15px;
  height: 15px;
  flex-shrink: 0;
  accent-color: var(--accent);
  cursor: pointer;
}

.option-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-secondary);
}

.option-hint {
  font-size: 12px;
  color: var(--text-muted);
}

/* Section panels */
.section-panel {
  padding: 16px 20px;
}

/* View cards */
.view-card {
  padding: 12px 0;
  border-bottom: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.view-card:last-child {
  border-bottom: none;
}

.view-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.section-name {
  font-size: 13px;
  color: var(--text-secondary);
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.directive-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.directive-text {
  flex: 1;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-secondary);
  background: var(--bg-base);
  border: 1px solid var(--border);
  border-radius: 4px;
  padding: 4px 8px;
  overflow-x: auto;
  white-space: nowrap;
}

.image-preview {
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: flex-start;
}

.view-image {
  max-width: 100%;
  border: 1px solid var(--border);
  border-radius: 4px;
}

/* Block header for aggregated sections */
.block-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}

/* Collapsible source block */
.source-details {
  margin-top: 4px;
}

.source-summary {
  font-size: 11px;
  color: var(--text-muted);
  cursor: pointer;
  user-select: none;
  padding: 4px 0;
  list-style: none;
}

.source-summary::-webkit-details-marker {
  display: none;
}

.source-summary::before {
  content: '▶ ';
  font-size: 9px;
}

details[open] .source-summary::before {
  content: '▼ ';
}

.source-block {
  margin-top: 8px;
  padding: 12px;
  background: var(--bg-base);
  border: 1px solid var(--border);
  border-radius: 4px;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-secondary);
  overflow-x: auto;
  max-height: 400px;
  overflow-y: auto;
  white-space: pre;
}

.copy-btn {
  font-size: 11px;
  font-weight: 600;
  padding: 3px 10px;
  border-radius: 4px;
  border: 1px solid var(--border-strong);
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  white-space: nowrap;
  transition: background 0.1s, color 0.1s;
  font-family: inherit;
  flex-shrink: 0;
}

.copy-btn:hover:not(:disabled) {
  background: var(--bg-base);
  color: var(--text-primary);
}

.copy-btn:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}

/* Offscreen capture containers */
.capture-area {
  position: fixed;
  left: -10000px;
  top: 0;
  pointer-events: none;
}

.capture-container {
  width: 1200px;
  height: 900px;
  overflow: hidden;
}
</style>
