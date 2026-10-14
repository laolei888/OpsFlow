<script setup>
import { computed, onMounted, reactive, ref } from 'vue'

const props = defineProps({ api: { type: Function, required: true }, isAdmin: { type: Boolean, default: false } })

const center = ref({ releases: [], availableChanges: [], freezeWindows: [] })
const selectedId = ref(null)
const detail = ref(null)
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const notice = ref('')
const showCreate = ref(false)
const showFreezeModal = ref(false)
const editingFreeze = ref(null)
const linkingChangeId = ref('')
const decisionNote = ref('')
const newChecklist = reactive({ phase: 'PRECHECK', title: '', requiredItem: true, sortOrder: 100 })
const createForm = reactive({
  name: '', description: '', environment: 'PRODUCTION', plannedStart: '', plannedEnd: '', changeIds: []
})
const freezeForm = reactive({ name: '', environment: '', startsAt: '', endsAt: '', reason: '', enabled: true })

const statusLabels = { DRAFT: '草稿', READY: '已就绪', IN_PROGRESS: '发布中', VERIFIED: '已验证', ROLLED_BACK: '已回滚', CANCELLED: '已取消' }
const environmentLabels = { DEVELOPMENT: '开发环境', STAGING: '预发布环境', PRODUCTION: '生产环境' }
const phaseLabels = { PRECHECK: '预检', DEPLOYMENT: '部署', VERIFICATION: '验证' }
const checklistStatusLabels = { PENDING: '待执行', PASSED: '已通过', FAILED: '失败', SKIPPED: '已跳过' }
const decisionLabels = { PENDING: '待决策', GO: 'GO', NO_GO: 'NO-GO' }
const phases = ['PRECHECK', 'DEPLOYMENT', 'VERIFICATION']

const releases = computed(() => center.value?.releases || [])
const availableChanges = computed(() => center.value?.availableChanges || [])
const freezeWindows = computed(() => center.value?.freezeWindows || [])
const activeReleases = computed(() => releases.value.filter(item => item.release.status === 'IN_PROGRESS').length)
const readyReleases = computed(() => releases.value.filter(item => item.release.status === 'READY').length)
const blockedReleases = computed(() => releases.value.filter(item => (item.gate?.failedRequired || 0) > 0).length)
const scheduledConflicts = computed(() => releases.value.filter(item => (item.conflictCount || 0) > 0 || (item.freezeConflictCount || 0) > 0).length)
const selectedChangeIds = computed(() => new Set((detail.value?.changes || []).map(item => String(item.link.changeId))))
const unlinkedChanges = computed(() => availableChanges.value.filter(change => !selectedChangeIds.value.has(String(change.id))))
const gatePercent = computed(() => {
  const gate = detail.value?.gate
  if (!gate?.requiredTotal) return 0
  return Math.round((gate.passedRequired / gate.requiredTotal) * 100)
})

function formatDate(value) {
  if (!value) return '未设置'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}

function releaseClass(status) { return String(status || '').toLowerCase() }
function checklistClass(status) { return String(status || '').toLowerCase() }
function phaseItems(phase) { return (detail.value?.checklist || []).filter(item => item.phase === phase) }
function canPlan(status) { return ['DRAFT', 'READY'].includes(status) }
function toLocalInput(value) { return value ? String(value).slice(0, 16) : '' }

async function loadCenter() {
  loading.value = true
  try {
    center.value = await props.api('/api/v1/releases')
  } catch (exception) {
    error.value = exception.message || '发布中心加载失败'
  } finally {
    loading.value = false
  }
}

async function loadDetail(id) {
  if (!id) return
  selectedId.value = id
  try {
    detail.value = await props.api('/api/v1/releases/' + id)
  } catch (exception) {
    error.value = exception.message || '发布详情加载失败'
  }
}

async function refreshSelected() {
  await loadCenter()
  if (selectedId.value) await loadDetail(selectedId.value)
}

async function execute(path, options, message) {
  busy.value = true
  error.value = ''
  try {
    await props.api(path, options)
    notice.value = message
    await refreshSelected()
  } catch (exception) {
    error.value = exception.message || '操作失败'
  } finally {
    busy.value = false
  }
}

function resetCreateForm() {
  Object.assign(createForm, { name: '', description: '', environment: 'PRODUCTION', plannedStart: '', plannedEnd: '', changeIds: [] })
}

