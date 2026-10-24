<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import NotificationDeliveryCenter from './NotificationDeliveryCenter.vue'
import NotificationTemplateCenter from './NotificationTemplateCenter.vue'

const props = defineProps({
  api: { type: Function, required: true },
  downloadCsv: { type: Function, required: true }
})

const summary = ref({ pending: 0, processing: 0, failed: 0, dead: 0, published: 0, oldestPendingAt: null })
const events = ref([])
const deadSummary = ref({ quarantined: 0, requeueing: 0, requeued: 0, discarded: 0, oldestQuarantinedAt: null })
const deadLetters = ref([])
const loading = ref(false)
const retrying = ref(false)
const diagnosticLoading = ref(false)
const error = ref('')
const notice = ref('')
const selectedDiagnostic = ref(null)
const selectedIds = ref([])
const status = ref('')
const notificationType = ref('')
const aggregateType = ref('')
const keyword = ref('')
const activeSection = ref('outbox')
const deadStatus = ref('')
const deadKeyword = ref('')
const selectedDeadIds = ref([])
const selectedDeadDetail = ref(null)
const deadDetailLoading = ref(false)
const deadOperationLoading = ref(false)
const deadOperation = ref(null)
const deadOperationForm = ref({ expectedMessageId: '', reason: '' })
let refreshTimer

const statusLabels = {
  PENDING: '等待发布', PROCESSING: '发布中', FAILED: '等待重试', DEAD: '已进入死信', PUBLISHED: '发布成功'
}
const typeLabels = {
  TICKET_CREATED: '工单创建', TICKET_ASSIGNED: '工单指派', TICKET_STATUS_CHANGED: '工单状态',
  TICKET_GROUP_ROUTED: '支持组路由', AUTOMATION_GROUP_ROUTED: '自动化路由',
  SLA_DUE_SOON: 'SLA 即将到期', SLA_OVERDUE: 'SLA 已逾期',
  SATISFACTION_REQUESTED: '服务评价', INCIDENT_DECLARED: '重大事件宣布',
  INCIDENT_STATUS_CHANGED: '重大事件状态', REPORT_READY: '报表生成',
  CHANGE_APPROVAL_REQUIRED: '变更待审批', CHANGE_APPROVED: '变更已批准',
  CHANGE_REJECTED: '变更已驳回', CHANGE_STATUS_CHANGED: '变更状态',
  MONITORING_ALERT: '监控告警', SERVICE_APPROVAL_REQUIRED: '服务申请待审批',
  SERVICE_REQUEST_RESULT: '服务申请结果', PROBLEM_CREATED: '问题单创建',
  PROBLEM_STATUS_CHANGED: '问题单状态', POSTMORTEM_REVIEW_REQUIRED: '复盘待评审',
  POSTMORTEM_APPROVED: '复盘已通过', POSTMORTEM_REJECTED: '复盘需修改',
  POSTMORTEM_PUBLISHED: '复盘已发布', ONCALL_ESCALATION: '值班升级',
  CAPACITY_THRESHOLD: '容量风险预警'
}
const diagnosticLabels = {
  DELIVERED: '投递完成', QUEUED: '等待发布', PUBLISHING: '发布处理中', ROUTING: '路由异常',
  BROKER_TIMEOUT: 'Broker 超时', BROKER_UNAVAILABLE: 'Broker 不可用',
  BROKER_REJECTED: 'Broker 拒绝', PAYLOAD: '负载异常', PUBLISH_FAILURE: '发布失败'
}
const deadStatusLabels = {
  QUARANTINED: '隔离待处置', REQUEUEING: '重新入队中', REQUEUED: '已重新入队', DISCARDED: '已作废'
}
const retryableEvents = computed(() => events.value.filter(item => ['FAILED', 'DEAD'].includes(item.status)))
const selectedRetryable = computed(() => retryableEvents.value.filter(item => selectedIds.value.includes(item.id)))
const allRetryableSelected = computed(() => retryableEvents.value.length > 0
  && retryableEvents.value.every(item => selectedIds.value.includes(item.id)))
const backlog = computed(() => Number(summary.value.pending || 0) + Number(summary.value.processing || 0)
  + Number(summary.value.failed || 0))
