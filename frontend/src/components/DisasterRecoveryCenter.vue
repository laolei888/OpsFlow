<script setup>
import { computed, onMounted, ref } from 'vue'

const props = defineProps({ api: { type: Function, required: true } })
const center = ref({ policy: null, summary: null, snapshots: [], drills: [] })
const selected = ref(null)
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const notice = ref('')
const showPolicy = ref(false)
const showBackup = ref(false)
const showDrill = ref(false)
const showCheck = ref(false)
const showComplete = ref(false)
const showCancel = ref(false)
const policyForm = ref({})
const backupForm = ref({})
const drillForm = ref({})
const checkForm = ref({})
const completeForm = ref({ evidenceNote: '', findings: '' })
const cancelReason = ref('')
const selectedCheck = ref(null)

const scopeLabels = { FULL: '全量备份', DATABASE: '数据库', OBJECT_STORAGE: '对象存储' }
const backupStatusLabels = { AVAILABLE: '可用', FAILED: '失败', EXPIRED: '已过期' }
const verificationLabels = { PENDING: '待校验', PASSED: '已通过', WARNING: '有警告', FAILED: '未通过' }
const drillStatusLabels = { PLANNED: '待执行', RUNNING: '执行中', PASSED: '通过', FAILED: '失败', CANCELLED: '已取消' }
const checkStatusLabels = { PENDING: '待检查', PASSED: '通过', WARNING: '警告', FAILED: '失败' }
const availableBackups = computed(() => (center.value.snapshots || []).filter(item => item.status === 'AVAILABLE'))

async function load(silent = false) {
  if (!silent) loading.value = true
  error.value = ''
  try {
    center.value = await props.api('/api/v1/admin/disaster-recovery')
    if (selected.value) await openDrill(selected.value.drill.id, true)
  } catch (exception) {
    error.value = exception.message || '灾备治理数据加载失败'
  } finally {
    loading.value = false
  }
}

function flash(message) {
  notice.value = message
  window.setTimeout(() => { notice.value = '' }, 3500)
}

function openPolicyEditor() {
  policyForm.value = { ...(center.value.policy || {}) }
  showPolicy.value = true
}

async function savePolicy() {
  busy.value = true
  error.value = ''
  try {
    const payload = { ...policyForm.value }
    ;['id', 'tenantId', 'inheritedDefaults', 'updatedAt'].forEach(key => delete payload[key])
    await props.api('/api/v1/admin/disaster-recovery/policy', { method: 'PUT', body: JSON.stringify(payload) })
    showPolicy.value = false
    flash('灾备策略已生效')
    await load(true)
  } catch (exception) {
    error.value = exception.message || '保存灾备策略失败'
  } finally { busy.value = false }
}

function openBackupEditor() {
  const now = new Date()
  const recovery = new Date(now.getTime() - 5 * 60 * 1000)
  const immutable = new Date(now.getTime() + Number(center.value.policy?.retentionDays || 30) * 86400000)
  backupForm.value = {
    scope: 'FULL', storageProvider: 'RustFS / S3', storageLocation: 's3://opsflow-backups/tenant-1/', region: 'local-primary',
    recoveryPointAt: toInput(recovery), capturedAt: toInput(recovery), completedAt: toInput(now), sizeBytes: 1048576,
    checksumSha256: '', encrypted: true, immutableUntil: toInput(immutable), crossRegionCopy: false, evidenceNote: ''
  }
  showBackup.value = true
}

async function registerBackup() {
  busy.value = true
  error.value = ''
  try {
    const payload = { ...backupForm.value,
      recoveryPointAt: localDateTime(backupForm.value.recoveryPointAt),
      capturedAt: localDateTime(backupForm.value.capturedAt),
      completedAt: localDateTime(backupForm.value.completedAt),
      immutableUntil: backupForm.value.immutableUntil ? localDateTime(backupForm.value.immutableUntil) : null
    }
    await props.api('/api/v1/admin/disaster-recovery/backups', { method: 'POST', body: JSON.stringify(payload) })
    showBackup.value = false
    flash('备份已登记并完成自动策略校验')
    await load(true)
  } catch (exception) {
    error.value = exception.message || '登记备份失败'
  } finally { busy.value = false }
}