async function createRelease() {
  busy.value = true
  error.value = ''
  try {
    const release = await props.api('/api/v1/releases', {
      method: 'POST',
      body: JSON.stringify({ ...createForm, changeIds: createForm.changeIds.map(Number) })
    })
    showCreate.value = false
    resetCreateForm()
    notice.value = '发布单已创建，并生成默认门禁清单'
    await loadCenter()
    await loadDetail(release.id)
  } catch (exception) {
    error.value = exception.message || '创建发布单失败'
  } finally {
    busy.value = false
  }
}

async function linkChange() {
  if (!linkingChangeId.value) return
  await execute('/api/v1/releases/' + selectedId.value + '/changes', {
    method: 'POST', body: JSON.stringify({ changeId: Number(linkingChangeId.value) })
  }, '已关联批准变更，若此前已 Go 将需要重新确认')
  linkingChangeId.value = ''
}

async function unlinkChange(changeId) {
  await execute('/api/v1/releases/' + selectedId.value + '/changes/' + changeId,
    { method: 'DELETE' }, '已移除关联变更')
}

async function saveChecklist() {
  await execute('/api/v1/releases/' + selectedId.value + '/checklist', {
    method: 'POST', body: JSON.stringify(newChecklist)
  }, '自定义门禁项已添加')
  Object.assign(newChecklist, { phase: 'PRECHECK', title: '', requiredItem: true, sortOrder: 100 })
}

async function setChecklistStatus(item, status) {
  await execute('/api/v1/releases/checklist/' + item.id, {
    method: 'PUT', body: JSON.stringify({ status, note: item.note || '' })
  }, '门禁执行结果已记录')
}

async function decide(go) {
  if (!go && !decisionNote.value.trim()) {
    error.value = '填写阻断原因后才能提交 No-Go 决策'
    return
  }
  await execute('/api/v1/releases/' + selectedId.value + '/go-no-go', {
    method: 'POST', body: JSON.stringify({ go, comment: decisionNote.value })
  }, go ? 'Go 决策已通过，发布单进入就绪状态' : 'No-Go 决策已记录，发布单回到草稿')
  decisionNote.value = ''
}

async function startRelease() {
  await execute('/api/v1/releases/' + selectedId.value + '/start', { method: 'POST' }, '发布已进入实施阶段')
}

async function verifyRelease() {
  await execute('/api/v1/releases/' + selectedId.value + '/verify', {
    method: 'POST', body: JSON.stringify({ note: decisionNote.value || '发布完成，验证通过' })
  }, '发布已完成并通过验证')
  decisionNote.value = ''
}

async function rollbackRelease() {
  if (!decisionNote.value.trim()) {
    error.value = '请填写回滚原因和处置结论'
    return
  }
  await execute('/api/v1/releases/' + selectedId.value + '/rollback', {
    method: 'POST', body: JSON.stringify({ note: decisionNote.value })
  }, '发布已标记为回滚')
  decisionNote.value = ''
}

async function cancelRelease() {
  if (!decisionNote.value.trim()) {
    error.value = '请填写取消发布的原因'
    return
  }
  await execute('/api/v1/releases/' + selectedId.value + '/cancel', {
    method: 'POST', body: JSON.stringify({ note: decisionNote.value })
  }, '发布已取消')
  decisionNote.value = ''
}

function openFreezeModal(freeze = null) {
  editingFreeze.value = freeze
  Object.assign(freezeForm, freeze ? {
    name: freeze.name, environment: freeze.environment || '', startsAt: toLocalInput(freeze.startsAt),
    endsAt: toLocalInput(freeze.endsAt), reason: freeze.reason, enabled: freeze.enabled !== false
  } : { name: '', environment: '', startsAt: '', endsAt: '', reason: '', enabled: true })
  showFreezeModal.value = true
}

async function saveFreeze() {
  const path = editingFreeze.value ? '/api/v1/releases/freezes/' + editingFreeze.value.id : '/api/v1/releases/freezes'
  await execute(path, { method: editingFreeze.value ? 'PUT' : 'POST', body: JSON.stringify({
    ...freezeForm, environment: freezeForm.environment || null
  }) }, editingFreeze.value ? '冻结窗口已更新' : '冻结窗口已创建')
  showFreezeModal.value = false
}

async function deleteFreeze(freeze) {
  if (!window.confirm('确认删除冻结窗口“' + freeze.name + '”吗？')) return
  await execute('/api/v1/releases/freezes/' + freeze.id, { method: 'DELETE' }, '冻结窗口已删除')
}

onMounted(loadCenter)
</script>

