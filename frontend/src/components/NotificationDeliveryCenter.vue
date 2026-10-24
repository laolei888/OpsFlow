<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'

const props = defineProps({ api: { type: Function, required: true } })
const summary = ref({ pending: 0, processing: 0, retrying: 0, delivered: 0, dead: 0, skipped: 0, oldestPendingAt: null })
const deliveries = ref([])
const loading = ref(false)
const error = ref('')
const notice = ref('')
const channel = ref('')
const status = ref('')
const keyword = ref('')
const retryTarget = ref(null)
const retryReason = ref('')
const retrying = ref(false)
let refreshTimer

const channelLabels = { IN_APP: '站内信', EMAIL: '邮件', WEBHOOK: '个人 Webhook', WECOM: '企业微信' }
const statusLabels = { PENDING: '待投递', PROCESSING: '投递中', RETRYING: '等待重试', DELIVERED: '已送达', DEAD: '死信', SKIPPED: '已跳过' }
const backlog = computed(() => Number(summary.value.pending || 0) + Number(summary.value.processing || 0) + Number(summary.value.retrying || 0))

async function load(silent = false) {
  if (!silent) loading.value = true
  error.value = ''
  const params = new URLSearchParams({ limit: '200' })
  if (channel.value) params.set('channel', channel.value)
  if (status.value) params.set('status', status.value)
  if (keyword.value.trim()) params.set('keyword', keyword.value.trim())
  try {
    const [nextSummary, nextDeliveries] = await Promise.all([
      props.api('/api/v1/admin/notification-deliveries/summary'),
      props.api('/api/v1/admin/notification-deliveries?' + params.toString())
    ])
    summary.value = nextSummary
    deliveries.value = nextDeliveries || []
  } catch (exception) {
    error.value = exception.message || '通知投递中心加载失败'
  } finally {
    loading.value = false
  }
}

async function retry() {
  if (!retryTarget.value || retryReason.value.trim().length < 5) {
    error.value = '请填写至少 5 个字符的重试原因'
    return
  }
  retrying.value = true
  error.value = ''
  try {
    await props.api('/api/v1/admin/notification-deliveries/' + retryTarget.value.id + '/retry', {
      method: 'POST', body: JSON.stringify({ reason: retryReason.value.trim() })
    })
    retryTarget.value = null
    retryReason.value = ''
    notice.value = '通知投递已重新排队'
    window.setTimeout(() => { notice.value = '' }, 2500)
    await load(true)
  } catch (exception) {
    error.value = exception.message || '通知投递重试失败'
  } finally {
    retrying.value = false
  }
}

function formatDate(value) {
  if (!value) return '—'
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' }).format(new Date(value))
}

onMounted(() => {
  load()
  refreshTimer = window.setInterval(() => load(true), 15000)
})
onUnmounted(() => window.clearInterval(refreshTimer))
</script>

