<script setup>
import { computed, onMounted, reactive, ref } from 'vue'

const props = defineProps({
  api: { type: Function, required: true },
  staffMembers: { type: Array, default: () => [] },
  tickets: { type: Array, default: () => [] },
  assets: { type: Array, default: () => [] },
  canManage: { type: Boolean, default: false }
})

const emit = defineEmits(['changed'])

const incidents = ref([])
const loading = ref(false)
const detailLoading = ref(false)
const error = ref('')
const notice = ref('')
const statusFilter = ref('')
const severityFilter = ref('')
const keyword = ref('')
const selectedDetail = ref(null)
const showCreateModal = ref(false)
const showCommandModal = ref(false)
const saving = ref(false)
const addingUpdate = ref(false)
const addingTask = ref(false)
const linkingTicket = ref(false)
const linkingAsset = ref(false)

const incidentForm = reactive({
  title: '', summary: '', severity: 'SEV2', commanderId: '', businessImpact: '', customerImpact: '',
  communicationChannel: '', taskTitles: ''
})
const commandForm = reactive({
  severity: 'SEV2', commanderId: '', businessImpact: '', customerImpact: '', communicationChannel: '', postmortem: ''
})
const updateForm = reactive({ updateType: 'INVESTIGATION', content: '' })
const taskForm = reactive({ title: '', assigneeId: '', sortOrder: 100 })
const ticketSelection = ref('')
const ticketLinkType = ref('SYMPTOM')
const assetSelection = ref('')
const assetImpactLevel = ref('IMPACTED')

const severityLabels = { SEV1: 'SEV1 · 极严重', SEV2: 'SEV2 · 高', SEV3: 'SEV3 · 中', SEV4: 'SEV4 · 低' }
const statusLabels = {
  DECLARED: '已宣布', INVESTIGATING: '调查中', MITIGATING: '缓解中', MONITORING: '观察中',
  RESOLVED: '已解决', CLOSED: '已关闭', CANCELLED: '已取消'
}
const taskStatusLabels = { TODO: '待执行', IN_PROGRESS: '进行中', DONE: '已完成' }
const updateTypeLabels = {
  SYSTEM: '系统记录', INVESTIGATION: '调查进展', MITIGATION: '缓解动作',
  CUSTOMER_UPDATE: '对外通报', INTERNAL_NOTE: '内部备注'
}
const ticketLinkLabels = { SYMPTOM: '症状工单', RELATED: '相关工单', MITIGATION: '缓解工单' }
const assetImpactLabels = { IMPACTED: '受影响', DEGRADED: '性能降级', RELATED: '相关资产' }

const activeCount = computed(() => incidents.value.filter(item => !['CLOSED', 'CANCELLED'].includes(item.status)).length)
const criticalCount = computed(() => incidents.value.filter(item => ['SEV1', 'SEV2'].includes(item.severity) && !['CLOSED', 'CANCELLED'].includes(item.status)).length)
const monitoringCount = computed(() => incidents.value.filter(item => item.status === 'MONITORING').length)
const completedTaskCount = computed(() => selectedDetail.value?.tasks.filter(task => task.status === 'DONE').length || 0)
const incompleteTaskCount = computed(() => selectedDetail.value?.tasks.filter(task => task.status !== 'DONE').length || 0)
const availableTickets = computed(() => {
  const linked = new Set((selectedDetail.value?.tickets || []).map(item => String(item.ticketId)))
  return props.tickets.filter(ticket => !linked.has(String(ticket.id)))
})
const availableAssets = computed(() => {
  const linked = new Set((selectedDetail.value?.assets || []).map(item => String(item.assetId)))
  return props.assets.filter(asset => asset.status !== 'RETIRED' && !linked.has(String(asset.id)))
})

function severityLabel(value) { return severityLabels[value] || value || '-' }
function statusLabel(value) { return statusLabels[value] || value || '-' }
function statusClass(value) { return 'incident-status-' + String(value || '').toLowerCase() }
function severityClass(value) { return 'incident-severity-' + String(value || '').toLowerCase() }
function staffName(id) {
  if (!id) return '未指定'
  return props.staffMembers.find(item => String(item.id) === String(id))?.displayName || '用户 #' + id
}
function formatDate(value) {
  if (!value) return '-'
  return new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}