<template>
  <section class="release-view">
    <header class="release-head">
      <div><p>RELEASE GOVERNANCE</p><h2>发布治理中心</h2><span>按发布列车聚合获批变更，以可追溯的门禁、Go/No-Go 决策和验证证据守住上线质量。</span></div>
      <div class="head-actions"><button class="secondary-button" :disabled="loading || busy" @click="loadCenter">刷新</button><button v-if="isAdmin" class="secondary-button" @click="openFreezeModal()">冻结策略</button><button class="primary-button" @click="showCreate = true">＋ 新建发布单</button></div>
    </header>

    <p v-if="error" class="release-error">{{ error }}<button @click="error = ''">×</button></p>
    <p v-if="notice" class="release-notice">{{ notice }}<button @click="notice = ''">×</button></p>

    <div class="release-metrics"><article><span>发布单</span><strong>{{ releases.length }}</strong><small>最近 120 条</small></article><article class="ready"><span>已就绪</span><strong>{{ readyReleases }}</strong><small>已通过 Go 决策</small></article><article class="active"><span>发布中</span><strong>{{ activeReleases }}</strong><small>实施窗口内</small></article><article :class="{ danger: blockedReleases || scheduledConflicts }"><span>需复核风险</span><strong>{{ blockedReleases + scheduledConflicts }}</strong><small>{{ blockedReleases }} 项门禁失败 · {{ scheduledConflicts }} 项排期冲突</small></article></div>

    <section class="release-card calendar-card"><div class="card-head"><div><p>CHANGE CALENDAR &amp; FREEZES</p><h3>发布日历与冻结窗口</h3></div><span>{{ freezeWindows.filter(item => item.enabled).length }} 个启用策略</span></div><div v-if="!freezeWindows.length" class="calendar-empty">当前没有冻结窗口；管理员可以为大促、节假日或关键审计期配置发布保护。</div><div v-else class="freeze-list"><article v-for="freeze in freezeWindows" :key="freeze.id" :class="['freeze-row', { disabled: !freeze.enabled }]"><div><strong>{{ freeze.name }}</strong><small>{{ freeze.environment ? environmentLabels[freeze.environment] : '全部环境' }} · {{ formatDate(freeze.startsAt) }} → {{ formatDate(freeze.endsAt) }}</small><p>{{ freeze.reason }}</p></div><div class="freeze-actions"><span>{{ freeze.enabled ? '生效中' : '已停用' }}</span><button v-if="isAdmin" class="row-action" @click="openFreezeModal(freeze)">编辑</button><button v-if="isAdmin" class="row-action danger" @click="deleteFreeze(freeze)">删除</button></div></article></div></section>

    <div class="release-layout">
      <section class="release-card list-card"><div class="card-head"><div><p>RELEASE TRAIN</p><h3>发布队列</h3></div><span>{{ releases.length }} 条</span></div>
        <div v-if="loading" class="empty-panel">正在同步发布信息…</div>
        <div v-else-if="!releases.length" class="empty-panel">还没有发布单。创建一项发布，关联已批准变更后即可开始准备门禁。</div>
        <div v-else class="release-list"><button v-for="item in releases" :key="item.release.id" :class="['release-row', { selected: selectedId === item.release.id }]" @click="loadDetail(item.release.id)"><div class="release-row-head"><code>{{ item.release.releaseNo }}</code><span :class="['release-status', releaseClass(item.release.status)]">{{ statusLabels[item.release.status] }}</span></div><strong>{{ item.release.name }}</strong><p>{{ environmentLabels[item.release.environment] }} · 负责人 #{{ item.release.releaseManagerId }}</p><div class="release-risk-hints"><span v-if="item.freezeConflictCount" class="freeze-hint">冻结冲突 {{ item.freezeConflictCount }}</span><span v-if="item.conflictCount" class="collision-hint">资产冲突 {{ item.conflictCount }}</span></div><div class="release-row-foot"><span>{{ item.linkedChangeCount }} 项变更</span><span :class="{ blocked: item.gate?.failedRequired }">门禁 {{ item.gate?.passedRequired || 0 }}/{{ item.gate?.requiredTotal || 0 }}</span><time>{{ item.release.plannedStart ? formatDate(item.release.plannedStart) : '未排期' }}</time></div></button></div>
      </section>

      <section v-if="detail" class="release-card detail-card">
        <header class="detail-head"><div><p>{{ environmentLabels[detail.release.environment] }}</p><code>{{ detail.release.releaseNo }}</code><h3>{{ detail.release.name }}</h3><small>负责人 #{{ detail.release.releaseManagerId }} · {{ formatDate(detail.release.plannedStart) }} → {{ formatDate(detail.release.plannedEnd) }}</small></div><span :class="['release-status', releaseClass(detail.release.status)]">{{ statusLabels[detail.release.status] }}</span></header>
        <p v-if="detail.release.description" class="release-description">{{ detail.release.description }}</p>

        <div class="gate-summary"><div><span>Go/No-Go</span><strong :class="String(detail.release.goNoGoStatus || 'PENDING').toLowerCase()">{{ decisionLabels[detail.release.goNoGoStatus] }}</strong><small>{{ detail.release.decisionComment || '尚未记录决策说明' }}</small></div><div><span>必做门禁</span><strong>{{ detail.gate.passedRequired }}/{{ detail.gate.requiredTotal }}</strong><small :class="{ blocked: detail.gate.failedRequired }">{{ detail.gate.failedRequired ? detail.gate.failedRequired + ' 项失败' : detail.gate.pendingRequired + ' 项待执行' }}</small></div><div><span>完成进度</span><strong>{{ gatePercent }}%</strong><div class="progress"><i :style="{ width: gatePercent + '%' }"></i></div></div></div>

        <section v-if="detail.freezeOverlaps?.length || detail.conflicts?.length" class="collision-panel"><div v-if="detail.freezeOverlaps?.length"><strong>发布冻结窗口重叠</strong><p>当前窗口与「{{ detail.freezeOverlaps.map(item => item.name).join('、') }}」重叠。普通发布会被 Go 门禁阻断；全部关联为紧急变更时可作为例外留痕。</p></div><div v-if="detail.conflicts?.length"><strong>受影响配置项排期冲突</strong><article v-for="conflict in detail.conflicts" :key="conflict.conflictingRelease.id"><span :class="{ blocking: conflict.blocking }">{{ conflict.blocking ? '阻断' : '提醒' }}</span><b>{{ conflict.conflictingRelease.releaseNo }} · {{ conflict.conflictingRelease.name }}</b><small>共享资产 #{{ conflict.sharedAssetIds.join('、#') }} · {{ statusLabels[conflict.conflictingRelease.status] }}</small></article></div></section>

        <section class="action-panel"><label>决策 / 结论说明<textarea v-model="decisionNote" maxlength="1000" rows="2" placeholder="No-Go、回滚、取消时必填；验证结论可选"></textarea></label><div class="action-buttons"><template v-if="detail.release.status === 'DRAFT'"><button class="primary-button" :disabled="busy" @click="decide(true)">✓ 通过 Go 门禁</button><button class="danger-button" :disabled="busy" @click="decide(false)">阻断 No-Go</button><button class="secondary-button" :disabled="busy" @click="cancelRelease">取消发布</button></template><template v-else-if="detail.release.status === 'READY'"><button class="primary-button" :disabled="busy" @click="startRelease">开始实施</button><button class="danger-button" :disabled="busy" @click="decide(false)">改为 No-Go</button><button class="secondary-button" :disabled="busy" @click="cancelRelease">取消发布</button></template><template v-else-if="detail.release.status === 'IN_PROGRESS'"><button class="primary-button" :disabled="busy" @click="verifyRelease">完成并验证</button><button class="danger-button" :disabled="busy" @click="rollbackRelease">执行回滚</button></template><small v-else>该发布已结束，所有记录保留为可审计证据。</small></div></section>

        <section class="detail-section"><div class="section-heading"><div><p>APPROVED CHANGES</p><h4>发布范围</h4></div><span>{{ detail.changes.length }} 项</span></div><div v-if="canPlan(detail.release.status)" class="link-change"><select v-model="linkingChangeId"><option value="">选择一项已批准变更加入发布…</option><option v-for="change in unlinkedChanges" :key="change.id" :value="change.id">{{ change.changeNo }} · {{ change.title }}</option></select><button class="row-action" :disabled="!linkingChangeId || busy" @click="linkChange">关联</button></div><div v-if="!detail.changes.length" class="sub-empty">尚未关联变更。Go 决策前至少需要一项已批准变更。</div><article v-for="item in detail.changes" :key="item.link.id" class="linked-change"><div><code>{{ item.change?.changeNo || '#' + item.link.changeId }}</code><strong>{{ item.change?.title || '变更记录不可用' }}</strong><small>{{ item.change?.riskLevel || '未知' }} 风险 · {{ item.change?.status || '未知状态' }}</small></div><button v-if="canPlan(detail.release.status)" class="row-action danger" :disabled="busy" @click="unlinkChange(item.link.changeId)">移除</button></article></section>

        <section class="detail-section"><div class="section-heading"><div><p>RELEASE GATES</p><h4>阶段门禁与执行证据</h4></div><span>{{ detail.checklist.length }} 项</span></div><div v-for="phase in phases" :key="phase" class="phase-block"><header><strong>{{ phaseLabels[phase] }}</strong><small>{{ phase === 'PRECHECK' ? 'Go 决策与开始实施前必须全部通过' : phase === 'DEPLOYMENT' ? '记录执行步骤、异常与落地结果' : '完成发布前必须留存验收与观测证据' }}</small></header><div v-if="!phaseItems(phase).length" class="sub-empty">暂无该阶段门禁。</div><article v-for="item in phaseItems(phase)" :key="item.id" :class="['check-row', checklistClass(item.status)]"><select :value="item.status" :disabled="busy || ['VERIFIED', 'ROLLED_BACK', 'CANCELLED'].includes(detail.release.status)" @change="setChecklistStatus(item, $event.target.value)"><option v-for="(label, key) in checklistStatusLabels" :key="key" :value="key">{{ label }}</option></select><div><strong>{{ item.title }}</strong><small>{{ item.requiredItem ? '必做门禁' : '可选检查' }}<span v-if="item.verifiedAt"> · #{{ item.verifiedBy }} 于 {{ formatDate(item.verifiedAt) }}</span></small><input v-model="item.note" :disabled="busy || ['VERIFIED', 'ROLLED_BACK', 'CANCELLED'].includes(detail.release.status)" maxlength="1000" placeholder="补充证据、检查结论或异常说明" @blur="item.note !== undefined && setChecklistStatus(item, item.status)" /></div><span :class="['check-badge', checklistClass(item.status)]">{{ checklistStatusLabels[item.status] }}</span></article></div>
          <form v-if="canPlan(detail.release.status)" class="new-gate" @submit.prevent="saveChecklist"><select v-model="newChecklist.phase"><option v-for="phase in phases" :key="phase" :value="phase">{{ phaseLabels[phase] }}</option></select><input v-model="newChecklist.title" required maxlength="180" placeholder="添加自定义门禁项…" /><label><input v-model="newChecklist.requiredItem" type="checkbox" /> 必做</label><button class="row-action" :disabled="busy">添加</button></form>
        </section>
      </section>
      <section v-else class="release-card detail-placeholder"><div class="empty-panel">选择左侧发布单，查看关联变更、门禁证据与 Go/No-Go 决策。</div></section>
    </div>

    <div v-if="showCreate" class="modal-backdrop" @click.self="showCreate = false"><form class="release-modal" @submit.prevent="createRelease"><header><div><p>NEW RELEASE TRAIN</p><h3>创建发布单</h3><small>系统会自动生成预检、部署、验证三阶段门禁。</small></div><button type="button" @click="showCreate = false">×</button></header><label>发布名称<input v-model="createForm.name" required maxlength="120" placeholder="例如：订单服务 9 月生产发布" /></label><label>发布说明<textarea v-model="createForm.description" maxlength="4000" rows="3" placeholder="说明发布目标、业务影响、沟通范围与成功标准"></textarea></label><div class="form-grid"><label>发布环境<select v-model="createForm.environment"><option v-for="(label, key) in environmentLabels" :key="key" :value="key">{{ label }}</option></select></label><label>计划开始<input v-model="createForm.plannedStart" required type="datetime-local" /></label></div><label>计划结束<input v-model="createForm.plannedEnd" required type="datetime-local" /></label><div class="change-picker"><strong>初始变更范围（仅显示已批准变更）</strong><small>至少选择一项，后续仍可在草稿阶段调整。</small><div v-if="!availableChanges.length" class="sub-empty">当前没有已批准的变更，请先在变更管理中完成审批。</div><label v-for="change in availableChanges" v-else :key="change.id" class="change-choice"><input v-model="createForm.changeIds" type="checkbox" :value="change.id" /><span><code>{{ change.changeNo }}</code>{{ change.title }}<small>{{ change.riskLevel }} 风险 · {{ formatDate(change.scheduledStart) }}</small></span></label></div><footer><button type="button" class="secondary-button" @click="showCreate = false">取消</button><button class="primary-button" :disabled="busy">{{ busy ? '创建中…' : '创建发布单' }}</button></footer></form></div>

    <div v-if="showFreezeModal" class="modal-backdrop" @click.self="showFreezeModal = false"><form class="release-modal freeze-modal" @submit.prevent="saveFreeze"><header><div><p>RELEASE FREEZE POLICY</p><h3>{{ editingFreeze ? '编辑冻结窗口' : '创建冻结窗口' }}</h3><small>冻结期会阻断普通发布的 Go 决策；只有全部为紧急变更的发布可以留痕例外。</small></div><button type="button" @click="showFreezeModal = false">×</button></header><label>冻结名称<input v-model="freezeForm.name" required maxlength="120" placeholder="例如：国庆生产冻结" /></label><label>适用环境<select v-model="freezeForm.environment"><option value="">全部环境</option><option v-for="(label, key) in environmentLabels" :key="key" :value="key">{{ label }}</option></select></label><div class="form-grid"><label>开始时间<input v-model="freezeForm.startsAt" required type="datetime-local" /></label><label>结束时间<input v-model="freezeForm.endsAt" required type="datetime-local" /></label></div><label>冻结原因<textarea v-model="freezeForm.reason" required maxlength="1000" rows="4" placeholder="说明保护原因、业务窗口和例外处理原则"></textarea></label><label class="toggle-line"><input v-model="freezeForm.enabled" type="checkbox" /> 启用冻结策略</label><footer><button type="button" class="secondary-button" @click="showFreezeModal = false">取消</button><button class="primary-button" :disabled="busy">{{ busy ? '保存中…' : '保存冻结策略' }}</button></footer></form></div>
  </section>