<template>
  <div class="delivery-center">
    <div class="delivery-heading">
      <div><p>MULTI-CHANNEL DELIVERY</p><h3>通知渠道投递</h3><span>统一追踪站内信、邮件、个人 Webhook 与企业微信，每个渠道拥有独立状态、模板版本、租约与重试次数。</span></div>
      <button :disabled="loading" @click="load()">{{ loading ? '同步中…' : '刷新投递' }}</button>
    </div>
    <div class="delivery-stats">
      <article><span>渠道积压</span><strong>{{ backlog }}</strong><small>待投递 / 处理中 / 重试</small></article>
      <article class="success"><span>已送达</span><strong>{{ summary.delivered }}</strong><small>包含即时站内信</small></article>
      <article class="danger"><span>渠道死信</span><strong>{{ summary.dead }}</strong><small>需要管理员复核</small></article>
      <article><span>已跳过</span><strong>{{ summary.skipped }}</strong><small>偏好关闭或目标缺失</small></article>
      <article><span>最早积压</span><strong class="date">{{ formatDate(summary.oldestPendingAt) }}</strong><small>用于判断投递延迟</small></article>
    </div>
    <p v-if="error" class="delivery-error">{{ error }} <button @click="error = ''">×</button></p>
    <p v-if="notice" class="delivery-notice">✓ {{ notice }}</p>
    <div class="delivery-toolbar">
      <input v-model="keyword" placeholder="事件类型或目标地址" @keyup.enter="load()" />
      <select v-model="channel" @change="load()"><option value="">全部渠道</option><option v-for="(label, value) in channelLabels" :key="value" :value="value">{{ label }}</option></select>
      <select v-model="status" @change="load()"><option value="">全部状态</option><option v-for="(label, value) in statusLabels" :key="value" :value="value">{{ label }}</option></select>
      <button @click="load()">查询</button>
    </div>
    <div v-if="loading && !deliveries.length" class="delivery-empty">正在读取渠道投递流水…</div>
    <div v-else-if="!deliveries.length" class="delivery-empty">当前筛选条件下没有投递记录。</div>
    <div v-else class="delivery-table-wrap"><table>
      <thead><tr><th>通知</th><th>渠道 / 目标</th><th>状态</th><th>尝试</th><th>时间</th><th></th></tr></thead>
      <tbody><tr v-for="item in deliveries" :key="item.id">
        <td><strong>{{ item.title || '通知 #' + item.notificationId }}</strong><small>{{ item.notificationType }} · 接收人 #{{ item.recipientId }}<template v-if="item.templateVersionId"> · 模板版本 #{{ item.templateVersionId }}</template></small></td>
        <td><b>{{ channelLabels[item.channel] || item.channel }}</b><code>{{ item.destinationPreview || '站内通知中心' }}</code></td>
        <td><span :class="['delivery-status', String(item.status).toLowerCase()]">{{ statusLabels[item.status] || item.status }}</span><small v-if="item.lastError" class="failure" :title="item.lastError">{{ item.lastError }}</small><small v-else-if="item.responseStatus">HTTP {{ item.responseStatus }}</small></td>
        <td><strong>{{ item.attemptCount || 0 }} / 5</strong><small v-if="['PENDING','PROCESSING','RETRYING'].includes(item.status)">下次 {{ formatDate(item.nextAttemptAt) }}</small></td>
        <td><time>{{ formatDate(item.createdAt) }}</time><small v-if="item.deliveredAt">送达 {{ formatDate(item.deliveredAt) }}</small></td>
        <td><button v-if="item.status === 'DEAD' || (item.status === 'SKIPPED' && item.channel !== 'IN_APP')" @click="retryTarget = item; retryReason = ''">人工重试</button></td>
      </tr></tbody>
    </table></div>

    <div v-if="retryTarget" class="retry-backdrop" @click.self="retryTarget = null"><form @submit.prevent="retry">
      <header><div><p>CONTROLLED RETRY</p><h3>重试渠道投递</h3><span>{{ channelLabels[retryTarget.channel] }} · {{ retryTarget.title }}</span></div><button type="button" @click="retryTarget = null">×</button></header>
      <div class="retry-context"><span>原状态：{{ statusLabels[retryTarget.status] }}</span><span>目标：{{ retryTarget.destinationPreview || '站内通知中心' }}</span><p v-if="retryTarget.lastError">{{ retryTarget.lastError }}</p></div>
      <label>重试原因<textarea v-model="retryReason" maxlength="500" rows="4" required placeholder="至少 5 个字符，说明故障已经消除或配置已经修复"></textarea></label>
      <footer><button type="button" class="cancel" @click="retryTarget = null">取消</button><button class="confirm" :disabled="retrying">{{ retrying ? '重新排队中…' : '确认重试' }}</button></footer>
    </form></div>
  </div>
</template>

