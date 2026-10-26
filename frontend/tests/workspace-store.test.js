import assert from 'node:assert/strict'
import test from 'node:test'
import { createPinia, setActivePinia } from 'pinia'

const values = new Map()
globalThis.localStorage = {
  getItem: key => values.get(key) ?? null,
  setItem: (key, value) => values.set(key, String(value)),
  removeItem: key => values.delete(key)
}

const { useWorkspaceStore } = await import('../src/stores/workspace.js')

function freshStore() {
  values.clear()
  setActivePinia(createPinia())
  return useWorkspaceStore()
}

test('workspace preferences persist sidebar and group choices', () => {
  const store = freshStore()
  store.toggleSidebar()
  store.toggleGroup('service')
  const persisted = JSON.parse(values.get('opsflow_workspace_preferences_v1'))
  assert.equal(persisted.sidebarCollapsed, true)
  assert.deepEqual(persisted.collapsedGroups, ['service'])
})

test('workspace history deduplicates visits and excludes overview', () => {
  const store = freshStore()
  store.recordVisit('tickets')
  store.recordVisit('knowledge')
  store.recordVisit('tickets')
  store.recordVisit('overview')
  assert.deepEqual(store.recentViews, ['tickets', 'knowledge'])
})

test('workspace favorites toggle without duplicates', () => {
  const store = freshStore()
  store.toggleFavorite('tickets')
  store.toggleFavorite('tickets')
  store.toggleFavorite('knowledge')
  assert.deepEqual(store.favoriteViews, ['knowledge'])
})