const selectedDeadLetters = computed(() => deadLetters.value.filter(item => selectedDeadIds.value.includes(item.id)))
const allDeadSelected = computed(() => deadLetters.value.length > 0
  && deadLetters.value.every(item => selectedDeadIds.value.includes(item.id)))

function queryString() {
  const params = new URLSearchParams({ limit: '200' })
  if (status.value) params.set('status', status.value)
  if (notificationType.value.trim()) params.set('notificationType', notificationType.value.trim())
  if (aggregateType.value) params.set('aggregateType', aggregateType.value)
  if (keyword.value.trim()) params.set('keyword', keyword.value.trim())
  return params.toString()
}

function deadQueryString() {
  const params = new URLSearchParams({ limit: '200' })
  if (deadStatus.value) params.set('status', deadStatus.value)
  if (deadKeyword.value.trim()) params.set('keyword', deadKeyword.value.trim())
  return params.toString()
}

async function load(silent = false) {
  if (!silent) loading.value = true
  error.value = ''
  try {
    const [nextSummary, nextEvents, nextDeadSummary, nextDeadLetters] = await Promise.all([
      props.api('/api/v1/admin/async-messages/summary'),
      props.api('/api/v1/admin/async-messages?' + queryString()),
      props.api('/api/v1/admin/async-messages/dead-letters/summary'),
      props.api('/api/v1/admin/async-messages/dead-letters?' + deadQueryString())
    ])
    summary.value = nextSummary
    events.value = nextEvents
    deadSummary.value = nextDeadSummary
    deadLetters.value = nextDeadLetters
    selectedIds.value = selectedIds.value.filter(id => nextEvents.some(item => item.id === id
      && ['FAILED', 'DEAD'].includes(item.status)))
    selectedDeadIds.value = selectedDeadIds.value.filter(id => nextDeadLetters.some(item => item.id === id))
  } catch (exception) {
    error.value = exception.message || '异步消息中心加载失败'
  } finally {
    loading.value = false
  }
}

function toggleAllDead() {
  selectedDeadIds.value = allDeadSelected.value ? [] : deadLetters.value.map(item => item.id)
}

function toggleDead(id) {
  selectedDeadIds.value = selectedDeadIds.value.includes(id)
    ? selectedDeadIds.value.filter(item => item !== id)
    : [...selectedDeadIds.value, id]
}

async function exportDeadLetters() {
  if (!selectedDeadLetters.value.length) return
  error.value = ''
  try {
    const ids = selectedDeadLetters.value.map(item => item.id).join(',')
    await props.downloadCsv('/api/v1/admin/async-messages/dead-letters/export.csv?ids=' + encodeURIComponent(ids),
      'opsflow-dead-letters.csv')
    flash('已导出 ' + selectedDeadLetters.value.length + ' 条死信隔离记录')
  } catch (exception) {
    error.value = exception.message || '死信记录导出失败'
  }
}

async function inspectDead(message) {
  deadDetailLoading.value = true
  error.value = ''
  try {
    selectedDeadDetail.value = await props.api('/api/v1/admin/async-messages/dead-letters/' + message.id)
  } catch (exception) {
    error.value = exception.message || '死信详情加载失败'
  } finally {
    deadDetailLoading.value = false
  }
}

function openDeadOperation(message, type) {
  deadOperation.value = { message, type }
  deadOperationForm.value = { expectedMessageId: '', reason: '' }
}

async function submitDeadOperation() {
  const operation = deadOperation.value
  if (!operation) return
  if (deadOperationForm.value.expectedMessageId.trim() !== operation.message.messageId) {
    error.value = '请输入完整且一致的消息 ID 进行二次确认'
    return
  }
  if (deadOperationForm.value.reason.trim().length < 5) {
    error.value = '处置原因至少填写 5 个字符'
    return
  }
  deadOperationLoading.value = true
  error.value = ''
  try {
    await props.api('/api/v1/admin/async-messages/dead-letters/' + operation.message.id + '/' + operation.type, {
      method: 'POST',
      body: JSON.stringify({
        expectedMessageId: deadOperationForm.value.expectedMessageId.trim(),
        reason: deadOperationForm.value.reason.trim()
      })
    })
    flash(operation.type === 'requeue' ? '死信消息已重新进入主队列' : '死信消息已作废')
    deadOperation.value = null
    selectedDeadDetail.value = null
    await load(true)
  } catch (exception) {
    error.value = exception.message || '死信处置失败'
  } finally {
    deadOperationLoading.value = false
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

async function inspect(event) {
  diagnosticLoading.value = true
  error.value = ''
  try {
    selectedDiagnostic.value = await props.api('/api/v1/admin/async-messages/' + event.id)
  } catch (exception) {
    error.value = exception.message || '消息诊断加载失败'
  } finally {
    diagnosticLoading.value = false
  }
}

function formatDate(value) {
  if (!value) return '—'
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit'
  }).format(new Date(value))
}

