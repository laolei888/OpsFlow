<script setup>
import { computed, onMounted, ref } from 'vue'

const props = defineProps({ api: { type: Function, required: true } })
const tab = ref('chaos')
const center = ref({ summary: {}, experiments: [] })
const agents = ref([])
const selected = ref(null)
const selectedAgent = ref(null)
const callbacks = ref([])
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const notice = ref('')
const showCreate = ref(false)
const showCheck = ref(false)
const showComplete = ref(false)
const showCancel = ref(false)
const showAgent = ref(false)
const credential = ref(null)
const selectedCheck = ref(null)
const createForm = ref({})
const checkForm = ref({})
const completeFindings = ref('')
const cancelReason = ref('')
const agentForm = ref({})

const targetLabels = { DATABASE: 'MySQL 数据库', REDIS: 'Redis 缓存', RABBITMQ: 'RabbitMQ 消息', OBJECT_STORAGE: '对象存储', WEBHOOK_DELIVERY: 'Webhook 投递' }
const statusLabels = { PLANNED: '待执行', BASELINE: '基线采集', FAULT_INJECTED: '故障观察', RECOVERING: '恢复验证', PASSED: '已通过', FAILED: '未通过', CANCELLED: '已取消' }
const phaseLabels = { BASELINE: '基线', FAULT: '故障', RECOVERY: '恢复' }
const checkLabels = { PENDING: '待记录', PASSED: '通过', WARNING: '警告', FAILED: '失败' }
const callbackUrl = computed(() => window.location.origin + '/api/v1/backup-agent/callbacks')

async function load(silent = false) {
  if (!silent) loading.value = true
  error.value = ''
  try {
    const [chaosData, agentData] = await Promise.all([
      props.api('/api/v1/admin/chaos-experiments'),
      props.api('/api/v1/admin/disaster-recovery/agents')
    ])
    center.value = chaosData
    agents.value = agentData
    if (selected.value) await openExperiment(selected.value.experiment.id, true)
    if (selectedAgent.value) {
      selectedAgent.value = agents.value.find(item => item.id === selectedAgent.value.id) || null
      if (selectedAgent.value) await loadCallbacks(selectedAgent.value, true)
    }
  } catch (exception) { error.value = exception.message || '韧性治理数据加载失败' }
  finally { loading.value = false }
}

function flash(message) {
  notice.value = message
  window.setTimeout(() => { notice.value = '' }, 3500)
}

function openCreate() {
  createForm.value = {
    name: 'RabbitMQ 故障恢复演练', target: 'RABBITMQ', environment: 'chaos-lab',
    hypothesis: 'Broker 不可用期间事务 Outbox 保留全部事件，恢复后消息可幂等消费且业务数据不丢失。',
    rollbackPlan: '恢复 Broker 网络与进程，确认 Publisher Confirm 正常，分批重放积压并检查重复副作用。',
    targetRecoveryMinutes: 15
  }
  showCreate.value = true
}

async function createExperiment() {
  busy.value = true; error.value = ''
  try {
    selected.value = await props.api('/api/v1/admin/chaos-experiments', { method: 'POST', body: JSON.stringify(createForm.value) })
    showCreate.value = false
    flash('实验已创建，平台不会执行任何破坏命令')
    await load(true)
  } catch (exception) { error.value = exception.message || '创建实验失败' }
  finally { busy.value = false }
}

async function openExperiment(id, silent = false) {
  if (!silent) error.value = ''
  try { selected.value = await props.api('/api/v1/admin/chaos-experiments/' + id) }
  catch (exception) { error.value = exception.message || '读取实验详情失败' }
}

