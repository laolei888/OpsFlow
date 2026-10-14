<script setup>
import { computed, onMounted, reactive, ref } from 'vue'

const props = defineProps({
  api: { type: Function, required: true },
  assets: { type: Array, default: () => [] },
  staffMembers: { type: Array, default: () => [] },
  isAdmin: { type: Boolean, default: false }
})

const alerts = ref([])
const summary = ref({ active: 0, unacknowledged: 0, critical: 0, suppressed: 0, resolvedLast24Hours: 0 })
const sources = ref([])
const rules = ref([])
const silences = ref([])
const schedules = ref([])
const selectedDetail = ref(null)
const selectedAlertId = ref('')
const loading = ref(false)
const detailLoading = ref(false)
const saving = ref(false)
const error = ref('')
const notice = ref('')
const keyword = ref('')
const statusFilter = ref('')
const severityFilter = ref('')
const sourceFilter = ref('')

const showSourceModal = ref(false)
const showRuleModal = ref(false)
const showSilenceModal = ref(false)
const showCredentialModal = ref(false)
const showSuppressModal = ref(false)
const editingSource = ref(null)
const editingRule = ref(null)
const editingSilence = ref(null)
const credential = ref(null)

const sourceForm = reactive({ name: '', description: '', defaultAssetId: '', enabled: true })
const ruleForm = reactive({ name: '', description: '', sourceId: '', minimumSeverity: 'HIGH', action: 'NOTIFY_ONCALL', scheduleId: '', sortOrder: 100, enabled: true })
const silenceForm = reactive({ name: '', sourceId: '', assetId: '', fingerprintPrefix: '', startsAt: '', endsAt: '', reason: '', enabled: true })
const suppressForm = reactive({ note: '' })

const severityLabels = { CRITICAL: '严重', HIGH: '高', MEDIUM: '中', LOW: '低', INFO: '信息' }
const statusLabels = { FIRING: '待确认', ACKNOWLEDGED: '处理中', SUPPRESSED: '已抑制', RESOLVED: '已解决' }
const actionLabels = { NOTIFY_ONCALL: '通知当前值班人', CREATE_TICKET: '自动创建工单', CREATE_INCIDENT: '自动宣布重大事件' }
const eventLabels = { TRIGGERED: '首次触发', REPEATED: '重复聚合', ACKNOWLEDGED: '客服确认', RESOLVED: '告警恢复', REOPENED: '重新触发', SUPPRESSED: '告警抑制', RULE_MATCHED: '规则命中', TICKET_CREATED: '自动建单', INCIDENT_CREATED: '重大事件' }

const selectedAlert = computed(() => selectedDetail.value?.alert || alerts.value.find(item => String(item.id) === String(selectedAlertId.value)) || null)
const activeSilences = computed(() => {
  const now = Date.now()
  return silences.value.filter(item => item.enabled !== false && new Date(item.startsAt).getTime() <= now && new Date(item.endsAt).getTime() > now)
})
const enabledRuleCount = computed(() => rules.value.filter(item => item.enabled !== false).length)
const healthySourceCount = computed(() => sources.value.filter(item => item.enabled !== false && item.lastReceivedAt).length)

function pad(value) { return String(value).padStart(2, '0') }
function toDateTimeInput(value) {
  const date = value ? new Date(value) : new Date()
  return date.getFullYear() + '-' + pad(date.getMonth() + 1) + '-' + pad(date.getDate())
    + 'T' + pad(date.getHours()) + ':' + pad(date.getMinutes())
}
function afterHours(hours) { return toDateTimeInput(new Date(Date.now() + hours * 60 * 60 * 1000)) }
function formatDate(value) {
  if (!value) return '-'
  return new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}
function severityLabel(value) { return severityLabels[value] || value || '-' }
function statusLabel(value) { return statusLabels[value] || value || '-' }
function actionLabel(value) { return actionLabels[value] || value || '-' }
function eventLabel(value) { return eventLabels[value] || value || '-' }
function sourceName(id) { return sources.value.find(item => String(item.id) === String(id))?.name || '告警源 #' + id }
function assetName(id) {
  if (!id) return '未关联资产'
  const asset = props.assets.find(item => String(item.id) === String(id))
  return asset ? asset.assetNo + ' · ' + asset.name : '资产 #' + id
}
function staffName(id) {
  if (!id) return '系统'
  return props.staffMembers.find(item => String(item.id) === String(id))?.displayName || '用户 #' + id
}
function scheduleName(id) { return schedules.value.find(item => String(item.id) === String(id))?.name || (id ? '值班表 #' + id : '全部当前值班人') }
function statusClass(value) { return 'alert-status-' + String(value || '').toLowerCase() }
function severityClass(value) { return 'alert-severity-' + String(value || '').toLowerCase() }
function labelsOf(alert) {
  if (!alert?.labelsJson) return []
  try { return Object.entries(JSON.parse(alert.labelsJson)) } catch { return [] }
}
function notify(message) {
  notice.value = message
  window.setTimeout(() => { notice.value = '' }, 3200)
}
function queryString() {
  const params = new URLSearchParams({ limit: '200' })
  if (keyword.value.trim()) params.set('keyword', keyword.value.trim())
  if (statusFilter.value) params.set('status', statusFilter.value)
  if (severityFilter.value) params.set('severity', severityFilter.value)
  if (sourceFilter.value) params.set('sourceId', sourceFilter.value)
  return params.toString()
}

