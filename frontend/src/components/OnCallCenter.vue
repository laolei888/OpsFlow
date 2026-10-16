<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'

const props = defineProps({
  api: { type: Function, required: true },
  staffMembers: { type: Array, default: () => [] },
  isAdmin: { type: Boolean, default: false }
})

const schedules = ref([])
const shifts = ref([])
const policies = ref([])
const escalationEvents = ref([])
const loading = ref(false)
const error = ref('')
const notice = ref('')
const selectedScheduleId = ref('')
const selectedPolicyId = ref('')
const nowTick = ref(Date.now())
const showScheduleModal = ref(false)
const showShiftModal = ref(false)
const showPolicyModal = ref(false)
const showStepModal = ref(false)
const saving = ref(false)
const editingSchedule = ref(null)
const editingShift = ref(null)
const editingPolicy = ref(null)
const editingStep = ref(null)

const scheduleForm = reactive({ name: '', description: '', timezone: 'Asia/Shanghai', enabled: true })
const shiftForm = reactive({ scheduleId: '', userId: '', startAt: '', endAt: '', primary: true, handoffNote: '' })
const policyForm = reactive({ name: '', description: '', minimumSeverity: 'SEV2', enabled: true })
const stepForm = reactive({ stepNo: 1, delayMinutes: 0, targetType: 'ON_CALL', scheduleId: '', targetUserId: '' })

const severityLabels = { SEV1: 'SEV1 · 极严重', SEV2: 'SEV2 · 高', SEV3: 'SEV3 · 中', SEV4: 'SEV4 · 低' }
const targetLabels = { ON_CALL: '当前值班人', USER: '指定成员', ALL_STAFF: '全部客服' }
const eventStatusLabels = { ACTIVE: '升级中', EXHAUSTED: '已完成升级', RESOLVED: '事件已解决', CANCELLED: '已停止' }
const deliveryStatusLabels = { SENT: '已入队', SUPPRESSED: '已去重' }

const selectedSchedule = computed(() => schedules.value.find(item => String(item.id) === String(selectedScheduleId.value)) || null)
const selectedPolicy = computed(() => policies.value.find(item => String(item.policy.id) === String(selectedPolicyId.value)) || null)
const activePrimaryShifts = computed(() => {
  const now = nowTick.value
  return shifts.value.filter(shift => shift.primary !== false
    && new Date(shift.startAt).getTime() <= now && new Date(shift.endAt).getTime() > now)
})
const selectedScheduleShifts = computed(() => shifts.value.filter(shift => String(shift.scheduleId) === String(selectedScheduleId.value)))
const upcomingSelectedShifts = computed(() => selectedScheduleShifts.value
  .filter(shift => new Date(shift.endAt).getTime() > nowTick.value)
  .sort((left, right) => new Date(left.startAt).getTime() - new Date(right.startAt).getTime()))
const activeCoverageCount = computed(() => new Set(activePrimaryShifts.value.map(item => item.userId)).size)
const activeEventCount = computed(() => escalationEvents.value.filter(item => item.event.status === 'ACTIVE').length)
const policyStepCount = computed(() => policies.value.reduce((total, item) => total + item.steps.length, 0))

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
function staffName(id) {
  if (!id) return '未指定'
  return props.staffMembers.find(item => String(item.id) === String(id))?.displayName || '用户 #' + id
}
function scheduleName(id) {
  return schedules.value.find(item => String(item.id) === String(id))?.name || '值班表 #' + id
}
function severityLabel(value) { return severityLabels[value] || value || '-' }
function targetLabel(value) { return targetLabels[value] || value || '-' }
function eventStatusLabel(value) { return eventStatusLabels[value] || value || '-' }
function statusClass(value) { return 'oncall-status-' + String(value || '').toLowerCase() }
function notify(message) {
  notice.value = message
  window.setTimeout(() => { notice.value = '' }, 3200)
}
function rangeQuery() {
  const start = toDateTimeInput(new Date(Date.now() - 24 * 60 * 60 * 1000))
  const end = toDateTimeInput(new Date(Date.now() + 14 * 24 * 60 * 60 * 1000))
  return 'from=' + encodeURIComponent(start) + '&to=' + encodeURIComponent(end)
}
async function loadData() {
  loading.value = true
  error.value = ''
  try {
    const [scheduleList, shiftList, policyList, eventList] = await Promise.all([
      props.api('/api/v1/oncall/schedules'),
      props.api('/api/v1/oncall/shifts?' + rangeQuery()),
      props.api('/api/v1/oncall/policies'),
      props.api('/api/v1/oncall/escalation-events?limit=80')
    ])
    schedules.value = scheduleList || []
    shifts.value = shiftList || []
    policies.value = policyList || []
    escalationEvents.value = eventList || []
    if (!schedules.value.some(item => String(item.id) === String(selectedScheduleId.value))) {
      selectedScheduleId.value = schedules.value[0]?.id || ''
    }
    if (!policies.value.some(item => String(item.policy.id) === String(selectedPolicyId.value))) {
      selectedPolicyId.value = policies.value[0]?.policy?.id || ''
    }
  } catch (err) {
    error.value = err.message
  } finally {
    loading.value = false
  }
}

