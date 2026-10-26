<script setup>
import { computed } from 'vue'
import { NAV_ITEMS } from '../../app/navigation'
import SystemHealthBadge from '../common/SystemHealthBadge.vue'

const props = defineProps({
  activeView: { type: String, required: true },
  isAdmin: { type: Boolean, default: false },
  isStaff: { type: Boolean, default: false },
  counts: { type: Object, default: () => ({}) }
})

defineEmits(['navigate', 'search', 'logout'])

const visibleItems = computed(() => NAV_ITEMS.filter(item => {
  if (item.access === 'admin') return props.isAdmin
  if (item.access === 'staff') return props.isStaff
  return true
}))

function countFor(item) {
  return item.count ? props.counts[item.count] : null
}
</script>

<template>
  <aside class="sidebar">
    <div class="brand-lockup">
      <div class="brand-mark small">OF</div>
      <div><strong>OpsFlow</strong><small>Service workspace</small></div>
    </div>
    <p class="workspace-label">工作台</p>
    <nav>
      <template v-for="item in visibleItems" :key="item.key">
        <button
          type="button"
          :class="{ active: activeView === item.key }"
          @click="$emit('navigate', item.key)"
        >
          <b>{{ item.icon }}</b>{{ item.label }}<em v-if="item.count && (!item.hideZero || countFor(item))">{{ countFor(item) }}</em>
        </button>
        <button v-if="item.key === 'overview'" type="button" @click="$emit('search')"><b>⌕</b>全局搜索<em>Ctrl K</em></button>
      </template>
    </nav>
    <div class="sidebar-bottom">
      <SystemHealthBadge />
      <button class="logout-button" type="button" @click="$emit('logout')">退出登录</button>
    </div>
  </aside>
</template>

