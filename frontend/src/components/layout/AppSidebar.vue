<script setup>
import { computed } from 'vue'
import { storeToRefs } from 'pinia'
import { NAV_GROUPS, NAV_ITEMS, navigationItem } from '../../app/navigation'
import { useWorkspaceStore } from '../../stores/workspace'
import SystemHealthBadge from '../common/SystemHealthBadge.vue'

const props = defineProps({
  activeView: { type: String, required: true },
  isAdmin: { type: Boolean, default: false },
  isStaff: { type: Boolean, default: false },
  counts: { type: Object, default: () => ({}) }
})

const emit = defineEmits(['navigate', 'search', 'logout'])
const workspace = useWorkspaceStore()
const { sidebarCollapsed, collapsedGroups, favoriteViews, recentViews } = storeToRefs(workspace)

function allowed(item) {
  if (item.access === 'admin') return props.isAdmin
  if (item.access === 'staff') return props.isStaff
  return true
}

const visibleItemKeys = computed(() => new Set(NAV_ITEMS.filter(allowed).map(item => item.key)))
const visibleGroups = computed(() => NAV_GROUPS
  .map(group => ({ ...group, entries: group.items.map(navigationItem).filter(allowed) }))
  .filter(group => group.entries.length))
const visibleFavorites = computed(() => favoriteViews.value
  .filter(view => visibleItemKeys.value.has(view))
  .map(navigationItem))
const visibleRecents = computed(() => recentViews.value
  .filter(view => visibleItemKeys.value.has(view) && !favoriteViews.value.includes(view))
  .slice(0, 4)
  .map(navigationItem))
const roleLabel = computed(() => props.isAdmin ? '管理员控制台' : props.isStaff ? '运维协作台' : '服务申请门户')

function countFor(item) {
  return item.count ? props.counts[item.count] : null
}

function groupExpanded(groupKey) {
  return sidebarCollapsed.value || !collapsedGroups.value.includes(groupKey)
}

function navigate(view) {
  emit('navigate', view)
}
</script>

<template>
  <aside :class="['sidebar', 'workspace-sidebar', { collapsed: sidebarCollapsed }]">
    <div class="sidebar-brand-row">
      <div class="brand-lockup">
        <div class="brand-mark small">OF</div>
        <div class="brand-copy"><strong>OpsFlow</strong><small>Service workspace</small></div>
      </div>
      <button class="sidebar-collapse" type="button" :title="sidebarCollapsed ? '展开导航' : '收起导航'" @click="workspace.toggleSidebar()">
        {{ sidebarCollapsed ? '›' : '‹' }}
      </button>
    </div>

    <div class="sidebar-role" :title="roleLabel">
      <span>{{ isAdmin ? 'A' : isStaff ? 'O' : 'R' }}</span>
      <div><small>当前工作空间</small><strong>{{ roleLabel }}</strong></div>
    </div>

    <button class="sidebar-search" type="button" title="全局搜索 Ctrl K" @click="$emit('search')">
      <b>⌕</b><span>全局搜索</span><em>Ctrl K</em>
    </button>

    <nav class="sidebar-nav" aria-label="主导航">
      <section v-if="visibleFavorites.length" class="nav-group favorite-group">
        <div class="nav-group-heading"><span>我的收藏</span><b>★</b></div>
        <div class="nav-group-items">
          <div v-for="item in visibleFavorites" :key="`favorite-${item.key}`" class="nav-item-row">
            <button class="nav-item-link" type="button" :class="{ active: activeView === item.key }" :title="item.label" @click="navigate(item.key)">
              <b>{{ item.icon }}</b><span>{{ item.label }}</span><em v-if="item.count && (!item.hideZero || countFor(item))">{{ countFor(item) }}</em>
            </button>
            <button class="nav-favorite active" type="button" :aria-label="`取消收藏${item.label}`" @click="workspace.toggleFavorite(item.key)">★</button>
          </div>
        </div>
      </section>

      <section v-if="visibleRecents.length && !sidebarCollapsed" class="nav-group recent-group">
        <div class="nav-group-heading"><span>最近访问</span><button type="button" @click="workspace.clearRecent()">清除</button></div>
        <div class="nav-group-items compact">
          <button v-for="item in visibleRecents" :key="`recent-${item.key}`" class="recent-link" type="button" @click="navigate(item.key)">
            <b>{{ item.icon }}</b><span>{{ item.label }}</span>
          </button>
        </div>
      </section>

      <section v-for="group in visibleGroups" :key="group.key" class="nav-group">
        <button class="nav-group-toggle" type="button" :aria-expanded="groupExpanded(group.key)" @click="workspace.toggleGroup(group.key)">
          <span>{{ group.label }}</span><b>{{ groupExpanded(group.key) ? '−' : '+' }}</b>
        </button>
        <div v-show="groupExpanded(group.key)" class="nav-group-items">
          <div v-for="item in group.entries" :key="item.key" class="nav-item-row">
            <button class="nav-item-link" type="button" :class="{ active: activeView === item.key }" :title="item.label" @click="navigate(item.key)">
              <b>{{ item.icon }}</b><span>{{ item.label }}</span><em v-if="item.count && (!item.hideZero || countFor(item))">{{ countFor(item) }}</em>
            </button>
            <button
              class="nav-favorite"
              :class="{ active: favoriteViews.includes(item.key) }"
              type="button"
              :aria-label="favoriteViews.includes(item.key) ? `取消收藏${item.label}` : `收藏${item.label}`"
              @click="workspace.toggleFavorite(item.key)"
            >{{ favoriteViews.includes(item.key) ? '★' : '☆' }}</button>
          </div>
        </div>
      </section>
    </nav>

    <div class="sidebar-bottom">
      <SystemHealthBadge />
      <button class="logout-button" type="button" title="退出登录" @click="$emit('logout')"><b>↪</b><span>退出登录</span></button>
    </div>
  </aside>
</template>

