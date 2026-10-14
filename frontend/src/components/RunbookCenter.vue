<script setup>
import { computed, onMounted, reactive, ref } from 'vue'

const props = defineProps({ api: { type: Function, required: true }, isAdmin: { type: Boolean, default: false } })

const center = ref({ runbooks: [], executions: [], alertMatchers: [], alertSources: [] })
const selectedRunbookId = ref(null)
const selectedRunbook = ref(null)
const selectedExecutionId = ref(null)
const selectedExecution = ref(null)
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const notice = ref('')
const showRunbookModal = ref(false)
const showExecutionModal = ref(false)
const editingRunbook = ref(null)
const editingMatcher = ref(null)
const newStep = reactive({ title: '', instruction: '', verificationHint: '', requiredStep: true })
const outcomeNote = ref('')
const runbookForm = reactive({ name: '', slug: '', category: '故障处置', targetType: 'GENERAL', description: '', enabled: true, stepsText: '' })
const executionForm = reactive({ sourceType: 'MANUAL', sourceId: '', title: '' })
const matcherForm = reactive({ name: '', alertSourceId: '', minimumSeverity: '', titleKeyword: '', sortOrder: 100, enabled: true })

const targetLabels = { GENERAL: '通用', ALERT: '监控告警', INCIDENT: '重大事件', TICKET: '服务工单' }
const sourceLabels = { MANUAL: '人工发起', ALERT: '监控告警', INCIDENT: '重大事件', TICKET: '服务工单' }
const statusLabels = { IN_PROGRESS: '处置中', COMPLETED: '已完成', FAILED: '失败', CANCELLED: '已取消' }
const stepStatusLabels = { PENDING: '待执行', PASSED: '已通过', FAILED: '失败', SKIPPED: '已跳过' }

const runbooks = computed(() => center.value?.runbooks || [])
const executions = computed(() => center.value?.executions || [])
const enabledRunbooks = computed(() => runbooks.value.filter(item => item.enabled !== false).length)
const activeExecutions = computed(() => executions.value.filter(item => item.status === 'IN_PROGRESS').length)
const failedExecutions = computed(() => executions.value.filter(item => item.status === 'FAILED').length)
const alertMatchers = computed(() => center.value?.alertMatchers || [])
const alertSources = computed(() => center.value?.alertSources || [])
const selectedTemplateStepCount = computed(() => selectedRunbook.value?.steps?.length || 0)
const executionProgress = computed(() => {
  const steps = selectedExecution.value?.steps || []
  if (!steps.length) return 0
  return Math.round(steps.filter(item => item.status === 'PASSED' || item.status === 'SKIPPED').length * 100 / steps.length)
})
const requiredRemaining = computed(() => (selectedExecution.value?.steps || []).filter(item => item.requiredStep && item.status !== 'PASSED').length)

function formatDate(value) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}
function statusClass(status) { return String(status || '').toLowerCase() }
function targetClass(target) { return String(target || '').toLowerCase() }
function normalizeSlug(value) { return String(value || '').trim().toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/(^-|-$)/g, '') }
function canExecute(runbook) { return runbook?.enabled !== false }
function matcherSourceName(id) { return id ? (alertSources.value.find(item => String(item.id) === String(id))?.name || '告警源 #' + id) : '所有告警源' }
function matchersFor(runbookId) { return alertMatchers.value.filter(item => String(item.runbookId) === String(runbookId)) }
function supportsAlertMatcher(runbook) { return ['GENERAL', 'ALERT'].includes(runbook?.targetType) }

async function loadCenter() {
  loading.value = true
  try {
    center.value = await props.api('/api/v1/runbooks')
  } catch (exception) {
    error.value = exception.message || '运行手册中心加载失败'
  } finally {
    loading.value = false
  }
}

async function loadRunbook(id) {
  if (!id) return
  selectedRunbookId.value = id
  try {
    selectedRunbook.value = await props.api('/api/v1/runbooks/' + id)
  } catch (exception) {
    error.value = exception.message || '运行手册详情加载失败'
  }
}

async function loadExecution(id) {
  if (!id) return
  selectedExecutionId.value = id
  try {
    selectedExecution.value = await props.api('/api/v1/runbooks/executions/' + id)
  } catch (exception) {
    error.value = exception.message || '处置记录加载失败'
  }
}

async function refresh() {
  await loadCenter()
  if (selectedRunbookId.value) await loadRunbook(selectedRunbookId.value)
  if (selectedExecutionId.value) await loadExecution(selectedExecutionId.value)
}