async function verifyBackup(item) {
  busy.value = true
  error.value = ''
  try {
    await props.api('/api/v1/admin/disaster-recovery/backups/' + item.id + '/verify', { method: 'POST' })
    flash('备份 ' + item.backupNo + ' 已重新校验')
    await load(true)
  } catch (exception) {
    error.value = exception.message || '备份校验失败'
  } finally { busy.value = false }
}

function openDrillEditor(snapshot = null) {
  const selectedBackup = snapshot || availableBackups.value[0]
  drillForm.value = { name: '季度恢复演练', backupSnapshotId: selectedBackup?.id || '', environment: 'isolated-restore', scheduledAt: '' }
  showDrill.value = true
}

async function createDrill() {
  busy.value = true
  error.value = ''
  try {
    const payload = { ...drillForm.value,
      backupSnapshotId: Number(drillForm.value.backupSnapshotId),
      scheduledAt: drillForm.value.scheduledAt ? localDateTime(drillForm.value.scheduledAt) : null
    }
    selected.value = await props.api('/api/v1/admin/disaster-recovery/drills', { method: 'POST', body: JSON.stringify(payload) })
    showDrill.value = false
    flash('恢复演练已创建，门禁清单已生成')
    await load(true)
  } catch (exception) {
    error.value = exception.message || '创建恢复演练失败'
  } finally { busy.value = false }
}

async function openDrill(id, silent = false) {
  if (!silent) error.value = ''
  try { selected.value = await props.api('/api/v1/admin/disaster-recovery/drills/' + id) }
  catch (exception) { error.value = exception.message || '读取演练详情失败' }
}

async function startDrill() {
  if (!window.confirm('确认已准备好隔离恢复环境并开始 RTO 计时？')) return
  busy.value = true
  try {
    selected.value = await props.api('/api/v1/admin/disaster-recovery/drills/' + selected.value.drill.id + '/start', { method: 'POST' })
    flash('演练已开始，RTO 正在计时')
    await load(true)
  } catch (exception) { error.value = exception.message || '开始演练失败' }
  finally { busy.value = false }
}

function openCheckEditor(check) {
  selectedCheck.value = check
  checkForm.value = { status: check.status === 'PENDING' ? 'PASSED' : check.status, detail: check.detail || '', evidence: check.evidence || '' }
  showCheck.value = true
}

async function saveCheck() {
  busy.value = true
  try {
    await props.api('/api/v1/admin/disaster-recovery/drills/' + selected.value.drill.id + '/checks/' + selectedCheck.value.id,
      { method: 'PUT', body: JSON.stringify(checkForm.value) })
    showCheck.value = false
    flash('检查项证据已保存')
    await openDrill(selected.value.drill.id, true)
    await load(true)
  } catch (exception) { error.value = exception.message || '保存检查项失败' }
  finally { busy.value = false }
}

function openCompleteEditor() {
  completeForm.value = { evidenceNote: '', findings: '' }
  showComplete.value = true
}

async function completeDrill() {
  busy.value = true
  try {
    selected.value = await props.api('/api/v1/admin/disaster-recovery/drills/' + selected.value.drill.id + '/complete',
      { method: 'POST', body: JSON.stringify(completeForm.value) })
    showComplete.value = false
    flash('演练已完成，RPO/RTO 与完整性结果已计算')
    await load(true)
  } catch (exception) { error.value = exception.message || '完成演练失败' }
  finally { busy.value = false }
}

function openCancelEditor() { cancelReason.value = ''; showCancel.value = true }
async function cancelDrill() {
  busy.value = true
  try {
    await props.api('/api/v1/admin/disaster-recovery/drills/' + selected.value.drill.id + '/cancel',
      { method: 'POST', body: JSON.stringify({ reason: cancelReason.value }) })
    showCancel.value = false
    flash('演练已取消并记录原因')
    await load(true)
  } catch (exception) { error.value = exception.message || '取消演练失败' }
  finally { busy.value = false }
}

