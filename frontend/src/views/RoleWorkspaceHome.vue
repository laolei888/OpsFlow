<script setup>
import { computed } from 'vue'

const props = defineProps({
  displayName: { type: String, default: '协作成员' },
  isAdmin: { type: Boolean, default: false },
  isStaff: { type: Boolean, default: false },
  counts: { type: Object, default: () => ({}) }
})

defineEmits(['navigate', 'create-ticket', 'search'])

const role = computed(() => props.isAdmin ? 'admin' : props.isStaff ? 'staff' : 'requester')
const profile = computed(() => ({
  requester: {
    eyebrow: 'REQUESTER SERVICE PORTAL',
    title: `${props.displayName}，需要什么帮助？`,
    description: '从标准服务申请开始，也可以查看已有工单、搜索解决方案或确认处理结果。',
    focusLabel: '我的服务进度',
    metrics: [
      { key: 'active', label: '处理中请求', tone: 'blue' },
      { key: 'waitingConfirmation', label: '待我确认', tone: 'violet' },
      { key: 'pendingServiceRequestCount', label: '待审批申请', tone: 'amber' },
      { key: 'articleTotal', label: '可用知识', tone: 'green' }
    ],
    actions: [
      { view: 'catalog', icon: '◇', label: '申请标准服务', hint: '从目录选择服务' },
      { view: 'tickets', icon: '◫', label: '查看我的工单', hint: '跟踪处理进度' },
      { view: 'knowledge', icon: '▤', label: '搜索知识库', hint: '优先自助解决' },
      { view: 'satisfaction', icon: '★', label: '服务评价', hint: '查看待评价事项' }
    ]
  },
  staff: {
    eyebrow: 'OPERATIONS WORKSPACE',
    title: `${props.displayName}，先处理最紧急的事项。`,
    description: '系统已经按照 SLA、优先级和责任归属汇总今天的运营重点。',
    focusLabel: '今日运营焦点',
    metrics: [
      { key: 'overdue', label: 'SLA 已逾期', tone: 'red' },
      { key: 'urgent', label: '紧急事项', tone: 'amber' },
      { key: 'unassigned', label: '待认领', tone: 'violet' },
      { key: 'active', label: '活跃工单', tone: 'blue' }
    ],
    actions: [
      { view: 'workbench', icon: '◎', label: '进入运营待办', hint: '按风险统一处理' },
      { view: 'tickets', icon: '◫', label: '打开工单队列', hint: '领取和批量处理' },
      { view: 'alerts', icon: '⚡', label: '查看监控告警', hint: '确认异常信号' },
      { view: 'oncall', icon: '◷', label: '值班与升级', hint: '检查当前轮班' }
    ]
  },
  admin: {
    eyebrow: 'ADMIN CONTROL CENTER',
    title: `${props.displayName}，平台运行状态已汇总。`,
    description: '关注流程积压、团队承载和平台异常，再进入对应治理模块完成配置。',
    focusLabel: '平台治理焦点',
    metrics: [
      { key: 'overdue', label: 'SLA 已逾期', tone: 'red' },
      { key: 'pendingChangeCount', label: '待审批变更', tone: 'amber' },
      { key: 'pendingProblemCount', label: '活跃问题', tone: 'violet' },
      { key: 'webhookDeadCount', label: '集成死信', tone: 'blue' }
    ],
    actions: [
      { view: 'workbench', icon: '◎', label: '运营控制台', hint: '查看全局积压' },
      { view: 'team', icon: '♙', label: '团队与权限', hint: '管理成员访问' },
      { view: 'supportGroups', icon: '♜', label: '支持组路由', hint: '调整队列承载' },
      { view: 'integrations', icon: '⌁', label: '集成中心', hint: '检查外部投递' }
    ]
  }
})[role.value])

function valueFor(metric) {
  return Number(props.counts[metric.key] || 0)
}
</script>

<template>
  <section :class="['role-workspace-home', `role-${role}`]">
    <div class="role-home-copy">
      <span class="role-home-eyebrow">{{ profile.eyebrow }}</span>
      <h2>{{ profile.title }}</h2>
      <p>{{ profile.description }}</p>
      <div class="role-home-primary-actions">
        <button class="primary-button" type="button" @click="$emit('create-ticket')">＋ 创建新工单</button>
        <button class="secondary-button" type="button" @click="$emit('search')">⌕ 全局搜索</button>
      </div>
    </div>
    <div class="role-focus-panel">
      <div class="role-focus-heading"><span>{{ profile.focusLabel }}</span><small>实时数据</small></div>
      <div class="role-focus-grid">
        <article v-for="metric in profile.metrics" :key="metric.key" :class="`tone-${metric.tone}`">
          <strong>{{ valueFor(metric) }}</strong><span>{{ metric.label }}</span>
        </article>
      </div>
    </div>
    <div class="role-quick-grid">
      <button v-for="action in profile.actions" :key="action.view" type="button" @click="$emit('navigate', action.view)">
        <b>{{ action.icon }}</b><span><strong>{{ action.label }}</strong><small>{{ action.hint }}</small></span><i>→</i>
      </button>
    </div>
  </section>
</template>