function notify(message) {
  notice.value = message
  window.setTimeout(() => { notice.value = '' }, 3000)
}
function isEditable(incident = selectedDetail.value?.incident) {
  return incident && !['CLOSED', 'CANCELLED'].includes(incident.status)
}
function resetCreateForm() {
  Object.assign(incidentForm, {
    title: '', summary: '', severity: 'SEV2', commanderId: '', businessImpact: '', customerImpact: '',
    communicationChannel: '', taskTitles: ''
  })
}
function resetCommandForm(incident = selectedDetail.value?.incident) {
  Object.assign(commandForm, {
    severity: incident?.severity || 'SEV2', commanderId: incident?.commanderId || '',
    businessImpact: incident?.businessImpact || '', customerImpact: incident?.customerImpact || '',
    communicationChannel: incident?.communicationChannel || '', postmortem: incident?.postmortem || ''
  })
}
function openCreateModal() {
  resetCreateForm()
  showCreateModal.value = true
}
function openCommandModal() {
  if (!selectedDetail.value?.incident || !props.canManage) return
  resetCommandForm()
  showCommandModal.value = true
}
function closeDetail() {
  selectedDetail.value = null
  ticketSelection.value = ''
  assetSelection.value = ''
}
function queryString() {
  const params = new URLSearchParams()
  if (statusFilter.value) params.set('status', statusFilter.value)
  if (severityFilter.value) params.set('severity', severityFilter.value)
  if (keyword.value.trim()) params.set('keyword', keyword.value.trim())
  params.set('limit', '100')
  return params.toString()
}
async function loadIncidents() {
  if (!props.canManage) return
  loading.value = true
  error.value = ''
  try {
    incidents.value = await props.api('/api/v1/incidents?' + queryString()) || []
    if (selectedDetail.value && !incidents.value.some(item => item.id === selectedDetail.value.incident.id)) closeDetail()
  } catch (err) {
    error.value = err.message
  } finally {
    loading.value = false
  }
}
async function openDetail(incident) {
  if (!incident) return
  detailLoading.value = true
  error.value = ''
  try {
    const detail = await props.api('/api/v1/incidents/' + incident.id)
    selectedDetail.value = detail
    const item = incidents.value.find(current => current.id === detail.incident.id)
    if (item) Object.assign(item, detail.incident)
    ticketSelection.value = ''
    assetSelection.value = ''
  } catch (err) {
    error.value = err.message
  } finally {
    detailLoading.value = false
  }
}
async function refreshDetail() {
  if (selectedDetail.value?.incident) await openDetail(selectedDetail.value.incident)
}
async function saveIncident() {
  if (!props.canManage) return
  saving.value = true
  error.value = ''
  try {
    const taskTitles = String(incidentForm.taskTitles || '').split(/\r?\n|;/).map(item => item.trim()).filter(Boolean)
    const saved = await props.api('/api/v1/incidents', {
      method: 'POST',
      headers: { 'Idempotency-Key': crypto.randomUUID ? crypto.randomUUID() : String(Date.now()) },
      body: JSON.stringify({
        title: incidentForm.title, summary: incidentForm.summary, severity: incidentForm.severity,
        commanderId: incidentForm.commanderId ? Number(incidentForm.commanderId) : null,
        businessImpact: incidentForm.businessImpact, customerImpact: incidentForm.customerImpact,
        communicationChannel: incidentForm.communicationChannel, taskTitles
      })
    })
    showCreateModal.value = false
    await loadIncidents()
    await openDetail(saved)
    emit('changed')
    notify('重大事件已宣布，协同响应已启动')
  } catch (err) {
    error.value = err.message
  } finally {
    saving.value = false
  }
}
async function saveCommand() {
  if (!selectedDetail.value?.incident || !props.canManage) return
  saving.value = true
  error.value = ''
  try {
    await props.api('/api/v1/incidents/' + selectedDetail.value.incident.id, {
      method: 'PUT',
      body: JSON.stringify({
        severity: commandForm.severity,
        commanderId: commandForm.commanderId ? Number(commandForm.commanderId) : null,
        businessImpact: commandForm.businessImpact,
        customerImpact: commandForm.customerImpact,
        communicationChannel: commandForm.communicationChannel,
        postmortem: commandForm.postmortem
      })
    })
    showCommandModal.value = false
    await refreshDetail()
    await loadIncidents()
    emit('changed')
    notify('事件指挥资料已保存')
  } catch (err) {
    error.value = err.message
  } finally {
    saving.value = false
  }
}
function transitions(status) {
  const mapping = {
    DECLARED: [['INVESTIGATING', '开始调查'], ['CANCELLED', '取消事件']],
    INVESTIGATING: [['MITIGATING', '进入缓解'], ['CANCELLED', '取消事件']],
    MITIGATING: [['MONITORING', '开始观察'], ['INVESTIGATING', '回到调查'], ['CANCELLED', '取消事件']],
    MONITORING: [['RESOLVED', '确认解决'], ['MITIGATING', '重新缓解']],
    RESOLVED: [['CLOSED', '关闭事件'], ['INVESTIGATING', '重新打开']]
  }
  return (mapping[status] || []).map(([target, label]) => ({ target, label, danger: target === 'CANCELLED' }))
}
async function transitionIncident(target) {
  if (!selectedDetail.value?.incident || !props.canManage) return
  error.value = ''
  try {
    const saved = await props.api('/api/v1/incidents/' + selectedDetail.value.incident.id + '/transition?target=' + target, { method: 'POST' })
    await openDetail(saved)
    await loadIncidents()
    emit('changed')
    notify('事件状态已更新为「' + statusLabel(saved.status) + '」')
  } catch (err) {
    error.value = err.message
  }
}
async function addUpdate() {
  if (!selectedDetail.value?.incident || !updateForm.content.trim() || !isEditable() || !props.canManage) return
  addingUpdate.value = true
  error.value = ''
  try {
    await props.api('/api/v1/incidents/' + selectedDetail.value.incident.id + '/updates', {
      method: 'POST', body: JSON.stringify({ updateType: updateForm.updateType, content: updateForm.content })
    })
    updateForm.content = ''
    await refreshDetail()
    emit('changed')
    notify('事件动态已记录')
  } catch (err) {
    error.value = err.message
  } finally {
    addingUpdate.value = false
  }
}
function nextTaskStatus(status) {
  return status === 'TODO' ? 'IN_PROGRESS' : status === 'IN_PROGRESS' ? 'DONE' : 'TODO'
}
async function addTask() {
  if (!selectedDetail.value?.incident || !taskForm.title.trim() || !isEditable() || !props.canManage) return
  addingTask.value = true
  error.value = ''
  try {
    await props.api('/api/v1/incidents/' + selectedDetail.value.incident.id + '/tasks', {
      method: 'POST',
      body: JSON.stringify({
        title: taskForm.title, assigneeId: taskForm.assigneeId ? Number(taskForm.assigneeId) : null,
        sortOrder: Number(taskForm.sortOrder) || 100
      })
    })
    Object.assign(taskForm, { title: '', assigneeId: '', sortOrder: 100 })
    await refreshDetail()
    emit('changed')
    notify('协同任务已添加')
  } catch (err) {
    error.value = err.message
  } finally {
    addingTask.value = false
  }
}
async function toggleTask(task) {
  if (!selectedDetail.value?.incident || !isEditable() || !props.canManage) return
  error.value = ''
  try {
    await props.api('/api/v1/incidents/tasks/' + task.id, {
      method: 'PUT', body: JSON.stringify({ status: nextTaskStatus(task.status) })
    })
    await refreshDetail()
    emit('changed')
  } catch (err) {
    error.value = err.message
  }
}
async function linkTicket() {
  if (!selectedDetail.value?.incident || !ticketSelection.value || !isEditable() || !props.canManage) return
  linkingTicket.value = true
  error.value = ''
  try {
    await props.api('/api/v1/incidents/' + selectedDetail.value.incident.id + '/tickets', {
      method: 'POST', body: JSON.stringify({ targetId: Number(ticketSelection.value), linkType: ticketLinkType.value, note: '' })
    })
    ticketSelection.value = ''
    await refreshDetail()
    emit('changed')
    notify('工单已纳入事件响应')
  } catch (err) {
    error.value = err.message
  } finally {
    linkingTicket.value = false
  }
}
async function unlinkTicket(item) {
  if (!selectedDetail.value?.incident || !isEditable() || !props.canManage) return
  try {
    await props.api('/api/v1/incidents/' + selectedDetail.value.incident.id + '/tickets/' + item.ticketId, { method: 'DELETE' })
    await refreshDetail()
    emit('changed')
    notify('事件工单关联已解除')
  } catch (err) { error.value = err.message }
}
async function linkAsset() {
  if (!selectedDetail.value?.incident || !assetSelection.value || !isEditable() || !props.canManage) return
  linkingAsset.value = true
  error.value = ''
  try {
    await props.api('/api/v1/incidents/' + selectedDetail.value.incident.id + '/assets', {
      method: 'POST', body: JSON.stringify({ targetId: Number(assetSelection.value), linkType: assetImpactLevel.value, note: '' })
    })
    assetSelection.value = ''
    await refreshDetail()
    emit('changed')
    notify('受影响配置项已关联')
  } catch (err) {
    error.value = err.message
  } finally {
    linkingAsset.value = false
  }
}
async function unlinkAsset(item) {
  if (!selectedDetail.value?.incident || !isEditable() || !props.canManage) return
  try {
    await props.api('/api/v1/incidents/' + selectedDetail.value.incident.id + '/assets/' + item.assetId, { method: 'DELETE' })
    await refreshDetail()
    emit('changed')
    notify('事件配置项关联已解除')
  } catch (err) { error.value = err.message }
}

