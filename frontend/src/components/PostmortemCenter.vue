<script setup>
import { computed, onMounted, reactive, ref } from 'vue'

const props = defineProps({
  api: { type: Function, required: true },
  staffMembers: { type: Array, default: () => [] },
  isAdmin: { type: Boolean, default: false }
})

const reviews = ref([])
const incidents = ref([])
const selected = ref(null)
const loading = ref(false)
const detailLoading = ref(false)
const busy = ref(false)
const error = ref('')
const notice = ref('')
const statusFilter = ref('')
const keyword = ref('')
const showCreate = ref(false)
const showEditor = ref(false)
const showAction = ref(false)
const editingAction = ref(null)
const reviewComment = ref('')

const createForm = reactive({ incidentId: '', title: '', facilitatorId: '', reviewerId: '', dueAt: '' })
const editor = reactive({
  title: '', facilitatorId: '', reviewerId: '', dueAt: '', executiveSummary: '', customerImpact: '',
  rootCause: '', contributingFactors: '', detectionGap: '', resolutionSummary: '', lessonsLearned: '', preventionPlan: ''
})
const actionForm = reactive({ title: '', description: '', ownerId: '', priority: 'MEDIUM', dueAt: '' })

const statusLabels = { DRAFT: '草稿', IN_REVIEW: '待评审', APPROVED: '已批准', PUBLISHED: '已发布' }
const incidentStatusLabels = {
  DECLARED: '已宣布', INVESTIGATING: '调查中', MITIGATING: '缓解中', MONITORING: '观察中',
  RESOLVED: '已解决', CLOSED: '已关闭', CANCELLED: '已取消'
}
const priorityLabels = { LOW: '低', MEDIUM: '中', HIGH: '高', CRITICAL: '紧急' }
const actionStatusLabels = { OPEN: '待处理', IN_PROGRESS: '进行中', DONE: '已完成', CANCELLED: '已取消' }

const selectedReview = computed(() => selected.value?.postmortem || null)
const selectedIncident = computed(() => selected.value?.incident || null)
const readiness = computed(() => selected.value?.readiness || { missingItems: [], actionCount: 0, completedActionCount: 0, overdueActionCount: 0 })
const draftCount = computed(() => reviews.value.filter(item => item.postmortem.status === 'DRAFT').length)
const reviewCount = computed(() => reviews.value.filter(item => item.postmortem.status === 'IN_REVIEW').length)
const overdueCount = computed(() => reviews.value.reduce((sum, item) => sum + Number(item.overdueActionCount || 0), 0))
const publishedCount = computed(() => reviews.value.filter(item => item.postmortem.status === 'PUBLISHED').length)
const usedIncidentIds = computed(() => new Set(reviews.value.map(item => String(item.postmortem.incidentId))))
const availableIncidents = computed(() => incidents.value.filter(item => item.status !== 'CANCELLED' && !usedIncidentIds.value.has(String(item.id))))
const canEditStructure = computed(() => selectedReview.value?.status === 'DRAFT')
const actionProgress = computed(() => {
  const total = readiness.value.actionCount || 0
  return total ? Math.round((readiness.value.completedActionCount / total) * 100) : 0
})

function statusLabel(value) { return statusLabels[value] || value || '-' }
function incidentStatusLabel(value) { return incidentStatusLabels[value] || value || '-' }
function staffName(id) {
  if (!id) return '未指定'
  return props.staffMembers.find(item => String(item.id) === String(id))?.displayName || '用户 #' + id
}
function formatDate(value) {
  if (!value) return '未设置'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}
function toInput(value) { return value ? String(value).slice(0, 16) : '' }
function payloadDate(value) { return value || null }
function flash(message) {
  notice.value = message
  window.setTimeout(() => { notice.value = '' }, 3200)
}
function isOverdue(action) {
  return action.dueAt && new Date(action.dueAt) < new Date() && !['DONE', 'CANCELLED'].includes(action.status)
}
function queryString() {
  const params = new URLSearchParams({ limit: '100' })
  if (statusFilter.value) params.set('status', statusFilter.value)
  if (keyword.value.trim()) params.set('keyword', keyword.value.trim())
  return params.toString()
}

async function loadReviews() {
  loading.value = true
  error.value = ''
  try {
    reviews.value = await props.api('/api/v1/postmortems?' + queryString()) || []
    if (selectedReview.value && !reviews.value.some(item => item.postmortem.id === selectedReview.value.id)) selected.value = null
  } catch (exception) {
    error.value = exception.message || '复盘中心加载失败'
  } finally {
    loading.value = false
  }
}

async function loadIncidents() {
  try {
    incidents.value = await props.api('/api/v1/incidents?limit=200') || []
  } catch (exception) {
    error.value = exception.message || '重大事件列表加载失败'
  }
}

