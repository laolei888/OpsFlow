<script setup>
defineProps({ items: { type: Array, default: () => [] } })
defineEmits(['navigate'])
</script>

<template>
  <nav class="breadcrumb-trail" aria-label="面包屑导航">
    <template v-for="(item, index) in items" :key="`${item.label}-${index}`">
      <button v-if="item.view && !item.current" type="button" @click="$emit('navigate', item.view)">{{ item.label }}</button>
      <span v-else :aria-current="item.current ? 'page' : undefined">{{ item.label }}</span>
      <i v-if="index < items.length - 1">/</i>
    </template>
  </nav>
</template>

