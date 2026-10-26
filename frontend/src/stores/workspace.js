import { defineStore } from 'pinia'

const STORAGE_KEY = 'opsflow_workspace_preferences_v1'

function storedPreferences() {
  try {
    const value = JSON.parse(localStorage.getItem(STORAGE_KEY) || '{}')
    return value && typeof value === 'object' ? value : {}
  } catch (_) {
    localStorage.removeItem(STORAGE_KEY)
    return {}
  }
}

const initial = storedPreferences()

export const useWorkspaceStore = defineStore('workspace', {
  state: () => ({
    sidebarCollapsed: Boolean(initial.sidebarCollapsed),
    collapsedGroups: Array.isArray(initial.collapsedGroups) ? initial.collapsedGroups : [],
    favoriteViews: Array.isArray(initial.favoriteViews) ? initial.favoriteViews.slice(0, 8) : [],
    recentViews: Array.isArray(initial.recentViews) ? initial.recentViews.slice(0, 6) : []
  }),
  actions: {
    persist() {
      localStorage.setItem(STORAGE_KEY, JSON.stringify({
        sidebarCollapsed: this.sidebarCollapsed,
        collapsedGroups: this.collapsedGroups,
        favoriteViews: this.favoriteViews,
        recentViews: this.recentViews
      }))
    },
    toggleSidebar() {
      this.sidebarCollapsed = !this.sidebarCollapsed
      this.persist()
    },
    toggleGroup(groupKey) {
      this.collapsedGroups = this.collapsedGroups.includes(groupKey)
        ? this.collapsedGroups.filter(key => key !== groupKey)
        : [...this.collapsedGroups, groupKey]
      this.persist()
    },
    toggleFavorite(view) {
      this.favoriteViews = this.favoriteViews.includes(view)
        ? this.favoriteViews.filter(key => key !== view)
        : [...this.favoriteViews, view].slice(-8)
      this.persist()
    },
    recordVisit(view) {
      if (!view || view === 'overview') return
      this.recentViews = [view, ...this.recentViews.filter(key => key !== view)].slice(0, 6)
      this.persist()
    },
    clearRecent() {
      this.recentViews = []
      this.persist()
    }
  }
})