function resetRunbookForm() {
  Object.assign(runbookForm, { name: '', slug: '', category: '故障处置', targetType: 'GENERAL', description: '', enabled: true, stepsText: '' })
}

function openRunbookModal(runbook = null) {
  editingRunbook.value = runbook
  Object.assign(runbookForm, runbook ? {
    name: runbook.name, slug: runbook.slug, category: runbook.category, targetType: runbook.targetType,
    description: runbook.description || '', enabled: runbook.enabled !== false, stepsText: ''
  } : { name: '', slug: '', category: '故障处置', targetType: 'GENERAL', description: '', enabled: true, stepsText: '' })
  showRunbookModal.value = true
}

function parseSteps() {
  return runbookForm.stepsText.split('\n').map(item => item.trim()).filter(Boolean).map((item, index) => {
    const [title, ...rest] = item.split('|')
    return { sortOrder: (index + 1) * 10, title: title.trim(), instruction: (rest.join('|').trim() || title.trim()), requiredStep: true }
  })
}

async function saveRunbook() {
  const isEditing = Boolean(editingRunbook.value)
  const steps = parseSteps()
  if (!isEditing && !steps.length) {
    error.value = '至少填写一个处置步骤；每行可写“步骤标题 | 操作说明”'
    return
  }
  busy.value = true
  error.value = ''
  try {
    const body = { name: runbookForm.name, slug: normalizeSlug(runbookForm.slug), category: runbookForm.category,
      targetType: runbookForm.targetType, description: runbookForm.description, enabled: runbookForm.enabled,
      steps: isEditing ? undefined : steps }
    const saved = await props.api(isEditing ? '/api/v1/runbooks/' + editingRunbook.value.id : '/api/v1/runbooks', {
      method: isEditing ? 'PUT' : 'POST', body: JSON.stringify(body)
    })
    showRunbookModal.value = false
    resetRunbookForm()
    notice.value = isEditing ? '运行手册已更新，下一次执行会使用新版本' : '运行手册已创建，可立即发起一次人工处置'
    await loadCenter()
    await loadRunbook(saved.id)
  } catch (exception) {
    error.value = exception.message || '保存运行手册失败'
  } finally {
    busy.value = false
  }
}

async function addTemplateStep() {
  if (!selectedRunbook.value?.runbook) return
  busy.value = true
  error.value = ''
  try {
    await props.api('/api/v1/runbooks/' + selectedRunbook.value.runbook.id + '/steps', {
      method: 'POST', body: JSON.stringify({ ...newStep, sortOrder: (selectedTemplateStepCount.value + 1) * 10 })
    })
    Object.assign(newStep, { title: '', instruction: '', verificationHint: '', requiredStep: true })
    notice.value = '模板步骤已加入；版本号已递增，历史执行不受影响'
    await refresh()
  } catch (exception) {
    error.value = exception.message || '添加模板步骤失败'
  } finally {
    busy.value = false
  }
}

function openMatcherForm(matcher = null) {
  editingMatcher.value = matcher
  Object.assign(matcherForm, matcher ? {
    name: matcher.name, alertSourceId: matcher.alertSourceId || '', minimumSeverity: matcher.minimumSeverity || '',
    titleKeyword: matcher.titleKeyword || '', sortOrder: matcher.sortOrder ?? 100, enabled: matcher.enabled !== false
  } : { name: '', alertSourceId: '', minimumSeverity: '', titleKeyword: '', sortOrder: 100, enabled: true })
}

async function saveMatcher() {
  const runbook = selectedRunbook.value?.runbook
  if (!runbook) return
  busy.value = true
  error.value = ''
  try {
    const payload = { ...matcherForm, alertSourceId: matcherForm.alertSourceId ? Number(matcherForm.alertSourceId) : null,
      minimumSeverity: matcherForm.minimumSeverity || null, titleKeyword: matcherForm.titleKeyword || null,
      sortOrder: Number(matcherForm.sortOrder) }
    const path = editingMatcher.value ? '/api/v1/runbooks/alert-matchers/' + editingMatcher.value.id
      : '/api/v1/runbooks/' + runbook.id + '/alert-matchers'
    await props.api(path, { method: editingMatcher.value ? 'PUT' : 'POST', body: JSON.stringify(payload) })
    notice.value = editingMatcher.value ? '告警推荐规则已更新' : '告警推荐规则已创建；匹配后仍需人工确认执行'
    openMatcherForm()
    await refresh()
  } catch (exception) {
    error.value = exception.message || '保存告警推荐规则失败'
  } finally {
    busy.value = false
  }
}