async function openDetail(item) {
  if (!item) return
  detailLoading.value = true
  error.value = ''
  try {
    selected.value = await props.api('/api/v1/postmortems/' + item.postmortem.id)
  } catch (exception) {
    error.value = exception.message || '复盘详情加载失败'
  } finally {
    detailLoading.value = false
  }
}

async function refresh(message = '') {
  await loadReviews()
  if (selectedReview.value) {
    const listItem = reviews.value.find(item => item.postmortem.id === selectedReview.value.id)
    if (listItem) await openDetail(listItem)
  }
  if (message) flash(message)
}

function resetCreate() {
  Object.assign(createForm, {
    incidentId: '', title: '', facilitatorId: props.staffMembers[0]?.id || '', reviewerId: '', dueAt: ''
  })
}

function chooseIncident() {
  const incident = incidents.value.find(item => String(item.id) === String(createForm.incidentId))
  if (incident && !createForm.title) createForm.title = incident.title + ' · 事件复盘'
}

async function createReview() {
  busy.value = true
  error.value = ''
  try {
    const saved = await props.api('/api/v1/postmortems', {
      method: 'POST',
      body: JSON.stringify({
        incidentId: Number(createForm.incidentId), title: createForm.title || null,
        facilitatorId: createForm.facilitatorId ? Number(createForm.facilitatorId) : null,
        reviewerId: createForm.reviewerId ? Number(createForm.reviewerId) : null,
        dueAt: payloadDate(createForm.dueAt)
      })
    })
    showCreate.value = false
    await loadReviews()
    const item = reviews.value.find(current => current.postmortem.id === saved.id)
    if (item) await openDetail(item)
    flash('复盘草稿已创建，开始补全事实、根因与整改计划')
  } catch (exception) {
    error.value = exception.message || '创建复盘失败'
  } finally {
    busy.value = false
  }
}

function openEditor() {
  const review = selectedReview.value
  if (!review || review.status !== 'DRAFT') return
  Object.assign(editor, {
    title: review.title || '', facilitatorId: review.facilitatorId || '', reviewerId: review.reviewerId || '',
    dueAt: toInput(review.dueAt), executiveSummary: review.executiveSummary || '', customerImpact: review.customerImpact || '',
    rootCause: review.rootCause || '', contributingFactors: review.contributingFactors || '', detectionGap: review.detectionGap || '',
    resolutionSummary: review.resolutionSummary || '', lessonsLearned: review.lessonsLearned || '', preventionPlan: review.preventionPlan || ''
  })
  showEditor.value = true
}

async function saveReview() {
  busy.value = true
  error.value = ''
  try {
    await props.api('/api/v1/postmortems/' + selectedReview.value.id, {
      method: 'PUT', body: JSON.stringify({
        ...editor,
        facilitatorId: editor.facilitatorId ? Number(editor.facilitatorId) : null,
        reviewerId: editor.reviewerId ? Number(editor.reviewerId) : null,
        dueAt: payloadDate(editor.dueAt)
      })
    })
    showEditor.value = false
    await refresh('结构化复盘内容已保存')
  } catch (exception) {
    error.value = exception.message || '保存复盘失败'
  } finally {
    busy.value = false
  }
}

function openActionModal(action = null) {
  editingAction.value = action
  Object.assign(actionForm, action ? {
    title: action.title, description: action.description || '', ownerId: action.ownerId || '',
    priority: action.priority, dueAt: toInput(action.dueAt)
  } : { title: '', description: '', ownerId: '', priority: 'MEDIUM', dueAt: '' })
  showAction.value = true
}

async function saveAction() {
  busy.value = true
  error.value = ''
  try {
    const body = JSON.stringify({
      title: actionForm.title, description: actionForm.description,
      ownerId: actionForm.ownerId ? Number(actionForm.ownerId) : null,
      priority: actionForm.priority, dueAt: payloadDate(actionForm.dueAt)
    })
    if (editingAction.value) {
      await props.api('/api/v1/postmortems/actions/' + editingAction.value.id, { method: 'PUT', body })
    } else {
      await props.api('/api/v1/postmortems/' + selectedReview.value.id + '/actions', { method: 'POST', body })
    }
    showAction.value = false
    await refresh(editingAction.value ? '整改行动项已更新' : '整改行动项已添加')
  } catch (exception) {
    error.value = exception.message || '保存整改行动项失败'
  } finally {
    busy.value = false
  }
}

async function deleteAction(action) {
  if (!window.confirm('确认删除整改项“' + action.title + '”吗？')) return
  await run('/api/v1/postmortems/actions/' + action.id, { method: 'DELETE' }, '整改行动项已删除')
}

