<script setup>
defineProps({
  eyebrow: { type: String, default: 'OPSFLOW' },
  title: { type: String, default: 'OpsFlow' },
  displayName: { type: String, default: '协作成员' },
  user: { type: Object, default: null },
  unread: { type: Number, default: 0 },
  notifications: { type: Array, default: () => [] },
  notificationsVisible: { type: Boolean, default: false },
  notificationsLoading: { type: Boolean, default: false },
  isStaff: { type: Boolean, default: false },
  formatDate: { type: Function, required: true }
})

defineEmits(['toggle-notifications', 'preferences', 'scan-sla', 'mark-all', 'open-notification', 'change-password'])
</script>

<template>
  <header class="topbar">
    <div><p class="eyebrow">{{ eyebrow }}</p><h1>{{ title }}</h1></div>
    <div class="topbar-user">
      <div class="notification-wrap">
        <button class="notification-button" type="button" aria-label="打开通知中心" @click="$emit('toggle-notifications')">♢<b v-if="unread">{{ unread > 99 ? '99+' : unread }}</b></button>
        <div v-if="notificationsVisible" class="notification-popover">
          <div class="notification-header">
            <strong>通知中心</strong>
            <div>
              <button class="notification-scan" type="button" @click.stop="$emit('preferences')">渠道偏好</button>
              <button v-if="isStaff" class="notification-scan" type="button" @click.stop="$emit('scan-sla')">扫描 SLA</button>
              <button class="notification-scan" type="button" @click.stop="$emit('mark-all')">全部已读</button>
            </div>
          </div>
          <div v-if="notificationsLoading" class="notification-empty">正在加载通知…</div>
          <div v-else-if="!notifications.length" class="notification-empty">暂无通知</div>
          <button
            v-for="notification in notifications"
            v-else
            :key="notification.id"
            :class="['notification-item', { unread: !notification.readAt }]"
            type="button"
            @click="$emit('open-notification', notification)"
          >
            <i></i><div><strong>{{ notification.title }}</strong><p>{{ notification.content }}</p><small>{{ formatDate(notification.createdAt) }}</small></div>
          </button>
          <div class="notification-footer">支持站内信、邮件与签名 Webhook · 仅展示最近 20 条</div>
        </div>
      </div>
      <div class="avatar">{{ displayName.slice(0, 1) }}</div>
      <div><strong>{{ displayName }}</strong><small>{{ user?.roles?.join(' · ') || 'REQUESTER' }}{{ user?.authSource === 'OIDC' ? ' · 企业身份' : '' }}</small></div>
      <button v-if="user?.authSource !== 'OIDC'" class="account-action" type="button" @click="$emit('change-password')">修改密码</button>
    </div>
  </header>
</template>

