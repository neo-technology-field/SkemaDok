<template>
  <div class="app-shell">
    <header class="app-topbar">
      <h1>SkemaDok</h1>
      <span v-if="store.document" class="db-badge">
        {{ store.document.databaseName }}
        <span v-if="store.document.databaseVersion" class="db-version">v{{ store.document.databaseVersion }}</span>
      </span>
      <span v-if="store.document" class="ts-strip">
        <span class="ts-item" :title="store.document.capturedAt">synced {{ formatTs(store.document.capturedAt) }}</span>
        <span class="ts-sep">·</span>
        <span class="ts-item" :title="store.document.lastEditedAt">edited {{ formatTs(store.document.lastEditedAt) }}</span>
      </span>
      <nav>
        <RouterLink to="/metadata">Metadata</RouterLink>
        <RouterLink to="/views">Views</RouterLink>
        <RouterLink to="/generate">Generate</RouterLink>
      </nav>
      <div class="save-controls">
        <button
          class="save-btn"
          :class="{ dirty: store.dirty }"
          :disabled="!store.dirty || saving"
          :title="store.dirty ? 'Save changes to disk' : 'No unsaved changes'"
          @click="save"
        >{{ saving ? 'Saving…' : 'Save' }}</button>
        <button
          class="restore-btn"
          :disabled="restoring"
          title="Discard all changes and reload from disk"
          @click="restore"
        >{{ restoring ? 'Reloading…' : 'Restore' }}</button>
      </div>
      <button class="theme-btn" :title="isDark ? 'Switch to light mode' : 'Switch to dark mode'" @click="toggleTheme">
        <i :class="isDark ? 'pi pi-sun' : 'pi pi-moon'" />
      </button>
    </header>

    <main class="app-content">
      <div v-if="store.loading" class="status-overlay">Loading schema…</div>
      <div v-else-if="store.error" class="status-overlay error">
        Could not load schema: {{ store.error }}
      </div>
      <RouterView v-else />
    </main>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { RouterLink, RouterView } from 'vue-router'
import { useSchemaStore } from './stores/schema.js'
import { schemaApi } from './api/schemaApi.js'

const store = useSchemaStore()
onMounted(async () => {
  store.loadSchema()
  try {
    const filename = await schemaApi.getFilename()
    document.title = `${filename} — SkemaDok`
  } catch {
    // Spring JAR not running (Vite dev without backend) — leave default title
  }
})

const saving   = ref(false)
const restoring = ref(false)

async function save() {
  if (!store.dirty) return
  saving.value = true
  try {
    await store.saveSchema()
  } finally {
    saving.value = false
  }
}

async function restore() {
  if (!confirm('Discard all unsaved changes and reload the schema from disk?')) return
  restoring.value = true
  try {
    await store.reloadSchema()
  } finally {
    restoring.value = false
  }
}

const isDark = ref(true)

function applyTheme(dark) {
  document.documentElement.dataset.theme = dark ? 'dark' : 'light'
}

function toggleTheme() {
  isDark.value = !isDark.value
  localStorage.setItem('skemadok-theme', isDark.value ? 'dark' : 'light')
  applyTheme(isDark.value)
}

onMounted(() => {
  const saved = localStorage.getItem('skemadok-theme')
  isDark.value = saved !== 'light'
  applyTheme(isDark.value)
})

function formatTs(iso) {
  if (!iso) return '—'
  const d = new Date(iso)
  return new Intl.DateTimeFormat('en-GB', {
    day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit'
  }).format(d)
}
</script>

<style scoped>
.db-badge {
  font-size: 12px;
  font-family: var(--font-mono);
  color: var(--text-secondary);
  display: flex;
  align-items: center;
  gap: 6px;
}

.db-version {
  color: var(--text-muted);
  font-size: 11px;
}

.ts-strip {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  font-family: var(--font-mono);
  color: var(--text-muted);
}

.ts-sep {
  color: var(--border-strong);
}

.save-controls {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-left: 4px;
}

.save-btn,
.restore-btn {
  padding: 3px 12px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  font-family: var(--font-sans, sans-serif);
  transition: background 0.12s, color 0.12s, border-color 0.12s;
  white-space: nowrap;
}

.save-btn {
  background: var(--bg-elevated);
  color: var(--text-muted);
  border: 1px solid var(--border-strong);
}

.save-btn.dirty {
  background: var(--accent-bright, #a5b4fc);
  color: #1a1c35;
  border-color: var(--accent-bright, #a5b4fc);
  cursor: pointer;
}

.save-btn.dirty:hover {
  background: #c7d2fe;
  border-color: #c7d2fe;
}

.save-btn:disabled:not(.dirty) {
  opacity: 0.45;
  cursor: default;
}

.restore-btn {
  background: transparent;
  color: var(--text-secondary);
  border: 1px solid var(--border-strong);
}

.restore-btn:hover:not(:disabled) {
  background: var(--bg-hover);
  color: var(--danger, #f87171);
  border-color: var(--danger, #f87171);
}

.restore-btn:disabled {
  opacity: 0.45;
  cursor: default;
}

.theme-btn {
  margin-left: 12px;
  padding: 3px 10px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
  background: var(--bg-elevated);
  color: var(--text-secondary);
  border: 1px solid var(--border-strong);
  font-family: var(--font-sans);
  transition: background 0.12s, color 0.12s;
}

.theme-btn:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}

.status-overlay {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  font-size: 14px;
  color: var(--text-secondary);
}

.status-overlay.error {
  color: var(--danger);
}
</style>