async function loadData(keepSelection = true) {
  loading.value = true
  error.value = ''
  try {
    const [alertList, summaryData, sourceList, ruleList, silenceList, scheduleList] = await Promise.all([
      props.api('/api/v1/alert-center/alerts?' + queryString()),
      props.api('/api/v1/alert-center/summary'),
      props.api('/api/v1/alert-center/sources'),
      props.api('/api/v1/alert-center/rules'),
      props.api('/api/v1/alert-center/silences'),
      props.api('/api/v1/oncall/schedules')
    ])
    alerts.value = alertList || []
    summary.value = summaryData || summary.value
    sources.value = sourceList || []
    rules.value = ruleList || []
    silences.value = silenceList || []
    schedules.value = scheduleList || []
    if (keepSelection && selectedAlertId.value && alerts.value.some(item => String(item.id) === String(selectedAlertId.value))) {
      await loadDetail(selectedAlertId.value)
    } else if (!keepSelection) {
      selectedAlertId.value = ''
      selectedDetail.value = null
    }
  } catch (err) { error.value = err.message }
  finally { loading.value = false }
}

async function loadDetail(id) {
  selectedAlertId.value = id
  detailLoading.value = true
  error.value = ''
  try { selectedDetail.value = await props.api('/api/v1/alert-center/alerts/' + id) }
  catch (err) { error.value = err.message }
  finally { detailLoading.value = false }
}

async function transition(action, body = null, successMessage = '操作成功') {
  if (!selectedAlert.value) return
  saving.value = true
  error.value = ''
  try {
    await props.api('/api/v1/alert-center/alerts/' + selectedAlert.value.id + '/' + action, {
      method: 'POST', body: body === null ? undefined : JSON.stringify(body)
    })
    showSuppressModal.value = false
    await loadData(true)
    notify(successMessage)
  } catch (err) { error.value = err.message }
  finally { saving.value = false }
}
function acknowledgeAlert() { transition('acknowledge', null, '告警已确认并进入处理') }
function resolveAlert() {
  const note = window.prompt('填写解决说明（可选）', '')
  if (note === null) return
  transition('resolve', { note }, '告警已解决')
}
function reopenAlert() {
  const note = window.prompt('填写重新打开原因（可选）', '')
  if (note === null) return
  transition('reopen', { note }, '告警已重新打开并重新执行规则')
}
function openSuppressModal() {
  suppressForm.note = ''
  showSuppressModal.value = true
}
function suppressAlert() { transition('suppress', { note: suppressForm.note }, '告警已抑制') }

async function startRecommendedRunbook(recommendation) {
  if (!selectedAlert.value || !recommendation?.runbook) return
  if (!window.confirm('确认按推荐手册「' + recommendation.runbook.name + '」发起人工处置吗？平台将创建可审计的步骤快照，不会自动执行服务器命令。')) return
  saving.value = true
  error.value = ''
  try {
    const execution = await props.api('/api/v1/runbooks/executions', {
      method: 'POST', body: JSON.stringify({ runbookId: recommendation.runbook.id, sourceType: 'ALERT', sourceId: selectedAlert.value.id })
    })
    notify('已发起运行手册处置：' + execution.executionNo)
  } catch (err) { error.value = err.message || '发起运行手册处置失败' }
  finally { saving.value = false }
}

function openSourceModal(source = null) {
  editingSource.value = source
  Object.assign(sourceForm, {
    name: source?.name || '', description: source?.description || '',
    defaultAssetId: source?.defaultAssetId || '', enabled: source?.enabled !== false
  })
  showSourceModal.value = true
}
async function saveSource() {
  saving.value = true
  error.value = ''
  try {
    const payload = { ...sourceForm, defaultAssetId: sourceForm.defaultAssetId ? Number(sourceForm.defaultAssetId) : null }
    const path = editingSource.value ? '/api/v1/alert-center/sources/' + editingSource.value.id : '/api/v1/alert-center/sources'
    const result = await props.api(path, { method: editingSource.value ? 'PUT' : 'POST', body: JSON.stringify(payload) })
    showSourceModal.value = false
    if (!editingSource.value) showCredential(result)
    await loadData(true)
    notify(editingSource.value ? '告警源已更新' : '告警源已创建')
  } catch (err) { error.value = err.message }
  finally { saving.value = false }
}
async function rotateSourceKey(source) {
  if (!window.confirm('轮换后旧密钥会立即失效，是否继续？')) return
  try {
    const result = await props.api('/api/v1/alert-center/sources/' + source.id + '/rotate-key', { method: 'POST' })
    showCredential(result)
    await loadData(true)
  } catch (err) { error.value = err.message }
}
function showCredential(result) {
  credential.value = {
    source: result.source,
    ingestKey: result.ingestKey,
    endpoint: window.location.origin + '/api/v1/alert-ingest/' + result.source.sourceKey
  }
  showCredentialModal.value = true
}
async function copyText(value, message) {
  try { await navigator.clipboard.writeText(value); notify(message) }
  catch { error.value = '浏览器未允许复制，请手动选择文本' }
}
function sampleCurl() {
  if (!credential.value) return ''
  return `curl -X POST "${credential.value.endpoint}" \\\n  -H "X-OpsFlow-Alert-Key: ${credential.value.ingestKey}" \\\n  -H "Content-Type: application/json" \\\n  -d '{"fingerprint":"order-api:error-rate","title":"订单 API 错误率过高","description":"5 分钟错误率超过 10%","severity":"HIGH","state":"FIRING","labels":{"service":"order-api","env":"prod"}}'`
}