</template>

<style scoped>
.release-view{padding:30px 34px 48px;color:#1d2b40}.release-head{display:flex;align-items:flex-end;justify-content:space-between;gap:24px;margin-bottom:24px}.release-head p,.card-head p,.detail-head p,.section-heading p,.release-modal header p{margin:0;color:#6b7d94;font-size:10px;font-weight:800;letter-spacing:.13em}.release-head h2{margin:6px 0;font-size:25px;letter-spacing:-.04em}.release-head>div>span{display:block;max-width:690px;color:#758399;font-size:13px;line-height:1.55}.head-actions,.action-buttons{display:flex;gap:9px;flex-wrap:wrap}.primary-button,.secondary-button,.danger-button{padding:9px 13px;border-radius:8px;font-size:13px;font-weight:700;cursor:pointer}.primary-button{border:1px solid #1f4c7d;background:#245a91;color:#fff}.secondary-button{border:1px solid #d6dfea;background:#fff;color:#526682}.danger-button{border:1px solid #cf6771;background:#c84955;color:#fff}.primary-button:disabled,.secondary-button:disabled,.danger-button:disabled,.row-action:disabled{opacity:.55;cursor:wait}.release-error,.release-notice{margin:0 0 16px;padding:10px 13px;border-radius:8px;font-size:13px}.release-error{background:#fff0f1;color:#ac3d49}.release-notice{background:#eaf7f0;color:#197653}.release-error button,.release-notice button{float:right;border:0;background:transparent;color:inherit;cursor:pointer}.release-metrics{display:grid;grid-template-columns:repeat(4,1fr);gap:13px;margin-bottom:18px}.release-metrics article{padding:16px;border:1px solid #e3e9f1;border-radius:11px;background:#fff}.release-metrics span,.release-metrics small{display:block;color:#78869a;font-size:12px}.release-metrics strong{display:block;margin:5px 0;font-size:23px}.release-metrics .ready{border-color:#cde8d9;background:#f8fdf9}.release-metrics .active{border-color:#cedbf0;background:#f8fbff}.release-metrics .danger{border-color:#edc8cd;background:#fff8f9}.release-layout{display:grid;grid-template-columns:minmax(320px,.82fr) minmax(0,1.45fr);gap:18px;align-items:start}.release-card{border:1px solid #e3e9f1;border-radius:12px;background:#fff;box-shadow:0 5px 16px rgba(19,37,65,.025)}.list-card{padding:20px}.detail-card{padding:22px}.card-head,.detail-head,.section-heading{display:flex;justify-content:space-between;gap:12px;align-items:flex-start}.card-head h3,.detail-head h3,.section-heading h4{margin:5px 0 0;font-size:16px}.card-head>span,.section-heading>span{color:#8290a4;font-size:12px}.empty-panel,.sub-empty{margin-top:16px;padding:28px 16px;border:1px dashed #dae2ec;border-radius:8px;color:#8491a4;text-align:center;font-size:13px}.release-list{margin-top:14px}.release-row{display:block;width:100%;box-sizing:border-box;margin:0 0 9px;padding:14px;border:1px solid #e5ebf2;border-radius:9px;background:#fff;color:inherit;text-align:left;cursor:pointer;transition:.14s}.release-row:hover,.release-row.selected{border-color:#98b6d8;box-shadow:0 5px 14px rgba(38,81,125,.08)}.release-row-head,.release-row-foot{display:flex;justify-content:space-between;gap:8px;align-items:center}.release-row code,.linked-change code,.change-choice code,.detail-head code{color:#6380a3;font-size:10px}.release-row>strong{display:block;margin:8px 0 4px;font-size:14px}.release-row>p{margin:0;color:#748398;font-size:11px}.release-row-foot{margin-top:10px;color:#7f8ea2;font-size:10px}.release-row-foot .blocked{color:#b74450;font-weight:800}.release-status,.check-badge{padding:4px 7px;border-radius:999px;font-size:10px;font-weight:800;white-space:nowrap}.release-status.draft{background:#f1f4f7;color:#728095}.release-status.ready{background:#eaf7f0;color:#197653}.release-status.in_progress{background:#edf3fd;color:#406d9f}.release-status.verified{background:#e3f6ec;color:#08744a}.release-status.rolled_back,.release-status.cancelled{background:#fff0f1;color:#b63f4b}.detail-head code{display:block;margin-top:4px}.detail-head small{display:block;margin-top:7px;color:#7c8b9f;font-size:11px}.release-description{margin:16px 0;color:#60728a;font-size:13px;line-height:1.65}.gate-summary{display:grid;grid-template-columns:1.1fr 1fr .8fr;gap:10px;margin:17px 0}.gate-summary>div{padding:12px;border-radius:8px;background:#f7f9fb}.gate-summary span,.gate-summary small,.gate-summary strong{display:block}.gate-summary span{color:#8290a4;font-size:10px}.gate-summary strong{margin:5px 0;color:#405670;font-size:14px}.gate-summary strong.go{color:#188055}.gate-summary strong.no_go{color:#bb3f4d}.gate-summary small{color:#78889c;font-size:10px}.gate-summary .blocked{color:#bc4050}.progress{height:6px;margin-top:8px;overflow:hidden;border-radius:999px;background:#e3eaf1}.progress i{display:block;height:100%;border-radius:inherit;background:#41a274}.action-panel{padding:13px;border:1px solid #dbe7f3;border-radius:9px;background:#f8fbfe}.action-panel label{display:block;color:#596d86;font-size:11px;font-weight:700}.action-panel textarea{box-sizing:border-box;width:100%;margin:6px 0 10px;padding:8px;border:1px solid #d7e1ec;border-radius:6px;font:inherit;resize:vertical}.action-panel small{color:#8190a3;font-size:11px}.detail-section{margin-top:22px;padding-top:19px;border-top:1px solid #e9eef3}.link-change,.new-gate{display:flex;gap:8px;margin:13px 0}.link-change select,.new-gate select,.new-gate input,.check-row input{box-sizing:border-box;min-width:0;flex:1;padding:7px 8px;border:1px solid #d7e0ea;border-radius:6px;background:#fff;color:#40546d;font:inherit;font-size:12px}.row-action{padding:6px 8px;border:1px solid #d8e0ea;border-radius:6px;background:#fff;color:#526782;font-size:12px;cursor:pointer}.row-action.danger{border-color:#efc5ca;color:#b74450}.linked-change{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:11px 0;border-bottom:1px solid #edf1f4}.linked-change:last-child{border-bottom:0}.linked-change strong,.linked-change small{display:block}.linked-change strong{margin:4px 0;color:#40556f;font-size:13px}.linked-change small{color:#7e8c9e;font-size:10px}.phase-block{margin-top:15px}.phase-block>header{display:flex;gap:9px;align-items:baseline;margin-bottom:7px}.phase-block>header strong{color:#425a75;font-size:12px}.phase-block>header small{color:#8290a2;font-size:10px;line-height:1.45}.check-row{display:grid;grid-template-columns:92px minmax(0,1fr) auto;gap:10px;align-items:start;padding:10px 0;border-bottom:1px solid #edf1f4}.check-row select{padding:6px;border:1px solid #d7e0ea;border-radius:6px;background:#fff;color:#52657d;font-size:11px}.check-row strong,.check-row small{display:block}.check-row strong{color:#40546d;font-size:12px}.check-row small{margin-top:3px;color:#8290a3;font-size:10px}.check-row input{width:100%;margin-top:6px}.check-badge.passed{background:#eaf7f0;color:#197653}.check-badge.failed{background:#fff0f1;color:#b74450}.check-badge.pending{background:#f0f3f6;color:#748398}.check-badge.skipped{background:#fff5e5;color:#9f6d19}.new-gate{align-items:center}.new-gate label{display:flex;gap:4px;align-items:center;white-space:nowrap;color:#63748a;font-size:11px}.detail-placeholder{min-height:280px;padding:20px}.modal-backdrop{position:fixed;inset:0;z-index:30;display:grid;place-items:center;padding:20px;background:rgba(20,31,49,.45)}.release-modal{box-sizing:border-box;width:min(650px,100%);max-height:calc(100vh - 40px);overflow:auto;padding:22px;border-radius:13px;background:#fff;box-shadow:0 22px 70px rgba(9,22,42,.3)}.release-modal header{display:flex;justify-content:space-between;gap:12px;margin-bottom:15px}.release-modal header h3{margin:5px 0;font-size:19px}.release-modal header small{color:#8290a2;font-size:11px}.release-modal header>button{border:0;background:transparent;color:#8b98a8;font-size:23px;cursor:pointer}.release-modal>label,.form-grid label{display:block;margin:12px 0;color:#50647e;font-size:12px;font-weight:700}.release-modal input,.release-modal textarea,.release-modal select{box-sizing:border-box;width:100%;margin-top:6px;padding:9px 10px;border:1px solid #dbe3ec;border-radius:7px;background:#fff;color:#23334c;font:inherit}.release-modal textarea{resize:vertical}.form-grid{display:grid;grid-template-columns:1fr 1fr;gap:12px}.change-picker{margin-top:15px;padding:13px;border-radius:8px;background:#f7f9fb}.change-picker>strong,.change-picker>small{display:block}.change-picker>strong{color:#465b73;font-size:12px}.change-picker>small{margin-top:3px;color:#8290a4;font-size:10px}.change-choice{display:flex;gap:8px;align-items:flex-start;margin:9px 0;color:#50647e;font-size:12px;font-weight:600}.change-choice input{width:auto;margin:2px 0 0}.change-choice small{display:block;margin-top:3px;color:#8491a2;font-size:10px}.release-modal footer{display:flex;justify-content:flex-end;gap:9px;margin-top:17px}@media(max-width:980px){.release-layout{grid-template-columns:1fr}.release-metrics{grid-template-columns:repeat(2,1fr)}}@media(max-width:680px){.release-view{padding:22px 16px 36px}.release-head{display:block}.head-actions{margin-top:15px}.gate-summary,.form-grid{grid-template-columns:1fr}.check-row{grid-template-columns:1fr}.check-badge{justify-self:start}.new-gate{flex-wrap:wrap}.new-gate input{min-width:180px}}
.calendar-card{margin-bottom:18px;padding:20px}.calendar-empty{margin-top:14px;padding:12px;border-radius:8px;background:#f7f9fb;color:#7d8ca0;font-size:12px}.freeze-list{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;margin-top:14px}.freeze-row{display:flex;justify-content:space-between;gap:12px;padding:13px;border:1px solid #e7d8b9;border-radius:8px;background:#fffaf0}.freeze-row.disabled{border-color:#e1e7ed;background:#fafbfc;opacity:.68}.freeze-row strong,.freeze-row small{display:block}.freeze-row strong{color:#6d5525;font-size:13px}.freeze-row small{margin-top:4px;color:#897a5d;font-size:10px;line-height:1.5}.freeze-row p{margin:8px 0 0;color:#7c725d;font-size:11px;line-height:1.5}.freeze-actions{display:flex;align-items:flex-end;flex-direction:column;gap:5px;min-width:58px}.freeze-actions>span{color:#9c6e19;font-size:10px;font-weight:800;white-space:nowrap}.release-risk-hints{display:flex;gap:5px;flex-wrap:wrap;margin-top:8px}.freeze-hint,.collision-hint{padding:3px 5px;border-radius:4px;font-size:10px;font-weight:800}.freeze-hint{background:#fff4df;color:#a16309}.collision-hint{background:#fff0f1;color:#b74450}.collision-panel{display:grid;gap:10px;margin:16px 0;padding:13px;border:1px solid #edd7ad;border-radius:9px;background:#fffaf1}.collision-panel>div>strong{color:#916111;font-size:12px}.collision-panel p{margin:5px 0 0;color:#796a50;font-size:11px;line-height:1.55}.collision-panel article{display:grid;grid-template-columns:auto 1fr;gap:5px 8px;margin-top:8px;padding-top:8px;border-top:1px solid #f0e1bf}.collision-panel article span{grid-row:span 2;padding:3px 5px;height:max-content;border-radius:4px;background:#f1f4f7;color:#728095;font-size:10px;font-weight:800}.collision-panel article span.blocking{background:#fff0f1;color:#b74450}.collision-panel article b{color:#50647d;font-size:11px}.collision-panel article small{color:#8090a3;font-size:10px}.toggle-line{display:flex!important;gap:7px;align-items:center}.toggle-line input{width:auto!important;margin:0!important}@media(max-width:980px){.freeze-list{grid-template-columns:1fr}}
</style>