function resetScheduleForm(schedule = null) {
  editingSchedule.value = schedule
  Object.assign(scheduleForm, {
    name: schedule?.name || '', description: schedule?.description || '',
    timezone: schedule?.timezone || 'Asia/Shanghai', enabled: schedule?.enabled !== false
  })
}
function openScheduleModal(schedule = null) {
  resetScheduleForm(schedule)
  showScheduleModal.value = true
}
async function saveSchedule() {
  saving.value = true
  error.value = ''
  try {
    const path = editingSchedule.value ? '/api/v1/oncall/schedules/' + editingSchedule.value.id : '/api/v1/oncall/schedules'
    await props.api(path, { method: editingSchedule.value ? 'PUT' : 'POST', body: JSON.stringify(scheduleForm) })
    showScheduleModal.value = false
    await loadData()
    notify(editingSchedule.value ? '值班表已更新' : '值班表已创建')
  } catch (err) { error.value = err.message }
  finally { saving.value = false }
}

function resetShiftForm(shift = null) {
  editingShift.value = shift
  Object.assign(shiftForm, {
    scheduleId: shift?.scheduleId || selectedScheduleId.value || schedules.value[0]?.id || '',
    userId: shift?.userId || '', startAt: shift ? toDateTimeInput(shift.startAt) : afterHours(1),
    endAt: shift ? toDateTimeInput(shift.endAt) : afterHours(9), primary: shift?.primary !== false,
    handoffNote: shift?.handoffNote || ''
  })
}
function openShiftModal(shift = null) {
  if (!schedules.value.length) {
    error.value = '请先创建一个值班表'
    return
  }
  resetShiftForm(shift)
  showShiftModal.value = true
}
async function saveShift() {
  saving.value = true
  error.value = ''
  try {
    const payload = { ...shiftForm, scheduleId: undefined, userId: Number(shiftForm.userId) }
    const path = editingShift.value ? '/api/v1/oncall/shifts/' + editingShift.value.id
      : '/api/v1/oncall/schedules/' + shiftForm.scheduleId + '/shifts'
    await props.api(path, { method: editingShift.value ? 'PUT' : 'POST', body: JSON.stringify(payload) })
    showShiftModal.value = false
    await loadData()
    notify(editingShift.value ? '值班轮班已更新' : '值班轮班已创建')
  } catch (err) { error.value = err.message }
  finally { saving.value = false }
}
async function deleteShift(shift) {
  if (!window.confirm('确定删除这条值班轮班吗？')) return
  try {
    await props.api('/api/v1/oncall/shifts/' + shift.id, { method: 'DELETE' })
    await loadData()
    notify('值班轮班已删除')
  } catch (err) { error.value = err.message }
}

function resetPolicyForm(policy = null) {
  editingPolicy.value = policy
  Object.assign(policyForm, {
    name: policy?.name || '', description: policy?.description || '',
    minimumSeverity: policy?.minimumSeverity || 'SEV2', enabled: policy?.enabled !== false
  })
}
function openPolicyModal(policy = null) {
  resetPolicyForm(policy)
  showPolicyModal.value = true
}
async function savePolicy() {
  saving.value = true
  error.value = ''
  try {
    const path = editingPolicy.value ? '/api/v1/oncall/policies/' + editingPolicy.value.id : '/api/v1/oncall/policies'
    await props.api(path, { method: editingPolicy.value ? 'PUT' : 'POST', body: JSON.stringify(policyForm) })
    showPolicyModal.value = false
    await loadData()
    notify(editingPolicy.value ? '升级策略已更新' : '升级策略已创建')
  } catch (err) { error.value = err.message }
  finally { saving.value = false }
}

