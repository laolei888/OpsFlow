<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'

const props = defineProps({ api: { type: Function, required: true } })
const summary = ref({ pending: 0, scanning: 0, clean: 0, infected: 0, failed: 0, released: 0, oldestPendingAt: null })
const records = ref([])
const storage = ref({ configuredBackend: 'LOCAL', availableBackends: ['LOCAL'], bucket: null, presignedDownloadTtl: 'PT5M' })
const loading = ref(false)
const error = ref('')
const notice = ref('')
const status = ref('')
const keyword = ref('')
const operation = ref(null)
const reason = ref('')
const confirmingHash = ref('')
const submitting = ref(false)
let refreshTimer

const statusLabels = { PENDING: '等待扫描', SCANNING: '扫描中', CLEAN: '安全放行', INFECTED: '检测到威胁', FAILED: '扫描失败', RELEASED: '人工放行' }
const backlog = computed(() => Number(summary.value.pending || 0) + Number(summary.value.scanning || 0))

async function load(silent = false) {
  if (!silent) loading.value = true
  error.value = ''
  const params = new URLSearchParams({ limit: '200' })
  if (status.value) params.set('status', status.value)
  if (keyword.value.trim()) params.set('keyword', keyword.value.trim())
  try {
    const [nextSummary, nextRecords, nextStorage] = await Promise.all([
      props.api('/api/v1/admin/attachment-security/summary'),
      props.api('/api/v1/admin/attachment-security?' + params.toString()),
      props.api('/api/v1/admin/attachment-security/storage')
    ])
    summary.value = nextSummary
    records.value = nextRecords || []
    storage.value = nextStorage
  } catch (exception) {
    error.value = exception.message || '附件安全中心加载失败'
  } finally {
    loading.value = false
  }
}

function openOperation(item, mode) {
  operation.value = { item, mode }
  reason.value = ''
  confirmingHash.value = ''
  error.value = ''
}

async function submitOperation() {
  if (!operation.value || reason.value.trim().length < 5) {
    error.value = '请填写至少 5 个字符的操作原因'
    return
  }
  const { item, mode } = operation.value
  if (mode === 'release' && confirmingHash.value.trim().toLowerCase() !== String(item.sha256 || '').toLowerCase()) {
    error.value = '请输入完整 SHA-256，确认你放行的是当前这份文件'
    return
  }
  submitting.value = true
  error.value = ''
  try {
    const body = mode === 'release'
      ? { reason: reason.value.trim(), expectedSha256: confirmingHash.value.trim() }
      : { reason: reason.value.trim() }
    await props.api('/api/v1/admin/attachment-security/' + item.id + '/' + mode, {
      method: 'POST', body: JSON.stringify(body)
    })
    operation.value = null
    notice.value = mode === 'release' ? '附件已人工放行，操作已写入审计日志' : '附件已重新进入扫描队列'
    window.setTimeout(() => { notice.value = '' }, 3000)
    await load(true)
  } catch (exception) {
    error.value = exception.message || '附件安全操作失败'
  } finally {
    submitting.value = false
  }
}

function formatDate(value) {
  if (!value) return '—'
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' }).format(new Date(value))
}

function formatSize(bytes) {
  if (!bytes) return '0 B'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1024 / 1024).toFixed(1) + ' MB'
}

function shortHash(value) {
  if (!value) return '等待生成'
  return value.slice(0, 12) + '…' + value.slice(-8)
}

onMounted(() => {
  load()
  refreshTimer = window.setInterval(() => load(true), 15000)
})
onUnmounted(() => window.clearInterval(refreshTimer))
</script>