function openExecutionModal(runbook = selectedRunbook.value?.runbook) {
  if (!runbook || !canExecute(runbook)) return
  selectedRunbookId.value = runbook.id
  Object.assign(executionForm, { sourceType: runbook.targetType === 'GENERAL' ? 'MANUAL' : runbook.targetType, sourceId: '', title: '' })
  showExecutionModal.value = true
}

async function startExecution() {
  busy.value = true
  error.value = ''
  try {
    const started = await props.api('/api/v1/runbooks/executions', {
      method: 'POST', body: JSON.stringify({ runbookId: Number(selectedRunbookId.value), sourceType: executionForm.sourceType,
        sourceId: executionForm.sourceType === 'MANUAL' || !executionForm.sourceId ? null : Number(executionForm.sourceId), title: executionForm.title })
    })
    showExecutionModal.value = false
    notice.value = '处置已经开始。请按步骤留下验证证据，所有必做步骤通过后才能完成。'
    await loadCenter()
    await loadExecution(started.id)
  } catch (exception) {
    error.value = exception.message || '发起处置失败'
  } finally {
    busy.value = false
  }
}

async function updateStep(step) {
  busy.value = true
  error.value = ''
  try {
    await props.api('/api/v1/runbooks/executions/steps/' + step.id, {
      method: 'PUT', body: JSON.stringify({ status: step.status, evidence: step.evidence || '' })
    })
    notice.value = '步骤状态与处置证据已记录'
    await refresh()
  } catch (exception) {
    error.value = exception.message || '记录步骤失败'
    await loadExecution(selectedExecutionId.value)
  } finally {
    busy.value = false
  }
}

async function finishExecution(action) {
  if (!selectedExecution.value?.execution) return
  if (action !== 'complete' && !outcomeNote.value.trim()) {
    error.value = '失败或取消必须填写原因，便于后续复盘'
    return
  }
  busy.value = true
  error.value = ''
  try {
    await props.api('/api/v1/runbooks/executions/' + selectedExecution.value.execution.id + '/' + action, {
      method: 'POST', body: JSON.stringify({ note: outcomeNote.value || '全部必做步骤已通过' })
    })
    notice.value = action === 'complete' ? '运行手册处置已闭环完成' : action === 'fail' ? '已记录失败结论' : '已记录取消原因'
    outcomeNote.value = ''
    await refresh()
  } catch (exception) {
    error.value = exception.message || '更新处置结论失败'
  } finally {
    busy.value = false
  }
}

onMounted(loadCenter)
</script>