function formatDate(value) { if (!value) return '—'; const raw = String(value).replace(' ', 'T'); return new Date(/(Z|[+-]\d{2}:\d{2})$/.test(raw) ? raw : raw + 'Z').toLocaleString('zh-CN', { hour12: false }) }
function formatSize(value) { const bytes = Number(value || 0); if (bytes < 1024) return bytes + ' B'; if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB'; if (bytes < 1073741824) return (bytes / 1048576).toFixed(1) + ' MB'; return (bytes / 1073741824).toFixed(2) + ' GB' }
function formatDuration(minutes) { if (minutes == null || minutes < 0) return '—'; return minutes < 60 ? minutes + ' 分钟' : Math.floor(minutes / 60) + '小时 ' + minutes % 60 + '分' }
function toInput(value) { const date = value instanceof Date ? value : new Date(value); const local = new Date(date.getTime() - date.getTimezoneOffset() * 60000); return local.toISOString().slice(0, 16) }
function localDateTime(value) { return value ? new Date(value).toISOString().slice(0, 19) : value }

onMounted(load)
</script>

<template>
  <section class="dr-center">
    <header class="dr-heading">
      <div><p>BUSINESS CONTINUITY</p><h2>备份恢复与灾难演练</h2><span>用 RPO、RTO 和证据链管理可恢复性；恢复仅在隔离环境执行。</span></div>
      <div class="heading-actions"><button class="secondary" @click="load()">刷新</button><button class="secondary" @click="openPolicyEditor">灾备策略</button><button @click="openBackupEditor">＋ 登记备份</button><button :disabled="!availableBackups.length" @click="openDrillEditor()">＋ 恢复演练</button></div>
    </header>
    <div v-if="error" class="dr-error">{{ error }}<button @click="error = ''">×</button></div>
    <div v-if="notice" class="dr-notice">{{ notice }}</div>
    <div v-if="loading" class="dr-empty">正在评估灾备就绪度…</div>
    <template v-else>
      <div class="dr-stats">
        <article :class="['readiness', String(center.summary?.state || '').toLowerCase()]"><span>灾备就绪度</span><strong>{{ center.summary?.state || 'UNKNOWN' }}</strong><small>{{ center.summary?.reason }}</small></article>
        <article><span>最新恢复点年龄</span><strong>{{ formatDuration(center.summary?.latestRecoveryPointAgeMinutes) }}</strong><small>目标 RPO {{ center.policy?.rpoMinutes }} 分钟</small></article>
        <article><span>可用备份</span><strong>{{ center.summary?.availableBackups || 0 }}</strong><small>{{ center.summary?.overdueVerifications || 0 }} 个超期未验证</small></article>
        <article><span>恢复演练通过率</span><strong>{{ Number(center.summary?.drillPassRate || 0).toFixed(0) }}%</strong><small>{{ center.summary?.passedDrills || 0 }} / {{ center.summary?.completedDrills || 0 }} 次通过</small></article>
        <article><span>恢复目标</span><strong>{{ center.policy?.rpoMinutes }} / {{ center.policy?.rtoMinutes }}</strong><small>RPO / RTO（分钟）</small></article>
      </div>

      <div class="dr-grid">
        <section class="panel backup-panel">
          <div class="panel-heading"><div><p>BACKUP CATALOG</p><h3>备份制品台账</h3></div><span>{{ center.snapshots?.length || 0 }} 条记录</span></div>
          <div v-if="!center.snapshots?.length" class="dr-empty compact">尚未登记备份。备份应由基础设施作业生成，再将元数据与校验值登记到这里。</div>
          <div v-else class="backup-list">
            <article v-for="item in center.snapshots" :key="item.id">
              <div class="backup-head"><div><code>{{ item.backupNo }}</code><strong>{{ scopeLabels[item.scope] }}</strong></div><div><em :class="String(item.status).toLowerCase()">{{ backupStatusLabels[item.status] }}</em><em :class="String(item.verificationStatus).toLowerCase()">{{ verificationLabels[item.verificationStatus] }}</em></div></div>
              <div class="backup-facts"><span><b>恢复点</b>{{ formatDate(item.recoveryPointAt) }}</span><span><b>大小</b>{{ formatSize(item.sizeBytes) }}</span><span><b>区域</b>{{ item.region }}</span><span><b>不可变至</b>{{ formatDate(item.immutableUntil) }}</span></div>
              <p>{{ item.verificationDetail }}</p><footer><small>{{ item.storageProvider }} · {{ item.storageLocation }}</small><div><button :disabled="busy || item.status !== 'AVAILABLE'" @click="verifyBackup(item)">重新校验</button><button :disabled="item.status !== 'AVAILABLE'" @click="openDrillEditor(item)">发起演练</button></div></footer>
            </article>
          </div>
        </section>

        <section class="panel drill-panel">
          <div class="panel-heading"><div><p>RECOVERY DRILLS</p><h3>隔离恢复演练</h3></div><span>{{ center.drills?.length || 0 }} 次</span></div>
          <div v-if="!center.drills?.length" class="dr-empty compact">还没有恢复演练。使用已校验备份创建第一场演练。</div>
          <div v-else class="drill-list"><button v-for="item in center.drills" :key="item.id" :class="{ selected: selected?.drill?.id === item.id }" @click="openDrill(item.id)"><span><code>{{ item.drillNo }}</code><strong>{{ item.name }}</strong><small>{{ item.environment }} · {{ formatDate(item.createdAt) }}</small></span><em :class="String(item.status).toLowerCase()">{{ drillStatusLabels[item.status] }}</em></button></div>
        </section>
      </div>

      <section v-if="selected" class="panel drill-detail">
        <div class="detail-title"><div><p>DRILL EVIDENCE</p><h3>{{ selected.drill.drillNo }} · {{ selected.drill.name }}</h3><span>备份 {{ selected.snapshot.backupNo }} · {{ selected.drill.environment }}</span></div><div><em :class="String(selected.drill.status).toLowerCase()">{{ drillStatusLabels[selected.drill.status] }}</em><button v-if="selected.drill.status === 'PLANNED'" :disabled="busy" @click="startDrill">开始演练</button><button v-if="selected.drill.status === 'RUNNING'" @click="openCompleteEditor">完成并评估</button><button v-if="['PLANNED','RUNNING'].includes(selected.drill.status)" class="danger" @click="openCancelEditor">取消</button><button class="close" @click="selected = null">×</button></div></div>
        <div class="result-strip"><span><b>目标 RPO</b>{{ selected.drill.targetRpoMinutes }} 分钟</span><span><b>实际 RPO</b>{{ formatDuration(selected.drill.achievedRpoMinutes) }}</span><span><b>目标 RTO</b>{{ selected.drill.targetRtoMinutes }} 分钟</span><span><b>实际 RTO</b>{{ formatDuration(selected.drill.achievedRtoMinutes) }}</span><span><b>完整性得分</b>{{ selected.drill.integrityScore == null ? '—' : selected.drill.integrityScore + '%' }}</span></div>
        <div class="check-list"><div class="check-row heading"><span>演练门禁</span><span>要求</span><span>状态</span><span>证据与结论</span><span>操作</span></div><div v-for="check in selected.checks" :key="check.id" class="check-row"><strong>{{ check.label }}<small>{{ check.checkKey }}</small></strong><span>{{ check.requiredCheck ? '必需' : '非必需' }}</span><em :class="String(check.status).toLowerCase()">{{ checkStatusLabels[check.status] }}</em><p>{{ check.evidence || check.detail || '等待记录验证证据' }}</p><button v-if="selected.drill.status === 'RUNNING'" @click="openCheckEditor(check)">记录</button><span v-else>—</span></div></div>
        <div v-if="selected.drill.evidenceNote || selected.drill.failureReason" class="drill-outcome"><p><b>恢复证据</b>{{ selected.drill.evidenceNote || '—' }}</p><p v-if="selected.drill.findings"><b>演练发现</b>{{ selected.drill.findings }}</p><p v-if="selected.drill.failureReason" class="failure"><b>未通过原因</b>{{ selected.drill.failureReason }}</p></div>
      </section>
    </template>

    <div v-if="showPolicy" class="dr-backdrop" @click.self="showPolicy = false"><form @submit.prevent="savePolicy"><header><div><p>RECOVERY OBJECTIVES</p><h3>租户灾备策略</h3></div><button type="button" @click="showPolicy = false">×</button></header><div class="form-grid"><label>RPO（分钟）<input v-model.number="policyForm.rpoMinutes" type="number" min="5" max="10080" required /></label><label>RTO（分钟）<input v-model.number="policyForm.rtoMinutes" type="number" min="5" max="10080" required /></label><label>保留天数<input v-model.number="policyForm.retentionDays" type="number" min="1" max="3650" required /></label><label>复验周期（小时）<input v-model.number="policyForm.verificationIntervalHours" type="number" min="1" max="720" required /></label></div><label class="toggle"><input v-model="policyForm.requireEncryption" type="checkbox" />要求备份静态加密</label><label class="toggle"><input v-model="policyForm.requireImmutableCopy" type="checkbox" />要求不可变副本覆盖整个保留期</label><label class="toggle"><input v-model="policyForm.requireCrossRegionCopy" type="checkbox" />要求跨区域副本</label><label class="toggle"><input v-model="policyForm.enabled" type="checkbox" />启用灾备就绪度评估</label><aside>后台只管理策略与证据，不直接在生产数据库执行恢复。所有恢复步骤必须在隔离环境完成。</aside><footer><button type="button" class="cancel" @click="showPolicy = false">取消</button><button :disabled="busy">保存策略</button></footer></form></div>

    <div v-if="showBackup" class="dr-backdrop" @click.self="showBackup = false"><form class="wide" @submit.prevent="registerBackup"><header><div><p>BACKUP EVIDENCE</p><h3>登记外部备份</h3></div><button type="button" @click="showBackup = false">×</button></header><div class="form-grid"><label>备份范围<select v-model="backupForm.scope"><option v-for="(label,key) in scopeLabels" :key="key" :value="key">{{ label }}</option></select></label><label>存储提供方<input v-model="backupForm.storageProvider" maxlength="64" required /></label><label>存储区域<input v-model="backupForm.region" maxlength="64" required /></label><label>备份大小（bytes）<input v-model.number="backupForm.sizeBytes" type="number" min="1" required /></label><label>恢复点时间<input v-model="backupForm.recoveryPointAt" type="datetime-local" required /></label><label>采集开始时间<input v-model="backupForm.capturedAt" type="datetime-local" required /></label><label>备份完成时间<input v-model="backupForm.completedAt" type="datetime-local" required /></label><label>不可变保护至<input v-model="backupForm.immutableUntil" type="datetime-local" /></label></div><label>存储位置<input v-model="backupForm.storageLocation" maxlength="500" required /></label><label>SHA-256 校验值<input v-model="backupForm.checksumSha256" minlength="64" maxlength="64" pattern="[a-fA-F0-9]{64}" required placeholder="64 位十六进制校验值" /></label><label>外部作业证据<textarea v-model="backupForm.evidenceNote" maxlength="1000" rows="3" placeholder="备份作业编号、日志位置或审批记录"></textarea></label><div class="inline-toggles"><label class="toggle"><input v-model="backupForm.encrypted" type="checkbox" />已加密</label><label class="toggle"><input v-model="backupForm.crossRegionCopy" type="checkbox" />已有跨区域副本</label></div><aside>登记动作不会上传或复制备份文件，只保存制品位置、校验值和安全属性用于审计与演练。</aside><footer><button type="button" class="cancel" @click="showBackup = false">取消</button><button :disabled="busy">登记并校验</button></footer></form></div>

    <div v-if="showDrill" class="dr-backdrop" @click.self="showDrill = false"><form @submit.prevent="createDrill"><header><div><p>ISOLATED RESTORE</p><h3>创建恢复演练</h3></div><button type="button" @click="showDrill = false">×</button></header><label>演练名称<input v-model="drillForm.name" minlength="2" maxlength="160" required /></label><label>使用备份<select v-model="drillForm.backupSnapshotId" required><option v-for="item in availableBackups" :key="item.id" :value="item.id">{{ item.backupNo }} · {{ scopeLabels[item.scope] }} · {{ formatDate(item.recoveryPointAt) }}</option></select></label><label>隔离环境<input v-model="drillForm.environment" minlength="2" maxlength="64" required /></label><label>计划时间（可选）<input v-model="drillForm.scheduledAt" type="datetime-local" /></label><aside>创建后会固化当前 RPO/RTO 目标，并自动生成制品、加密、不可变性、数据完整性和业务冒烟门禁。</aside><footer><button type="button" class="cancel" @click="showDrill = false">取消</button><button :disabled="busy">创建演练</button></footer></form></div>

    <div v-if="showCheck" class="dr-backdrop" @click.self="showCheck = false"><form @submit.prevent="saveCheck"><header><div><p>RESTORE CHECK</p><h3>{{ selectedCheck?.label }}</h3></div><button type="button" @click="showCheck = false">×</button></header><label>验证结果<select v-model="checkForm.status"><option value="PASSED">通过</option><option value="WARNING">警告</option><option value="FAILED">失败</option></select></label><label>检查结论<textarea v-model="checkForm.detail" maxlength="1000" rows="3" required /></label><label>证据位置或摘要<textarea v-model="checkForm.evidence" minlength="5" maxlength="1000" rows="4" required placeholder="查询结果、日志编号、截图或验证数据"></textarea></label><footer><button type="button" class="cancel" @click="showCheck = false">取消</button><button :disabled="busy">保存证据</button></footer></form></div>

    <div v-if="showComplete" class="dr-backdrop" @click.self="showComplete = false"><form @submit.prevent="completeDrill"><header><div><p>GO / NO-GO</p><h3>完成恢复演练</h3></div><button type="button" @click="showComplete = false">×</button></header><label>恢复证据汇总<textarea v-model="completeForm.evidenceNote" minlength="5" maxlength="2000" rows="5" required placeholder="说明隔离环境、恢复命令审批、完整性结果和业务验证范围"></textarea></label><label>演练发现与改进项<textarea v-model="completeForm.findings" maxlength="2000" rows="4" placeholder="例如：对象清单比对耗时过长，需要增加分片并发"></textarea></label><aside>提交后系统会根据开始时间、备份恢复点和必需检查项，自动判定 RPO、RTO 与完整性门禁是否通过。</aside><footer><button type="button" class="cancel" @click="showComplete = false">取消</button><button :disabled="busy">完成并评估</button></footer></form></div>

    <div v-if="showCancel" class="dr-backdrop" @click.self="showCancel = false"><form @submit.prevent="cancelDrill"><header><div><p>CANCEL DRILL</p><h3>取消恢复演练</h3></div><button type="button" @click="showCancel = false">×</button></header><label>取消原因<textarea v-model="cancelReason" minlength="5" maxlength="1000" rows="4" required /></label><footer><button type="button" class="cancel" @click="showCancel = false">返回</button><button class="danger-button" :disabled="busy">确认取消</button></footer></form></div>
  </section>
</template>

<style scoped>
.dr-center{overflow:hidden;border:1px solid #dfe7ee;border-radius:14px;background:#f6f8fb;color:#2c3d53}.dr-heading{display:flex;align-items:center;justify-content:space-between;gap:18px;padding:22px;background:linear-gradient(120deg,#edf7f5,#f1f4ff)}.dr-heading p,.panel-heading p,.detail-title p,.dr-backdrop header p{margin:0 0 5px;color:#287a70;font-size:9px;font-weight:900;letter-spacing:1.1px}.dr-heading h2,.panel-heading h3,.detail-title h3,.dr-backdrop h3{margin:0 0 5px}.dr-heading h2{font-size:18px}.dr-heading span,.detail-title span{color:#7c8998;font-size:10px}.heading-actions{display:flex;flex-wrap:wrap;justify-content:flex-end;gap:7px}.heading-actions button,.detail-title button{padding:9px 11px;border:1px solid #347e75;border-radius:8px;background:#347e75;color:#fff;font-size:9px;font-weight:900}.heading-actions .secondary{background:#fff;color:#347e75}.heading-actions button:disabled{opacity:.45}.dr-error,.dr-notice{margin:12px 14px 0;padding:9px 11px;border-radius:8px;font-size:10px}.dr-error{background:#fff0f2;color:#a42e43}.dr-error button{float:right;border:0;background:transparent;color:inherit}.dr-notice{background:#e5f7f0;color:#14765f}.dr-empty{display:grid;place-items:center;min-height:240px;color:#8794a4;font-size:10px}.dr-empty.compact{min-height:150px;padding:20px;text-align:center}.dr-stats{display:grid;grid-template-columns:repeat(5,1fr);gap:10px;padding:14px}.dr-stats article{padding:13px;border:1px solid #dfe7ee;border-radius:10px;background:#fff}.dr-stats span,.dr-stats small{display:block;color:#8491a1;font-size:9px}.dr-stats strong{display:block;margin:7px 0;color:#304158;font-size:19px}.dr-stats .healthy strong{color:#16755f}.dr-stats .warning strong{color:#a46b12}.dr-stats .critical strong{color:#ae3548}.dr-grid{display:grid;grid-template-columns:minmax(0,1.3fr) minmax(330px,.7fr);gap:12px;padding:0 14px 12px}.panel{border:1px solid #dfe7ee;border-radius:11px;background:#fff}.panel-heading{display:flex;align-items:center;justify-content:space-between;padding:14px;border-bottom:1px solid #e9eef3}.panel-heading h3{font-size:14px}.panel-heading>span{color:#7f8d9e;font-size:9px}.backup-list{display:grid;gap:9px;max-height:520px;overflow:auto;padding:12px}.backup-list article{padding:12px;border:1px solid #e4eaf0;border-radius:9px}.backup-head,.backup-head>div,.backup-list footer,.backup-list footer>div{display:flex;align-items:center;justify-content:space-between;gap:7px}.backup-head code,.drill-list code{display:block;color:#46736e;font-size:9px}.backup-head strong{font-size:12px}.backup-head em,.drill-list em,.detail-title em,.check-row em{padding:4px 7px;border-radius:999px;background:#eef2f6;color:#64758a;font-size:8px;font-style:normal;font-weight:900}.backup-head em.passed,.drill-list em.passed,.detail-title em.passed,.check-row em.passed,.backup-head em.available{background:#e4f6ef;color:#14745d}.backup-head em.warning,.check-row em.warning{background:#fff1d9;color:#97610d}.backup-head em.failed,.drill-list em.failed,.detail-title em.failed,.check-row em.failed{background:#ffe6ea;color:#a62f44}.backup-head em.expired,.drill-list em.cancelled,.detail-title em.cancelled{background:#edf0f4;color:#7b8796}.backup-head em.running,.drill-list em.running,.detail-title em.running{background:#e9edff;color:#5866ad}.backup-facts{display:grid;grid-template-columns:repeat(4,1fr);gap:7px;margin:10px 0}.backup-facts span{padding:7px;border-radius:7px;background:#f6f8fa;color:#58687c;font-size:8px}.backup-facts b,.result-strip b,.drill-outcome b{display:block;margin-bottom:3px;color:#8a96a5;font-size:7px}.backup-list article>p{margin:7px 0;color:#748295;font-size:9px}.backup-list footer small{max-width:65%;overflow:hidden;color:#8a97a7;font-size:8px;text-overflow:ellipsis;white-space:nowrap}.backup-list footer button,.check-row button{padding:6px 8px;border:1px solid #4e887f;border-radius:6px;background:#fff;color:#347e75;font-size:8px}.backup-list footer button:disabled{opacity:.4}.drill-list{display:grid;gap:6px;max-height:520px;overflow:auto;padding:10px}.drill-list>button{display:flex;align-items:center;justify-content:space-between;gap:8px;width:100%;padding:10px;border:1px solid #e4eaf0;border-radius:8px;background:#fff;text-align:left}.drill-list>button.selected{border-color:#4b9187;background:#f0faf7}.drill-list strong,.drill-list small{display:block}.drill-list strong{margin:3px 0;font-size:10px}.drill-list small{color:#8a96a4;font-size:8px}.drill-detail{margin:0 14px 14px}.detail-title{display:flex;align-items:center;justify-content:space-between;padding:15px;border-bottom:1px solid #e7edf2}.detail-title>div:last-child{display:flex;align-items:center;gap:6px}.detail-title button{padding:7px 9px}.detail-title button.danger,.danger-button{border-color:#d06b78;background:#b43e50!important}.detail-title button.close{border:0;background:transparent;color:#7f8c9b;font-size:18px}.result-strip{display:grid;grid-template-columns:repeat(5,1fr);gap:8px;padding:12px}.result-strip span{padding:9px;border-radius:8px;background:#f6f8fa;font-size:10px}.check-list{overflow:auto}.check-row{display:grid;grid-template-columns:1.25fr .45fr .55fr 2fr .4fr;align-items:center;gap:10px;min-width:820px;padding:10px 13px;border-top:1px solid #edf1f4;font-size:9px}.check-row.heading{border:0;background:#fafbfc;color:#8491a1}.check-row strong small{display:block;margin-top:3px;color:#8a97a6;font-size:7px}.check-row p{margin:0;color:#68778b}.drill-outcome{display:grid;gap:7px;padding:13px;border-top:1px solid #e8edf2;background:#fafcfd}.drill-outcome p{margin:0;font-size:9px}.drill-outcome .failure{color:#a52f43}.dr-backdrop{position:fixed;z-index:1600;inset:0;display:grid;place-items:center;padding:20px;background:rgba(13,27,42,.64)}.dr-backdrop form{width:min(540px,100%);max-height:90vh;overflow:auto;padding:22px;border-radius:15px;background:#fff;box-shadow:0 24px 70px rgba(10,24,38,.3)}.dr-backdrop form.wide{width:min(720px,100%)}.dr-backdrop header{display:flex;justify-content:space-between}.dr-backdrop header button{border:0;background:transparent;color:#7e8b9b;font-size:22px}.dr-backdrop label{display:grid;gap:6px;margin-top:12px;color:#596a7d;font-size:10px;font-weight:800}.dr-backdrop input,.dr-backdrop select,.dr-backdrop textarea{padding:10px;border:1px solid #d5dee7;border-radius:8px;font:inherit;resize:vertical}.form-grid{display:grid;grid-template-columns:1fr 1fr;gap:0 12px}.toggle{display:flex!important;grid-template-columns:auto!important;align-items:center;justify-content:flex-start;gap:7px!important}.toggle input{width:auto}.inline-toggles{display:flex;gap:24px}.dr-backdrop aside{margin-top:14px;padding:10px;border-radius:8px;background:#f2f7f6;color:#667e7a;font-size:9px;line-height:1.6}.dr-backdrop footer{display:flex;justify-content:flex-end;gap:8px;margin-top:17px}.dr-backdrop footer button{padding:9px 13px;border:0;border-radius:8px;background:#347e75;color:#fff;font-size:10px;font-weight:900}.dr-backdrop footer .cancel{border:1px solid #d5dee7;background:#fff;color:#637287}@media(max-width:1100px){.dr-stats{grid-template-columns:repeat(3,1fr)}.dr-grid{grid-template-columns:1fr}}@media(max-width:720px){.dr-heading,.detail-title{align-items:flex-start;flex-direction:column}.dr-stats{grid-template-columns:repeat(2,1fr)}.backup-facts,.result-strip,.form-grid{grid-template-columns:1fr 1fr}.inline-toggles{align-items:flex-start;flex-direction:column;gap:0}}
</style>
