<script setup>
defineProps({ message: { type: String, default: '' } })
defineEmits(['dismiss'])
</script>

<template>
  <p v-if="message" class="page-error">{{ message }} <button type="button" @click="$emit('dismiss')">×</button></p>
</template>