function openRuleModal(rule = null) {
  editingRule.value = rule
  Object.assign(ruleForm, {
    name: rule?.name || '', description: rule?.description || '', sourceId: rule?.sourceId || '',
    minimumSeverity: rule?.minimumSeverity || 'HIGH', action: rule?.action || 'NOTIFY_ONCALL',
    scheduleId: rule?.scheduleId || '', sortOrder: rule?.sortOrder ?? 100, enabled: rule?.enabled !== false
  })
  showRuleModal.value = true
}
async function saveRule() {
  saving.value = true
  error.value = ''
  try {
    const payload = { ...ruleForm, sourceId: ruleForm.sourceId ? Number(ruleForm.sourceId) : null,
      scheduleId: ruleForm.scheduleId ? Number(ruleForm.scheduleId) : null, sortOrder: Number(ruleForm.sortOrder) }
    const path = editingRule.value ? '/api/v1/alert-center/rules/' + editingRule.value.id : '/api/v1/alert-center/rules'
    await props.api(path, { method: editingRule.value ? 'PUT' : 'POST', body: JSON.stringify(payload) })
    showRuleModal.value = false
    await loadData(true)
    notify(editingRule.value ? '告警规则已更新' : '告警规则已创建')
  } catch (err) { error.value = err.message }
  finally { saving.value = false }
}

function openSilenceModal(silence = null) {
  editingSilence.value = silence
  Object.assign(silenceForm, {
    name: silence?.name || '', sourceId: silence?.sourceId || '', assetId: silence?.assetId || '',
    fingerprintPrefix: silence?.fingerprintPrefix || '', startsAt: silence ? toDateTimeInput(silence.startsAt) : toDateTimeInput(),
    endsAt: silence ? toDateTimeInput(silence.endsAt) : afterHours(2), reason: silence?.reason || '', enabled: silence?.enabled !== false
  })
  showSilenceModal.value = true
}
async function saveSilence() {
  saving.value = true
  error.value = ''
  try {
    const payload = { ...silenceForm, sourceId: silenceForm.sourceId ? Number(silenceForm.sourceId) : null,
      assetId: silenceForm.assetId ? Number(silenceForm.assetId) : null }
    const path = editingSilence.value ? '/api/v1/alert-center/silences/' + editingSilence.value.id : '/api/v1/alert-center/silences'
    await props.api(path, { method: editingSilence.value ? 'PUT' : 'POST', body: JSON.stringify(payload) })
    showSilenceModal.value = false
    await loadData(true)
    notify(editingSilence.value ? '静默窗口已更新' : '静默窗口已创建')
  } catch (err) { error.value = err.message }
  finally { saving.value = false }
}
async function deleteSilence(silence) {
  if (!window.confirm('确定删除静默窗口「' + silence.name + '」吗？')) return
  try {
    await props.api('/api/v1/alert-center/silences/' + silence.id, { method: 'DELETE' })
    await loadData(true)
    notify('静默窗口已删除')
  } catch (err) { error.value = err.message }
}

onMounted(() => loadData(false))
</script>

