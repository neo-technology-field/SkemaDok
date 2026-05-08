import {defineStore} from 'pinia'
import {computed, ref} from 'vue'
import {schemaApi} from '../api/schemaApi.js'

export const useSchemaStore = defineStore('schema', () => {
  const document = ref(null)
  const loading  = ref(false)
  const error    = ref(null)
  const dirty    = ref(false)

  const labels            = computed(() => document.value?.nodeLabels       ?? [])
  const relationshipTypes = computed(() => document.value?.relationshipTypes ?? [])
  const views             = computed(() => document.value?.views             ?? [])
  const constraints       = computed(() => document.value?.constraints       ?? [])
  const indexes           = computed(() => document.value?.indexes           ?? [])

  function markEdited() {
    dirty.value = true
  }

  async function loadSchema() {
    loading.value = true
    error.value   = null
    try {
      document.value = await schemaApi.getSchema()
      dirty.value    = false
    } catch (e) {
      error.value = e.message
    } finally {
      loading.value = false
    }
  }

  async function saveSchema() {
    await schemaApi.saveSchema(document.value)
    dirty.value = false
  }

  async function reloadSchema() {
    await loadSchema()
  }

  return {
    document, loading, error, dirty,
    labels, relationshipTypes, views, constraints, indexes,
    loadSchema, saveSchema, reloadSchema,
    markEdited,
  }
})