<template>
  <section class="runbook-view">
    <header class="runbook-head">
      <div><p>RUNBOOK ORCHESTRATION</p><h2>运行手册与处置编排</h2><span>把告警、重大事件和工单的排障经验固化为人工可执行步骤，强制留存验证证据，不在平台中直接执行任何服务器命令。</span></div>
      <div class="head-actions"><button class="secondary-button" :disabled="loading || busy" @click="loadCenter">刷新</button><button v-if="isAdmin" class="primary-button" @click="openRunbookModal()">＋ 新建手册</button></div>
    </header>

    <p v-if="error" class="runbook-error">{{ error }}<button @click="error = ''">×</button></p>
    <p v-if="notice" class="runbook-notice">{{ notice }}<button @click="notice = ''">×</button></p>

    <div class="runbook-metrics"><article><span>手册模板</span><strong>{{ runbooks.length }}</strong><small>{{ enabledRunbooks }} 个已启用</small></article><article class="active"><span>处置进行中</span><strong>{{ activeExecutions }}</strong><small>必须按门禁完成</small></article><article :class="{ danger: failedExecutions }"><span>失败处置</span><strong>{{ failedExecutions }}</strong><small>需要纳入复盘</small></article><article><span>执行记录</span><strong>{{ executions.length }}</strong><small>最近 160 条可追溯记录</small></article></div>

    <div class="runbook-layout">
      <section class="runbook-card template-card"><div class="card-head"><div><p>VERSIONED PLAYBOOKS</p><h3>运行手册模板</h3></div><span>管理员维护</span></div>
        <div v-if="loading" class="empty-panel">正在读取运行手册…</div>
        <div v-else-if="!runbooks.length" class="empty-panel">还没有手册。先把一次常见故障的确认、隔离、验证过程沉淀下来。</div>
        <div v-else class="runbook-list"><button v-for="item in runbooks" :key="item.id" :class="['runbook-row', { selected: selectedRunbookId === item.id, disabled: item.enabled === false }]" @click="loadRunbook(item.id)"><div><span :class="['target-badge', targetClass(item.targetType)]">{{ targetLabels[item.targetType] }}</span><code>v{{ item.versionNo }}</code></div><strong>{{ item.name }}</strong><p>{{ item.category }} · {{ item.enabled === false ? '已停用' : '可执行' }}</p><small>{{ item.description || '未填写手册说明' }}</small></button></div>
      </section>

      <section v-if="selectedRunbook" class="runbook-card template-detail"><header class="detail-head"><div><p>{{ targetLabels[selectedRunbook.runbook.targetType] }} RUNBOOK · v{{ selectedRunbook.runbook.versionNo }}</p><h3>{{ selectedRunbook.runbook.name }}</h3><small>{{ selectedRunbook.runbook.category }} · {{ selectedRunbook.runbook.enabled === false ? '已停用' : '已启用' }}</small></div><div class="detail-actions"><button v-if="isAdmin" class="row-action" @click="openRunbookModal(selectedRunbook.runbook)">编辑</button><button v-if="canExecute(selectedRunbook.runbook)" class="primary-button" @click="openExecutionModal()">发起处置</button></div></header>
        <p class="detail-description">{{ selectedRunbook.runbook.description || '未填写手册说明。建议明确触发条件、风险边界和预计验证方式。' }}</p>
        <div class="step-list"><article v-for="(step, index) in selectedRunbook.steps" :key="step.id" class="template-step"><span>{{ index + 1 }}</span><div><strong>{{ step.title }}<i v-if="step.requiredStep">必做</i><i v-else class="optional">可选</i></strong><p>{{ step.instruction }}</p><small v-if="step.verificationHint">验证：{{ step.verificationHint }}</small></div></article><div v-if="!selectedRunbook.steps.length" class="empty-panel">此模板还没有步骤，暂时不能发起执行。</div></div>
        <form v-if="isAdmin" class="add-step" @submit.prevent="addTemplateStep"><input v-model="newStep.title" required maxlength="180" placeholder="新增步骤标题" /><input v-model="newStep.instruction" required maxlength="10000" placeholder="操作说明 / 验证动作" /><button class="row-action" :disabled="busy">添加步骤</button></form>
        <section v-if="isAdmin && supportsAlertMatcher(selectedRunbook.runbook)" class="matcher-panel"><div class="matcher-head"><div><p>ALERT TO RUNBOOK</p><h4>告警推荐规则</h4><small>命中后只给客服展示建议，不能替代人工判断或自动执行。</small></div><button class="row-action" type="button" @click="openMatcherForm()">新建规则</button></div><div v-if="!matchersFor(selectedRunbook.runbook.id).length" class="matcher-empty">暂未配置。可按告警源、最低严重度、标题关键词组合匹配。</div><div v-else class="matcher-list"><article v-for="matcher in matchersFor(selectedRunbook.runbook.id)" :key="matcher.id" :class="{ disabled: matcher.enabled === false }"><div><strong>{{ matcher.name }}</strong><small>{{ matcherSourceName(matcher.alertSourceId) }} · {{ matcher.minimumSeverity ? '≥ ' + matcher.minimumSeverity : '不限严重度' }}<span v-if="matcher.titleKeyword"> · “{{ matcher.titleKeyword }}”</span></small></div><button type="button" class="row-action" @click="openMatcherForm(matcher)">编辑</button></article></div><form class="matcher-form" @submit.prevent="saveMatcher"><input v-model="matcherForm.name" required maxlength="120" placeholder="规则名称，例如：生产高优订单告警" /><select v-model="matcherForm.alertSourceId"><option value="">全部告警源</option><option v-for="source in alertSources" :key="source.id" :value="source.id">{{ source.name }}</option></select><select v-model="matcherForm.minimumSeverity"><option value="">不限严重度</option><option value="CRITICAL">严重</option><option value="HIGH">高</option><option value="MEDIUM">中</option><option value="LOW">低</option><option value="INFO">信息</option></select><input v-model="matcherForm.titleKeyword" maxlength="160" placeholder="标题/描述关键词（可选）" /><div class="matcher-form-foot"><label><input v-model="matcherForm.enabled" type="checkbox" /> 启用推荐</label><button class="primary-button" :disabled="busy">{{ editingMatcher ? '保存规则' : '添加推荐规则' }}</button></div></form></section>
      </section>
      <section v-else class="runbook-card select-placeholder"><div class="empty-panel">选择左侧手册，查看版本化步骤并发起处置。</div></section>
    </div>

    <section class="runbook-card execution-card"><div class="card-head"><div><p>EXECUTION LEDGER</p><h3>处置执行记录</h3></div><span>步骤快照不可被后续模板修改覆盖</span></div><div v-if="!executions.length" class="empty-panel">还没有执行记录。选一份已启用的手册，结合告警、事件或工单发起处置。</div><div v-else class="execution-list"><button v-for="execution in executions" :key="execution.id" :class="['execution-row', { selected: selectedExecutionId === execution.id }]" @click="loadExecution(execution.id)"><div><code>{{ execution.executionNo }}</code><span :class="['execution-status', statusClass(execution.status)]">{{ statusLabels[execution.status] }}</span></div><strong>{{ execution.title }}</strong><p>{{ sourceLabels[execution.sourceType] }} · {{ execution.sourceRef || '人工发起' }} · 手册版本 v{{ execution.runbookVersion }}</p><small>{{ formatDate(execution.startedAt) }}</small></button></div></section>

    <section v-if="selectedExecution" class="runbook-card execution-detail"><header class="detail-head"><div><p>LIVE EXECUTION · {{ selectedExecution.execution.executionNo }}</p><h3>{{ selectedExecution.execution.title }}</h3><small>{{ sourceLabels[selectedExecution.execution.sourceType] }} · {{ selectedExecution.execution.sourceRef || '人工发起' }} · 模板 {{ selectedExecution.runbook?.name || '#' + selectedExecution.execution.runbookId }} v{{ selectedExecution.execution.runbookVersion }}</small></div><span :class="['execution-status', statusClass(selectedExecution.execution.status)]">{{ statusLabels[selectedExecution.execution.status] }}</span></header>
      <div class="progress-line"><span>执行进度 {{ executionProgress }}%</span><div><i :style="{ width: executionProgress + '%' }"></i></div><small>必做未通过 {{ requiredRemaining }} 项</small></div>
      <div class="execution-step-list"><article v-for="(step, index) in selectedExecution.steps" :key="step.id" :class="['execution-step', statusClass(step.status)]"><span>{{ index + 1 }}</span><div class="execution-step-main"><strong>{{ step.title }}<i v-if="step.requiredStep">必做</i><i v-else class="optional">可选</i></strong><p>{{ step.instruction }}</p><small v-if="step.verificationHint">验证：{{ step.verificationHint }}</small><textarea v-model="step.evidence" :disabled="selectedExecution.execution.status !== 'IN_PROGRESS'" rows="2" maxlength="2000" placeholder="留下检查结果、链接、指标变化或其他处置证据"></textarea></div><div class="execution-step-action"><select v-model="step.status" :disabled="selectedExecution.execution.status !== 'IN_PROGRESS'"><option v-for="(label, key) in stepStatusLabels" :key="key" :value="key">{{ label }}</option></select><button v-if="selectedExecution.execution.status === 'IN_PROGRESS'" class="row-action" :disabled="busy" @click="updateStep(step)">保存</button></div></article></div>
      <div v-if="selectedExecution.execution.status === 'IN_PROGRESS'" class="execution-footer"><textarea v-model="outcomeNote" rows="2" maxlength="1000" placeholder="完成时可补充处置结论；失败或取消时必须写明原因"></textarea><div><button class="row-action danger" :disabled="busy" @click="finishExecution('fail')">标记失败</button><button class="row-action" :disabled="busy" @click="finishExecution('cancel')">取消处置</button><button class="primary-button" :disabled="busy || requiredRemaining" @click="finishExecution('complete')">完成处置</button></div></div><p v-else-if="selectedExecution.execution.outcomeNote" class="outcome-note"><b>处置结论：</b>{{ selectedExecution.execution.outcomeNote }}</p>
    </section>

    <div v-if="showRunbookModal" class="modal-backdrop" @click.self="showRunbookModal = false"><form class="runbook-modal" @submit.prevent="saveRunbook"><header><div><p>RUNBOOK TEMPLATE</p><h2>{{ editingRunbook ? '编辑运行手册' : '新建运行手册' }}</h2></div><button type="button" @click="showRunbookModal = false">×</button></header><div class="form-grid"><label>手册名称<input v-model="runbookForm.name" required maxlength="120" placeholder="例如：支付超时告警排障" /></label><label>手册标识<input v-model="runbookForm.slug" required maxlength="140" placeholder="payment-timeout" @blur="runbookForm.slug = normalizeSlug(runbookForm.slug)" /></label></div><div class="form-grid"><label>分类<input v-model="runbookForm.category" required maxlength="64" placeholder="故障处置" /></label><label>适用对象<select v-model="runbookForm.targetType"><option v-for="(label, key) in targetLabels" :key="key" :value="key">{{ label }}</option></select></label></div><label>手册说明<textarea v-model="runbookForm.description" rows="3" maxlength="10000" placeholder="说明触发场景、风险边界和预期结果"></textarea></label><label>启用状态<select v-model="runbookForm.enabled"><option :value="true">启用，可发起处置</option><option :value="false">停用，仅保留历史记录</option></select></label><label v-if="!editingRunbook">初始化步骤 <small>每行一项；支持“步骤标题 | 操作说明”格式</small><textarea v-model="runbookForm.stepsText" required rows="6" placeholder="确认告警影响范围 | 查看近 15 分钟错误率和受影响实例\n核对变更活动 | 检查当前是否存在发布或配置变更\n验证恢复结果 | 记录核心指标恢复到基线"></textarea></label><p v-else class="form-hint">编辑会生成新版本，历史执行记录保留开始时固化的步骤快照；可在详情页继续添加步骤。</p><footer><button type="button" class="secondary-button" @click="showRunbookModal = false">取消</button><button class="primary-button" :disabled="busy">{{ busy ? '保存中…' : '保存手册' }}</button></footer></form></div>

    <div v-if="showExecutionModal" class="modal-backdrop" @click.self="showExecutionModal = false"><form class="runbook-modal execution-modal" @submit.prevent="startExecution"><header><div><p>START EXECUTION</p><h2>发起运行手册处置</h2><small>{{ selectedRunbook?.runbook?.name }}</small></div><button type="button" @click="showExecutionModal = false">×</button></header><label>处置来源<select v-model="executionForm.sourceType"><option v-for="(label, key) in sourceLabels" :key="key" :value="key">{{ label }}</option></select></label><label v-if="executionForm.sourceType !== 'MANUAL'">{{ sourceLabels[executionForm.sourceType] }} ID<input v-model="executionForm.sourceId" type="number" min="1" required placeholder="填写平台中的记录 ID" /></label><label>处置标题（可选）<input v-model="executionForm.title" maxlength="180" placeholder="留空则按关联对象自动生成" /></label><p class="form-hint">本平台只编排人工可执行步骤并保存证据，不会直接登录服务器或执行命令。</p><footer><button type="button" class="secondary-button" @click="showExecutionModal = false">取消</button><button class="primary-button" :disabled="busy">{{ busy ? '发起中…' : '开始处置' }}</button></footer></form></div>
  </section>