<template>
  <section class="security-center">
    <header class="security-heading">
      <div><p>QUARANTINE & MALWARE DEFENSE</p><h2>附件安全中心</h2><span>所有新附件先进入隔离区，完成策略检查、压缩包防护与可选 ClamAV 扫描后才允许下载。</span></div>
      <button :disabled="loading" @click="load()">{{ loading ? '同步中…' : '刷新状态' }}</button>
    </header>

    <div class="security-stats">
      <article><span>扫描积压</span><strong>{{ backlog }}</strong><small>等待扫描 / 扫描中</small></article>
      <article class="safe"><span>自动放行</span><strong>{{ summary.clean }}</strong><small>策略与病毒扫描通过</small></article>
      <article class="danger"><span>威胁隔离</span><strong>{{ summary.infected }}</strong><small>禁止预览和下载</small></article>
      <article class="warning"><span>扫描失败</span><strong>{{ summary.failed }}</strong><small>等待管理员复核</small></article>
      <article><span>人工放行</span><strong>{{ summary.released }}</strong><small>高风险审计操作</small></article>
      <article><span>最早积压</span><strong class="date">{{ formatDate(summary.oldestPendingAt) }}</strong><small>用于判断扫描延迟</small></article>
    </div>

    <div class="storage-banner">
      <div><span>ACTIVE STORAGE</span><strong>{{ storage.configuredBackend === 'S3' ? 'S3 / MinIO 对象存储' : '本地持久化卷' }}</strong></div>
      <small v-if="storage.bucket">Bucket：{{ storage.bucket }}</small>
      <small>可用适配器：{{ (storage.availableBackends || []).join(' / ') }}</small>
      <small>{{ storage.configuredBackend === 'S3' ? '下载使用短时预签名地址' : '下载由 OpsFlow 鉴权代理' }}</small>
    </div>

    <p v-if="error" class="security-error">{{ error }} <button @click="error = ''">×</button></p>
    <p v-if="notice" class="security-notice">✓ {{ notice }}</p>

    <div class="security-toolbar">
      <input v-model="keyword" placeholder="搜索文件名或 SHA-256" @keyup.enter="load()" />
      <select v-model="status" @change="load()"><option value="">全部状态</option><option v-for="(label, value) in statusLabels" :key="value" :value="value">{{ label }}</option></select>
      <button @click="load()">查询</button>
    </div>

    <div v-if="loading && !records.length" class="security-empty">正在读取附件隔离记录…</div>
    <div v-else-if="!records.length" class="security-empty">当前筛选条件下没有附件安全记录。</div>
    <div v-else class="security-table"><table>
      <thead><tr><th>文件</th><th>安全状态</th><th>扫描证据</th><th>时间</th><th>处置</th></tr></thead>
      <tbody><tr v-for="item in records" :key="item.id">
        <td><strong>{{ item.originalName }}</strong><small>工单 #{{ item.ticketId }} · 上传人 #{{ item.uploaderId }} · {{ formatSize(item.sizeBytes) }}</small><code>{{ item.storageBackend || 'LOCAL' }} · {{ item.contentType }}</code></td>
        <td><span :class="['scan-status', String(item.scanStatus).toLowerCase()]">{{ statusLabels[item.scanStatus] || item.scanStatus }}</span><small>扫描 {{ item.scanAttempts || 0 }} / 5 次</small><small v-if="item.nextScanAt && ['PENDING','SCANNING'].includes(item.scanStatus)">下次 {{ formatDate(item.nextScanAt) }}</small></td>
        <td><b>{{ item.scanEngine || '等待扫描器' }}</b><small :class="{ threat: item.scanStatus === 'INFECTED' }">{{ item.scanResult || '尚无扫描结论' }}</small><code :title="item.sha256">SHA-256 {{ shortHash(item.sha256) }}</code></td>
        <td><time>上传 {{ formatDate(item.createdAt) }}</time><small v-if="item.scannedAt">扫描 {{ formatDate(item.scannedAt) }}</small><small v-if="item.releasedAt">放行 {{ formatDate(item.releasedAt) }}</small></td>
        <td><div class="row-actions" v-if="['INFECTED','FAILED'].includes(item.scanStatus)"><button @click="openOperation(item, 'retry')">重新扫描</button><button class="danger" :disabled="!item.sha256" @click="openOperation(item, 'release')">人工放行</button></div><small v-else>无需人工处置</small></td>
      </tr></tbody>
    </table></div>

    <div v-if="operation" class="operation-backdrop" @click.self="operation = null"><form @submit.prevent="submitOperation">
      <header><div><p>{{ operation.mode === 'release' ? 'HIGH-RISK RELEASE' : 'CONTROLLED RESCAN' }}</p><h3>{{ operation.mode === 'release' ? '人工放行隔离附件' : '重新执行安全扫描' }}</h3><span>{{ operation.item.originalName }} · {{ statusLabels[operation.item.scanStatus] }}</span></div><button type="button" @click="operation = null">×</button></header>
      <div v-if="operation.mode === 'release'" class="release-warning"><strong>警告：扫描器未确认该文件安全</strong><p>人工放行后，工单参与者将可以下载文件。请先在独立沙箱完成核验，并复制下方完整哈希进行二次确认。</p><code>{{ operation.item.sha256 }}</code></div>
      <label v-if="operation.mode === 'release'">确认 SHA-256<input v-model="confirmingHash" autocomplete="off" maxlength="64" required placeholder="粘贴上方 64 位哈希" /></label>
      <label>操作原因<textarea v-model="reason" maxlength="500" rows="4" required :placeholder="operation.mode === 'release' ? '至少 5 个字符，说明离线分析结论和放行依据' : '至少 5 个字符，说明扫描器或规则已经恢复'"></textarea></label>
      <footer><button type="button" class="cancel" @click="operation = null">取消</button><button :class="operation.mode === 'release' ? 'release' : 'confirm'" :disabled="submitting">{{ submitting ? '提交中…' : operation.mode === 'release' ? '确认高风险放行' : '确认重新扫描' }}</button></footer>
    </form></div>
  </section>