onMounted(loadIncidents)
</script>

<template>
  <section class="incident-command-center">
    <div class="incident-toolbar">
      <div>
        <p class="eyebrow">INCIDENT COMMAND</p>
        <h2>重大事件响应中心</h2>
        <small>把事件指挥、影响面、协同任务、工单与 CMDB 资产集中到一条可追溯的响应链路。</small>
      </div>
      <div class="incident-toolbar-actions">
        <button class="secondary-button" :disabled="loading" @click="loadIncidents">{{ loading ? '刷新中…' : '刷新' }}</button>
        <button v-if="canManage" class="primary-button incident-declare-button" @click="openCreateModal">＋ 宣布重大事件</button>
      </div>
    </div>

    <p v-if="error" class="incident-message error">{{ error }}</p>
    <p v-if="notice" class="incident-message notice">✓ {{ notice }}</p>

    <div class="incident-summary-strip">
      <article><span>响应中事件</span><strong>{{ activeCount }}</strong><small>未关闭 / 未取消</small></article>
      <article :class="{ attention: criticalCount }"><span>高优先级</span><strong>{{ criticalCount }}</strong><small>SEV1 / SEV2</small></article>
      <article><span>观察验证</span><strong>{{ monitoringCount }}</strong><small>等待稳定性确认</small></article>
      <article><span>当前协同任务</span><strong>{{ selectedDetail ? completedTaskCount + '/' + selectedDetail.tasks.length : '—' }}</strong><small>{{ selectedDetail ? '已完成 / 总数' : '选择事件查看进度' }}</small></article>
    </div>

    <div class="incident-workspace">
      <section class="incident-list-panel">
        <div class="incident-list-filters">
          <input v-model="keyword" maxlength="160" placeholder="搜索事件编号、标题或影响…" @keyup.enter="loadIncidents" />
          <select v-model="severityFilter" @change="loadIncidents"><option value="">全部级别</option><option v-for="(label, value) in severityLabels" :key="value" :value="value">{{ label }}</option></select>
          <select v-model="statusFilter" @change="loadIncidents"><option value="">全部状态</option><option v-for="(label, value) in statusLabels" :key="value" :value="value">{{ label }}</option></select>
          <button v-if="keyword || severityFilter || statusFilter" class="filter-reset" @click="keyword = ''; severityFilter = ''; statusFilter = ''; loadIncidents()">重置</button>
        </div>
        <div v-if="loading" class="incident-empty">正在读取重大事件…</div>
        <div v-else-if="!incidents.length" class="incident-empty">当前没有符合条件的重大事件。宣布一个 SEV1 / SEV2 事件，即可启动标准协同响应。</div>
        <div v-else class="incident-card-list">
          <article v-for="incident in incidents" :key="incident.id" :class="['incident-card', severityClass(incident.severity), { selected: selectedDetail?.incident?.id === incident.id }]" @click="openDetail(incident)">
            <div class="incident-card-head"><code>{{ incident.incidentNo }}</code><span :class="['incident-status', statusClass(incident.status)]">{{ statusLabel(incident.status) }}</span></div>
            <h3>{{ incident.title }}</h3>
            <p>{{ incident.summary }}</p>
            <div class="incident-card-meta"><span :class="['incident-severity', severityClass(incident.severity)]">{{ severityLabel(incident.severity) }}</span><span>指挥：{{ staffName(incident.commanderId) }}</span></div>
            <footer><span>{{ formatDate(incident.startedAt || incident.createdAt) }}</span><span v-if="incident.businessImpact">已记录业务影响</span></footer>
          </article>
        </div>
      </section>

      <section v-if="selectedDetail" class="incident-detail-panel">
        <div v-if="detailLoading" class="incident-empty">正在读取事件指挥详情…</div>
        <template v-else>
          <header class="incident-detail-header">
            <div><p class="eyebrow">LIVE INCIDENT</p><code>{{ selectedDetail.incident.incidentNo }}</code><h2>{{ selectedDetail.incident.title }}</h2><small>宣布人：{{ staffName(selectedDetail.incident.declaredBy) }} · 开始于 {{ formatDate(selectedDetail.incident.startedAt || selectedDetail.incident.createdAt) }}</small></div>
            <div class="incident-detail-header-actions"><span :class="['incident-status', statusClass(selectedDetail.incident.status)]">{{ statusLabel(selectedDetail.incident.status) }}</span><button class="close-button" @click="closeDetail">×</button></div>
          </header>

          <div class="incident-action-row">
            <button v-if="canManage && isEditable()" class="secondary-button" @click="openCommandModal">编辑指挥资料</button>
            <button v-for="action in transitions(selectedDetail.incident.status)" :key="action.target" :class="action.danger ? 'danger-button' : 'primary-button'" @click="transitionIncident(action.target)">{{ action.label }}</button>
          </div>
          <p v-if="selectedDetail.incident.status === 'RESOLVED'" class="incident-close-hint">关闭前需填写事后复盘，并完成全部协同任务（当前还剩 {{ incompleteTaskCount }} 项）。</p>

          <div class="incident-facts">
            <span><b>严重等级</b><i :class="['incident-severity', severityClass(selectedDetail.incident.severity)]">{{ severityLabel(selectedDetail.incident.severity) }}</i></span>
            <span><b>事件指挥人</b>{{ staffName(selectedDetail.incident.commanderId) }}</span>
            <span><b>沟通渠道</b>{{ selectedDetail.incident.communicationChannel || '尚未设置' }}</span>
            <span><b>解决时间</b>{{ formatDate(selectedDetail.incident.resolvedAt) }}</span>
          </div>
          <section class="incident-impact-section"><h3>影响说明</h3><p>{{ selectedDetail.incident.summary }}</p><div><article><strong>业务影响</strong><p>{{ selectedDetail.incident.businessImpact || '尚未补充' }}</p></article><article><strong>客户影响</strong><p>{{ selectedDetail.incident.customerImpact || '尚未补充' }}</p></article></div></section>

          <section class="incident-context-section"><div class="incident-section-heading"><div><h3>事件时间线</h3><small>调查、缓解和对外通报都会被永久记录，并同步至审计 / Webhook。</small></div><span>{{ selectedDetail.updates.length }} 条</span></div>
            <form v-if="isEditable() && canManage" class="incident-update-form" @submit.prevent="addUpdate"><select v-model="updateForm.updateType"><option v-for="(label, value) in updateTypeLabels" :key="value" :value="value">{{ label }}</option></select><textarea v-model="updateForm.content" required maxlength="10000" rows="2" placeholder="记录最新调查结论、缓解动作或沟通信息…"></textarea><button class="secondary-button" :disabled="addingUpdate">{{ addingUpdate ? '记录中…' : '添加动态' }}</button></form>
            <div v-if="!selectedDetail.updates.length" class="incident-empty compact">暂无动态。</div><ol v-else class="incident-timeline"><li v-for="update in selectedDetail.updates" :key="update.id" :class="'timeline-' + String(update.updateType).toLowerCase()"><span class="timeline-dot"></span><div><header><strong>{{ updateTypeLabels[update.updateType] || update.updateType }}</strong><small>{{ staffName(update.createdBy) }} · {{ formatDate(update.createdAt) }}</small></header><p>{{ update.content }}</p></div></li></ol>
          </section>

          <section class="incident-context-section"><div class="incident-section-heading"><div><h3>协同任务</h3><small>关闭事件前必须完成所有任务。</small></div><span>{{ completedTaskCount }}/{{ selectedDetail.tasks.length }} 完成</span></div>
            <div v-if="!selectedDetail.tasks.length" class="incident-empty compact">暂无协同任务。建议拆分调查、止损、验证与沟通动作。</div><div v-else class="incident-task-list"><div v-for="task in selectedDetail.tasks" :key="task.id" class="incident-task-row"><button type="button" :class="['incident-task-toggle', 'task-' + String(task.status).toLowerCase()]" :disabled="!isEditable() || !canManage" @click="toggleTask(task)">{{ task.status === 'DONE' ? '✓' : task.status === 'IN_PROGRESS' ? '→' : '○' }}</button><div><strong>{{ task.title }}</strong><small>{{ taskStatusLabels[task.status] || task.status }} · {{ task.assigneeId ? staffName(task.assigneeId) : '未指定负责人' }}</small></div><time v-if="task.completedAt">{{ formatDate(task.completedAt) }}</time></div></div>
            <form v-if="isEditable() && canManage" class="incident-task-form" @submit.prevent="addTask"><input v-model="taskForm.title" required maxlength="160" placeholder="添加协同任务，例如：切换流量并验证错误率" /><select v-model="taskForm.assigneeId"><option value="">未指定负责人</option><option v-for="staff in staffMembers" :key="staff.id" :value="staff.id">{{ staff.displayName }}</option></select><button class="secondary-button" :disabled="addingTask">{{ addingTask ? '添加中…' : '添加任务' }}</button></form>
          </section>

          <div class="incident-linked-grid">
            <section class="incident-context-section"><div class="incident-section-heading"><div><h3>关联工单</h3><small>收敛症状、缓解与相关请求。</small></div><span>{{ selectedDetail.tickets.length }}</span></div>
              <div v-if="isEditable() && canManage" class="incident-link-form"><select v-model="ticketSelection"><option value="">选择工单</option><option v-for="ticket in availableTickets" :key="ticket.id" :value="ticket.id">{{ ticket.ticketNo }} · {{ ticket.title }}</option></select><select v-model="ticketLinkType"><option v-for="(label, value) in ticketLinkLabels" :key="value" :value="value">{{ label }}</option></select><button class="secondary-button" :disabled="!ticketSelection || linkingTicket" @click="linkTicket">{{ linkingTicket ? '关联中…' : '关联' }}</button></div>
              <div v-if="!selectedDetail.tickets.length" class="incident-empty compact">尚未关联工单。</div><div v-else class="incident-linked-list"><article v-for="item in selectedDetail.tickets" :key="item.ticketId"><div><span>{{ ticketLinkLabels[item.linkType] || item.linkType }}</span><strong>{{ item.ticket?.ticketNo || '#' + item.ticketId }} · {{ item.ticket?.title || '工单已不可见' }}</strong></div><button v-if="isEditable() && canManage" class="link-remove" @click="unlinkTicket(item)">解除</button></article></div>
            </section>
            <section class="incident-context-section"><div class="incident-section-heading"><div><h3>受影响配置项</h3><small>从 CMDB 识别影响面与依赖。</small></div><span>{{ selectedDetail.assets.length }}</span></div>
              <div v-if="isEditable() && canManage" class="incident-link-form"><select v-model="assetSelection"><option value="">选择配置项</option><option v-for="asset in availableAssets" :key="asset.id" :value="asset.id">{{ asset.assetNo }} · {{ asset.name }}</option></select><select v-model="assetImpactLevel"><option v-for="(label, value) in assetImpactLabels" :key="value" :value="value">{{ label }}</option></select><button class="secondary-button" :disabled="!assetSelection || linkingAsset" @click="linkAsset">{{ linkingAsset ? '关联中…' : '关联' }}</button></div>
              <div v-if="!selectedDetail.assets.length" class="incident-empty compact">尚未关联配置项。</div><div v-else class="incident-linked-list"><article v-for="item in selectedDetail.assets" :key="item.assetId"><div><span>{{ assetImpactLabels[item.impactLevel] || item.impactLevel }}</span><strong>{{ item.asset?.assetNo || '#' + item.assetId }} · {{ item.asset?.name || '配置项已不可见' }}</strong></div><button v-if="isEditable() && canManage" class="link-remove" @click="unlinkAsset(item)">解除</button></article></div>
            </section>
          </div>

          <section v-if="selectedDetail.incident.postmortem" class="incident-postmortem"><div class="incident-section-heading"><div><h3>事后复盘</h3><small>关闭前的根因、改进行动与经验沉淀。</small></div></div><p>{{ selectedDetail.incident.postmortem }}</p></section>
        </template>
      </section>
      <section v-else class="incident-empty-detail"><span>⊕</span><h3>选择一个重大事件</h3><p>在此查看完整时间线、组织协同任务，并连接相关工单和受影响资产。</p></section>
    </div>

    <div v-if="showCreateModal" class="incident-modal-backdrop" @click.self="showCreateModal = false"><form class="incident-modal" @submit.prevent="saveIncident"><header><div><p class="eyebrow">DECLARE INCIDENT</p><h2>宣布重大事件</h2><small>建议 SEV1 / SEV2 事件设置明确的指挥人与初始协同任务。</small></div><button type="button" class="close-button" @click="showCreateModal = false">×</button></header><label>事件标题<input v-model="incidentForm.title" required maxlength="160" placeholder="例如：生产订单服务大面积不可用" /></label><label>事件摘要<textarea v-model="incidentForm.summary" required maxlength="10000" rows="4" placeholder="描述当前症状、开始时间和已经确认的范围"></textarea></label><div class="incident-form-grid"><label>严重等级<select v-model="incidentForm.severity"><option v-for="(label, value) in severityLabels" :key="value" :value="value">{{ label }}</option></select></label><label>事件指挥人<select v-model="incidentForm.commanderId"><option value="">当前宣布人（默认）</option><option v-for="staff in staffMembers" :key="staff.id" :value="staff.id">{{ staff.displayName }}</option></select></label></div><label>业务影响<input v-model="incidentForm.businessImpact" maxlength="1000" placeholder="例如：订单创建失败，客服无法查询订单" /></label><label>客户影响<input v-model="incidentForm.customerImpact" maxlength="1000" placeholder="例如：华东区用户下单超时，预计影响约 30% 请求" /></label><label>沟通渠道<input v-model="incidentForm.communicationChannel" maxlength="200" placeholder="例如：#inc-order-20260923 / 值班电话" /></label><label>初始协同任务<textarea v-model="incidentForm.taskTitles" maxlength="5000" rows="3" placeholder="每行一个任务，例如：\n确认错误率与受影响集群\n切换订单服务流量\n发布首次客户通报"></textarea></label><footer><button type="button" class="secondary-button" @click="showCreateModal = false">取消</button><button class="primary-button" :disabled="saving">{{ saving ? '宣布中…' : '宣布并启动响应' }}</button></footer></form></div>

    <div v-if="showCommandModal" class="incident-modal-backdrop" @click.self="showCommandModal = false"><form class="incident-modal command-modal" @submit.prevent="saveCommand"><header><div><p class="eyebrow">INCIDENT COMMAND</p><h2>编辑指挥资料</h2><small>{{ selectedDetail?.incident?.incidentNo }} · 这些资料会进入审计记录和订阅 Webhook。</small></div><button type="button" class="close-button" @click="showCommandModal = false">×</button></header><div class="incident-form-grid"><label>严重等级<select v-model="commandForm.severity"><option v-for="(label, value) in severityLabels" :key="value" :value="value">{{ label }}</option></select></label><label>事件指挥人<select v-model="commandForm.commanderId"><option value="">保持当前指挥人</option><option v-for="staff in staffMembers" :key="staff.id" :value="staff.id">{{ staff.displayName }}</option></select></label></div><label>业务影响<textarea v-model="commandForm.businessImpact" maxlength="1000" rows="3" placeholder="补充当前业务影响与止损情况"></textarea></label><label>客户影响<textarea v-model="commandForm.customerImpact" maxlength="1000" rows="3" placeholder="补充对客户、合作方或内部用户的影响"></textarea></label><label>沟通渠道<input v-model="commandForm.communicationChannel" maxlength="200" placeholder="事件群、值班电话或对外状态页" /></label><label>事后复盘<textarea v-model="commandForm.postmortem" maxlength="10000" rows="5" placeholder="关闭前请记录根因、缓解方式、永久修复与后续改进项"></textarea></label><footer><button type="button" class="secondary-button" @click="showCommandModal = false">取消</button><button class="primary-button" :disabled="saving">{{ saving ? '保存中…' : '保存指挥资料' }}</button></footer></form></div>
  </section>