async function setActionStatus(action, status) {
  let evidence = action.completionEvidence || null
  if (status === 'DONE') {
    evidence = window.prompt('请输入完成证据（验证结果、监控指标或变更记录）', evidence || '')
    if (!evidence?.trim()) return
  }
  await run('/api/v1/postmortems/actions/' + action.id, {
    method: 'PUT', body: JSON.stringify({ status, completionEvidence: evidence })
  }, status === 'DONE' ? '整改项已完成并保留验证证据' : '整改进度已更新')
}

async function submitReview() {
  if (!window.confirm('提交后结构化内容将锁定，并进入管理员评审队列。确认提交吗？')) return
  await run('/api/v1/postmortems/' + selectedReview.value.id + '/submit', { method: 'POST' }, '复盘已提交评审')
}

async function decide(approve) {
  if (!approve && !reviewComment.value.trim()) {
    error.value = '驳回复盘时必须填写具体修改意见'
    return
  }
  await run('/api/v1/postmortems/' + selectedReview.value.id + '/review', {
    method: 'POST', body: JSON.stringify({ approve, comment: reviewComment.value || null })
  }, approve ? '复盘已批准，可发布并继续跟踪整改' : '复盘已驳回草稿')
  reviewComment.value = ''
}

async function publishReview() {
  if (!window.confirm('确认发布这份复盘吗？发布不会停止整改项跟踪。')) return
  await run('/api/v1/postmortems/' + selectedReview.value.id + '/publish', { method: 'POST' }, '复盘已正式发布')
}

async function run(path, options, message) {
  busy.value = true
  error.value = ''
  try {
    await props.api(path, options)
    await refresh(message)
  } catch (exception) {
    error.value = exception.message || '操作失败'
  } finally {
    busy.value = false
  }
}

onMounted(async () => {
  await Promise.all([loadReviews(), loadIncidents()])
})
</script>

