<script setup>
import { reactive } from 'vue'

defineProps({
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' },
  ssoLoading: { type: Boolean, default: false },
  ssoProviders: { type: Array, default: () => [] }
})

const emit = defineEmits(['submit'])
const credentials = reactive({ username: '', password: '' })

function submit() {
  emit('submit', { ...credentials })
}
</script>

<template>
  <section class="login-page">
    <div class="login-card">
      <div class="brand-mark">OF</div>
      <p class="eyebrow">ENTERPRISE SERVICE OPERATIONS</p>
      <h1>让每个请求，都有清晰的流转路径。</h1>
      <p class="login-subtitle">OpsFlow 将工单、知识和团队协作放在同一个工作台。</p>
      <form class="login-form" @submit.prevent="submit">
        <label>账号<input v-model="credentials.username" autocomplete="username" required /></label>
        <label>密码<input v-model="credentials.password" type="password" autocomplete="current-password" required /></label>
        <p v-if="error" class="form-error">{{ error }}</p>
        <button class="primary-button" :disabled="loading">{{ loading ? '登录中…' : '进入工作台' }}</button>
      </form>
      <div v-if="ssoLoading || ssoProviders.length" class="sso-login-block">
        <div class="sso-divider"><span>或使用企业身份</span></div>
        <p v-if="ssoLoading" class="sso-loading">正在读取企业登录入口…</p>
        <a
          v-for="provider in ssoProviders"
          v-else
          :key="provider.registrationId"
          class="sso-provider-button"
          :href="provider.authorizationUrl"
        >
          <span>{{ provider.displayName.slice(0, 1) }}</span>
          使用 {{ provider.displayName }} 登录
          <b>SSO →</b>
        </a>
      </div>
      <p class="demo-hint">请使用自行注册或管理员分配的账号登录</p>
    </div>
    <div class="login-side">
      <p class="eyebrow">OPS / FLOW / 01</p>
      <h2>把“有人跟进”<br /><span>变成可观测的流程。</span></h2>
      <div class="signal-card"><i></i>系统运行正常<strong>99.9%</strong></div>
    </div>
  </section>
</template>

