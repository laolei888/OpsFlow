<script setup>
defineProps({
  healthy: { type: Boolean, default: true },
  label: { type: String, default: 'API & Redis 正常' }
})
</script>

<template>
  <div :class="['mini-status', { unhealthy: !healthy }]">
    <i></i><span>{{ healthy ? label : '基础服务状态异常' }}</span>
  </div>
</template>