function shortId(value) { return value ? value.slice(0, 8) : '—' }
function formatAge(seconds) {
  const value = Number(seconds || 0)
  if (value < 60) return value + ' 秒'
  if (value < 3600) return Math.floor(value / 60) + ' 分钟'
  if (value < 86400) return Math.floor(value / 3600) + ' 小时'
  return Math.floor(value / 86400) + ' 天'
}

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
      <article class="dead"><span>发布死信</span><strong>{{ summary.dead }}</strong><small>Outbox 发布重试耗尽</small></article>
      <article class="quarantine"><span>消费隔离</span><strong>{{ deadSummary.quarantined }}</strong><small>Broker 死信待人工处置</small></article>
      <article class="success"><span>发布成功</span><strong>{{ summary.published }}</strong><small>已获得 Broker Confirm</small></article>
      <article><span>最早积压</span><strong class="date-value">{{ formatDate(summary.oldestPendingAt) }}</strong><small>用于判断队列阻塞时间</small></article>
    </div>

    <p v-if="error" class="message-error">{{ error }} <button @click="error = ''">×</button></p>
    <transition name="message-toast"><p v-if="notice" class="message-notice">✓ {{ notice }}</p></transition>

    <nav class="message-tabs" aria-label="消息中心视图">
      <button :class="{ active: activeSection === 'outbox' }" @click="activeSection = 'outbox'">事务 Outbox</button>
      <button :class="{ active: activeSection === 'deadLetters' }" @click="activeSection = 'deadLetters'">死信治理 <span v-if="deadSummary.quarantined">{{ deadSummary.quarantined }}</span></button>
      <button :class="{ active: activeSection === 'deliveries' }" @click="activeSection = 'deliveries'">渠道投递</button>
      <button :class="{ active: activeSection === 'templates' }" @click="activeSection = 'templates'">通知模板</button>
    </nav>

    <div v-if="activeSection === 'outbox'" class="message-panel">
      <div class="message-toolbar">
        <input v-model="keyword" placeholder="消息 ID、标题或幂等键" @keyup.enter="load()" />
        <select v-model="status" @change="load()"><option value="">全部状态</option><option v-for="(label, value) in statusLabels" :key="value" :value="value">{{ label }}</option></select>
        <select v-model="aggregateType" @change="load()"><option value="">全部业务域</option><option value="TICKET">工单</option><option value="INCIDENT">重大事件</option><option value="CHANGE_REQUEST">变更</option><option value="SERVICE_REQUEST">服务申请</option><option value="MONITORING_ALERT">监控告警</option><option value="PROBLEM">问题管理</option><option value="POSTMORTEM">事件复盘</option><option value="ONCALL_ESCALATION">值班升级</option><option value="REPORT_SUBSCRIPTION">报表订阅</option><option value="NOTIFICATION">通用通知</option></select>
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
              <td><div class="message-actions"><button class="diagnostic-button" :disabled="diagnosticLoading" @click="inspect(event)">诊断</button><button v-if="['FAILED', 'DEAD'].includes(event.status)" class="retry-button" :disabled="retrying" @click="retryOne(event)">重放</button></div></td>
            </tr>
          </tbody>
        </table>
      </div>
      <aside v-if="selectedDiagnostic" class="diagnostic-panel">
        <header><div><p>MESSAGE DIAGNOSTIC</p><h3>{{ selectedDiagnostic.event.title || '异步消息诊断' }}</h3></div><button @click="selectedDiagnostic = null">×</button></header>
        <div class="diagnostic-grid">
          <article><span>诊断分类</span><strong>{{ diagnosticLabels[selectedDiagnostic.failureCategory] || selectedDiagnostic.failureCategory }}</strong></article>
          <article><span>消息状态</span><strong>{{ statusLabels[selectedDiagnostic.event.status] || selectedDiagnostic.event.status }}</strong></article>
          <article><span>存续时间</span><strong>{{ formatAge(selectedDiagnostic.ageSeconds) }}</strong></article>
          <article><span>剩余自动重试</span><strong>{{ selectedDiagnostic.automaticAttemptsRemaining }} 次</strong></article>
        </div>
        <p class="diagnostic-recommendation">{{ selectedDiagnostic.recommendation }}</p>
        <dl><div><dt>消息 ID</dt><dd>{{ selectedDiagnostic.event.messageId }}</dd></div><div><dt>路由键</dt><dd>{{ selectedDiagnostic.routingKey }}</dd></div><div><dt>幂等键</dt><dd>{{ selectedDiagnostic.event.dedupeKey }}</dd></div><div v-if="selectedDiagnostic.event.lastError"><dt>最近错误</dt><dd class="diagnostic-error">{{ selectedDiagnostic.event.lastError }}</dd></div></dl>
        <details><summary>查看消息负载</summary><pre>{{ selectedDiagnostic.payloadJson }}</pre></details>
      </aside>
    </div>

    <div v-else-if="activeSection === 'deadLetters'" class="message-panel dead-letter-panel">
      <div class="dead-letter-heading">
        <div><p>BROKER DEAD-LETTER GOVERNANCE</p><h3>死信隔离区</h3><span>消费重试耗尽的消息会从 RabbitMQ DLQ 自动归档；处置操作要求消息 ID 二次确认。</span></div>
        <div class="dead-letter-summary"><b>{{ deadSummary.quarantined }}</b><span>待处置</span><b>{{ deadSummary.requeued }}</b><span>已重投</span><b>{{ deadSummary.discarded }}</b><span>已作废</span></div>
      </div>
      <div class="dead-letter-toolbar">
        <input v-model="deadKeyword" placeholder="消息 ID、标题或幂等键" @keyup.enter="load()" />
        <select v-model="deadStatus" @change="load()"><option value="">全部处置状态</option><option v-for="(label, value) in deadStatusLabels" :key="value" :value="value">{{ label }}</option></select>
        <button @click="load()">查询</button>
      </div>
      <div class="message-selection">
        <label><input type="checkbox" :checked="allDeadSelected" :disabled="!deadLetters.length" @change="toggleAllDead" />选择当前死信记录</label>
        <span>共 {{ deadLetters.length }} 条，选中 {{ selectedDeadLetters.length }} 条</span>
        <button :disabled="!selectedDeadLetters.length" @click="exportDeadLetters">导出所选 CSV</button>
      </div>
      <div v-if="loading && !deadLetters.length" class="message-empty">正在读取死信隔离记录…</div>
      <div v-else-if="!deadLetters.length" class="message-empty">当前没有符合筛选条件的死信消息。</div>
      <div v-else class="message-table-wrap">
        <table class="message-table dead-letter-table">
          <thead><tr><th></th><th>隔离消息</th><th>Broker 原因</th><th>状态</th><th>时间</th><th></th></tr></thead>
          <tbody>
            <tr v-for="message in deadLetters" :key="message.id" :class="{ selected: selectedDeadIds.includes(message.id) }">
              <td><input type="checkbox" :checked="selectedDeadIds.includes(message.id)" @change="toggleDead(message.id)" /></td>
              <td><div class="message-title"><span>{{ typeLabels[message.notificationType] || message.notificationType || 'UNKNOWN' }}</span><strong>{{ message.title || '未命名死信消息' }}</strong><code :title="message.messageId">{{ shortId(message.messageId) }}</code></div><small class="dead-letter-key">{{ message.dedupeKey || '无幂等键' }} · 接收人 #{{ message.recipientId || '—' }}</small></td>
              <td><b>{{ message.deadLetterReason }}</b><small>死亡 {{ message.deathCount || 1 }} 次 · {{ message.sourceQueue }}</small></td>
              <td><span :class="['message-status', 'dead-' + message.status.toLowerCase()]">{{ deadStatusLabels[message.status] || message.status }}</span><small>重投 {{ message.requeueCount || 0 }} 次</small></td>
              <td><time>{{ formatDate(message.quarantinedAt) }}</time><small v-if="message.lastRequeuedAt">重投 {{ formatDate(message.lastRequeuedAt) }}</small></td>
              <td><div class="message-actions"><button class="diagnostic-button" :disabled="deadDetailLoading" @click="inspectDead(message)">查看</button><button v-if="['QUARANTINED', 'REQUEUEING'].includes(message.status)" class="requeue-button" @click="openDeadOperation(message, 'requeue')">{{ message.status === 'REQUEUEING' ? '恢复重投' : '重新入队' }}</button><button v-if="message.status === 'QUARANTINED'" class="discard-button" @click="openDeadOperation(message, 'discard')">作废</button></div></td>
            </tr>
          </tbody>
        </table>
      </div>
      <aside v-if="selectedDeadDetail" class="diagnostic-panel dead-letter-detail">
        <header><div><p>QUARANTINE EVIDENCE</p><h3>{{ selectedDeadDetail.message.title || '死信证据详情' }}</h3></div><button @click="selectedDeadDetail = null">×</button></header>
        <div class="diagnostic-grid">
          <article><span>处置状态</span><strong>{{ deadStatusLabels[selectedDeadDetail.message.status] }}</strong></article>
          <article><span>死亡原因</span><strong>{{ selectedDeadDetail.message.deadLetterReason }}</strong></article>
          <article><span>死亡次数</span><strong>{{ selectedDeadDetail.message.deathCount }} 次</strong></article>
          <article><span>重新入队</span><strong>{{ selectedDeadDetail.message.requeueCount }} 次</strong></article>
        </div>
        <p class="diagnostic-recommendation">{{ selectedDeadDetail.recommendation }}</p>
        <dl><div><dt>消息 ID</dt><dd>{{ selectedDeadDetail.message.messageId }}</dd></div><div><dt>源队列</dt><dd>{{ selectedDeadDetail.message.sourceQueue }}</dd></div><div><dt>原始路由</dt><dd>{{ selectedDeadDetail.message.originalRoutingKey }}</dd></div><div v-if="selectedDeadDetail.resolutionNote"><dt>处置说明</dt><dd>{{ selectedDeadDetail.resolutionNote }}</dd></div><div v-if="selectedDeadDetail.lastError"><dt>重投错误</dt><dd class="diagnostic-error">{{ selectedDeadDetail.lastError }}</dd></div></dl>
        <details><summary>查看原始消息负载</summary><pre>{{ selectedDeadDetail.payloadJson }}</pre></details>
        <details><summary>查看 RabbitMQ 头信息</summary><pre>{{ selectedDeadDetail.headersJson }}</pre></details>
      </aside>
    </div>

    <div v-else-if="activeSection === 'deliveries'" class="message-panel"><NotificationDeliveryCenter :api="api" /></div>
    <div v-else class="message-panel"><NotificationTemplateCenter :api="api" /></div>

    <div v-if="deadOperation" class="dead-operation-backdrop" @click.self="deadOperation = null">
      <form class="dead-operation-card" @submit.prevent="submitDeadOperation">
        <header><div><p>{{ deadOperation.type === 'requeue' ? 'CONTROLLED REQUEUE' : 'CONTROLLED DISCARD' }}</p><h3>{{ deadOperation.type === 'requeue' ? '受控重新入队' : '作废死信消息' }}</h3></div><button type="button" @click="deadOperation = null">×</button></header>
        <p class="dead-operation-warning">{{ deadOperation.type === 'requeue' ? '请确认故障已经消除。重新入队后仍由消费端幂等键保护，但会产生新的投递尝试。' : '作废后消息不会再次投递，该操作将写入审计日志。' }}</p>
        <label>处置原因<textarea v-model="deadOperationForm.reason" maxlength="500" rows="3" placeholder="至少 5 个字符，说明排查结果和处置依据" required></textarea></label>
        <label>输入完整消息 ID 进行确认<input v-model="deadOperationForm.expectedMessageId" :placeholder="deadOperation.message.messageId" autocomplete="off" required /></label>
        <code>{{ deadOperation.message.messageId }}</code>
        <footer><button type="button" class="cancel-button" @click="deadOperation = null">取消</button><button :class="deadOperation.type === 'discard' ? 'danger-confirm' : 'primary-confirm'" :disabled="deadOperationLoading">{{ deadOperationLoading ? '处理中…' : (deadOperation.type === 'requeue' ? '确认重新入队' : '确认作废') }}</button></footer>
      </form>
    </div>
  </section>