async function transition(action, confirmation) {
  if (confirmation && !window.confirm(confirmation)) return
  busy.value = true; error.value = ''
  try {
    selected.value = await props.api('/api/v1/admin/chaos-experiments/' + selected.value.experiment.id + '/' + action, { method: 'POST' })
    const messages = { start: '基线阶段已开始', 'fault-injected': '已进入故障观察阶段', recovery: '已进入恢复验证阶段' }
    flash(messages[action])
    await load(true)
  } catch (exception) { error.value = exception.message || '实验状态推进失败' }
  finally { busy.value = false }
}

function canRecord(check) {
  const status = selected.value?.experiment?.status
  return (status === 'BASELINE' && check.phase === 'BASELINE')
    || (status === 'FAULT_INJECTED' && check.phase === 'FAULT')
    || (status === 'RECOVERING' && check.phase === 'RECOVERY')
}

function openCheckEditor(check) {
  selectedCheck.value = check
  checkForm.value = { status: check.status === 'PENDING' ? 'PASSED' : check.status, detail: check.detail || '', evidence: check.evidence || '' }
  showCheck.value = true
}

async function saveCheck() {
  busy.value = true; error.value = ''
  try {
    await props.api('/api/v1/admin/chaos-experiments/' + selected.value.experiment.id + '/checks/' + selectedCheck.value.id,
      { method: 'PUT', body: JSON.stringify(checkForm.value) })
    showCheck.value = false
    flash('检查证据已保存')
    await openExperiment(selected.value.experiment.id, true)
  } catch (exception) { error.value = exception.message || '保存检查证据失败' }
  finally { busy.value = false }
}

async function completeExperiment() {
  busy.value = true; error.value = ''
  try {
    selected.value = await props.api('/api/v1/admin/chaos-experiments/' + selected.value.experiment.id + '/complete',
      { method: 'POST', body: JSON.stringify({ findings: completeFindings.value }) })
    showComplete.value = false
    flash('实验已完成并计算恢复结果')
    await load(true)
  } catch (exception) { error.value = exception.message || '完成实验失败' }
  finally { busy.value = false }
}

async function cancelExperiment() {
  busy.value = true; error.value = ''
  try {
    await props.api('/api/v1/admin/chaos-experiments/' + selected.value.experiment.id + '/cancel',
      { method: 'POST', body: JSON.stringify({ reason: cancelReason.value }) })
    showCancel.value = false
    flash('实验已取消并保留审计记录')
    await load(true)
  } catch (exception) { error.value = exception.message || '取消实验失败' }
  finally { busy.value = false }
}

function openAgentEditor() {
  agentForm.value = { name: '夜间数据库备份 Agent', description: '接收基础设施备份作业的签名回调', enabled: true }
  showAgent.value = true
}

async function createAgent() {
  busy.value = true; error.value = ''
  try {
    credential.value = await props.api('/api/v1/admin/disaster-recovery/agents', { method: 'POST', body: JSON.stringify(agentForm.value) })
    showAgent.value = false
    flash('Agent 已创建，请立即保存一次性密钥')
    await load(true)
  } catch (exception) { error.value = exception.message || '创建 Agent 失败' }
  finally { busy.value = false }
}

async function rotateAgent(agent) {
  if (!window.confirm('轮换后旧签名密钥立即失效，确认继续？')) return
  busy.value = true; error.value = ''
  try {
    credential.value = await props.api('/api/v1/admin/disaster-recovery/agents/' + agent.id + '/rotate', { method: 'POST' })
    flash('签名密钥已轮换')
    await load(true)
  } catch (exception) { error.value = exception.message || '轮换密钥失败' }
  finally { busy.value = false }
}

async function toggleAgent(agent) {
  busy.value = true; error.value = ''
  try {
    await props.api('/api/v1/admin/disaster-recovery/agents/' + agent.id + '/status',
      { method: 'PUT', body: JSON.stringify({ enabled: !agent.enabled }) })
    flash(agent.enabled ? 'Agent 已停用' : 'Agent 已启用')
    await load(true)
  } catch (exception) { error.value = exception.message || '更新 Agent 状态失败' }
  finally { busy.value = false }
}

