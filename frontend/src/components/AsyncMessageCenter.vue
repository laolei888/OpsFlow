<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'

const props = defineProps({ api: { type: Function, required: true } })

const summary = ref({ pending: 0, processing: 0, failed: 0, dead: 0, published: 0, oldestPendingAt: null })
const events = ref([])
const loading = ref(false)
const retrying = ref(false)
const error = ref('')
const notice = ref('')
const selectedIds = ref([])
const status = ref('')
const notificationType = ref('')
const aggregateType = ref('')
const keyword = ref('')
let refreshTimer

const statusLabels = {
  PENDING: '等待发布', PROCESSING: '发布中', FAILED: '等待重试', DEAD: '已进入死信', PUBLISHED: '发布成功'
}
const typeLabels = {
  TICKET_CREATED: '工单创建', TICKET_ASSIGNED: '工单指派', TICKET_STATUS_CHANGED: '工单状态',
  TICKET_GROUP_ROUTED: '支持组路由', AUTOMATION_GROUP_ROUTED: '自动化路由',
  SLA_DUE_SOON: 'SLA 即将到期', SLA_OVERDUE: 'SLA 已逾期',
  SATISFACTION_REQUESTED: '服务评价', INCIDENT_DECLARED: '重大事件宣布',
  INCIDENT_STATUS_CHANGED: '重大事件状态', REPORT_READY: '报表生成'
}
const retryableEvents = computed(() => events.value.filter(item => ['FAILED', 'DEAD'].includes(item.status)))
const selectedRetryable = computed(() => retryableEvents.value.filter(item => selectedIds.value.includes(item.id)))
const allRetryableSelected = computed(() => retryableEvents.value.length > 0
  && retryableEvents.value.every(item => selectedIds.value.includes(item.id)))
const backlog = computed(() => Number(summary.value.pending || 0) + Number(summary.value.processing || 0)
  + Number(summary.value.failed || 0))

function queryString() {
  const params = new URLSearchParams({ limit: '200' })
  if (status.value) params.set('status', status.value)
  if (notificationType.value.trim()) params.set('notificationType', notificationType.value.trim())
  if (aggregateType.value) params.set('aggregateType', aggregateType.value)
  if (keyword.value.trim()) params.set('keyword', keyword.value.trim())
  return params.toString()
}

async function load(silent = false) {
  if (!silent) loading.value = true
  error.value = ''
  try {
    const [nextSummary, nextEvents] = await Promise.all([
      props.api('/api/v1/admin/async-messages/summary'),
      props.api('/api/v1/admin/async-messages?' + queryString())
    ])
    summary.value = nextSummary
    events.value = nextEvents
    selectedIds.value = selectedIds.value.filter(id => nextEvents.some(item => item.id === id
      && ['FAILED', 'DEAD'].includes(item.status)))
  } catch (exception) {
    error.value = exception.message || '异步消息中心加载失败'
  } finally {
    loading.value = false
  }
}

function flash(message) {
  notice.value = message
  window.setTimeout(() => { notice.value = '' }, 2800)
}

function toggleAll() {
  selectedIds.value = allRetryableSelected.value ? [] : retryableEvents.value.map(item => item.id)
}

function toggle(id) {
  selectedIds.value = selectedIds.value.includes(id)
    ? selectedIds.value.filter(item => item !== id)
    : [...selectedIds.value, id]
}

async function retryOne(event) {
  retrying.value = true
  error.value = ''
  try {
    await props.api('/api/v1/admin/async-messages/' + event.id + '/retry', { method: 'POST' })
    flash('消息已重新排队')
    await load(true)
  } catch (exception) {
    error.value = exception.message || '消息重试失败'
  } finally {
    retrying.value = false
  }
}

async function retrySelected() {
  if (!selectedRetryable.value.length) return
  retrying.value = true
  error.value = ''
  try {
    const count = await props.api('/api/v1/admin/async-messages/retry', {
      method: 'POST', body: JSON.stringify({ ids: selectedRetryable.value.map(item => item.id) })
    })
    selectedIds.value = []
    flash('已重新排队 ' + count + ' 条消息')
    await load(true)
  } catch (exception) {
    error.value = exception.message || '批量重试失败'
  } finally {
    retrying.value = false
  }
}

function formatDate(value) {
  if (!value) return '—'
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit'
  }).format(new Date(value))
}

function shortId(value) { return value ? value.slice(0, 8) : '—' }

onMounted(() => {
  load()
  refreshTimer = window.setInterval(() => load(true), 15000)
})
onUnmounted(() => window.clearInterval(refreshTimer))
</script>