<template>
  <section class="alert-center">
    <div class="alert-toolbar">
      <div><p class="alert-eyebrow">OBSERVABILITY · SIGNAL TO ACTION</p><h2>监控告警中心</h2><small>告警去重、静默、值班通知与事件自动化在同一个响应闭环中</small></div>
      <div class="alert-toolbar-actions"><button class="alert-secondary" :disabled="loading" @click="loadData(true)">刷新</button><button v-if="isAdmin" class="alert-secondary" @click="openSilenceModal()">＋ 静默窗口</button><button v-if="isAdmin" class="alert-secondary" @click="openRuleModal()">＋ 自动化规则</button><button v-if="isAdmin" class="alert-primary" @click="openSourceModal()">＋ 接入告警源</button></div>
    </div>

    <p v-if="error" class="alert-message error">{{ error }} <button @click="error = ''">×</button></p>
    <p v-if="notice" class="alert-message success">{{ notice }}</p>

    <div class="alert-summary">
      <article><span>活跃告警</span><strong>{{ summary.active }}</strong><small>待确认与处理中</small></article>
      <article :class="{ danger: summary.critical }"><span>严重告警</span><strong>{{ summary.critical }}</strong><small>CRITICAL 活跃信号</small></article>
      <article :class="{ attention: summary.unacknowledged }"><span>尚未确认</span><strong>{{ summary.unacknowledged }}</strong><small>需要客服响应</small></article>
      <article><span>静默抑制</span><strong>{{ summary.suppressed }}</strong><small>{{ activeSilences.length }} 个生效窗口</small></article>
      <article><span>24h 已恢复</span><strong>{{ summary.resolvedLast24Hours }}</strong><small>近期恢复信号</small></article>
    </div>

    <div class="alert-workspace">
      <section class="alert-panel alert-queue">
        <div class="alert-panel-heading"><div><p class="alert-eyebrow">ALERT QUEUE</p><h3>实时告警队列</h3></div><span>{{ alerts.length }} 条</span></div>
        <div class="alert-filters">
          <input v-model="keyword" placeholder="搜索编号、标题、指纹…" @keyup.enter="loadData(false)" />
          <select v-model="statusFilter" @change="loadData(false)"><option value="">全部状态</option><option v-for="(label, key) in statusLabels" :key="key" :value="key">{{ label }}</option></select>
          <select v-model="severityFilter" @change="loadData(false)"><option value="">全部等级</option><option v-for="(label, key) in severityLabels" :key="key" :value="key">{{ label }}</option></select>
          <select v-model="sourceFilter" @change="loadData(false)"><option value="">全部来源</option><option v-for="source in sources" :key="source.id" :value="source.id">{{ source.name }}</option></select>
          <button class="alert-secondary" @click="loadData(false)">筛选</button>
        </div>
        <div v-if="loading" class="alert-empty">正在同步监控信号…</div>
        <div v-else-if="!alerts.length" class="alert-empty">当前没有符合条件的告警。配置告警源后，可使用生成的接入地址推送第一条信号。</div>
        <div v-else class="alert-list">
          <article v-for="alert in alerts" :key="alert.id" :class="['alert-card', { selected: String(selectedAlertId) === String(alert.id) }]" @click="loadDetail(alert.id)">
            <div class="alert-card-head"><div><code>{{ alert.alertNo }}</code><strong>{{ alert.title }}</strong></div><span :class="['alert-severity', severityClass(alert.severity)]">{{ severityLabel(alert.severity) }}</span></div>
            <p>{{ alert.description || '暂无告警描述' }}</p>
            <div class="alert-card-meta"><span :class="['alert-status', statusClass(alert.status)]">{{ statusLabel(alert.status) }}</span><span>{{ sourceName(alert.sourceId) }}</span><span>聚合 {{ alert.occurrenceCount }} 次</span></div>
            <footer><small>{{ formatDate(alert.lastSeenAt) }} · {{ assetName(alert.assetId) }}</small><span v-if="alert.incidentId">INC #{{ alert.incidentId }}</span><span v-else-if="alert.ticketId">TICKET #{{ alert.ticketId }}</span></footer>
          </article>
        </div>
      </section>

      <section class="alert-panel alert-detail">
        <div v-if="!selectedAlert" class="alert-empty detail-placeholder"><b>选择一条告警</b><span>查看指纹聚合、资产上下文、规则动作和完整响应时间线。</span></div>
        <div v-else-if="detailLoading" class="alert-empty">正在读取告警上下文…</div>
        <template v-else>
          <div class="alert-detail-head"><div><p class="alert-eyebrow">{{ selectedAlert.alertNo }} · GENERATION {{ selectedAlert.generation }}</p><h2>{{ selectedAlert.title }}</h2><small>{{ sourceName(selectedAlert.sourceId) }} · 首次 {{ formatDate(selectedAlert.firstSeenAt) }}</small></div><div><span :class="['alert-severity large', severityClass(selectedAlert.severity)]">{{ severityLabel(selectedAlert.severity) }}</span><span :class="['alert-status', statusClass(selectedAlert.status)]">{{ statusLabel(selectedAlert.status) }}</span></div></div>
          <div class="alert-actions">
            <button v-if="selectedAlert.status === 'FIRING'" class="alert-primary" :disabled="saving" @click="acknowledgeAlert">确认并处理</button>
            <button v-if="['FIRING', 'ACKNOWLEDGED'].includes(selectedAlert.status)" class="alert-secondary" :disabled="saving" @click="resolveAlert">标记解决</button>
            <button v-if="selectedAlert.status !== 'RESOLVED' && selectedAlert.status !== 'SUPPRESSED'" class="alert-secondary danger-text" :disabled="saving" @click="openSuppressModal">人工抑制</button>
            <button v-if="['RESOLVED', 'SUPPRESSED'].includes(selectedAlert.status)" class="alert-primary" :disabled="saving" @click="reopenAlert">重新打开</button>
          </div>
          <div class="alert-facts">
            <span><b>告警指纹</b><code>{{ selectedAlert.fingerprint }}</code></span>
            <span><b>受影响资产</b>{{ assetName(selectedAlert.assetId) }}</span>
            <span><b>外部编号</b>{{ selectedAlert.externalAlertId || '-' }}</span>
            <span><b>出现次数</b>{{ selectedAlert.occurrenceCount }}</span>
            <span><b>确认人</b>{{ staffName(selectedAlert.acknowledgedBy) }}</span>
            <span><b>最近出现</b>{{ formatDate(selectedAlert.lastSeenAt) }}</span>
          </div>
          <p class="alert-description">{{ selectedAlert.description || '暂无详细描述' }}</p>
          <div v-if="labelsOf(selectedAlert).length" class="alert-labels"><span v-for="([key, value]) in labelsOf(selectedAlert)" :key="key"><b>{{ key }}</b>{{ value }}</span></div>
          <div class="alert-link-strip"><div><small>命中规则</small><strong>{{ selectedDetail?.rule?.name || '未命中自动化规则' }}</strong></div><div><small>自动工单</small><strong>{{ selectedAlert.ticketId ? '#' + selectedAlert.ticketId : '未创建' }}</strong></div><div><small>重大事件</small><strong>{{ selectedAlert.incidentId ? '#' + selectedAlert.incidentId : '未创建' }}</strong></div></div>
          <section class="alert-runbook-recommendations"><div class="alert-section-heading"><div><p class="alert-eyebrow">RUNBOOK RECOMMENDATION</p><h3>推荐处置手册</h3></div><span>{{ selectedDetail?.runbookRecommendations?.length || 0 }} 条匹配</span></div><p class="runbook-recommendation-hint">推荐基于管理员配置的告警源、严重度和关键词规则；客服需人工确认，平台不会自动操作真实环境。</p><div v-if="!selectedDetail?.runbookRecommendations?.length" class="alert-empty compact">暂无匹配手册。管理员可在“运行手册”配置告警推荐规则。</div><div v-else class="alert-runbook-list"><article v-for="item in selectedDetail.runbookRecommendations" :key="item.matcher.id"><div><strong>{{ item.runbook.name }} <small>v{{ item.runbook.versionNo }}</small></strong><p>{{ item.runbook.category }} · {{ item.reasons.join(' · ') }}</p></div><button class="alert-secondary" :disabled="saving || ['RESOLVED', 'SUPPRESSED'].includes(selectedAlert.status)" @click="startRecommendedRunbook(item)">发起处置</button></article></div></section>
          <section class="alert-timeline"><div class="alert-section-heading"><div><p class="alert-eyebrow">RESPONSE TIMELINE</p><h3>响应时间线</h3></div><span>{{ selectedDetail?.events?.length || 0 }} 个节点</span></div><div v-if="!selectedDetail?.events?.length" class="alert-empty">暂无时间线记录。</div><div v-else class="alert-events"><div v-for="event in selectedDetail.events" :key="event.id" class="alert-event"><i></i><div><header><strong>{{ eventLabel(event.eventType) }}</strong><time>{{ formatDate(event.createdAt) }}</time></header><p>{{ event.message }}</p><small>{{ staffName(event.actorId) }}</small></div></div></div></section>
        </template>
      </section>
    </div>

    <section class="alert-config-grid">
      <div class="alert-panel"><div class="alert-panel-heading"><div><p class="alert-eyebrow">INGESTION SOURCES</p><h3>监控源</h3></div><span>{{ healthySourceCount }}/{{ sources.length }} 已接收</span></div><div v-if="!sources.length" class="alert-empty">还没有接入 Prometheus、Grafana 或其他监控源。</div><div v-else class="alert-config-list"><article v-for="source in sources" :key="source.id"><div><strong>{{ source.name }}</strong><small>{{ source.sourceKey }} · {{ source.enabled ? '启用' : '停用' }}</small><small>最后接收：{{ formatDate(source.lastReceivedAt) }}</small></div><div v-if="isAdmin"><button @click="openSourceModal(source)">编辑</button><button @click="rotateSourceKey(source)">轮换密钥</button></div></article></div></div>
      <div class="alert-panel"><div class="alert-panel-heading"><div><p class="alert-eyebrow">AUTOMATION POLICY</p><h3>告警规则</h3></div><span>{{ enabledRuleCount }}/{{ rules.length }} 启用</span></div><div v-if="!rules.length" class="alert-empty">未配置规则的告警只会入队，不会自动通知或建单。</div><div v-else class="alert-config-list"><article v-for="rule in rules" :key="rule.id"><div><strong>{{ rule.sortOrder }} · {{ rule.name }}</strong><small>{{ rule.sourceId ? sourceName(rule.sourceId) : '全部告警源' }} · ≥ {{ severityLabel(rule.minimumSeverity) }}</small><small>{{ actionLabel(rule.action) }} · {{ scheduleName(rule.scheduleId) }}</small></div><button v-if="isAdmin" @click="openRuleModal(rule)">编辑</button></article></div></div>
      <div class="alert-panel"><div class="alert-panel-heading"><div><p class="alert-eyebrow">MAINTENANCE WINDOWS</p><h3>静默窗口</h3></div><span>{{ activeSilences.length }} 个生效</span></div><div v-if="!silences.length" class="alert-empty">暂无静默窗口，计划维护期间可按来源、资产或指纹抑制噪声。</div><div v-else class="alert-config-list"><article v-for="silence in silences" :key="silence.id"><div><strong>{{ silence.name }}</strong><small>{{ formatDate(silence.startsAt) }} → {{ formatDate(silence.endsAt) }}</small><small>{{ silence.reason }}</small></div><div v-if="isAdmin"><button @click="openSilenceModal(silence)">编辑</button><button class="danger-text" @click="deleteSilence(silence)">删除</button></div></article></div></div>
    </section>

    <div v-if="showSourceModal" class="alert-modal-backdrop" @click.self="showSourceModal = false"><form class="alert-modal" @submit.prevent="saveSource"><header><div><p class="alert-eyebrow">INGESTION SOURCE</p><h3>{{ editingSource ? '编辑告警源' : '接入告警源' }}</h3></div><button type="button" @click="showSourceModal = false">×</button></header><label>名称<input v-model="sourceForm.name" required maxlength="100" placeholder="Prometheus 生产集群" /></label><label>说明<textarea v-model="sourceForm.description" maxlength="1000" placeholder="说明来源、环境和负责人"></textarea></label><label>默认关联资产<select v-model="sourceForm.defaultAssetId"><option value="">不自动关联</option><option v-for="asset in assets" :key="asset.id" :value="asset.id">{{ asset.assetNo }} · {{ asset.name }}</option></select></label><label class="alert-checkbox"><input v-model="sourceForm.enabled" type="checkbox" /> 启用该告警源</label><footer><button type="button" class="alert-secondary" @click="showSourceModal = false">取消</button><button class="alert-primary" :disabled="saving">{{ saving ? '保存中…' : '保存告警源' }}</button></footer></form></div>

    <div v-if="showRuleModal" class="alert-modal-backdrop" @click.self="showRuleModal = false"><form class="alert-modal" @submit.prevent="saveRule"><header><div><p class="alert-eyebrow">AUTOMATION RULE</p><h3>{{ editingRule ? '编辑告警规则' : '创建告警规则' }}</h3></div><button type="button" @click="showRuleModal = false">×</button></header><label>名称<input v-model="ruleForm.name" required maxlength="100" placeholder="生产严重告警自动升级" /></label><label>说明<textarea v-model="ruleForm.description" maxlength="1000"></textarea></label><div class="alert-form-grid"><label>告警源<select v-model="ruleForm.sourceId"><option value="">全部告警源</option><option v-for="source in sources" :key="source.id" :value="source.id">{{ source.name }}</option></select></label><label>最低严重度<select v-model="ruleForm.minimumSeverity"><option v-for="(label, key) in severityLabels" :key="key" :value="key">{{ label }}</option></select></label><label>执行动作<select v-model="ruleForm.action"><option v-for="(label, key) in actionLabels" :key="key" :value="key">{{ label }}</option></select></label><label>通知值班表<select v-model="ruleForm.scheduleId"><option value="">全部当前值班人</option><option v-for="schedule in schedules" :key="schedule.id" :value="schedule.id">{{ schedule.name }}</option></select></label><label>匹配顺序<input v-model.number="ruleForm.sortOrder" type="number" min="0" max="9999" /></label></div><label class="alert-checkbox"><input v-model="ruleForm.enabled" type="checkbox" /> 启用该规则</label><footer><button type="button" class="alert-secondary" @click="showRuleModal = false">取消</button><button class="alert-primary" :disabled="saving">{{ saving ? '保存中…' : '保存规则' }}</button></footer></form></div>

    <div v-if="showSilenceModal" class="alert-modal-backdrop" @click.self="showSilenceModal = false"><form class="alert-modal" @submit.prevent="saveSilence"><header><div><p class="alert-eyebrow">MAINTENANCE WINDOW</p><h3>{{ editingSilence ? '编辑静默窗口' : '创建静默窗口' }}</h3></div><button type="button" @click="showSilenceModal = false">×</button></header><label>名称<input v-model="silenceForm.name" required maxlength="100" placeholder="订单系统发布窗口" /></label><div class="alert-form-grid"><label>限定告警源<select v-model="silenceForm.sourceId"><option value="">不限来源</option><option v-for="source in sources" :key="source.id" :value="source.id">{{ source.name }}</option></select></label><label>限定资产<select v-model="silenceForm.assetId"><option value="">不限资产</option><option v-for="asset in assets" :key="asset.id" :value="asset.id">{{ asset.assetNo }} · {{ asset.name }}</option></select></label><label>指纹前缀<input v-model="silenceForm.fingerprintPrefix" maxlength="128" placeholder="order-api:" /></label><label>开始时间<input v-model="silenceForm.startsAt" type="datetime-local" required /></label><label>结束时间<input v-model="silenceForm.endsAt" type="datetime-local" required /></label></div><label>静默原因<textarea v-model="silenceForm.reason" required maxlength="1000" placeholder="计划内版本发布，相关告警暂不升级"></textarea></label><small class="alert-form-hint">至少限定告警源、资产或指纹前缀之一，防止误静默全部信号。</small><label class="alert-checkbox"><input v-model="silenceForm.enabled" type="checkbox" /> 启用该静默窗口</label><footer><button type="button" class="alert-secondary" @click="showSilenceModal = false">取消</button><button class="alert-primary" :disabled="saving">{{ saving ? '保存中…' : '保存静默窗口' }}</button></footer></form></div>

    <div v-if="showCredentialModal && credential" class="alert-modal-backdrop"><section class="alert-modal alert-credential"><header><div><p class="alert-eyebrow">SAVE IT NOW</p><h3>一次性接入凭证</h3></div><button type="button" @click="showCredentialModal = false">×</button></header><p>密钥只在当前窗口展示一次。关闭后只能轮换，无法再次查看。</p><label>接入地址<div class="alert-copy-row"><code>{{ credential.endpoint }}</code><button @click="copyText(credential.endpoint, '接入地址已复制')">复制</button></div></label><label>接入密钥<div class="alert-copy-row"><code>{{ credential.ingestKey }}</code><button @click="copyText(credential.ingestKey, '接入密钥已复制')">复制</button></div></label><label>快速测试<pre>{{ sampleCurl() }}</pre></label><footer><button class="alert-primary" @click="showCredentialModal = false">我已安全保存</button></footer></section></div>

    <div v-if="showSuppressModal" class="alert-modal-backdrop" @click.self="showSuppressModal = false"><form class="alert-modal compact" @submit.prevent="suppressAlert"><header><div><p class="alert-eyebrow">MANUAL SUPPRESSION</p><h3>人工抑制告警</h3></div><button type="button" @click="showSuppressModal = false">×</button></header><label>抑制原因<textarea v-model="suppressForm.note" required maxlength="1000" placeholder="误报、已知维护或暂不处理的原因"></textarea></label><footer><button type="button" class="alert-secondary" @click="showSuppressModal = false">取消</button><button class="alert-primary danger" :disabled="saving">确认抑制</button></footer></form></div>
  </section>