<template>
  <section class="pm-view">
    <header class="pm-hero">
      <div>
        <p>POST-INCIDENT REVIEW</p>
        <h2>重大事件复盘中心</h2>
        <span>把事故后的经验沉淀为可评审的根因结论、可追责的整改项与可验证的关闭证据。</span>
      </div>
      <button class="pm-primary" :disabled="!availableIncidents.length" @click="resetCreate(); showCreate = true">＋ 创建事件复盘</button>
    </header>

    <div v-if="error" class="pm-alert error">{{ error }}<button @click="error = ''">×</button></div>
    <div v-if="notice" class="pm-alert success">{{ notice }}</div>

    <div class="pm-stats">
      <article><span>复盘草稿</span><strong>{{ draftCount }}</strong><small>待补全结构化结论</small></article>
      <article :class="{ attention: reviewCount }"><span>等待评审</span><strong>{{ reviewCount }}</strong><small>{{ isAdmin ? '需要管理员决策' : '管理员评审中' }}</small></article>
      <article :class="{ danger: overdueCount }"><span>逾期整改</span><strong>{{ overdueCount }}</strong><small>未完成且超过截止时间</small></article>
      <article><span>正式发布</span><strong>{{ publishedCount }}</strong><small>已沉淀复盘资产</small></article>
    </div>

    <div class="pm-toolbar">
      <input v-model="keyword" placeholder="搜索复盘编号、标题或根因…" @keyup.enter="loadReviews" />
      <select v-model="statusFilter" @change="loadReviews">
        <option value="">全部状态</option><option value="DRAFT">草稿</option><option value="IN_REVIEW">待评审</option>
        <option value="APPROVED">已批准</option><option value="PUBLISHED">已发布</option>
      </select>
      <button @click="loadReviews">查询</button>
    </div>

    <div class="pm-layout">
      <section class="pm-card pm-queue">
        <div class="pm-card-head"><div><p>REVIEW QUEUE</p><h3>复盘队列</h3></div><span>{{ reviews.length }} 份</span></div>
        <div v-if="loading" class="pm-empty">正在加载复盘队列…</div>
        <div v-else-if="!reviews.length" class="pm-empty">当前没有符合条件的事件复盘。</div>
        <button v-for="item in reviews" v-else :key="item.postmortem.id"
          :class="['pm-list-item', { selected: selectedReview?.id === item.postmortem.id }]" @click="openDetail(item)">
          <div class="pm-list-top"><code>{{ item.postmortem.reviewNo }}</code><span :class="['pm-status', item.postmortem.status.toLowerCase()]">{{ statusLabel(item.postmortem.status) }}</span></div>
          <strong>{{ item.postmortem.title }}</strong>
          <small>{{ item.incident?.incidentNo }} · {{ item.incident?.severity }} · {{ incidentStatusLabel(item.incident?.status) }}</small>
          <div class="pm-list-progress"><span><i :style="{ width: (item.actionCount ? Math.round(item.completedActionCount / item.actionCount * 100) : 0) + '%' }"></i></span><b>{{ item.completedActionCount }}/{{ item.actionCount }} 整改完成</b></div>
          <em v-if="item.overdueActionCount">{{ item.overdueActionCount }} 项已逾期</em>
        </button>
      </section>

      <section class="pm-card pm-detail">
        <div v-if="detailLoading" class="pm-empty">正在读取复盘详情…</div>
        <div v-else-if="!selected" class="pm-empty large"><b>选择一份复盘</b><span>查看根因结论、评审状态与整改行动项。</span></div>
        <template v-else>
          <header class="pm-detail-head">
            <div><p>{{ selectedReview.reviewNo }} · {{ selectedIncident.incidentNo }}</p><h2>{{ selectedReview.title }}</h2><span>{{ selectedIncident.severity }} · {{ incidentStatusLabel(selectedIncident.status) }} · 负责人 {{ staffName(selectedReview.facilitatorId) }}</span></div>
            <div class="pm-detail-actions">
              <span :class="['pm-status large', selectedReview.status.toLowerCase()]">{{ statusLabel(selectedReview.status) }}</span>
              <button v-if="canEditStructure" @click="openEditor">编辑复盘</button>
            </div>
          </header>

          <div v-if="selectedReview.rejectedReason" class="pm-review-note rejected"><b>最近驳回意见</b>{{ selectedReview.rejectedReason }}</div>
          <div v-else-if="selectedReview.reviewComment" class="pm-review-note"><b>评审意见</b>{{ selectedReview.reviewComment }}</div>

          <section class="pm-readiness">
            <div class="pm-readiness-main">
              <div><span>提交完整度</span><strong>{{ readiness.readyForSubmit ? '已满足' : '待补全' }}</strong></div>
              <p v-if="readiness.missingItems?.length"><span v-for="item in readiness.missingItems" :key="item">{{ item }}</span></p>
              <p v-else class="ready-copy">关键字段、评审人与整改责任均已就绪。</p>
            </div>
            <div class="pm-progress-ring"><strong>{{ actionProgress }}%</strong><span>整改完成</span></div>
          </section>

          <div class="pm-facts">
            <span><b>评审人</b>{{ staffName(selectedReview.reviewerId) }}</span>
            <span><b>复盘截止</b>{{ formatDate(selectedReview.dueAt) }}</span>
            <span><b>提交时间</b>{{ formatDate(selectedReview.submittedAt) }}</span>
            <span><b>发布时间</b>{{ formatDate(selectedReview.publishedAt) }}</span>
          </div>

          <div class="pm-content-grid">
            <article class="wide"><span>执行摘要</span><p>{{ selectedReview.executiveSummary || '尚未填写' }}</p></article>
            <article><span>客户影响</span><p>{{ selectedReview.customerImpact || '尚未填写' }}</p></article>
            <article class="root"><span>根因分析</span><p>{{ selectedReview.rootCause || '尚未填写' }}</p></article>
            <article><span>促成因素</span><p>{{ selectedReview.contributingFactors || '尚未填写' }}</p></article>
            <article><span>发现缺口</span><p>{{ selectedReview.detectionGap || '尚未填写' }}</p></article>
            <article><span>解决过程</span><p>{{ selectedReview.resolutionSummary || '尚未填写' }}</p></article>
            <article><span>经验教训</span><p>{{ selectedReview.lessonsLearned || '尚未填写' }}</p></article>
            <article class="wide"><span>预防计划</span><p>{{ selectedReview.preventionPlan || '尚未填写' }}</p></article>
          </div>

          <section class="pm-actions-section">
            <div class="pm-section-head"><div><p>CORRECTIVE ACTIONS</p><h3>整改行动项</h3></div><button v-if="canEditStructure" @click="openActionModal()">＋ 添加整改项</button></div>
            <div v-if="!selected.actions.length" class="pm-empty compact">还没有整改行动项。提交评审前至少添加一项。</div>
            <article v-for="action in selected.actions" v-else :key="action.id" :class="['pm-action', { overdue: isOverdue(action) }]">
              <div class="pm-action-check"><span :class="action.status.toLowerCase()">{{ action.status === 'DONE' ? '✓' : action.status === 'CANCELLED' ? '×' : '•' }}</span></div>
              <div class="pm-action-main">
                <div><strong>{{ action.title }}</strong><span :class="['pm-priority', action.priority.toLowerCase()]">{{ priorityLabels[action.priority] }}</span><em v-if="isOverdue(action)">已逾期</em></div>
                <p>{{ action.description || '暂无详细说明' }}</p>
                <small>{{ staffName(action.ownerId) }} · 截止 {{ formatDate(action.dueAt) }} · {{ actionStatusLabels[action.status] }}</small>
                <blockquote v-if="action.completionEvidence"><b>完成证据</b>{{ action.completionEvidence }}</blockquote>
              </div>
              <div class="pm-action-buttons">
                <button v-if="canEditStructure" @click="openActionModal(action)">编辑</button>
                <button v-if="canEditStructure" class="danger" @click="deleteAction(action)">删除</button>
                <button v-if="action.status === 'OPEN'" :disabled="busy" @click="setActionStatus(action, 'IN_PROGRESS')">开始</button>
                <button v-if="action.status === 'IN_PROGRESS'" :disabled="busy" @click="setActionStatus(action, 'DONE')">完成</button>
                <button v-if="action.status === 'DONE'" :disabled="busy" @click="setActionStatus(action, 'IN_PROGRESS')">重新打开</button>
              </div>
            </article>
          </section>

          <footer class="pm-workflow">
            <div v-if="selectedReview.status === 'DRAFT'">
              <span>准备完成后提交管理员评审，提交后正文锁定。</span>
              <button class="pm-primary" :disabled="busy || !readiness.readyForSubmit" @click="submitReview">提交评审</button>
            </div>
            <div v-else-if="selectedReview.status === 'IN_REVIEW'">
              <template v-if="isAdmin"><textarea v-model="reviewComment" placeholder="填写评审意见；驳回时必填"></textarea><button class="danger" :disabled="busy" @click="decide(false)">驳回修改</button><button class="pm-primary" :disabled="busy" @click="decide(true)">批准复盘</button></template>
              <span v-else>复盘正在等待管理员评审，整改项进度仍可更新。</span>
            </div>
            <div v-else-if="selectedReview.status === 'APPROVED'">
              <span>复盘已获批准，结构化结论可用于重大事件关闭门禁。</span>
              <button v-if="isAdmin" class="pm-primary" :disabled="busy" @click="publishReview">正式发布</button>
            </div>
            <div v-else><span>复盘已发布。整改项将持续跟踪，完成时必须填写验证证据。</span></div>
          </footer>
        </template>
      </section>
    </div>

    <div v-if="showCreate" class="pm-modal-backdrop" @click.self="showCreate = false">
      <form class="pm-modal" @submit.prevent="createReview">
        <header><div><p>NEW POST-INCIDENT REVIEW</p><h3>创建重大事件复盘</h3></div><button type="button" @click="showCreate = false">×</button></header>
        <label>关联重大事件<select v-model="createForm.incidentId" required @change="chooseIncident"><option value="" disabled>选择尚未复盘的事件</option><option v-for="incident in availableIncidents" :key="incident.id" :value="incident.id">{{ incident.incidentNo }} · {{ incident.severity }} · {{ incident.title }}</option></select></label>
        <label>复盘标题<input v-model="createForm.title" maxlength="160" placeholder="默认使用事件标题" /></label>
        <div class="pm-form-grid"><label>复盘负责人<select v-model="createForm.facilitatorId"><option v-for="member in staffMembers" :key="member.id" :value="member.id">{{ member.displayName }}</option></select></label><label>评审人<select v-model="createForm.reviewerId"><option value="">稍后指定</option><option v-for="member in staffMembers" :key="member.id" :value="member.id">{{ member.displayName }}</option></select></label></div>
        <label>计划完成时间<input v-model="createForm.dueAt" type="datetime-local" /></label>
        <footer><button type="button" @click="showCreate = false">取消</button><button class="pm-primary" :disabled="busy">{{ busy ? '创建中…' : '创建草稿' }}</button></footer>
      </form>
    </div>

    <div v-if="showEditor" class="pm-modal-backdrop" @click.self="showEditor = false">
      <form class="pm-modal wide" @submit.prevent="saveReview">
        <header><div><p>STRUCTURED REVIEW</p><h3>编辑结构化复盘</h3></div><button type="button" @click="showEditor = false">×</button></header>
        <label>复盘标题<input v-model="editor.title" required maxlength="160" /></label>
        <div class="pm-form-grid"><label>复盘负责人<select v-model="editor.facilitatorId" required><option v-for="member in staffMembers" :key="member.id" :value="member.id">{{ member.displayName }}</option></select></label><label>评审人<select v-model="editor.reviewerId" required><option value="" disabled>请选择评审人</option><option v-for="member in staffMembers" :key="member.id" :value="member.id">{{ member.displayName }}</option></select></label><label>复盘截止<input v-model="editor.dueAt" type="datetime-local" /></label></div>
        <label>执行摘要<textarea v-model="editor.executiveSummary" rows="3" placeholder="用管理层可理解的方式概括事故、影响、根因和结果"></textarea></label>
        <div class="pm-editor-grid"><label>客户影响<textarea v-model="editor.customerImpact" rows="4" placeholder="影响用户、持续时间和业务损失"></textarea></label><label>根因分析<textarea v-model="editor.rootCause" rows="4" placeholder="描述技术与流程根因，避免只写直接现象"></textarea></label><label>促成因素<textarea v-model="editor.contributingFactors" rows="4" placeholder="架构、容量、流程或人员因素"></textarea></label><label>发现缺口<textarea v-model="editor.detectionGap" rows="4" placeholder="为什么没有更早发现或自动阻断"></textarea></label><label>解决过程<textarea v-model="editor.resolutionSummary" rows="4" placeholder="关键缓解、恢复和验证步骤"></textarea></label><label>经验教训<textarea v-model="editor.lessonsLearned" rows="4" placeholder="做得好的、需要改进的"></textarea></label></div>
        <label>预防计划<textarea v-model="editor.preventionPlan" rows="4" placeholder="后续防复发策略，与下方具体整改项对应"></textarea></label>
        <footer><button type="button" @click="showEditor = false">取消</button><button class="pm-primary" :disabled="busy">{{ busy ? '保存中…' : '保存复盘' }}</button></footer>
      </form>
    </div>

    <div v-if="showAction" class="pm-modal-backdrop" @click.self="showAction = false">
      <form class="pm-modal" @submit.prevent="saveAction">
        <header><div><p>CORRECTIVE ACTION</p><h3>{{ editingAction ? '编辑整改行动项' : '新增整改行动项' }}</h3></div><button type="button" @click="showAction = false">×</button></header>
        <label>行动项标题<input v-model="actionForm.title" required maxlength="180" placeholder="例如：为数据库连接池补充容量告警" /></label>
        <label>实施说明<textarea v-model="actionForm.description" rows="4" maxlength="2000" placeholder="明确范围、验收标准和预期结果"></textarea></label>
        <div class="pm-form-grid"><label>负责人<select v-model="actionForm.ownerId" required><option value="" disabled>选择负责人</option><option v-for="member in staffMembers" :key="member.id" :value="member.id">{{ member.displayName }}</option></select></label><label>优先级<select v-model="actionForm.priority"><option v-for="(label, value) in priorityLabels" :key="value" :value="value">{{ label }}</option></select></label><label>截止时间<input v-model="actionForm.dueAt" type="datetime-local" required /></label></div>
        <footer><button type="button" @click="showAction = false">取消</button><button class="pm-primary" :disabled="busy">{{ busy ? '保存中…' : '保存行动项' }}</button></footer>
      </form>
    </div>
  </section>