<template>
  <section class="message-center">
    <div class="message-hero">
      <div><p>RELIABLE EVENT DELIVERY</p><h2>异步消息中心</h2><span>追踪事务 Outbox、RabbitMQ 发布状态与失败重放，页面每 15 秒自动刷新。</span></div>
      <button :disabled="loading" @click="load()">{{ loading ? '同步中…' : '刷新状态' }}</button>
    </div>

    <div class="message-stats">
      <article><span>待处理积压</span><strong>{{ backlog }}</strong><small>等待、处理中与待重试</small></article>
      <article class="failed"><span>待重试</span><strong>{{ summary.failed }}</strong><small>按指数退避自动恢复</small></article>
      <article class="dead"><span>死信消息</span><strong>{{ summary.dead }}</strong><small>需要管理员检查并重放</small></article>
      <article class="success"><span>发布成功</span><strong>{{ summary.published }}</strong><small>已获得 Broker Confirm</small></article>
      <article><span>最早积压</span><strong class="date-value">{{ formatDate(summary.oldestPendingAt) }}</strong><small>用于判断队列阻塞时间</small></article>
    </div>

    <p v-if="error" class="message-error">{{ error }} <button @click="error = ''">×</button></p>
    <transition name="message-toast"><p v-if="notice" class="message-notice">✓ {{ notice }}</p></transition>

    <div class="message-panel">
      <div class="message-toolbar">
        <input v-model="keyword" placeholder="消息 ID、标题或幂等键" @keyup.enter="load()" />
        <select v-model="status" @change="load()"><option value="">全部状态</option><option v-for="(label, value) in statusLabels" :key="value" :value="value">{{ label }}</option></select>
        <select v-model="aggregateType" @change="load()"><option value="">全部业务域</option><option value="TICKET">工单</option><option value="INCIDENT">重大事件</option><option value="REPORT_SUBSCRIPTION">报表订阅</option><option value="NOTIFICATION">通用通知</option></select>
        <input v-model="notificationType" placeholder="通知类型，如 SLA_OVERDUE" @keyup.enter="load()" />
        <button @click="load()">查询</button>
      </div>

      <div class="message-selection">
        <label><input type="checkbox" :checked="allRetryableSelected" :disabled="!retryableEvents.length" @change="toggleAll" />选择当前失败/死信消息</label>
        <span>共 {{ events.length }} 条，选中 {{ selectedRetryable.length }} 条</span>
        <button :disabled="retrying || !selectedRetryable.length" @click="retrySelected">{{ retrying ? '重新排队中…' : '批量重放' }}</button>
      </div>

      <div v-if="loading && !events.length" class="message-empty">正在读取消息流水…</div>
      <div v-else-if="!events.length" class="message-empty">当前筛选条件下没有消息。</div>
      <div v-else class="message-table-wrap">
        <table class="message-table">
          <thead><tr><th></th><th>消息</th><th>业务对象</th><th>接收人</th><th>状态 / 尝试</th><th>时间</th><th></th></tr></thead>
          <tbody>
            <tr v-for="event in events" :key="event.id" :class="['status-' + event.status.toLowerCase(), { selected: selectedIds.includes(event.id) }]">
              <td><input v-if="['FAILED', 'DEAD'].includes(event.status)" type="checkbox" :checked="selectedIds.includes(event.id)" @change="toggle(event.id)" /></td>
              <td><div class="message-title"><span>{{ typeLabels[event.notificationType] || event.notificationType || event.eventType }}</span><strong>{{ event.title || '异步通知' }}</strong><code :title="event.messageId">{{ shortId(event.messageId) }}</code></div><p v-if="event.lastError" class="message-failure" :title="event.lastError">{{ event.lastError }}</p></td>
              <td><b>{{ event.aggregateType || 'NOTIFICATION' }}</b><small>#{{ event.aggregateId || '—' }}</small></td>
              <td><code>#{{ event.recipientId }}</code></td>
              <td><span :class="['message-status', event.status.toLowerCase()]">{{ statusLabels[event.status] || event.status }}</span><small>第 {{ event.attemptCount || 0 }} 次</small></td>
              <td><time>{{ formatDate(event.createdAt) }}</time><small v-if="event.publishedAt">发布 {{ formatDate(event.publishedAt) }}</small><small v-else-if="event.nextAttemptAt">下次 {{ formatDate(event.nextAttemptAt) }}</small></td>
              <td><button v-if="['FAILED', 'DEAD'].includes(event.status)" class="retry-button" :disabled="retrying" @click="retryOne(event)">重放</button></td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </section>
</template>