</template>

<style scoped>
.alert-center{display:flex;flex-direction:column;gap:18px;color:#182234}.alert-toolbar{display:flex;justify-content:space-between;gap:20px;align-items:flex-start}.alert-toolbar h2,.alert-panel h3,.alert-detail h2{margin:3px 0 0}.alert-toolbar small{color:#718096}.alert-eyebrow{margin:0;color:#7b61ff;font-size:11px;font-weight:800;letter-spacing:.15em}.alert-toolbar-actions,.alert-actions{display:flex;gap:8px;flex-wrap:wrap}.alert-primary,.alert-secondary{border:0;border-radius:10px;padding:10px 14px;font-weight:750;cursor:pointer}.alert-primary{background:#1e2742;color:#fff}.alert-primary.danger{background:#b42318}.alert-secondary{background:#fff;border:1px solid #dfe3ec;color:#263248}.alert-primary:disabled,.alert-secondary:disabled{opacity:.55;cursor:not-allowed}.alert-message{margin:0;padding:11px 14px;border-radius:10px;display:flex;justify-content:space-between}.alert-message.error{background:#fff0ef;color:#b42318}.alert-message.success{background:#ebfbf3;color:#087a55}.alert-message button{border:0;background:transparent;color:inherit;font-size:18px}.alert-summary{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:12px}.alert-summary article{background:#fff;border:1px solid #e7e9f0;border-radius:14px;padding:16px}.alert-summary span,.alert-summary small{display:block;color:#7c8799}.alert-summary strong{display:block;font-size:28px;margin:6px 0}.alert-summary .danger{border-color:#f2b8b5;background:#fff8f7}.alert-summary .danger strong{color:#b42318}.alert-summary .attention strong{color:#c65a11}.alert-workspace{display:grid;grid-template-columns:minmax(380px,.95fr) minmax(480px,1.3fr);gap:14px;align-items:start}.alert-panel{background:#fff;border:1px solid #e5e8ef;border-radius:16px;padding:17px;box-shadow:0 8px 30px rgba(36,45,72,.04)}.alert-panel-heading,.alert-section-heading{display:flex;justify-content:space-between;align-items:center;gap:12px}.alert-panel-heading>span,.alert-section-heading>span{font-size:12px;color:#77839a;background:#f4f5f8;padding:5px 9px;border-radius:999px}.alert-filters{display:grid;grid-template-columns:1.6fr repeat(3,1fr) auto;gap:8px;margin:15px 0}.alert-filters input,.alert-filters select,.alert-modal input,.alert-modal select,.alert-modal textarea{width:100%;box-sizing:border-box;border:1px solid #dfe3eb;border-radius:9px;padding:10px 11px;background:#fff;color:#202b3d;font:inherit}.alert-list{display:flex;flex-direction:column;gap:9px;max-height:760px;overflow:auto;padding-right:3px}.alert-card{border:1px solid #e7e9ee;border-radius:12px;padding:13px;cursor:pointer;transition:.18s}.alert-card:hover,.alert-card.selected{border-color:#8c76ff;background:#fbfaff;transform:translateY(-1px)}.alert-card-head{display:flex;justify-content:space-between;gap:10px}.alert-card-head>div{min-width:0}.alert-card code{display:block;color:#7d8696;font-size:11px}.alert-card strong{display:block;margin-top:4px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.alert-card p{font-size:13px;color:#657085;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}.alert-card-meta{display:flex;gap:7px;flex-wrap:wrap}.alert-card-meta>span{font-size:11px;background:#f4f5f8;padding:4px 7px;border-radius:999px}.alert-card footer{margin-top:11px;display:flex;justify-content:space-between;gap:8px;color:#727e91;font-size:11px}.alert-card footer span{color:#6f55e8;font-weight:700}.alert-severity,.alert-status{display:inline-flex;align-items:center;border-radius:999px;font-weight:800;font-size:11px;padding:5px 8px;white-space:nowrap}.alert-severity.large{font-size:13px;padding:7px 10px}.alert-severity-critical{background:#fee4e2;color:#b42318}.alert-severity-high{background:#fff0e3;color:#b54708}.alert-severity-medium{background:#fff8d8;color:#8a6100}.alert-severity-low{background:#eaf3ff;color:#175cd3}.alert-severity-info{background:#eef1f5;color:#475467}.alert-status-firing{background:#fee4e2!important;color:#b42318}.alert-status-acknowledged{background:#eaf3ff!important;color:#175cd3}.alert-status-suppressed{background:#f1edff!important;color:#6941c6}.alert-status-resolved{background:#e7f8ef!important;color:#087a55}.alert-detail{min-height:470px}.alert-detail-head{display:flex;justify-content:space-between;gap:18px}.alert-detail-head>div:last-child{display:flex;align-items:flex-start;gap:7px}.alert-detail-head small{color:#7b8699}.alert-actions{margin:17px 0}.danger-text{color:#b42318!important}.alert-facts{display:grid;grid-template-columns:repeat(3,1fr);gap:8px;background:#f7f8fb;border-radius:12px;padding:12px}.alert-facts span{font-size:12px;color:#4d596d;min-width:0}.alert-facts b{display:block;color:#8a94a5;font-size:10px;text-transform:uppercase;margin-bottom:4px}.alert-facts code{display:block;overflow:hidden;text-overflow:ellipsis}.alert-description{line-height:1.65;color:#586579}.alert-labels{display:flex;gap:7px;flex-wrap:wrap;margin:12px 0}.alert-labels span{background:#f1efff;border:1px solid #ddd6fe;color:#563bb8;border-radius:7px;padding:5px 8px;font-size:11px}.alert-labels b{margin-right:5px}.alert-link-strip{display:grid;grid-template-columns:repeat(3,1fr);gap:8px;margin:14px 0}.alert-link-strip>div{border:1px solid #e6e8ef;border-radius:10px;padding:11px}.alert-link-strip small,.alert-link-strip strong{display:block}.alert-link-strip small{color:#8b95a6;margin-bottom:4px}.alert-timeline{border-top:1px solid #eceef3;padding-top:15px}.alert-events{margin-top:12px;display:flex;flex-direction:column}.alert-event{display:grid;grid-template-columns:15px 1fr;gap:10px;position:relative;padding-bottom:15px}.alert-event:before{content:'';position:absolute;left:5px;top:11px;bottom:-2px;width:1px;background:#e1e4eb}.alert-event:last-child:before{display:none}.alert-event i{width:11px;height:11px;border-radius:50%;background:#7b61ff;margin-top:3px;z-index:1}.alert-event header{display:flex;justify-content:space-between;gap:10px}.alert-event time,.alert-event small{color:#8b95a6;font-size:11px}.alert-event p{margin:4px 0;color:#566176;font-size:13px}.alert-empty{padding:35px 16px;text-align:center;color:#8490a3}.detail-placeholder{min-height:400px;display:flex;flex-direction:column;justify-content:center;gap:6px}.detail-placeholder b{color:#303b50;font-size:18px}.alert-config-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:14px}.alert-config-list{display:flex;flex-direction:column;gap:8px;margin-top:13px;max-height:330px;overflow:auto}.alert-config-list article{display:flex;justify-content:space-between;align-items:flex-start;gap:10px;border:1px solid #e8eaf0;border-radius:10px;padding:11px}.alert-config-list strong,.alert-config-list small{display:block}.alert-config-list small{color:#7e899b;margin-top:3px}.alert-config-list article>div:last-child{display:flex;gap:5px}.alert-config-list button{border:0;background:#f1efff;color:#6048c7;padding:6px 8px;border-radius:7px;cursor:pointer;white-space:nowrap}.alert-modal-backdrop{position:fixed;inset:0;background:rgba(19,25,41,.55);z-index:80;display:flex;align-items:center;justify-content:center;padding:24px}.alert-modal{width:min(620px,100%);max-height:90vh;overflow:auto;background:#fff;border-radius:16px;padding:20px;box-shadow:0 24px 80px rgba(0,0,0,.22);display:flex;flex-direction:column;gap:12px}.alert-modal.compact{width:min(480px,100%)}.alert-modal header,.alert-modal footer{display:flex;justify-content:space-between;align-items:center;gap:12px}.alert-modal header>button{border:0;background:#f2f3f6;border-radius:8px;width:34px;height:34px;font-size:20px}.alert-modal footer{justify-content:flex-end;margin-top:5px}.alert-modal label{display:flex;flex-direction:column;gap:6px;font-size:12px;font-weight:700;color:#4a566a}.alert-modal textarea{min-height:86px;resize:vertical}.alert-form-grid{display:grid;grid-template-columns:repeat(2,1fr);gap:10px}.alert-checkbox{flex-direction:row!important;align-items:center}.alert-checkbox input{width:auto}.alert-form-hint{color:#8a94a6}.alert-credential p{color:#5c687b}.alert-copy-row{display:grid;grid-template-columns:1fr auto;gap:7px}.alert-copy-row code,.alert-credential pre{background:#151b2b;color:#dfe7ff;border-radius:9px;padding:11px;overflow:auto;font-size:11px;white-space:pre-wrap;word-break:break-all}.alert-copy-row button{border:0;background:#ece9ff;color:#6248ca;border-radius:8px;padding:0 12px;font-weight:700}.alert-credential pre{max-height:180px}.alert-modal .danger{background:#b42318}
.alert-runbook-recommendations{margin:17px 0;padding:15px 0;border-top:1px solid #eceef3;border-bottom:1px solid #eceef3}.runbook-recommendation-hint{margin:8px 0;color:#718096;font-size:12px;line-height:1.5}.alert-empty.compact{padding:15px}.alert-runbook-list{display:flex;flex-direction:column;gap:8px;margin-top:11px}.alert-runbook-list article{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:11px;border:1px solid #e5e2fa;border-radius:9px;background:#fbfaff}.alert-runbook-list strong{display:block;color:#393160;font-size:13px}.alert-runbook-list strong small{color:#897bb8;font-size:10px}.alert-runbook-list p{margin:4px 0 0;color:#6e6a86;font-size:11px}.alert-runbook-list .alert-secondary{padding:7px 9px;white-space:nowrap}
@media(max-width:1200px){.alert-summary{grid-template-columns:repeat(3,1fr)}.alert-workspace{grid-template-columns:1fr}.alert-config-grid{grid-template-columns:1fr 1fr}.alert-list{max-height:520px}}
@media(max-width:760px){.alert-toolbar{flex-direction:column}.alert-summary,.alert-config-grid,.alert-facts,.alert-link-strip{grid-template-columns:1fr}.alert-filters{grid-template-columns:1fr 1fr}.alert-form-grid{grid-template-columns:1fr}.alert-detail-head{flex-direction:column}.alert-modal-backdrop{padding:10px}.alert-copy-row{grid-template-columns:1fr}}
</style>