function resetStepForm(step = null) {
  editingStep.value = step
  const suggested = selectedPolicy.value?.steps.length ? Math.max(...selectedPolicy.value.steps.map(item => item.stepNo || 0)) + 1 : 1
  Object.assign(stepForm, {
    stepNo: step?.stepNo || suggested, delayMinutes: step?.delayMinutes || 0,
    targetType: step?.targetType || 'ON_CALL', scheduleId: step?.scheduleId || selectedScheduleId.value || '',
    targetUserId: step?.targetUserId || ''
  })
}
function openStepModal(step = null) {
  if (!selectedPolicy.value) {
    error.value = '请先选择一个升级策略'
    return
  }
  resetStepForm(step)
  showStepModal.value = true
}
async function saveStep() {
  if (!selectedPolicy.value) return
  saving.value = true
  error.value = ''
  try {
    const payload = {
      stepNo: Number(stepForm.stepNo), delayMinutes: Number(stepForm.delayMinutes) || 0,
      targetType: stepForm.targetType,
      scheduleId: stepForm.targetType === 'ON_CALL' && stepForm.scheduleId ? Number(stepForm.scheduleId) : null,
      targetUserId: stepForm.targetType === 'USER' && stepForm.targetUserId ? Number(stepForm.targetUserId) : null
    }
    const path = editingStep.value ? '/api/v1/oncall/steps/' + editingStep.value.id
      : '/api/v1/oncall/policies/' + selectedPolicy.value.policy.id + '/steps'
    await props.api(path, { method: editingStep.value ? 'PUT' : 'POST', body: JSON.stringify(payload) })
    showStepModal.value = false
    await loadData()
    notify(editingStep.value ? '升级步骤已更新' : '升级步骤已添加')
  } catch (err) { error.value = err.message }
  finally { saving.value = false }
}
async function deleteStep(step) {
  if (!window.confirm('确定删除这个升级步骤吗？')) return
  try {
    await props.api('/api/v1/oncall/steps/' + step.id, { method: 'DELETE' })
    await loadData()
    notify('升级步骤已删除')
  } catch (err) { error.value = err.message }
}
function stepDestination(step) {
  if (step.targetType === 'ON_CALL') return scheduleName(step.scheduleId)
  if (step.targetType === 'USER') return staffName(step.targetUserId)
  return '当前租户全部客服'
}

let ticker
onMounted(() => {
  loadData()
  ticker = window.setInterval(() => { nowTick.value = Date.now() }, 60000)
})
onBeforeUnmount(() => window.clearInterval(ticker))
</script>