<style scoped>
.message-center{padding:28px 30px 48px;color:#263449}.message-hero{display:flex;align-items:center;justify-content:space-between;gap:24px;padding:24px 27px;border-radius:18px;background:linear-gradient(135deg,#12233d,#1b3c50);color:white;box-shadow:0 18px 45px rgba(20,44,65,.16)}.message-hero p{margin:0 0 7px;color:#71e0c0;font-size:10px;font-weight:900;letter-spacing:1.5px}.message-hero h2{margin:0 0 8px;font-size:25px}.message-hero span{color:#b9c9d7;font-size:12px}.message-hero button,.message-toolbar button,.message-selection button{border:0;border-radius:9px;background:#24a888;color:#fff;font-weight:800;cursor:pointer}.message-hero button{padding:11px 17px}.message-hero button:disabled,.message-selection button:disabled{opacity:.45;cursor:not-allowed}.message-stats{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:12px;margin:16px 0}.message-stats article{padding:17px;border:1px solid #e2e9ef;border-radius:13px;background:#fff}.message-stats span,.message-stats small{display:block;color:#798699;font-size:10px}.message-stats strong{display:block;margin:7px 0;color:#263449;font-size:25px}.message-stats .date-value{font-size:14px;line-height:24px}.message-stats .failed{border-color:#f1d6a9;background:#fffcf5}.message-stats .dead{border-color:#f0c6cb;background:#fff8f8}.message-stats .dead strong{color:#b72f45}.message-stats .success{border-color:#bee5d8;background:#f6fdfb}.message-stats .success strong{color:#16866e}.message-error,.message-notice{margin:0 0 12px;padding:10px 13px;border-radius:9px;font-size:11px}.message-error{background:#fff0f2;color:#a8273d}.message-error button{float:right;background:transparent;color:inherit}.message-notice{background:#ecfaf5;color:#177a65}.message-panel{overflow:hidden;border:1px solid #e1e8ef;border-radius:15px;background:#fff;box-shadow:0 10px 30px rgba(23,45,72,.06)}.message-toolbar{display:grid;grid-template-columns:minmax(190px,1.4fr) 150px 160px minmax(180px,1fr) 72px;gap:9px;padding:14px;border-bottom:1px solid #e8edf2;background:#f8fafc}.message-toolbar input,.message-toolbar select{min-width:0;padding:10px;border:1px solid #d6e0e9;border-radius:9px;background:#fff;color:#34435a;outline:none;font-size:11px}.message-toolbar input:focus,.message-toolbar select:focus{border-color:#24a888;box-shadow:0 0 0 3px rgba(36,168,136,.1)}.message-selection{display:flex;align-items:center;gap:15px;padding:11px 15px;border-bottom:1px solid #edf1f4;color:#6c7b8f;font-size:10px}.message-selection label{display:flex;align-items:center;gap:7px;font-weight:800}.message-selection span{margin-left:auto}.message-selection button{padding:8px 12px}.message-empty{padding:60px 20px;color:#8a96a5;text-align:center;font-size:12px}.message-table-wrap{overflow:auto}.message-table{width:100%;border-collapse:collapse;min-width:1020px}.message-table th{padding:10px 12px;background:#fbfcfd;color:#8793a3;font-size:9px;letter-spacing:.5px;text-align:left}.message-table td{padding:12px;border-top:1px solid #edf1f4;vertical-align:middle;font-size:11px}.message-table tr.selected{background:#f1fbf8}.message-table tr.status-dead{background:#fffafb}.message-title{display:grid;grid-template-columns:auto minmax(140px,1fr) auto;align-items:center;gap:8px;min-width:300px}.message-title span{padding:3px 6px;border-radius:5px;background:#edf2f6;color:#566b82;font-size:9px;font-weight:800}.message-title strong{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.message-title code{color:#95a0ae;font-size:9px}.message-failure{max-width:430px;margin:6px 0 0;overflow:hidden;color:#b13548;font-size:9px;text-overflow:ellipsis;white-space:nowrap}.message-table td>b,.message-table td>small,.message-table td>time{display:block}.message-table td>b{color:#53667d;font-size:10px}.message-table td>small,.message-table td>time{margin-top:3px;color:#8995a4;font-size:9px}.message-status{display:inline-block;padding:4px 7px;border-radius:999px;background:#eef2f6;color:#62748a;font-size:9px;font-weight:900}.message-status.failed{background:#fff1d9;color:#99610b}.message-status.dead{background:#ffe6e9;color:#a62d42}.message-status.published{background:#e5f7f1;color:#147862}.message-status.processing{background:#e9efff;color:#4e68bf}.retry-button{padding:7px 10px;border:1px solid #d8aeb5;border-radius:7px;background:#fff;color:#a52f43;font-size:10px;font-weight:800;cursor:pointer}.retry-button:hover{background:#fff2f4}@media(max-width:1100px){.message-stats{grid-template-columns:repeat(3,1fr)}.message-toolbar{grid-template-columns:1fr 1fr}.message-toolbar button{min-height:38px}}@media(max-width:700px){.message-center{padding:20px 14px 36px}.message-hero{align-items:flex-start;flex-direction:column}.message-stats{grid-template-columns:repeat(2,1fr)}.message-toolbar{grid-template-columns:1fr}.message-selection{align-items:flex-start;flex-wrap:wrap}.message-selection span{margin-left:0}.message-selection button{margin-left:auto}}
</style>