</template>

<style scoped>
.pm-view{display:grid;gap:18px;color:#18202b}.pm-hero{display:flex;justify-content:space-between;gap:24px;align-items:flex-end;padding:26px 30px;border-radius:22px;background:linear-gradient(125deg,#12243d,#173b51 58%,#1d5b61);color:#fff;box-shadow:0 16px 36px #17364d25}.pm-hero p,.pm-card-head p,.pm-section-head p,.pm-modal header p,.pm-detail-head p{margin:0 0 6px;font-size:11px;letter-spacing:.16em;font-weight:800;color:#57d5c2}.pm-hero h2{margin:0 0 8px;font-size:27px}.pm-hero span{font-size:13px;color:#c8d8e2}.pm-primary{border:0!important;background:#17a58f!important;color:#fff!important;box-shadow:0 7px 18px #08796735}.pm-primary:disabled{opacity:.45;box-shadow:none}.pm-hero button{white-space:nowrap;padding:12px 18px;border-radius:12px}.pm-alert{display:flex;justify-content:space-between;padding:12px 16px;border-radius:12px;font-size:13px}.pm-alert.error{background:#fff0ef;color:#a43f38;border:1px solid #f1c4c0}.pm-alert.success{background:#eaf9f5;color:#147663;border:1px solid #bfe8dd}.pm-alert button{border:0;background:none;color:inherit;font-size:18px}.pm-stats{display:grid;grid-template-columns:repeat(4,1fr);gap:14px}.pm-stats article{display:grid;gap:3px;padding:17px 19px;background:#fff;border:1px solid #e5ebef;border-radius:16px}.pm-stats article.attention{border-color:#f2d49b;background:#fffaf1}.pm-stats article.danger{border-color:#efbab6;background:#fff8f7}.pm-stats span{font-size:12px;color:#66717d}.pm-stats strong{font-size:27px}.pm-stats small{font-size:11px;color:#929ba4}.pm-toolbar{display:flex;gap:10px}.pm-toolbar input{min-width:330px}.pm-toolbar input,.pm-toolbar select,.pm-toolbar button,.pm-detail-actions button,.pm-section-head button,.pm-action-buttons button{height:38px;border:1px solid #dce4e9;border-radius:10px;background:#fff;padding:0 12px;color:#33404d}.pm-toolbar button,.pm-detail-actions button,.pm-section-head button,.pm-action-buttons button{cursor:pointer;font-weight:700}.pm-layout{display:grid;grid-template-columns:350px minmax(0,1fr);gap:16px;align-items:start}.pm-card{background:#fff;border:1px solid #e3e9ed;border-radius:18px;box-shadow:0 10px 28px #2438490b}.pm-queue{overflow:hidden;position:sticky;top:12px;max-height:calc(100vh - 120px);overflow-y:auto}.pm-card-head,.pm-section-head{display:flex;justify-content:space-between;align-items:center;padding:18px 20px;border-bottom:1px solid #edf1f3}.pm-card-head p,.pm-section-head p{color:#5f7a8f}.pm-card-head h3,.pm-section-head h3{margin:0;font-size:16px}.pm-card-head>span{font-size:12px;color:#7c8791}.pm-list-item{position:relative;width:100%;display:grid;gap:8px;text-align:left;padding:17px 19px;border:0;border-bottom:1px solid #edf1f3;background:#fff;cursor:pointer}.pm-list-item:hover,.pm-list-item.selected{background:#f4faf9}.pm-list-item.selected:before{content:"";position:absolute;left:0;top:0;bottom:0;width:4px;background:#16a58e}.pm-list-top{display:flex;justify-content:space-between;align-items:center}.pm-list-item code{font-size:11px;color:#4f6e82}.pm-list-item strong{font-size:14px}.pm-list-item small{font-size:11px;color:#7d8791}.pm-list-item em{font-style:normal;font-size:11px;color:#bd4a42}.pm-status{display:inline-flex;align-items:center;padding:4px 8px;border-radius:999px;font-size:10px;font-weight:800;background:#eef2f5;color:#576574}.pm-status.draft{background:#eef1f3}.pm-status.in_review{background:#fff3d9;color:#96650e}.pm-status.approved{background:#e8f4ff;color:#276c9e}.pm-status.published{background:#e5f7f1;color:#137563}.pm-status.large{padding:7px 11px;font-size:11px}.pm-list-progress{display:flex;align-items:center;gap:9px}.pm-list-progress>span{height:5px;flex:1;overflow:hidden;background:#e8edef;border-radius:5px}.pm-list-progress i{display:block;height:100%;background:#18a28d}.pm-list-progress b{font-size:10px;color:#71808b}.pm-detail{padding:0 24px 24px;min-height:520px}.pm-detail-head{display:flex;justify-content:space-between;gap:18px;padding:23px 0 19px;border-bottom:1px solid #e8edef}.pm-detail-head p{color:#568096}.pm-detail-head h2{margin:0 0 7px;font-size:22px}.pm-detail-head span{font-size:12px;color:#75808b}.pm-detail-actions{display:flex;align-items:center;gap:9px}.pm-review-note{display:grid;gap:5px;margin-top:16px;padding:13px 15px;border-radius:11px;background:#f1f7fb;color:#476377;font-size:12px}.pm-review-note.rejected{background:#fff2f0;color:#9c433c}.pm-readiness{display:flex;align-items:center;justify-content:space-between;gap:20px;margin:18px 0;padding:18px 20px;border-radius:15px;background:#f5f8fa}.pm-readiness-main{flex:1}.pm-readiness-main>div{display:flex;gap:12px;align-items:baseline}.pm-readiness-main span{font-size:12px;color:#71808b}.pm-readiness-main strong{font-size:17px}.pm-readiness-main p{display:flex;gap:6px;flex-wrap:wrap;margin:10px 0 0}.pm-readiness-main p span{padding:4px 7px;border-radius:6px;background:#fff1de;color:#a16515;font-size:10px}.pm-readiness-main p.ready-copy{font-size:11px;color:#27806d}.pm-progress-ring{display:grid;place-items:center;width:76px;height:76px;border:7px solid #d7eee9;border-top-color:#16a58e;border-radius:50%}.pm-progress-ring strong{font-size:16px}.pm-progress-ring span{font-size:9px;color:#6e7c86}.pm-facts{display:grid;grid-template-columns:repeat(4,1fr);gap:10px;margin-bottom:16px}.pm-facts span{display:grid;gap:4px;padding:11px 12px;border:1px solid #e8edef;border-radius:10px;font-size:11px;color:#596875}.pm-facts b{font-size:9px;text-transform:uppercase;color:#9aa3ab}.pm-content-grid{display:grid;grid-template-columns:1fr 1fr;gap:11px}.pm-content-grid article{min-height:95px;padding:14px 15px;border:1px solid #e7ecef;border-radius:12px;background:#fff}.pm-content-grid article.wide{grid-column:1/-1}.pm-content-grid article.root{border-color:#a8ded4;background:#f5fbf9}.pm-content-grid span{font-size:10px;font-weight:800;letter-spacing:.08em;color:#6b8798}.pm-content-grid p{margin:8px 0 0;white-space:pre-wrap;line-height:1.65;font-size:12px;color:#42505d}.pm-actions-section{margin-top:22px;border:1px solid #e5ebee;border-radius:15px;overflow:hidden}.pm-section-head{padding:15px 17px}.pm-action{display:grid;grid-template-columns:34px minmax(0,1fr) auto;gap:12px;padding:15px 17px;border-top:1px solid #edf1f3}.pm-action.overdue{background:#fff9f8}.pm-action-check span{display:grid;place-items:center;width:28px;height:28px;border-radius:9px;background:#edf1f3;color:#536471;font-weight:900}.pm-action-check span.in_progress{background:#e5f1fb;color:#256a9a}.pm-action-check span.done{background:#e4f7f1;color:#147762}.pm-action-check span.cancelled{background:#f0f1f2;color:#858b91}.pm-action-main>div{display:flex;align-items:center;gap:7px;flex-wrap:wrap}.pm-action-main strong{font-size:13px}.pm-action-main p{margin:6px 0;color:#65717c;font-size:11px}.pm-action-main small{font-size:10px;color:#89929a}.pm-action-main em{font-size:9px;font-style:normal;color:#b9463e}.pm-action-main blockquote{display:grid;gap:3px;margin:9px 0 0;padding:9px 11px;border-left:3px solid #21a58f;background:#f3faf8;font-size:10px;color:#4b665f}.pm-priority{font-size:9px;padding:3px 6px;border-radius:5px;background:#eef1f3}.pm-priority.high,.pm-priority.critical{background:#ffebe8;color:#ad4037}.pm-action-buttons{display:flex;align-items:flex-start;gap:5px;flex-wrap:wrap;max-width:150px}.pm-action-buttons button{height:29px;padding:0 8px;font-size:10px}.danger{color:#b3453d!important;border-color:#ebc2bf!important}.pm-workflow{margin-top:18px;padding:15px 17px;border-radius:13px;background:#172c3d;color:#d8e3ea}.pm-workflow>div{display:flex;align-items:center;justify-content:flex-end;gap:9px}.pm-workflow span{margin-right:auto;font-size:11px}.pm-workflow textarea{min-height:38px;flex:1;resize:vertical;border-radius:8px;padding:9px}.pm-workflow button{height:37px;border:1px solid #668092;border-radius:9px;background:#fff;padding:0 13px;font-weight:800}.pm-empty{padding:42px 20px;text-align:center;color:#87919a;font-size:12px}.pm-empty.large{min-height:420px;display:grid;place-content:center;gap:6px}.pm-empty.large b{font-size:17px;color:#53616d}.pm-empty.compact{padding:24px}.pm-modal-backdrop{position:fixed;z-index:1200;inset:0;display:grid;place-items:center;padding:24px;background:#14202c99;backdrop-filter:blur(3px)}.pm-modal{width:min(590px,96vw);max-height:90vh;overflow:auto;display:grid;gap:14px;padding:23px;border-radius:18px;background:#fff;box-shadow:0 28px 80px #08141d55}.pm-modal.wide{width:min(900px,96vw)}.pm-modal header{display:flex;justify-content:space-between}.pm-modal header p{color:#538197}.pm-modal header h3{margin:0;font-size:20px}.pm-modal header button{border:0;background:none;font-size:22px}.pm-modal label{display:grid;gap:6px;font-size:11px;font-weight:700;color:#5d6b76}.pm-modal input,.pm-modal select,.pm-modal textarea{width:100%;box-sizing:border-box;border:1px solid #dce4e9;border-radius:9px;padding:10px 11px;background:#fbfcfd;color:#273541;font:inherit;font-size:12px}.pm-modal textarea{resize:vertical;line-height:1.5}.pm-form-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:11px}.pm-editor-grid{display:grid;grid-template-columns:1fr 1fr;gap:11px}.pm-modal footer{display:flex;justify-content:flex-end;gap:9px;padding-top:5px}.pm-modal footer button{height:39px;border:1px solid #d7e0e5;border-radius:10px;background:#fff;padding:0 16px;font-weight:800}
@media(max-width:1100px){.pm-layout{grid-template-columns:1fr}.pm-queue{position:static;max-height:430px}.pm-facts{grid-template-columns:1fr 1fr}}@media(max-width:720px){.pm-hero{align-items:flex-start;flex-direction:column}.pm-stats{grid-template-columns:1fr 1fr}.pm-toolbar{flex-wrap:wrap}.pm-toolbar input{min-width:100%}.pm-detail{padding:0 15px 15px}.pm-detail-head{flex-direction:column}.pm-content-grid,.pm-editor-grid{grid-template-columns:1fr}.pm-content-grid article.wide{grid-column:auto}.pm-action{grid-template-columns:30px 1fr}.pm-action-buttons{grid-column:2}.pm-form-grid{grid-template-columns:1fr}.pm-facts{grid-template-columns:1fr}.pm-workflow>div{align-items:stretch;flex-direction:column}.pm-workflow span{margin:0}}
</style>