</template>

<style scoped>
.storage-banner{display:flex;align-items:center;gap:18px;padding:11px 14px;border-top:1px solid #e9edf2;border-bottom:1px solid #e9edf2;background:#fcfdfd}.storage-banner div{margin-right:auto}.storage-banner span,.storage-banner small{color:#8491a2;font-size:9px}.storage-banner span{display:block;margin-bottom:3px;font-weight:900;letter-spacing:1px}.storage-banner strong{color:#3d5065;font-size:11px}
.security-center{overflow:hidden;border:1px solid #e3e9ef;border-radius:14px;background:#fff;color:#29384d}.security-heading{display:flex;align-items:center;justify-content:space-between;gap:20px;padding:22px;background:linear-gradient(120deg,#f4f8ff,#f8fbfa)}.security-heading p,.operation-backdrop header p{margin:0 0 5px;color:#4f6ec4;font-size:9px;font-weight:900;letter-spacing:1px}.security-heading h2{margin:0 0 5px;font-size:18px}.security-heading span{color:#7d8b9e;font-size:10px}.security-heading>button,.security-toolbar button{padding:9px 12px;border:0;border-radius:8px;background:#5872c9;color:#fff;font-size:10px;font-weight:900;cursor:pointer}.security-stats{display:grid;grid-template-columns:repeat(6,1fr);gap:10px;padding:14px;background:#f8fafc}.security-stats article{padding:13px;border:1px solid #e2e8ef;border-radius:10px;background:#fff}.security-stats span,.security-stats small{display:block;color:#8491a2;font-size:9px}.security-stats strong{display:block;margin:6px 0;color:#314157;font-size:21px}.security-stats .safe strong{color:#15816a}.security-stats .danger strong{color:#b32f45}.security-stats .warning strong{color:#a66a0d}.security-stats .date{font-size:11px;line-height:23px}.security-error,.security-notice{margin:12px 14px 0;padding:9px 11px;border-radius:8px;font-size:10px}.security-error{background:#fff0f2;color:#a52e43}.security-error button{float:right;border:0;background:transparent;color:inherit}.security-notice{background:#eaf8f3;color:#147560}.security-toolbar{display:grid;grid-template-columns:1fr 180px 70px;gap:9px;padding:14px;border-bottom:1px solid #e8edf2}.security-toolbar input,.security-toolbar select{padding:10px;border:1px solid #d7e0e8;border-radius:8px;color:#34445a;font-size:10px}.security-empty{padding:62px;color:#8995a5;text-align:center;font-size:11px}.security-table{overflow:auto}.security-table table{width:100%;min-width:1100px;border-collapse:collapse}.security-table th{padding:10px 13px;background:#fbfcfd;color:#8793a3;font-size:9px;text-align:left}.security-table td{padding:12px 13px;border-top:1px solid #edf1f4;vertical-align:middle;font-size:10px}.security-table strong,.security-table small,.security-table b,.security-table code,.security-table time{display:block}.security-table td>strong{color:#3d4e65;font-size:11px}.security-table small,.security-table time,.security-table code{margin-top:4px;color:#8995a4;font-size:9px}.security-table code{max-width:280px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.scan-status{display:inline-block;padding:4px 7px;border-radius:999px;background:#edf1f5;color:#627389;font-size:9px;font-weight:900}.scan-status.clean{background:#e5f7f1;color:#147862}.scan-status.infected{background:#ffe5e9;color:#a62d43}.scan-status.failed{background:#fff0d8;color:#96600d}.scan-status.scanning{background:#e9efff;color:#4d68bb}.scan-status.released{background:#f3eaff;color:#7650a9}.threat{max-width:300px;color:#b22d43!important}.row-actions{display:flex;gap:6px}.row-actions button{padding:7px 9px;border:1px solid #cdd8e3;border-radius:7px;background:#fff;color:#4e65ad;font-size:9px;font-weight:900}.row-actions .danger{border-color:#d8a7b0;color:#a42f44}.operation-backdrop{position:fixed;z-index:1400;inset:0;display:grid;place-items:center;padding:20px;background:rgba(14,27,43,.62)}.operation-backdrop form{width:min(560px,100%);padding:22px;border-radius:16px;background:#fff;box-shadow:0 24px 70px rgba(10,24,38,.3)}.operation-backdrop header{display:flex;justify-content:space-between}.operation-backdrop h3{margin:0 0 4px}.operation-backdrop header span{color:#8793a3;font-size:10px}.operation-backdrop header button{border:0;background:transparent;color:#7f8b9a;font-size:22px}.release-warning{margin:15px 0;padding:13px;border:1px solid #f0b8c0;border-radius:9px;background:#fff5f6;color:#8e2638}.release-warning p{font-size:10px;line-height:1.55}.release-warning code{display:block;overflow-wrap:anywhere;font-size:9px}.operation-backdrop label{display:grid;gap:6px;margin-top:13px;color:#596a7e;font-size:10px;font-weight:800}.operation-backdrop input,.operation-backdrop textarea{padding:10px;border:1px solid #d7e0e8;border-radius:8px;resize:vertical}.operation-backdrop footer{display:flex;justify-content:flex-end;gap:8px;margin-top:17px}.operation-backdrop footer button{padding:9px 13px;border-radius:8px;font-size:10px;font-weight:900}.cancel{border:1px solid #d6dee6;background:#fff;color:#647287}.confirm{border:0;background:#5872c9;color:#fff}.release{border:0;background:#aa3045;color:#fff}@media(max-width:1050px){.security-stats{grid-template-columns:repeat(3,1fr)}}@media(max-width:650px){.security-heading{align-items:flex-start;flex-direction:column}.security-stats{grid-template-columns:repeat(2,1fr)}.security-toolbar{grid-template-columns:1fr}}
</style>