</template>

<style scoped>
.incident-command-center { display: grid; gap: 18px; }
.incident-toolbar { display: flex; align-items: flex-start; justify-content: space-between; gap: 18px; }
.incident-toolbar h2 { margin: 3px 0 5px; color: #1a2942; font-size: 25px; }
.incident-toolbar small { color: #718097; }
.incident-toolbar-actions, .incident-action-row { display: flex; flex-wrap: wrap; gap: 8px; }
.incident-declare-button { background: linear-gradient(135deg, #c43a4e, #8f2338); border-color: #8f2338; }
.incident-message { margin: 0; border-radius: 10px; padding: 10px 13px; font-size: 13px; }
.incident-message.error { color: #b4233c; background: #fff1f3; border: 1px solid #ffd3db; }
.incident-message.notice { color: #0f766e; background: #edfffb; border: 1px solid #b9f2e5; }
.incident-summary-strip { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
.incident-summary-strip article { min-height: 96px; border: 1px solid #e5eaf1; border-radius: 13px; background: linear-gradient(145deg, #fff, #f8fafc); padding: 15px; display: grid; gap: 3px; }
.incident-summary-strip article.attention { border-color: #f5c5cc; background: linear-gradient(145deg, #fff, #fff5f6); }
.incident-summary-strip span, .incident-summary-strip small { color: #77859a; font-size: 12px; }.incident-summary-strip strong { color: #1e2c43; font-size: 25px; line-height: 1.1; }
.incident-workspace { display: grid; grid-template-columns: minmax(300px, .78fr) minmax(520px, 1.55fr); gap: 16px; align-items: start; }
.incident-list-panel, .incident-detail-panel, .incident-empty-detail { border: 1px solid #e4eaf2; border-radius: 15px; background: #fff; box-shadow: 0 7px 24px rgba(25, 44, 75, .045); }
.incident-list-panel { min-height: 620px; overflow: hidden; }.incident-list-filters { display: grid; grid-template-columns: 1fr 115px 115px auto; gap: 7px; padding: 12px; border-bottom: 1px solid #edf0f5; background: #fbfcfe; }
.incident-list-filters input, .incident-list-filters select, .incident-update-form select, .incident-update-form textarea, .incident-task-form input, .incident-task-form select, .incident-link-form select, .incident-modal input, .incident-modal select, .incident-modal textarea { width: 100%; min-width: 0; border: 1px solid #d9e1ec; border-radius: 8px; background: #fff; color: #24324a; padding: 9px 10px; outline: none; font: inherit; font-size: 13px; box-sizing: border-box; }
.incident-list-filters input:focus, .incident-list-filters select:focus, .incident-update-form select:focus, .incident-update-form textarea:focus, .incident-task-form input:focus, .incident-task-form select:focus, .incident-link-form select:focus, .incident-modal input:focus, .incident-modal select:focus, .incident-modal textarea:focus { border-color: #466fa9; box-shadow: 0 0 0 3px rgba(70, 111, 169, .12); }
.filter-reset { border: 0; color: #53709a; background: transparent; cursor: pointer; font-size: 12px; }.incident-card-list { display: grid; }.incident-card { position: relative; padding: 15px 16px; border-bottom: 1px solid #edf0f5; border-left: 4px solid #9ca8b9; cursor: pointer; transition: background .16s, transform .16s; }.incident-card:hover { background: #f8fbff; }.incident-card.selected { background: #f0f6ff; box-shadow: inset 0 0 0 1px #b9cef0; }.incident-card.incident-severity-sev1 { border-left-color: #bd2034; }.incident-card.incident-severity-sev2 { border-left-color: #e56a2b; }.incident-card.incident-severity-sev3 { border-left-color: #be8b19; }.incident-card.incident-severity-sev4 { border-left-color: #5d7da7; }
.incident-card-head, .incident-card-meta, .incident-card footer, .incident-detail-header, .incident-detail-header-actions, .incident-section-heading, .incident-linked-list article, .incident-modal header, .incident-modal footer { display: flex; align-items: center; justify-content: space-between; gap: 8px; }.incident-card code, .incident-detail-header code { color: #5272a0; font-size: 11px; font-weight: 700; }.incident-card h3 { margin: 10px 0 5px; color: #27364e; font-size: 15px; }.incident-card p { overflow: hidden; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; margin: 0 0 10px; color: #69778b; font-size: 12px; line-height: 1.5; }.incident-card-meta, .incident-card footer { color: #758298; font-size: 11px; }.incident-card footer { margin-top: 11px; padding-top: 9px; border-top: 1px dashed #e5eaf1; }.incident-status, .incident-severity { display: inline-flex; align-items: center; width: fit-content; border-radius: 99px; padding: 4px 8px; font-size: 11px; font-style: normal; white-space: nowrap; }.incident-status-declared { background: #eef3fb; color: #496a97; }.incident-status-investigating, .incident-status-mitigating { background: #fff1e7; color: #b84d12; }.incident-status-monitoring { background: #fff8d9; color: #8a6810; }.incident-status-resolved { background: #e9faf3; color: #197756; }.incident-status-closed { background: #edf1f5; color: #607084; }.incident-status-cancelled { background: #fff0f1; color: #ac4050; }.incident-severity-sev1 { background: #fff0f2; color: #b81f35; }.incident-severity-sev2 { background: #fff2e9; color: #c2571a; }.incident-severity-sev3 { background: #fff8df; color: #956f12; }.incident-severity-sev4 { background: #eef4fa; color: #55729b; }
.incident-empty, .incident-empty-detail { color: #8290a4; padding: 34px 22px; font-size: 13px; line-height: 1.65; text-align: center; }.incident-empty.compact { padding: 16px; background: #fafbfc; border-radius: 9px; text-align: left; }.incident-empty-detail { min-height: 500px; display: grid; place-content: center; max-width: none; }.incident-empty-detail span { color: #84a2cf; font-size: 34px; }.incident-empty-detail h3 { color: #455773; margin: 8px 0 2px; }.incident-empty-detail p { margin: 0; max-width: 290px; }
.incident-detail-panel { padding: 22px; min-height: 620px; }.incident-detail-header { align-items: flex-start; padding-bottom: 16px; border-bottom: 1px solid #e8edf4; }.incident-detail-header h2 { margin: 4px 0; color: #1d2b43; font-size: 23px; }.incident-detail-header small { color: #7c889b; }.incident-detail-header-actions { align-items: flex-start; }.incident-action-row { padding: 14px 0; }.incident-close-hint { margin: 0 0 13px; border-left: 3px solid #dba420; color: #8d6914; background: #fffbea; border-radius: 5px; padding: 9px 11px; font-size: 12px; }.incident-facts { display: grid; grid-template-columns: repeat(4, 1fr); gap: 9px; }.incident-facts span { min-height: 55px; border: 1px solid #e7edf4; border-radius: 9px; background: #fbfcfe; color: #536278; padding: 9px; font-size: 12px; }.incident-facts b { display: block; margin-bottom: 5px; color: #8794a6; font-size: 11px; font-weight: 500; }.incident-impact-section, .incident-context-section, .incident-postmortem { margin-top: 17px; padding-top: 17px; border-top: 1px solid #e9eef5; }.incident-impact-section h3, .incident-context-section h3, .incident-postmortem h3 { margin: 0; color: #293952; font-size: 15px; }.incident-impact-section > p, .incident-postmortem > p { margin: 9px 0 0; color: #526177; white-space: pre-wrap; font-size: 13px; line-height: 1.65; }.incident-impact-section > div { display: grid; grid-template-columns: 1fr 1fr; gap: 9px; margin-top: 10px; }.incident-impact-section article { border-radius: 9px; background: #f7f9fc; padding: 10px; }.incident-impact-section article strong { color: #526987; font-size: 11px; }.incident-impact-section article p { margin: 5px 0 0; color: #526177; white-space: pre-wrap; font-size: 12px; line-height: 1.5; }.incident-section-heading { align-items: flex-start; }.incident-section-heading small { color: #8592a4; font-size: 11px; }.incident-section-heading > span { color: #7d8ba0; font-size: 12px; }.incident-update-form { display: grid; grid-template-columns: 130px 1fr auto; gap: 7px; margin-top: 12px; }.incident-update-form textarea { resize: vertical; }.incident-timeline { position: relative; display: grid; gap: 11px; margin: 15px 0 0; padding: 0 0 0 18px; list-style: none; }.incident-timeline::before { position: absolute; top: 7px; bottom: 8px; left: 4px; width: 1px; background: #dce4ee; content: ''; }.incident-timeline li { position: relative; }.timeline-dot { position: absolute; z-index: 1; left: -18px; top: 5px; width: 9px; height: 9px; border: 2px solid #fff; border-radius: 50%; background: #6685ad; box-shadow: 0 0 0 1px #c7d4e4; }.timeline-mitigation .timeline-dot { background: #dc762e; }.timeline-customer_update .timeline-dot { background: #9a6ac0; }.timeline-system .timeline-dot { background: #98a4b4; }.incident-timeline li > div { border: 1px solid #e6ebf2; border-radius: 9px; background: #fbfcfe; padding: 9px 11px; }.incident-timeline header { display: flex; justify-content: space-between; gap: 12px; }.incident-timeline strong { color: #425773; font-size: 12px; }.incident-timeline small { color: #8995a6; font-size: 11px; }.incident-timeline p { margin: 6px 0 0; color: #516076; white-space: pre-wrap; font-size: 12px; line-height: 1.55; }
.incident-task-list, .incident-linked-list { display: grid; gap: 6px; margin-top: 11px; }.incident-task-row { display: grid; grid-template-columns: 29px 1fr auto; align-items: center; gap: 9px; border: 1px solid #e7ecf3; border-radius: 9px; padding: 9px; }.incident-task-toggle { width: 25px; height: 25px; border: 1px solid #cbd6e3; border-radius: 50%; color: #6a7d97; background: #fff; cursor: pointer; }.incident-task-toggle.task-in_progress { color: #b56618; border-color: #efc18b; background: #fff7eb; }.incident-task-toggle.task-done { color: #167257; border-color: #94d8c5; background: #eafff7; }.incident-task-row strong { display: block; color: #3d4f68; font-size: 12px; }.incident-task-row small, .incident-task-row time { color: #8491a3; font-size: 11px; }.incident-task-form { display: grid; grid-template-columns: 1fr 145px auto; gap: 7px; margin-top: 9px; }.incident-linked-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 15px; }.incident-link-form { display: grid; grid-template-columns: 1fr 108px auto; gap: 6px; margin-top: 10px; }.incident-linked-list article { border: 1px solid #e7ecf3; border-radius: 8px; padding: 8px 9px; }.incident-linked-list article div { display: grid; gap: 4px; min-width: 0; }.incident-linked-list span { color: #6781a4; font-size: 10px; }.incident-linked-list strong { overflow: hidden; color: #465975; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }.link-remove { border: 0; color: #a14b59; background: transparent; cursor: pointer; font-size: 11px; }.incident-modal-backdrop { position: fixed; z-index: 60; inset: 0; display: grid; place-items: center; padding: 20px; background: rgba(20, 31, 48, .45); }.incident-modal { width: min(680px, 100%); max-height: calc(100vh - 40px); overflow: auto; border-radius: 15px; background: #fff; box-shadow: 0 22px 65px rgba(17, 28, 45, .3); padding: 22px; }.incident-modal header { align-items: flex-start; margin-bottom: 15px; }.incident-modal header h2 { margin: 4px 0; color: #1f2e46; }.incident-modal header small { color: #79869a; }.incident-modal label { display: grid; gap: 6px; margin-top: 11px; color: #536278; font-size: 12px; font-weight: 600; }.incident-modal textarea { resize: vertical; }.incident-form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }.incident-modal footer { justify-content: flex-end; margin-top: 19px; }.command-modal { width: min(740px, 100%); }
@media (max-width: 1180px) { .incident-workspace { grid-template-columns: 1fr; }.incident-list-panel { min-height: auto; }.incident-card-list { grid-template-columns: repeat(2, minmax(0, 1fr)); }.incident-detail-panel { min-height: auto; } }
@media (max-width: 780px) { .incident-toolbar, .incident-detail-header { display: grid; }.incident-summary-strip, .incident-facts, .incident-impact-section > div, .incident-linked-grid { grid-template-columns: 1fr 1fr; }.incident-list-filters, .incident-update-form, .incident-task-form, .incident-link-form { grid-template-columns: 1fr; }.incident-card-list { grid-template-columns: 1fr; }.incident-form-grid { grid-template-columns: 1fr; }.incident-modal-backdrop { padding: 9px; }.incident-modal { padding: 17px; }.incident-summary-strip article:last-child { grid-column: span 2; } }
</style>