<template>
  <section class="oncall-center">
    <div class="oncall-toolbar">
      <div><p class="eyebrow">ON-CALL & ESCALATION</p><h2>值班与升级中心</h2><small>将未分派 SLA 告警和重大事件，优先交给当前值班人，并按策略自动逐级升级。</small></div>
      <div class="oncall-toolbar-actions"><button class="secondary-button" :disabled="loading" @click="loadData">{{ loading ? '刷新中…' : '刷新' }}</button><button v-if="isAdmin" class="primary-button" @click="openScheduleModal()">＋ 新建值班表</button></div>
    </div>
    <p v-if="error" class="oncall-message error">{{ error }}</p><p v-if="notice" class="oncall-message notice">✓ {{ notice }}</p>

    <div class="oncall-summary"><article><span>当前覆盖</span><strong>{{ activeCoverageCount }}</strong><small>主值班成员</small></article><article :class="{ attention: activeEventCount }"><span>正在升级</span><strong>{{ activeEventCount }}</strong><small>等待响应的事件</small></article><article><span>值班表</span><strong>{{ schedules.length }}</strong><small>当前租户配置</small></article><article><span>升级步骤</span><strong>{{ policyStepCount }}</strong><small>策略中的响应链路</small></article></div>

    <div class="oncall-main-grid">
      <section class="oncall-panel schedule-panel"><header class="panel-heading"><div><p class="eyebrow">COVERAGE</p><h3>值班覆盖</h3></div><span>{{ schedules.length }} 个</span></header>
        <div v-if="!schedules.length" class="oncall-empty">尚未配置值班表。管理员可先建立业务线或系统的值班安排。</div>
        <div v-else class="schedule-list"><article v-for="schedule in schedules" :key="schedule.id" :class="['schedule-card', { selected: String(selectedScheduleId) === String(schedule.id), disabled: schedule.enabled === false }]" @click="selectedScheduleId = schedule.id"><div><strong>{{ schedule.name }}</strong><small>{{ schedule.timezone }}</small></div><span :class="schedule.enabled === false ? 'schedule-disabled' : 'schedule-enabled'">{{ schedule.enabled === false ? '已停用' : '运行中' }}</span><p>{{ schedule.description || '未填写说明' }}</p><footer><span>{{ activePrimaryShifts.filter(item => String(item.scheduleId) === String(schedule.id)).length ? '当前有人值班' : '当前无主值班' }}</span><button v-if="isAdmin" class="text-action" @click.stop="openScheduleModal(schedule)">编辑</button></footer></article></div>
      </section>
      <section class="oncall-panel shift-panel"><header class="panel-heading"><div><p class="eyebrow">SHIFT TIMELINE</p><h3>{{ selectedSchedule?.name || '选择值班表' }}</h3></div><button v-if="isAdmin && selectedSchedule" class="secondary-button small" @click="openShiftModal()">＋ 排班</button></header>
        <div v-if="!selectedSchedule" class="oncall-empty">选择左侧值班表后查看轮班时间轴。</div>
        <template v-else><div class="coverage-now"><div><span>当前主值班</span><strong>{{ activePrimaryShifts.filter(item => String(item.scheduleId) === String(selectedSchedule.id)).map(item => staffName(item.userId)).join('、') || '暂无覆盖' }}</strong></div><small>{{ selectedSchedule.timezone }} · 未分派 SLA 告警优先通知这里的成员</small></div><div v-if="!upcomingSelectedShifts.length" class="oncall-empty compact">未来 14 天还没有排班。</div><div v-else class="shift-list"><article v-for="shift in upcomingSelectedShifts" :key="shift.id" :class="['shift-row', { active: new Date(shift.startAt).getTime() <= nowTick && new Date(shift.endAt).getTime() > nowTick }]"><span class="shift-marker">{{ shift.primary === false ? '备' : '主' }}</span><div><strong>{{ staffName(shift.userId) }}</strong><small>{{ formatDate(shift.startAt) }} → {{ formatDate(shift.endAt) }}</small><p v-if="shift.handoffNote">{{ shift.handoffNote }}</p></div><div class="shift-actions"><span v-if="new Date(shift.startAt).getTime() <= nowTick && new Date(shift.endAt).getTime() > nowTick">值班中</span><button v-if="isAdmin" class="text-action" @click="openShiftModal(shift)">编辑</button><button v-if="isAdmin" class="text-action danger" @click="deleteShift(shift)">删除</button></div></article></div></template>
      </section>
    </div>

    <div class="oncall-main-grid policy-grid">
      <section class="oncall-panel policy-panel"><header class="panel-heading"><div><p class="eyebrow">ESCALATION POLICY</p><h3>升级策略</h3></div><button v-if="isAdmin" class="secondary-button small" @click="openPolicyModal()">＋ 新建策略</button></header>
        <div v-if="!policies.length" class="oncall-empty">没有升级策略。可以创建“SEV2 订单服务告警”策略，将第一步路由至当前值班人。</div>
        <div v-else class="policy-list"><article v-for="view in policies" :key="view.policy.id" :class="['policy-card', { selected: String(selectedPolicyId) === String(view.policy.id), disabled: view.policy.enabled === false }]" @click="selectedPolicyId = view.policy.id"><div><strong>{{ view.policy.name }}</strong><small>{{ severityLabel(view.policy.minimumSeverity) }} 及以上</small></div><span>{{ view.steps.length }} 步</span><p>{{ view.policy.description || '未填写说明' }}</p><footer><i :class="view.policy.enabled === false ? 'toggle-off' : 'toggle-on'"></i>{{ view.policy.enabled === false ? '已停用' : '已启用' }}<button v-if="isAdmin" class="text-action" @click.stop="openPolicyModal(view.policy)">编辑</button></footer></article></div>
      </section>
      <section class="oncall-panel step-panel"><header class="panel-heading"><div><p class="eyebrow">RESPONSE RUNBOOK</p><h3>{{ selectedPolicy?.policy?.name || '选择升级策略' }}</h3></div><button v-if="isAdmin && selectedPolicy" class="secondary-button small" @click="openStepModal()">＋ 添加步骤</button></header>
        <div v-if="!selectedPolicy" class="oncall-empty">选择一个策略后，可以配置每一级的延迟与接收对象。</div>
        <template v-else><p class="step-hint">事件宣布后，系统按顺序执行。下一步的延迟从上一步派发完成时开始计算。</p><div v-if="!selectedPolicy.steps.length" class="oncall-empty compact">该策略尚未配置步骤，不会触发任何通知。</div><ol v-else class="step-list"><li v-for="step in selectedPolicy.steps" :key="step.id"><span class="step-no">L{{ step.stepNo }}</span><div><strong>{{ targetLabel(step.targetType) }}</strong><small>{{ stepDestination(step) }} · {{ step.delayMinutes || 0 }} 分钟后</small></div><div class="step-actions"><button v-if="isAdmin" class="text-action" @click="openStepModal(step)">编辑</button><button v-if="isAdmin" class="text-action danger" @click="deleteStep(step)">删除</button></div></li></ol></template>
      </section>
    </div>

    <section class="oncall-panel event-panel"><header class="panel-heading"><div><p class="eyebrow">ESCALATION HISTORY</p><h3>升级投递记录</h3></div><span>最近 {{ escalationEvents.length }} 条</span></header>
      <div v-if="!escalationEvents.length" class="oncall-empty">尚未有重大事件触发升级。配置策略并宣布符合严重度的事件后，系统会在这里记录投递状态。</div>
      <div v-else class="event-list"><article v-for="item in escalationEvents" :key="item.event.id" class="event-row"><div class="event-primary"><div><code>{{ item.event.incidentNo }}</code><strong>{{ item.event.incidentTitle }}</strong><small>{{ item.policyName }} · {{ severityLabel(item.event.severity) }} · {{ formatDate(item.event.createdAt) }}</small></div><span :class="['event-status', statusClass(item.event.status)]">{{ eventStatusLabel(item.event.status) }}</span></div><div class="event-progress"><span>已执行 L{{ item.event.currentStep || 0 }}</span><span v-if="item.event.nextEscalateAt">下一步：{{ formatDate(item.event.nextEscalateAt) }}</span><span v-else>{{ item.event.resolvedAt ? '结束：' + formatDate(item.event.resolvedAt) : '已无后续步骤' }}</span><span v-if="item.event.lastError" class="event-error">{{ item.event.lastError }}</span></div><div v-if="item.deliveries.length" class="delivery-tags"><span v-for="delivery in item.deliveries" :key="delivery.id" :class="delivery.status === 'SENT' ? 'delivery-sent' : 'delivery-suppressed'">L{{ delivery.stepNo }} · {{ staffName(delivery.recipientId) }} · {{ deliveryStatusLabels[delivery.status] || delivery.status }}</span></div></article></div>
    </section>

    <div v-if="showScheduleModal" class="oncall-modal-backdrop" @click.self="showScheduleModal = false"><form class="oncall-modal" @submit.prevent="saveSchedule"><header><div><p class="eyebrow">ON-CALL SCHEDULE</p><h2>{{ editingSchedule ? '编辑值班表' : '新建值班表' }}</h2></div><button type="button" class="close-button" @click="showScheduleModal = false">×</button></header><label>值班表名称<input v-model="scheduleForm.name" required maxlength="100" placeholder="例如：订单服务一线值班" /></label><label>说明<textarea v-model="scheduleForm.description" maxlength="1000" rows="3" placeholder="说明系统范围、业务线和交接规则"></textarea></label><div class="oncall-form-grid"><label>时区<input v-model="scheduleForm.timezone" required maxlength="64" placeholder="Asia/Shanghai" /></label><label>状态<select v-model="scheduleForm.enabled"><option :value="true">启用</option><option :value="false">停用</option></select></label></div><footer><button type="button" class="secondary-button" @click="showScheduleModal = false">取消</button><button class="primary-button" :disabled="saving">{{ saving ? '保存中…' : '保存值班表' }}</button></footer></form></div>
    <div v-if="showShiftModal" class="oncall-modal-backdrop" @click.self="showShiftModal = false"><form class="oncall-modal" @submit.prevent="saveShift"><header><div><p class="eyebrow">SHIFT PLANNING</p><h2>{{ editingShift ? '编辑值班轮班' : '安排值班轮班' }}</h2></div><button type="button" class="close-button" @click="showShiftModal = false">×</button></header><div class="oncall-form-grid"><label>值班表<select v-model="shiftForm.scheduleId" :disabled="Boolean(editingShift)"><option v-for="schedule in schedules" :key="schedule.id" :value="schedule.id">{{ schedule.name }}</option></select></label><label>值班人员<select v-model="shiftForm.userId" required><option value="">选择客服成员</option><option v-for="staff in staffMembers" :key="staff.id" :value="staff.id">{{ staff.displayName }}</option></select></label></div><div class="oncall-form-grid"><label>开始时间<input v-model="shiftForm.startAt" required type="datetime-local" /></label><label>结束时间<input v-model="shiftForm.endAt" required type="datetime-local" /></label></div><label>轮班角色<select v-model="shiftForm.primary"><option :value="true">主值班（接收默认告警）</option><option :value="false">备份值班</option></select></label><label>交接备注<textarea v-model="shiftForm.handoffNote" maxlength="1000" rows="3" placeholder="例如：晚高峰期间重点关注支付回调错误率"></textarea></label><footer><button type="button" class="secondary-button" @click="showShiftModal = false">取消</button><button class="primary-button" :disabled="saving">{{ saving ? '保存中…' : '保存轮班' }}</button></footer></form></div>
    <div v-if="showPolicyModal" class="oncall-modal-backdrop" @click.self="showPolicyModal = false"><form class="oncall-modal" @submit.prevent="savePolicy"><header><div><p class="eyebrow">ESCALATION POLICY</p><h2>{{ editingPolicy ? '编辑升级策略' : '新建升级策略' }}</h2></div><button type="button" class="close-button" @click="showPolicyModal = false">×</button></header><label>策略名称<input v-model="policyForm.name" required maxlength="100" placeholder="例如：生产 SEV2 值班升级" /></label><label>说明<textarea v-model="policyForm.description" maxlength="1000" rows="3" placeholder="说明适用服务、升级目的和响应要求"></textarea></label><div class="oncall-form-grid"><label>触发阈值<select v-model="policyForm.minimumSeverity"><option v-for="(label, value) in severityLabels" :key="value" :value="value">{{ label }} 及以上</option></select></label><label>状态<select v-model="policyForm.enabled"><option :value="true">启用</option><option :value="false">停用</option></select></label></div><p class="oncall-form-hint">“SEV2 及以上”会匹配 SEV1 和 SEV2 的重大事件；策略需要至少一个步骤才会实际派发通知。</p><footer><button type="button" class="secondary-button" @click="showPolicyModal = false">取消</button><button class="primary-button" :disabled="saving">{{ saving ? '保存中…' : '保存策略' }}</button></footer></form></div>
    <div v-if="showStepModal" class="oncall-modal-backdrop" @click.self="showStepModal = false"><form class="oncall-modal step-modal" @submit.prevent="saveStep"><header><div><p class="eyebrow">ESCALATION STEP</p><h2>{{ editingStep ? '编辑升级步骤' : '添加升级步骤' }}</h2><small>{{ selectedPolicy?.policy?.name }}</small></div><button type="button" class="close-button" @click="showStepModal = false">×</button></header><div class="oncall-form-grid"><label>步骤级别<select v-model.number="stepForm.stepNo"><option v-for="number in 10" :key="number" :value="number">L{{ number }}</option></select></label><label>延迟分钟<input v-model.number="stepForm.delayMinutes" type="number" min="0" max="1440" /></label></div><label>接收对象<select v-model="stepForm.targetType"><option v-for="(label, value) in targetLabels" :key="value" :value="value">{{ label }}</option></select></label><label v-if="stepForm.targetType === 'ON_CALL'">目标值班表<select v-model="stepForm.scheduleId" required><option value="">选择值班表</option><option v-for="schedule in schedules" :key="schedule.id" :value="schedule.id">{{ schedule.name }}</option></select></label><label v-if="stepForm.targetType === 'USER'">指定客服<select v-model="stepForm.targetUserId" required><option value="">选择客服成员</option><option v-for="staff in staffMembers" :key="staff.id" :value="staff.id">{{ staff.displayName }}</option></select></label><p class="oncall-form-hint">第一步一般设置为 0 分钟；后续步骤的延迟从前一步通知派发完成后开始计时。</p><footer><button type="button" class="secondary-button" @click="showStepModal = false">取消</button><button class="primary-button" :disabled="saving">{{ saving ? '保存中…' : '保存步骤' }}</button></footer></form></div>
  </section>