</template>

<style scoped>
.runbook-view { padding: 30px 34px 48px; color: #1d2b40; }.runbook-head, .card-head, .detail-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; }.runbook-head { margin-bottom: 24px; }.runbook-head p, .card-head p, .detail-head p, .runbook-modal header p { margin: 0; color: #6d7f96; font-size: 10px; font-weight: 800; letter-spacing: .14em; }.runbook-head h2 { margin: 6px 0; font-size: 25px; letter-spacing: -.04em; }.runbook-head span { display: block; max-width: 730px; color: #76869a; font-size: 13px; line-height: 1.55; }.head-actions, .detail-actions { display: flex; gap: 9px; }.primary-button, .secondary-button, .row-action { border-radius: 7px; padding: 8px 12px; font-size: 12px; font-weight: 700; cursor: pointer; }.primary-button { border: 1px solid #1f4d80; color: #fff; background: #245d97; }.secondary-button, .row-action { border: 1px solid #d7e0ea; color: #536780; background: #fff; }.primary-button:disabled, .secondary-button:disabled, .row-action:disabled { opacity: .55; cursor: wait; }.runbook-error, .runbook-notice { margin: 0 0 16px; padding: 10px 13px; border-radius: 8px; font-size: 13px; }.runbook-error { color: #ae3e4a; background: #fff0f1; }.runbook-notice { color: #197653; background: #eaf7f0; }.runbook-error button, .runbook-notice button { float: right; border: 0; color: inherit; background: transparent; cursor: pointer; }.runbook-metrics { display: grid; grid-template-columns: repeat(4, 1fr); gap: 13px; margin-bottom: 18px; }.runbook-metrics article { padding: 16px; border: 1px solid #e3e9f1; border-radius: 11px; background: #fff; }.runbook-metrics span, .runbook-metrics small { display: block; color: #79889b; font-size: 12px; }.runbook-metrics strong { display: block; margin: 4px 0; font-size: 24px; }.runbook-metrics .active { border-color: #c8e5d7; background: #f6fcf8; }.runbook-metrics .danger { border-color: #edc7cc; background: #fff8f9; }.runbook-card { margin-bottom: 18px; padding: 20px; border: 1px solid #e3e9f1; border-radius: 12px; background: #fff; box-shadow: 0 5px 16px rgba(19, 37, 65, .025); }.card-head h3, .detail-head h3 { margin: 5px 0 0; font-size: 16px; }.card-head > span, .detail-head small { color: #8090a4; font-size: 12px; }.runbook-layout { display: grid; grid-template-columns: minmax(260px, .9fr) minmax(0, 1.7fr); gap: 18px; align-items: start; }.runbook-list { margin-top: 15px; display: grid; gap: 8px; }.runbook-row, .execution-row { width: 100%; box-sizing: border-box; padding: 13px; text-align: left; border: 1px solid #e1e8f0; border-radius: 9px; background: #fff; cursor: pointer; }.runbook-row:hover, .runbook-row.selected, .execution-row:hover, .execution-row.selected { border-color: #92b3d6; background: #f7fbff; }.runbook-row.disabled { opacity: .6; }.runbook-row > div, .execution-row > div { display: flex; justify-content: space-between; gap: 8px; align-items: center; }.runbook-row strong, .execution-row strong { display: block; margin: 7px 0 4px; color: #243853; font-size: 13px; }.runbook-row p, .execution-row p { margin: 0; color: #698098; font-size: 11px; }.runbook-row small, .execution-row small { display: block; margin-top: 7px; color: #8794a4; font-size: 10px; line-height: 1.4; }.runbook-row code, .execution-row code { color: #7890aa; font-size: 10px; }.target-badge, .execution-status, .template-step i, .execution-step i { padding: 3px 6px; border-radius: 999px; font-size: 10px; font-style: normal; font-weight: 800; }.target-badge.general { color: #4c6889; background: #edf3fa; }.target-badge.alert { color: #9b6511; background: #fff3dd; }.target-badge.incident { color: #aa3e4d; background: #fff0f1; }.target-badge.ticket { color: #26715f; background: #eaf7f0; }.detail-description { margin: 16px 0; color: #60748d; line-height: 1.6; font-size: 13px; }.step-list { border-top: 1px solid #e9eef3; }.template-step, .execution-step { display: grid; grid-template-columns: 28px minmax(0, 1fr); gap: 11px; padding: 13px 0; border-bottom: 1px solid #e9eef3; }.template-step > span, .execution-step > span { display: grid; place-items: center; width: 25px; height: 25px; border-radius: 7px; color: #4a6687; background: #edf3fa; font-size: 11px; font-weight: 800; }.template-step strong, .execution-step strong { color: #263b56; font-size: 13px; }.template-step i, .execution-step i { margin-left: 7px; color: #aa690d; background: #fff3de; }.template-step i.optional, .execution-step i.optional { color: #5c718c; background: #edf2f7; }.template-step p, .execution-step p { margin: 6px 0; color: #637890; font-size: 12px; line-height: 1.55; white-space: pre-wrap; }.template-step small, .execution-step small { color: #8290a3; font-size: 11px; }.add-step { display: grid; grid-template-columns: 1fr 1.5fr auto; gap: 8px; margin-top: 15px; }.add-step input, .runbook-modal input, .runbook-modal textarea, .runbook-modal select, .execution-step textarea, .execution-step select, .execution-footer textarea { box-sizing: border-box; width: 100%; padding: 8px 9px; border: 1px solid #d9e2ec; border-radius: 7px; color: #263b56; background: #fff; font: inherit; }.select-placeholder { min-height: 220px; display: grid; place-items: center; }.empty-panel { margin-top: 15px; padding: 30px 16px; border: 1px dashed #d8e2ec; border-radius: 8px; color: #8491a4; text-align: center; font-size: 13px; }.execution-list { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; margin-top: 15px; }.execution-status.in_progress { color: #1d6f9b; background: #eaf6fc; }.execution-status.completed { color: #187752; background: #eaf7f0; }.execution-status.failed { color: #b13d4b; background: #fff0f1; }.execution-status.cancelled { color: #62748a; background: #eef2f6; }.progress-line { display: grid; grid-template-columns: auto 1fr auto; gap: 10px; align-items: center; margin: 18px 0 4px; color: #657990; font-size: 11px; }.progress-line > div { height: 8px; overflow: hidden; border-radius: 999px; background: #e8eef3; }.progress-line i { display: block; height: 100%; border-radius: inherit; background: #3e9b71; }.execution-step { grid-template-columns: 28px minmax(0, 1fr) 105px; }.execution-step-main textarea { margin-top: 9px; min-height: 54px; resize: vertical; font-size: 12px; }.execution-step-action { display: flex; flex-direction: column; gap: 7px; }.execution-step.pending > span { color: #748297; background: #eef2f6; }.execution-step.failed > span { color: #b1404c; background: #fff0f1; }.execution-step.passed > span { color: #197753; background: #eaf7f0; }.execution-footer { display: grid; grid-template-columns: 1fr auto; gap: 12px; align-items: end; margin-top: 17px; }.execution-footer textarea { min-height: 60px; resize: vertical; }.execution-footer > div { display: flex; gap: 8px; flex-wrap: wrap; justify-content: flex-end; }.row-action.danger { color: #ad3d4a; border-color: #efc9ce; }.outcome-note { padding: 12px; border-radius: 8px; color: #526880; background: #f3f7fa; font-size: 12px; }.modal-backdrop { position: fixed; inset: 0; z-index: 30; display: grid; place-items: center; padding: 20px; background: rgba(18, 30, 49, .46); }.runbook-modal { width: min(650px, 100%); max-height: calc(100vh - 40px); overflow: auto; box-sizing: border-box; padding: 22px; border-radius: 13px; background: #fff; box-shadow: 0 22px 70px rgba(9, 22, 42, .3); }.runbook-modal header { display: flex; justify-content: space-between; gap: 12px; margin-bottom: 15px; }.runbook-modal header h2 { margin: 5px 0 0; font-size: 19px; }.runbook-modal header small { color: #8190a3; font-size: 11px; }.runbook-modal header > button { border: 0; color: #8996a7; background: transparent; font-size: 23px; cursor: pointer; }.runbook-modal label { display: block; margin: 12px 0; color: #50647e; font-size: 12px; font-weight: 700; }.runbook-modal label small { font-weight: 400; color: #8190a3; }.runbook-modal label input, .runbook-modal label textarea, .runbook-modal label select { display: block; margin-top: 6px; }.runbook-modal textarea { line-height: 1.5; resize: vertical; }.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }.form-hint { color: #8190a3; font-size: 11px; line-height: 1.5; }.runbook-modal footer { display: flex; justify-content: flex-end; gap: 9px; margin-top: 18px; }
.matcher-panel { margin-top: 19px; padding-top: 16px; border-top: 1px solid #e7edf2; }.matcher-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }.matcher-head p { margin: 0; color: #687f9a; font-size: 10px; font-weight: 800; letter-spacing: .1em; }.matcher-head h4 { margin: 4px 0; font-size: 14px; }.matcher-head small, .matcher-list small { display: block; color: #7c8da3; font-size: 11px; line-height: 1.5; }.matcher-empty { margin: 12px 0; padding: 10px; border-radius: 7px; color: #8190a4; background: #f5f8fb; font-size: 11px; }.matcher-list { margin-top: 12px; }.matcher-list article { display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; padding: 9px 0; border-bottom: 1px solid #edf1f5; }.matcher-list article.disabled { opacity: .55; }.matcher-list strong { display: block; margin-bottom: 2px; color: #405873; font-size: 12px; }.matcher-form { display: grid; grid-template-columns: 1.2fr 1fr 1fr 1.3fr; gap: 7px; margin-top: 12px; }.matcher-form input, .matcher-form select { box-sizing: border-box; width: 100%; padding: 8px 9px; border: 1px solid #d9e2ec; border-radius: 7px; color: #263b56; background: #fff; font: inherit; }.matcher-form-foot { grid-column: 1 / -1; display: flex; align-items: center; justify-content: space-between; gap: 12px; }.matcher-form-foot label { color: #71839a; font-size: 11px; }.matcher-form-foot input { width: auto; margin-right: 4px; }
@media (max-width: 980px) { .runbook-layout { grid-template-columns: 1fr; }.execution-list { grid-template-columns: repeat(2, 1fr); }.runbook-metrics { grid-template-columns: repeat(2, 1fr); }.matcher-form { grid-template-columns: 1fr 1fr; } }
@media (max-width: 680px) { .runbook-view { padding: 22px 16px 36px; }.runbook-head { display: block; }.head-actions { margin-top: 15px; }.execution-list, .runbook-metrics, .form-grid, .add-step, .execution-footer, .matcher-form { grid-template-columns: 1fr; }.execution-step { grid-template-columns: 28px 1fr; }.execution-step-action { grid-column: 2; flex-direction: row; }.execution-footer > div { justify-content: flex-start; }.progress-line { grid-template-columns: 1fr; gap: 6px; } }
</style>