</template>

<style scoped>
.message-center{padding:28px 30px 48px;color:#263449}.message-hero{display:flex;align-items:center;justify-content:space-between;gap:24px;padding:24px 27px;border-radius:18px;background:linear-gradient(135deg,#12233d,#1b3c50);color:white;box-shadow:0 18px 45px rgba(20,44,65,.16)}.message-hero p{margin:0 0 7px;color:#71e0c0;font-size:10px;font-weight:900;letter-spacing:1.5px}.message-hero h2{margin:0 0 8px;font-size:25px}.message-hero span{color:#b9c9d7;font-size:12px}.message-hero button,.message-toolbar button,.message-selection button{border:0;border-radius:9px;background:#24a888;color:#fff;font-weight:800;cursor:pointer}.message-hero button{padding:11px 17px}.message-hero button:disabled,.message-selection button:disabled{opacity:.45;cursor:not-allowed}.message-stats{display:grid;grid-template-columns:repeat(6,minmax(0,1fr));gap:12px;margin:16px 0}.message-stats article{padding:17px;border:1px solid #e2e9ef;border-radius:13px;background:#fff}.message-stats span,.message-stats small{display:block;color:#798699;font-size:10px}.message-stats strong{display:block;margin:7px 0;color:#263449;font-size:25px}.message-stats .date-value{font-size:14px;line-height:24px}.message-stats .failed{border-color:#f1d6a9;background:#fffcf5}.message-stats .dead{border-color:#f0c6cb;background:#fff8f8}.message-stats .dead strong{color:#b72f45}.message-stats .quarantine{border-color:#ddcef3;background:#fcf9ff}.message-stats .quarantine strong{color:#7147a8}.message-stats .success{border-color:#bee5d8;background:#f6fdfb}.message-stats .success strong{color:#16866e}.message-error,.message-notice{margin:0 0 12px;padding:10px 13px;border-radius:9px;font-size:11px}.message-error{background:#fff0f2;color:#a8273d}.message-error button{float:right;background:transparent;color:inherit}.message-notice{background:#ecfaf5;color:#177a65}.message-panel{overflow:hidden;border:1px solid #e1e8ef;border-radius:15px;background:#fff;box-shadow:0 10px 30px rgba(23,45,72,.06)}.message-toolbar{display:grid;grid-template-columns:minmax(190px,1.4fr) 150px 160px minmax(180px,1fr) 72px;gap:9px;padding:14px;border-bottom:1px solid #e8edf2;background:#f8fafc}.message-toolbar input,.message-toolbar select{min-width:0;padding:10px;border:1px solid #d6e0e9;border-radius:9px;background:#fff;color:#34435a;outline:none;font-size:11px}.message-toolbar input:focus,.message-toolbar select:focus{border-color:#24a888;box-shadow:0 0 0 3px rgba(36,168,136,.1)}.message-selection{display:flex;align-items:center;gap:15px;padding:11px 15px;border-bottom:1px solid #edf1f4;color:#6c7b8f;font-size:10px}.message-selection label{display:flex;align-items:center;gap:7px;font-weight:800}.message-selection span{margin-left:auto}.message-selection button{padding:8px 12px}.message-empty{padding:60px 20px;color:#8a96a5;text-align:center;font-size:12px}.message-table-wrap{overflow:auto}.message-table{width:100%;border-collapse:collapse;min-width:1020px}.message-table th{padding:10px 12px;background:#fbfcfd;color:#8793a3;font-size:9px;letter-spacing:.5px;text-align:left}.message-table td{padding:12px;border-top:1px solid #edf1f4;vertical-align:middle;font-size:11px}.message-table tr.selected{background:#f1fbf8}.message-table tr.status-dead{background:#fffafb}.message-title{display:grid;grid-template-columns:auto minmax(140px,1fr) auto;align-items:center;gap:8px;min-width:300px}.message-title span{padding:3px 6px;border-radius:5px;background:#edf2f6;color:#566b82;font-size:9px;font-weight:800}.message-title strong{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.message-title code{color:#95a0ae;font-size:9px}.message-failure{max-width:430px;margin:6px 0 0;overflow:hidden;color:#b13548;font-size:9px;text-overflow:ellipsis;white-space:nowrap}.message-table td>b,.message-table td>small,.message-table td>time{display:block}.message-table td>b{color:#53667d;font-size:10px}.message-table td>small,.message-table td>time{margin-top:3px;color:#8995a4;font-size:9px}.message-status{display:inline-block;padding:4px 7px;border-radius:999px;background:#eef2f6;color:#62748a;font-size:9px;font-weight:900}.message-status.failed{background:#fff1d9;color:#99610b}.message-status.dead{background:#ffe6e9;color:#a62d42}.message-status.published{background:#e5f7f1;color:#147862}.message-status.processing{background:#e9efff;color:#4e68bf}.retry-button{padding:7px 10px;border:1px solid #d8aeb5;border-radius:7px;background:#fff;color:#a52f43;font-size:10px;font-weight:800;cursor:pointer}.retry-button:hover{background:#fff2f4}@media(max-width:1200px){.message-stats{grid-template-columns:repeat(3,1fr)}}@media(max-width:1100px){.message-toolbar{grid-template-columns:1fr 1fr}.message-toolbar button{min-height:38px}}@media(max-width:700px){.message-center{padding:20px 14px 36px}.message-hero{align-items:flex-start;flex-direction:column}.message-stats{grid-template-columns:repeat(2,1fr)}.message-toolbar{grid-template-columns:1fr}.message-selection{align-items:flex-start;flex-wrap:wrap}.message-selection span{margin-left:0}.message-selection button{margin-left:auto}}
.message-actions{display:flex;gap:6px}.diagnostic-button{padding:7px 10px;border:1px solid #b9d6d0;border-radius:7px;background:#fff;color:#187961;font-size:10px;font-weight:800;cursor:pointer}.diagnostic-button:hover{background:#f0faf7}.diagnostic-panel{padding:18px;border-top:1px solid #dfe8ee;background:#f8fbfc}.diagnostic-panel header{display:flex;align-items:flex-start;justify-content:space-between}.diagnostic-panel header p{margin:0 0 4px;color:#19836c;font-size:9px;font-weight:900;letter-spacing:1px}.diagnostic-panel h3{margin:0;font-size:16px}.diagnostic-panel header button{border:0;background:transparent;color:#8090a1;font-size:22px;cursor:pointer}.diagnostic-grid{display:grid;grid-template-columns:repeat(4,1fr);gap:9px;margin:14px 0}.diagnostic-grid article{padding:11px;border:1px solid #dfe8ee;border-radius:9px;background:white}.diagnostic-grid span{display:block;color:#8995a4;font-size:9px}.diagnostic-grid strong{display:block;margin-top:5px;font-size:12px}.diagnostic-recommendation{padding:11px;border-radius:8px;background:#e9f7f3;color:#176d5b;font-size:11px}.diagnostic-panel dl{display:grid;gap:6px;margin:12px 0}.diagnostic-panel dl div{display:grid;grid-template-columns:80px 1fr;gap:10px;font-size:10px}.diagnostic-panel dt{color:#8794a3}.diagnostic-panel dd{margin:0;overflow-wrap:anywhere;color:#475a70;font-family:monospace}.diagnostic-panel .diagnostic-error{color:#aa3247}.diagnostic-panel details{font-size:10px}.diagnostic-panel summary{color:#536c82;cursor:pointer;font-weight:800}.diagnostic-panel pre{max-height:220px;margin:9px 0 0;padding:12px;overflow:auto;border-radius:8px;background:#172438;color:#d8e6ef;white-space:pre-wrap;word-break:break-all}@media(max-width:1100px){.diagnostic-grid{grid-template-columns:repeat(2,1fr)}}@media(max-width:700px){.diagnostic-grid{grid-template-columns:1fr}}
.message-tabs{display:flex;gap:5px;margin:0 0 10px;padding:4px;border:1px solid #e1e8ef;border-radius:11px;background:#f4f7f9;width:max-content}.message-tabs button{display:flex;align-items:center;gap:7px;padding:8px 13px;border:0;border-radius:8px;background:transparent;color:#68788c;font-size:10px;font-weight:900;cursor:pointer}.message-tabs button.active{background:#fff;color:#176f5e;box-shadow:0 2px 8px rgba(22,48,68,.08)}.message-tabs span{min-width:18px;padding:2px 5px;border-radius:999px;background:#f8dfe3;color:#a02e42;font-size:8px}.dead-letter-heading{display:flex;align-items:center;justify-content:space-between;gap:20px;padding:18px 20px;background:linear-gradient(120deg,#fbf8ff,#f8fbfc);border-bottom:1px solid #e8edf2}.dead-letter-heading p{margin:0 0 4px;color:#724aa4;font-size:9px;font-weight:900;letter-spacing:1px}.dead-letter-heading h3{margin:0 0 4px;font-size:16px}.dead-letter-heading>div>span{color:#7f8c9d;font-size:10px}.dead-letter-summary{display:grid;grid-template-columns:auto auto;align-items:center;gap:2px 7px;padding:9px 13px;border:1px solid #e3dcef;border-radius:10px;background:#fff}.dead-letter-summary b{color:#67438e;font-size:14px}.dead-letter-summary span{color:#8b7b9d;font-size:8px}.dead-letter-toolbar{display:grid;grid-template-columns:1fr 180px 72px;gap:9px;padding:13px 14px;border-bottom:1px solid #e8edf2}.dead-letter-toolbar input,.dead-letter-toolbar select{padding:10px;border:1px solid #d6e0e9;border-radius:9px;background:#fff;color:#34435a;font-size:11px}.dead-letter-toolbar button{border:0;border-radius:9px;background:#72519a;color:#fff;font-weight:800;cursor:pointer}.dead-letter-key{display:block;margin-top:6px!important}.message-status.dead-quarantined{background:#f1e7fb;color:#72459d}.message-status.dead-requeueing{background:#e8efff;color:#4867b1}.message-status.dead-requeued{background:#e4f6ef;color:#13745e}.message-status.dead-discarded{background:#edf0f3;color:#68727e}.requeue-button,.discard-button{padding:7px 9px;border-radius:7px;background:#fff;font-size:9px;font-weight:800;cursor:pointer}.requeue-button{border:1px solid #a8cfc5;color:#16745e}.discard-button{border:1px solid #e0b9c0;color:#a53245}.dead-letter-detail details+details{margin-top:10px}.dead-operation-backdrop{position:fixed;z-index:1000;inset:0;display:grid;place-items:center;padding:20px;background:rgba(15,28,44,.58);backdrop-filter:blur(3px)}.dead-operation-card{width:min(520px,100%);padding:22px;border-radius:16px;background:#fff;box-shadow:0 24px 70px rgba(10,24,38,.28)}.dead-operation-card header{display:flex;align-items:flex-start;justify-content:space-between}.dead-operation-card header p{margin:0 0 5px;color:#76509f;font-size:9px;font-weight:900;letter-spacing:1px}.dead-operation-card h3{margin:0;font-size:18px}.dead-operation-card header button{border:0;background:transparent;color:#7e8998;font-size:22px;cursor:pointer}.dead-operation-warning{margin:15px 0;padding:11px;border-radius:9px;background:#fff7e7;color:#825a17;font-size:10px;line-height:1.6}.dead-operation-card label{display:grid;gap:6px;margin-top:12px;color:#596a7d;font-size:10px;font-weight:800}.dead-operation-card input,.dead-operation-card textarea{padding:10px;border:1px solid #d8e0e8;border-radius:8px;color:#324156;font:inherit;resize:vertical}.dead-operation-card>code{display:block;margin-top:7px;padding:7px;border-radius:6px;background:#f3f5f7;color:#6b7888;font-size:9px;overflow-wrap:anywhere}.dead-operation-card footer{display:flex;justify-content:flex-end;gap:8px;margin-top:18px}.dead-operation-card footer button{padding:9px 14px;border-radius:8px;font-size:10px;font-weight:900;cursor:pointer}.cancel-button{border:1px solid #d6dee6;background:#fff;color:#627186}.primary-confirm{border:0;background:#208b72;color:#fff}.danger-confirm{border:0;background:#b43d50;color:#fff}@media(max-width:700px){.message-tabs{width:100%}.message-tabs button{flex:1;justify-content:center}.dead-letter-heading{align-items:flex-start;flex-direction:column}.dead-letter-toolbar{grid-template-columns:1fr}.dead-letter-toolbar button{min-height:38px}}
</style>