</template>

<style scoped>
.oncall-center { display: grid; gap: 17px; }.oncall-toolbar, .oncall-toolbar-actions, .panel-heading, .schedule-card > div, .schedule-card footer, .policy-card > div, .policy-card footer, .shift-row, .event-primary, .oncall-modal header, .oncall-modal footer { display: flex; align-items: center; justify-content: space-between; gap: 10px; }.oncall-toolbar { align-items: flex-start; }.oncall-toolbar h2 { margin: 3px 0 5px; color: #1d2c44; font-size: 25px; }.oncall-toolbar small { color: #718096; }.oncall-toolbar-actions { flex-wrap: wrap; }.oncall-message { margin: 0; border-radius: 10px; padding: 10px 13px; font-size: 13px; }.oncall-message.error { border: 1px solid #ffd3db; background: #fff1f3; color: #b4233c; }.oncall-message.notice { border: 1px solid #bceee0; background: #effff9; color: #137760; }
.oncall-summary { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 11px; }.oncall-summary article { display: grid; gap: 3px; min-height: 92px; padding: 14px; border: 1px solid #e5eaf1; border-radius: 13px; background: linear-gradient(145deg, #fff, #f7fafc); }.oncall-summary article.attention { border-color: #f4c8cc; background: linear-gradient(145deg, #fff, #fff4f5); }.oncall-summary span, .oncall-summary small { color: #7a8798; font-size: 12px; }.oncall-summary strong { color: #22324a; font-size: 25px; line-height: 1.1; }
.oncall-main-grid { display: grid; grid-template-columns: minmax(280px, .8fr) minmax(480px, 1.45fr); gap: 16px; align-items: start; }.oncall-panel { overflow: hidden; border: 1px solid #e4eaf2; border-radius: 15px; background: #fff; box-shadow: 0 7px 24px rgba(25, 44, 75, .045); }.panel-heading { min-height: 43px; padding: 14px 16px; border-bottom: 1px solid #e9eef4; background: #fbfcfe; }.panel-heading h3 { margin: 2px 0 0; color: #2a3a53; font-size: 15px; }.panel-heading > span { color: #7c8a9d; font-size: 12px; }.small { min-height: auto; padding: 7px 10px; font-size: 12px; }
.oncall-empty { padding: 37px 22px; color: #8491a4; font-size: 13px; line-height: 1.65; text-align: center; }.oncall-empty.compact { padding: 17px; background: #fafbfc; text-align: left; }.schedule-list, .policy-list { display: grid; }.schedule-card, .policy-card { padding: 13px 15px; border-bottom: 1px solid #edf0f4; cursor: pointer; transition: background .16s; }.schedule-card:hover, .policy-card:hover { background: #f8fbff; }.schedule-card.selected, .policy-card.selected { background: #eff6ff; box-shadow: inset 0 0 0 1px #bcd1ef; }.schedule-card.disabled, .policy-card.disabled { opacity: .63; }.schedule-card strong, .policy-card strong { display: block; color: #34465f; font-size: 14px; }.schedule-card small, .policy-card small { color: #8090a4; font-size: 11px; }.schedule-card > span { border-radius: 99px; padding: 3px 7px; font-size: 10px; }.schedule-enabled { color: #18785b; background: #eafff5; }.schedule-disabled { color: #6f7b8c; background: #eef1f5; }.schedule-card p, .policy-card p { margin: 8px 0 9px; color: #68778b; font-size: 12px; line-height: 1.45; }.schedule-card footer, .policy-card footer { border-top: 1px dashed #e6ebf2; padding-top: 8px; color: #8290a2; font-size: 11px; }.text-action { border: 0; background: transparent; color: #5377a7; cursor: pointer; font-size: 11px; }.text-action.danger { color: #b24c5a; }.toggle-on, .toggle-off { display: inline-block; width: 7px; height: 7px; margin-right: 5px; border-radius: 50%; }.toggle-on { background: #22a77b; }.toggle-off { background: #a9b2bf; }
.coverage-now { display: grid; grid-template-columns: 1fr auto; align-items: center; gap: 13px; margin: 14px; border: 1px solid #d6e7de; border-radius: 11px; background: linear-gradient(120deg, #f3fffa, #fbfffd); padding: 12px; }.coverage-now span, .coverage-now small { color: #688075; font-size: 11px; }.coverage-now strong { display: block; margin-top: 4px; color: #23684e; font-size: 14px; }.shift-list { display: grid; padding: 0 14px 14px; gap: 7px; }.shift-row { align-items: flex-start; border: 1px solid #e6ebf2; border-radius: 9px; padding: 9px; }.shift-row.active { border-color: #9bd8bc; background: #f2fff8; }.shift-marker { display: grid; flex: 0 0 25px; width: 25px; height: 25px; place-items: center; border-radius: 7px; background: #edf3fb; color: #53729c; font-size: 11px; }.shift-row.active .shift-marker { background: #d9f8e9; color: #177056; }.shift-row > div:nth-child(2) { flex: 1; min-width: 0; }.shift-row strong { display: block; color: #42546e; font-size: 12px; }.shift-row small, .shift-row p { color: #8491a2; font-size: 11px; }.shift-row p { margin: 4px 0 0; line-height: 1.4; }.shift-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 5px; color: #1e865f; font-size: 11px; }
.policy-grid { grid-template-columns: minmax(280px, .8fr) minmax(480px, 1.45fr); }.policy-card > span { color: #6a7f9d; border-radius: 99px; background: #edf3fb; padding: 3px 7px; font-size: 10px; }.step-hint { margin: 14px 16px 0; color: #8490a2; font-size: 12px; line-height: 1.5; }.step-list { position: relative; display: grid; gap: 9px; margin: 15px 16px; padding: 0; list-style: none; }.step-list::before { position: absolute; top: 17px; bottom: 17px; left: 16px; width: 1px; background: #dbe4ef; content: ''; }.step-list li { position: relative; display: grid; grid-template-columns: 34px 1fr auto; align-items: center; gap: 9px; border: 1px solid #e7ecf3; border-radius: 9px; background: #fff; padding: 9px; }.step-no { z-index: 1; display: grid; width: 30px; height: 30px; place-items: center; border: 2px solid #fff; border-radius: 50%; background: #557cae; color: #fff; font-size: 10px; box-shadow: 0 0 0 1px #b9cce2; }.step-list strong { display: block; color: #40546e; font-size: 12px; }.step-list small { color: #8491a3; font-size: 11px; }.step-actions { display: flex; gap: 5px; }
.event-panel { min-height: 210px; }.event-list { display: grid; }.event-row { border-bottom: 1px solid #ecf0f5; padding: 12px 16px; }.event-primary { align-items: flex-start; }.event-primary > div { display: grid; gap: 3px; min-width: 0; }.event-primary code { color: #5879a5; font-size: 10px; }.event-primary strong { overflow: hidden; color: #40526a; font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }.event-primary small { color: #8390a2; font-size: 11px; }.event-status { border-radius: 99px; padding: 4px 8px; font-size: 10px; white-space: nowrap; }.oncall-status-active { background: #fff1e8; color: #bd5818; }.oncall-status-exhausted { background: #eef3fb; color: #55709a; }.oncall-status-resolved { background: #eafff3; color: #197556; }.oncall-status-cancelled { background: #f0f2f5; color: #6f7c8f; }.event-progress { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 9px; color: #748298; font-size: 11px; }.event-error { color: #b9505e; }.delivery-tags { display: flex; flex-wrap: wrap; gap: 5px; margin-top: 9px; }.delivery-tags span { border-radius: 99px; padding: 3px 7px; font-size: 10px; }.delivery-sent { background: #e9faf3; color: #197758; }.delivery-suppressed { background: #f0f3f7; color: #738094; }
.oncall-modal-backdrop { position: fixed; z-index: 60; inset: 0; display: grid; place-items: center; padding: 20px; background: rgba(20, 31, 48, .45); }.oncall-modal { width: min(620px, 100%); max-height: calc(100vh - 40px); overflow: auto; border-radius: 15px; background: #fff; box-shadow: 0 22px 65px rgba(17, 28, 45, .3); padding: 21px; }.oncall-modal header { align-items: flex-start; margin-bottom: 14px; }.oncall-modal h2 { margin: 4px 0; color: #203049; }.oncall-modal header small { color: #7a879a; }.oncall-modal label { display: grid; gap: 6px; margin-top: 11px; color: #536278; font-size: 12px; font-weight: 600; }.oncall-modal input, .oncall-modal select, .oncall-modal textarea { box-sizing: border-box; width: 100%; border: 1px solid #d8e0ea; border-radius: 8px; color: #26354d; padding: 9px 10px; font: inherit; font-size: 13px; outline: none; }.oncall-modal input:focus, .oncall-modal select:focus, .oncall-modal textarea:focus { border-color: #547aac; box-shadow: 0 0 0 3px rgba(84, 122, 172, .12); }.oncall-modal textarea { resize: vertical; }.oncall-modal footer { justify-content: flex-end; margin-top: 20px; }.oncall-form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }.oncall-form-hint { margin: 12px 0 0; border-left: 3px solid #779ac7; background: #f5f9ff; color: #607795; padding: 8px 10px; font-size: 11px; line-height: 1.55; }
@media (max-width: 1120px) { .oncall-main-grid, .policy-grid { grid-template-columns: 1fr; } }.oncall-panel.schedule-panel, .oncall-panel.policy-panel { min-height: 205px; }
@media (max-width: 760px) { .oncall-toolbar { display: grid; }.oncall-summary { grid-template-columns: 1fr 1fr; }.oncall-form-grid { grid-template-columns: 1fr; }.coverage-now { grid-template-columns: 1fr; }.event-primary { display: grid; }.oncall-modal-backdrop { padding: 9px; }.oncall-modal { padding: 16px; } }
</style>