async function loadCallbacks(agent, silent = false) {
  if (!silent) error.value = ''
  selectedAgent.value = agent
  try { callbacks.value = await props.api('/api/v1/admin/disaster-recovery/agents/' + agent.id + '/callbacks') }
  catch (exception) { error.value = exception.message || '读取回调记录失败' }
}

async function copy(value) {
  try { await navigator.clipboard.writeText(value); flash('已复制到剪贴板') }
  catch { error.value = '浏览器未授予剪贴板权限，请手动复制' }
}

function formatDate(value) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—' }
function formatSeconds(value) { if (value == null) return '—'; return value < 60 ? value + ' 秒' : Math.floor(value / 60) + ' 分 ' + value % 60 + ' 秒' }
onMounted(load)
</script>

<template>
  <section class="rc-center">
    <header class="rc-heading">
      <div><p>CONTROLLED RESILIENCE</p><h2>关键链路韧性演练</h2><span>签名接入外部备份元数据，以阶段门禁完成非生产故障演练。</span></div>
      <div class="heading-actions"><button class="secondary" :class="{ current: tab === 'chaos' }" @click="tab = 'chaos'">混沌实验</button><button class="secondary" :class="{ current: tab === 'agents' }" @click="tab = 'agents'">备份 Agent</button><button v-if="tab === 'chaos'" @click="openCreate">＋ 创建实验</button><button v-else @click="openAgentEditor">＋ 创建 Agent</button></div>
    </header>
    <div class="safety-banner"><b>安全边界</b>平台只编排实验状态、证据和恢复目标，不会执行 Docker、网络阻断、进程终止或生产环境命令。</div>
    <div v-if="error" class="rc-error">{{ error }}<button @click="error = ''">×</button></div>
    <div v-if="notice" class="rc-notice">{{ notice }}</div>
    <div v-if="loading" class="empty">正在加载韧性治理数据…</div>

    <template v-else-if="tab === 'chaos'">
      <div class="rc-stats"><article><span>实验总数</span><strong>{{ center.summary?.total || 0 }}</strong><small>最多显示 100 场</small></article><article><span>执行中</span><strong>{{ center.summary?.active || 0 }}</strong><small>基线 / 故障 / 恢复</small></article><article><span>已完成</span><strong>{{ center.summary?.completed || 0 }}</strong><small>已形成结论</small></article><article><span>通过率</span><strong>{{ Number(center.summary?.passRate || 0).toFixed(1) }}%</strong><small>{{ center.summary?.passed || 0 }} 场通过</small></article></div>
      <div class="rc-grid">
        <section class="panel experiment-list"><div class="panel-title"><div><p>EXPERIMENTS</p><h3>实验队列</h3></div><span>{{ center.experiments?.length || 0 }} 场</span></div><div v-if="!center.experiments?.length" class="empty compact">尚未创建实验。建议先从 RabbitMQ 或 Redis 的隔离环境开始。</div><button v-for="item in center.experiments" :key="item.id" :class="{ selected: selected?.experiment?.id === item.id }" @click="openExperiment(item.id)"><span><code>{{ item.experimentNo }}</code><strong>{{ item.name }}</strong><small>{{ targetLabels[item.target] }} · {{ item.environment }} · {{ formatDate(item.createdAt) }}</small></span><em :class="String(item.status).toLowerCase()">{{ statusLabels[item.status] }}</em></button></section>
        <section v-if="selected" class="panel experiment-detail">
          <div class="detail-head"><div><p>EXPERIMENT CONTROL</p><h3>{{ selected.experiment.experimentNo }} · {{ selected.experiment.name }}</h3><span>{{ targetLabels[selected.experiment.target] }} · 恢复目标 {{ selected.experiment.targetRecoveryMinutes }} 分钟</span></div><div><em :class="String(selected.experiment.status).toLowerCase()">{{ statusLabels[selected.experiment.status] }}</em><button class="close" @click="selected = null">×</button></div></div>
          <div class="hypothesis"><p><b>稳态假设</b>{{ selected.experiment.hypothesis }}</p><p><b>回滚方案</b>{{ selected.experiment.rollbackPlan }}</p></div>
          <div class="playbook"><p><b>注入指引</b>{{ selected.playbook.injectionGuide }}</p><p><b>预期行为</b>{{ selected.playbook.expectedBehavior }}</p><p><b>恢复指引</b>{{ selected.playbook.rollbackGuide }}</p></div>
          <div class="timeline"><span>开始 {{ formatDate(selected.experiment.startedAt) }}</span><span>故障 {{ formatDate(selected.experiment.faultStartedAt) }}</span><span>恢复 {{ formatDate(selected.experiment.recoveryStartedAt) }}</span><span>耗时 {{ formatSeconds(selected.experiment.achievedRecoverySeconds) }}</span></div>
          <div class="check-list"><div class="check-row heading"><span>阶段 / 检查项</span><span>状态</span><span>证据</span><span>操作</span></div><div v-for="check in selected.checks" :key="check.id" class="check-row"><strong><small>{{ phaseLabels[check.phase] }}</small>{{ check.label }}</strong><em :class="String(check.status).toLowerCase()">{{ checkLabels[check.status] }}</em><p>{{ check.evidence || check.detail || '等待记录证据' }}</p><button v-if="canRecord(check)" @click="openCheckEditor(check)">记录</button><span v-else>—</span></div></div>
          <footer class="experiment-actions"><button v-if="selected.experiment.status === 'PLANNED'" :disabled="busy" @click="transition('start', '确认在非生产隔离环境开始基线采集？')">开始基线</button><button v-if="selected.experiment.status === 'BASELINE'" :disabled="busy" @click="transition('fault-injected', '请确认已由操作者在隔离环境完成故障注入。平台不会执行该操作。')">确认故障已注入</button><button v-if="selected.experiment.status === 'FAULT_INJECTED'" :disabled="busy" @click="transition('recovery', '确认已执行回滚方案并开始恢复验证？')">进入恢复验证</button><button v-if="selected.experiment.status === 'RECOVERING'" @click="completeFindings = ''; showComplete = true">完成实验</button><button v-if="['PLANNED','BASELINE','FAULT_INJECTED','RECOVERING'].includes(selected.experiment.status)" class="danger" @click="cancelReason = ''; showCancel = true">取消</button></footer>
          <div v-if="selected.experiment.findings || selected.experiment.cancellationReason" class="outcome"><b>最终结论</b>{{ selected.experiment.findings || selected.experiment.cancellationReason }}</div>
        </section>
        <section v-else class="panel empty compact">选择一场实验查看状态机、操作指引和验证证据。</section>
      </div>
    </template>

    <template v-else-if="!loading">
      <div class="agent-summary"><article><span>Agent 总数</span><strong>{{ agents.length }}</strong></article><article><span>已启用</span><strong>{{ agents.filter(item => item.enabled).length }}</strong></article><article><span>已接入</span><strong>{{ agents.filter(item => item.lastSeenAt).length }}</strong></article><article><span>回调地址</span><code>{{ callbackUrl }}</code></article></div>
      <div class="rc-grid agents-grid">
        <section class="panel agent-list"><div class="panel-title"><div><p>SIGNED BACKUP INGEST</p><h3>外部备份 Agent</h3></div><span>HMAC-SHA256</span></div><div v-if="!agents.length" class="empty compact">还没有 Agent。创建后密钥只会显示一次。</div><article v-for="agent in agents" :key="agent.id" :class="{ selected: selectedAgent?.id === agent.id }" @click="loadCallbacks(agent)"><div><code>{{ agent.agentKey }}</code><strong>{{ agent.name }}</strong><small>{{ agent.description || '无描述' }}</small></div><span><em :class="agent.enabled ? 'passed' : 'cancelled'">{{ agent.enabled ? '已启用' : '已停用' }}</em><small>最近回调 {{ formatDate(agent.lastSeenAt) }}</small><button @click.stop="rotateAgent(agent)">轮换密钥</button><button @click.stop="toggleAgent(agent)">{{ agent.enabled ? '停用' : '启用' }}</button></span></article></section>
        <section class="panel callback-list"><div class="panel-title"><div><p>CALLBACK EVIDENCE</p><h3>{{ selectedAgent ? selectedAgent.name : '回调审计' }}</h3></div><span>{{ callbacks.length }} 条</span></div><div v-if="!selectedAgent" class="empty compact">选择 Agent 查看最近 100 条验签成功记录。</div><div v-else-if="!callbacks.length" class="empty compact">该 Agent 尚未产生成功回调。</div><article v-for="item in callbacks" :key="item.id"><div><code>{{ item.eventId }}</code><strong>备份 #{{ item.backupSnapshotId }}</strong></div><span>{{ formatDate(item.receivedAt) }}</span><small>nonce {{ item.nonce }} · payload {{ item.payloadSha256?.slice(0, 16) }}…</small></article></section>
      </div>
    </template>

    <div v-if="showCreate" class="backdrop" @click.self="showCreate = false"><form class="wide" @submit.prevent="createExperiment"><header><div><p>SAFE CHAOS</p><h3>创建关键链路实验</h3></div><button type="button" @click="showCreate = false">×</button></header><div class="form-grid"><label>实验名称<input v-model="createForm.name" minlength="2" maxlength="160" required /></label><label>依赖目标<select v-model="createForm.target"><option v-for="(label,key) in targetLabels" :key="key" :value="key">{{ label }}</option></select></label><label>非生产环境<input v-model="createForm.environment" minlength="2" maxlength="64" required /></label><label>恢复目标（分钟）<input v-model.number="createForm.targetRecoveryMinutes" type="number" min="1" max="240" required /></label></div><label>稳态假设<textarea v-model="createForm.hypothesis" maxlength="1000" rows="4" required /></label><label>回滚方案<textarea v-model="createForm.rollbackPlan" maxlength="1000" rows="4" required /></label><aside>系统会拒绝 prod、production、live 和“生产”环境名称。实际注入与回滚必须由获授权人员在隔离环境完成。</aside><footer><button type="button" class="cancel" @click="showCreate = false">取消</button><button :disabled="busy">创建实验</button></footer></form></div>
    <div v-if="showCheck" class="backdrop" @click.self="showCheck = false"><form @submit.prevent="saveCheck"><header><div><p>EXPERIMENT EVIDENCE</p><h3>{{ selectedCheck?.label }}</h3></div><button type="button" @click="showCheck = false">×</button></header><label>验证结果<select v-model="checkForm.status"><option value="PASSED">通过</option><option value="WARNING">警告</option><option value="FAILED">失败</option></select></label><label>检查结论<textarea v-model="checkForm.detail" maxlength="1000" rows="3" /></label><label>证据位置或摘要<textarea v-model="checkForm.evidence" minlength="5" maxlength="1000" rows="4" required /></label><footer><button type="button" class="cancel" @click="showCheck = false">取消</button><button :disabled="busy">保存证据</button></footer></form></div>
    <div v-if="showComplete" class="backdrop" @click.self="showComplete = false"><form @submit.prevent="completeExperiment"><header><div><p>RESILIENCE VERDICT</p><h3>完成实验</h3></div><button type="button" @click="showComplete = false">×</button></header><label>复盘结论<textarea v-model="completeFindings" minlength="5" maxlength="2000" rows="6" required placeholder="说明假设是否成立、恢复耗时、数据一致性与改进项" /></label><aside>最终状态由必需门禁和从故障注入到验证完成的恢复耗时共同判定。</aside><footer><button type="button" class="cancel" @click="showComplete = false">取消</button><button :disabled="busy">完成并判定</button></footer></form></div>
    <div v-if="showCancel" class="backdrop" @click.self="showCancel = false"><form @submit.prevent="cancelExperiment"><header><div><p>STOP EXPERIMENT</p><h3>取消实验</h3></div><button type="button" @click="showCancel = false">×</button></header><label>取消原因<textarea v-model="cancelReason" minlength="5" maxlength="1000" rows="4" required /></label><footer><button type="button" class="cancel" @click="showCancel = false">返回</button><button class="danger-button" :disabled="busy">确认取消</button></footer></form></div>
    <div v-if="showAgent" class="backdrop" @click.self="showAgent = false"><form @submit.prevent="createAgent"><header><div><p>BACKUP AGENT</p><h3>创建签名接入凭证</h3></div><button type="button" @click="showAgent = false">×</button></header><label>Agent 名称<input v-model="agentForm.name" minlength="2" maxlength="120" required /></label><label>用途说明<textarea v-model="agentForm.description" maxlength="500" rows="3" /></label><label class="toggle"><input v-model="agentForm.enabled" type="checkbox" />创建后立即启用</label><aside>密钥会加密保存，明文只在创建或轮换成功后显示一次。</aside><footer><button type="button" class="cancel" @click="showAgent = false">取消</button><button :disabled="busy">创建 Agent</button></footer></form></div>
    <div v-if="credential" class="backdrop"><section class="credential-card"><header><div><p>ONE-TIME CREDENTIAL</p><h3>立即保存签名密钥</h3></div><button @click="credential = null">×</button></header><aside>关闭后无法再次查看，只能轮换。不要把密钥写入仓库、日志或工单。</aside><label>Agent Key<div><code>{{ credential.agent.agentKey }}</code><button @click="copy(credential.agent.agentKey)">复制</button></div></label><label>Secret<div><code>{{ credential.secret }}</code><button @click="copy(credential.secret)">复制</button></div></label><label>回调地址<div><code>{{ callbackUrl }}</code><button @click="copy(callbackUrl)">复制</button></div></label><label>签名原文模板<pre>{{ credential.canonicalTemplate }}</pre></label><footer><button @click="credential = null">我已安全保存</button></footer></section></div>
  </section>