<style scoped>
.delivery-center{color:#263449}.delivery-heading{display:flex;align-items:center;justify-content:space-between;gap:20px;padding:19px 21px;border-bottom:1px solid #e8edf2;background:linear-gradient(120deg,#f5f9ff,#f8fbfc)}.delivery-heading p{margin:0 0 4px;color:#5570c9;font-size:9px;font-weight:900;letter-spacing:1px}.delivery-heading h3{margin:0 0 4px;font-size:16px}.delivery-heading span{color:#7e8b9d;font-size:10px}.delivery-heading>button,.delivery-toolbar button{border:0;border-radius:8px;background:#5e75ce;color:#fff;font-size:10px;font-weight:900;cursor:pointer}.delivery-heading>button{padding:9px 12px}.delivery-stats{display:grid;grid-template-columns:repeat(5,1fr);gap:10px;padding:14px;background:#f8fafc}.delivery-stats article{padding:13px;border:1px solid #e1e8ef;border-radius:10px;background:#fff}.delivery-stats span,.delivery-stats small{display:block;color:#8491a2;font-size:9px}.delivery-stats strong{display:block;margin:6px 0;color:#304158;font-size:21px}.delivery-stats .date{font-size:11px;line-height:23px}.delivery-stats .success strong{color:#16826c}.delivery-stats .danger strong{color:#b3364a}.delivery-error,.delivery-notice{margin:12px 14px 0;padding:9px 11px;border-radius:8px;font-size:10px}.delivery-error{background:#fff0f2;color:#a52e43}.delivery-error button{float:right;border:0;background:transparent;color:inherit}.delivery-notice{background:#eaf8f3;color:#147560}.delivery-toolbar{display:grid;grid-template-columns:1fr 160px 160px 70px;gap:9px;padding:14px;border-bottom:1px solid #e8edf2}.delivery-toolbar input,.delivery-toolbar select{padding:10px;border:1px solid #d7e0e8;border-radius:8px;color:#34445a;font-size:10px}.delivery-empty{padding:58px;color:#8995a5;text-align:center;font-size:11px}.delivery-table-wrap{overflow:auto}.delivery-table-wrap table{width:100%;min-width:980px;border-collapse:collapse}.delivery-table-wrap th{padding:10px 13px;background:#fbfcfd;color:#8793a3;font-size:9px;text-align:left}.delivery-table-wrap td{padding:12px 13px;border-top:1px solid #edf1f4;vertical-align:middle;font-size:10px}.delivery-table-wrap strong,.delivery-table-wrap small,.delivery-table-wrap b,.delivery-table-wrap code,.delivery-table-wrap time{display:block}.delivery-table-wrap td>strong{color:#3d4e65;font-size:11px}.delivery-table-wrap small,.delivery-table-wrap time,.delivery-table-wrap code{margin-top:4px;color:#8995a4;font-size:9px}.delivery-table-wrap code{max-width:240px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.delivery-table-wrap td>button{padding:7px 9px;border:1px solid #d6aeb6;border-radius:7px;background:#fff;color:#a43145;font-size:9px;font-weight:800;cursor:pointer}.delivery-status{display:inline-block;padding:4px 7px;border-radius:999px;background:#edf1f5;color:#627389;font-size:9px;font-weight:900}.delivery-status.delivered{background:#e5f7f1;color:#147862}.delivery-status.dead{background:#ffe7ea;color:#a52f43}.delivery-status.retrying{background:#fff1d9;color:#96600d}.delivery-status.processing{background:#e9efff;color:#4e68bb}.delivery-status.skipped{background:#eff1f3;color:#707985}.failure{max-width:280px;overflow:hidden!important;color:#ad3347!important;text-overflow:ellipsis;white-space:nowrap}.retry-backdrop{position:fixed;z-index:1300;inset:0;display:grid;place-items:center;padding:20px;background:rgba(15,28,44,.58)}.retry-backdrop form{width:min(500px,100%);padding:22px;border-radius:16px;background:#fff;box-shadow:0 24px 70px rgba(10,24,38,.28)}.retry-backdrop header{display:flex;justify-content:space-between}.retry-backdrop header p{margin:0 0 4px;color:#5e75ce;font-size:9px;font-weight:900;letter-spacing:1px}.retry-backdrop h3{margin:0 0 4px}.retry-backdrop header span{color:#8793a3;font-size:10px}.retry-backdrop header button{border:0;background:transparent;color:#7f8b9a;font-size:22px}.retry-context{margin:14px 0;padding:11px;border-radius:8px;background:#f6f8fb;color:#66758a;font-size:10px}.retry-context span{display:block;margin-bottom:4px}.retry-context p{margin:8px 0 0;color:#a33146}.retry-backdrop label{display:grid;gap:6px;color:#596a7e;font-size:10px;font-weight:800}.retry-backdrop textarea{padding:10px;border:1px solid #d7e0e8;border-radius:8px;resize:vertical}.retry-backdrop footer{display:flex;justify-content:flex-end;gap:8px;margin-top:17px}.retry-backdrop footer button{padding:9px 13px;border-radius:8px;font-size:10px;font-weight:900}.cancel{border:1px solid #d6dee6;background:#fff;color:#647287}.confirm{border:0;background:#5e75ce;color:#fff}@media(max-width:900px){.delivery-stats{grid-template-columns:repeat(2,1fr)}.delivery-toolbar{grid-template-columns:1fr 1fr}}@media(max-width:600px){.delivery-heading{align-items:flex-start;flex-direction:column}.delivery-toolbar{grid-template-columns:1fr}}
</style>