</template>

<style scoped>
.rc-center{overflow:hidden;border:1px solid #dde5ed;border-radius:14px;background:#f6f8fb;color:#2c3d53}.rc-heading{display:flex;align-items:center;justify-content:space-between;gap:18px;padding:22px;background:linear-gradient(120deg,#edf4ff,#eef9f5)}.rc-heading p,.panel-title p,.detail-head p,.backdrop header p,.credential-card header p{margin:0 0 5px;color:#4469a5;font-size:9px;font-weight:900;letter-spacing:1.1px}.rc-heading h2,.panel-title h3,.detail-head h3,.backdrop h3,.credential-card h3{margin:0 0 5px}.rc-heading h2{font-size:18px}.rc-heading span,.detail-head span{color:#7d8999;font-size:10px}.heading-actions{display:flex;flex-wrap:wrap;justify-content:flex-end;gap:7px}.heading-actions button,.experiment-actions button{padding:9px 11px;border:1px solid #446fae;border-radius:8px;background:#446fae;color:#fff;font-size:9px;font-weight:900}.heading-actions .secondary{background:#fff;color:#446fae}.heading-actions .secondary.current{background:#e2ecfa}.safety-banner{margin:12px 14px 0;padding:10px 12px;border:1px solid #f0d8a3;border-radius:9px;background:#fff9e9;color:#76591d;font-size:9px}.safety-banner b{margin-right:8px}.rc-error,.rc-notice{margin:10px 14px 0;padding:9px 11px;border-radius:8px;font-size:10px}.rc-error{background:#fff0f2;color:#a42e43}.rc-error button{float:right;border:0;background:transparent;color:inherit}.rc-notice{background:#e5f7f0;color:#14765f}.empty{display:grid;place-items:center;min-height:260px;color:#8593a5;font-size:10px}.empty.compact{min-height:150px;padding:20px;text-align:center}.rc-stats,.agent-summary{display:grid;grid-template-columns:repeat(4,1fr);gap:10px;padding:14px}.rc-stats article,.agent-summary article{padding:13px;border:1px solid #dfe7ee;border-radius:10px;background:#fff}.rc-stats span,.rc-stats small,.agent-summary span{display:block;color:#8491a1;font-size:9px}.rc-stats strong,.agent-summary strong{display:block;margin:7px 0;color:#304158;font-size:19px}.agent-summary article:last-child{overflow:hidden}.agent-summary code{display:block;margin-top:9px;overflow:hidden;color:#426da7;font-size:9px;text-overflow:ellipsis;white-space:nowrap}.rc-grid{display:grid;grid-template-columns:minmax(300px,.7fr) minmax(0,1.3fr);gap:12px;padding:0 14px 14px}.panel{border:1px solid #dfe7ee;border-radius:11px;background:#fff}.panel-title,.detail-head{display:flex;align-items:center;justify-content:space-between;padding:14px;border-bottom:1px solid #e9eef3}.panel-title h3,.detail-head h3{font-size:14px}.panel-title>span{color:#7f8d9e;font-size:9px}.experiment-list>button{display:flex;align-items:center;justify-content:space-between;gap:9px;width:calc(100% - 20px);margin:7px 10px;padding:11px;border:1px solid #e4eaf0;border-radius:9px;background:#fff;text-align:left}.experiment-list>button.selected{border-color:#5d82ba;background:#f1f6fd}.experiment-list code,.agent-list code,.callback-list code{display:block;color:#4b6f9f;font-size:8px}.experiment-list strong,.experiment-list small{display:block}.experiment-list strong{margin:4px 0;font-size:10px}.experiment-list small{color:#8995a4;font-size:8px}.experiment-list em,.detail-head em,.check-row em,.agent-list em{padding:4px 7px;border-radius:999px;background:#eef2f6;color:#64758a;font-size:8px;font-style:normal;font-weight:900}.experiment-list em.passed,.detail-head em.passed,.check-row em.passed,.agent-list em.passed{background:#e4f6ef;color:#14745d}.experiment-list em.failed,.detail-head em.failed,.check-row em.failed{background:#ffe6ea;color:#a62f44}.experiment-list em.baseline,.experiment-list em.fault_injected,.experiment-list em.recovering,.detail-head em.baseline,.detail-head em.fault_injected,.detail-head em.recovering{background:#e7effc;color:#426cac}.check-row em.warning{background:#fff1d9;color:#97610d}.detail-head>div:last-child{display:flex;align-items:center;gap:7px}.detail-head .close{border:0;background:transparent;color:#8390a0;font-size:19px}.hypothesis,.playbook{display:grid;gap:8px;padding:12px}.hypothesis{grid-template-columns:1fr 1fr}.hypothesis p,.playbook p{margin:0;padding:10px;border-radius:8px;background:#f6f8fa;color:#647286;font-size:9px;line-height:1.55}.hypothesis b,.playbook b{display:block;margin-bottom:4px;color:#42546a}.playbook{grid-template-columns:repeat(3,1fr);background:#f8fbff}.timeline{display:flex;flex-wrap:wrap;gap:7px;padding:0 12px 12px}.timeline span{padding:6px 8px;border-radius:6px;background:#eef3f8;color:#65768a;font-size:8px}.check-list{overflow:auto;border-top:1px solid #edf1f4}.check-row{display:grid;grid-template-columns:1.2fr .5fr 1.8fr .35fr;align-items:center;gap:9px;min-width:700px;padding:10px 12px;border-bottom:1px solid #edf1f4;font-size:9px}.check-row.heading{background:#fafbfc;color:#8592a2}.check-row strong small{display:block;margin-bottom:3px;color:#5575a0;font-size:7px}.check-row p{margin:0;color:#6c7a8d}.check-row button,.agent-list button{padding:6px 8px;border:1px solid #7895bc;border-radius:6px;background:#fff;color:#496e9f;font-size:8px}.experiment-actions{display:flex;justify-content:flex-end;gap:7px;padding:12px}.experiment-actions .danger,.danger-button{border-color:#c95162!important;background:#b63d50!important}.outcome{padding:12px;border-top:1px solid #e9eef3;background:#f8fafc;font-size:9px}.outcome b{margin-right:8px}.agent-list article{display:grid;grid-template-columns:1fr auto;gap:10px;margin:9px;padding:12px;border:1px solid #e3e9ef;border-radius:9px;cursor:pointer}.agent-list article.selected{border-color:#5d82ba;background:#f3f7fd}.agent-list strong,.agent-list small{display:block;margin-top:4px}.agent-list small,.callback-list small{color:#8794a4;font-size:8px}.agent-list article>span{text-align:right}.agent-list article>span button{margin:8px 0 0 5px}.callback-list article{margin:9px;padding:11px;border-bottom:1px solid #e8edf2}.callback-list article>div{display:flex;justify-content:space-between}.callback-list article>span{font-size:8px;color:#7e8b9c}.callback-list article>small{display:block;margin-top:6px}.backdrop{position:fixed;z-index:1600;inset:0;display:grid;place-items:center;padding:20px;background:rgba(13,27,42,.64)}.backdrop form,.credential-card{width:min(540px,100%);max-height:90vh;overflow:auto;padding:22px;border-radius:15px;background:#fff;box-shadow:0 24px 70px rgba(10,24,38,.3)}.backdrop form.wide{width:min(720px,100%)}.backdrop header,.credential-card header{display:flex;justify-content:space-between}.backdrop header button,.credential-card header button{border:0;background:transparent;color:#7e8b9b;font-size:22px}.backdrop label,.credential-card label{display:grid;gap:6px;margin-top:12px;color:#596a7d;font-size:10px;font-weight:800}.backdrop input,.backdrop select,.backdrop textarea{padding:10px;border:1px solid #d5dee7;border-radius:8px;font:inherit;resize:vertical}.form-grid{display:grid;grid-template-columns:1fr 1fr;gap:0 12px}.toggle{display:flex!important;align-items:center;gap:7px!important}.toggle input{width:auto}.backdrop aside,.credential-card aside{margin-top:14px;padding:10px;border-radius:8px;background:#fff7e3;color:#77602d;font-size:9px;line-height:1.6}.backdrop footer,.credential-card footer{display:flex;justify-content:flex-end;gap:8px;margin-top:17px}.backdrop footer button,.credential-card footer button{padding:9px 13px;border:0;border-radius:8px;background:#446fae;color:#fff;font-size:10px;font-weight:900}.backdrop footer .cancel{border:1px solid #d5dee7;background:#fff;color:#637287}.credential-card label>div{display:flex;align-items:center;gap:8px}.credential-card code,.credential-card pre{flex:1;overflow:auto;padding:9px;border-radius:7px;background:#f2f5f8;color:#3d5673;font-size:9px;white-space:pre-wrap}.credential-card label button{padding:7px;border:1px solid #7895bc;border-radius:6px;background:#fff;color:#496e9f}@media(max-width:1100px){.rc-grid{grid-template-columns:1fr}.playbook{grid-template-columns:1fr}}@media(max-width:720px){.rc-heading,.detail-head{align-items:flex-start;flex-direction:column}.rc-stats,.agent-summary,.form-grid,.hypothesis{grid-template-columns:1fr 1fr}.agent-list article{grid-template-columns:1fr}.agent-list article>span{text-align:left}}
</style>
