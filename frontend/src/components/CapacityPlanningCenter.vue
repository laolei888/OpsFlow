<script setup>
import { computed, onMounted, ref } from 'vue'

const props = defineProps({ api: { type: Function, required: true } })
const report = ref({ totalSnapshots: 0, latest: null, snapshots: [], trends: [] })
const releases = ref([])
const comparison = ref(null)
const loading = ref(false)
const comparing = ref(false)
const saving = ref(false)
const error = ref('')
const notice = ref('')
const days = ref(30)
const chartMetric = ref('activeTickets')
const baselineId = ref('')
const currentId = ref('')
const showCapture = ref(false)
const captureForm = ref({ label: '', appVersion: '', releaseId: '' })

const levelLabels = { HEALTHY: '健康', WATCH: '需观察', WARNING: '预警', CRITICAL: '已超阈值', INFO: '信息' }
const typeLabels = { SCHEDULED: '定时采样', MANUAL: '手工快照', RELEASE_BASELINE: '发布基线' }
const impactLabels = { IMPROVED: '改善', REGRESSED: '退化', STABLE: '持平', NEUTRAL: '容量变化' }
const snapshotsDesc = computed(() => [...(report.value.snapshots || [])].reverse())
const selectedTrend = computed(() => (report.value.trends || []).find(item => item.key === chartMetric.value))
const chartPoints = computed(() => {
  const snapshots = report.value.snapshots || []
  const values = snapshots.map(item => Number(item[chartMetric.value] || 0))
  const maximum = Math.max(1, ...values)
  return snapshots.map((item, index) => ({
    id: item.id,
    value: values[index],
    height: Math.max(5, Math.round(values[index] / maximum * 100)),
    time: item.capturedAt
  }))
})
const riskTrends = computed(() => (report.value.trends || []).filter(item => item.threshold != null))

async function load(silent = false) {
  if (!silent) loading.value = true
  error.value = ''
  try {
    const [nextReport, releaseCenter] = await Promise.all([
      props.api('/api/v1/admin/capacity/report?days=' + days.value),
      props.api('/api/v1/releases')
    ])
    report.value = nextReport || { totalSnapshots: 0, latest: null, snapshots: [], trends: [] }
    releases.value = releaseCenter?.releases || []
    chooseComparisonDefaults()
  } catch (exception) {
    error.value = exception.message || '容量规划数据加载失败'
  } finally {
    loading.value = false
  }
}

function chooseComparisonDefaults() {
  const list = snapshotsDesc.value
  if (!list.length) return
  if (!currentId.value || !list.some(item => String(item.id) === String(currentId.value))) currentId.value = String(list[0].id)
  const baseline = list.find(item => item.captureType === 'RELEASE_BASELINE' && String(item.id) !== String(currentId.value))
    || list.find(item => String(item.id) !== String(currentId.value))
  if (baseline && (!baselineId.value || !list.some(item => String(item.id) === String(baselineId.value)))) baselineId.value = String(baseline.id)
}

async function capture() {
  saving.value = true
  error.value = ''
  try {
    await props.api('/api/v1/admin/capacity/snapshots', {
      method: 'POST',
      body: JSON.stringify({
        label: captureForm.value.label.trim() || null,
        appVersion: captureForm.value.appVersion.trim() || null,
        releaseId: captureForm.value.releaseId ? Number(captureForm.value.releaseId) : null
      })
    })
    showCapture.value = false
    captureForm.value = { label: '', appVersion: '', releaseId: '' }
    baselineId.value = ''
    currentId.value = ''
    comparison.value = null
    notice.value = '容量快照已保存，现在可以用于基线比较'
    window.setTimeout(() => { notice.value = '' }, 3500)
    await load(true)
  } catch (exception) {
    error.value = exception.message || '创建容量快照失败'
  } finally {
    saving.value = false
  }
}

async function compare() {
  if (!baselineId.value || !currentId.value || baselineId.value === currentId.value) {
    error.value = '请选择两个不同的容量快照'
    return
  }
  comparing.value = true
  error.value = ''
  try {
    comparison.value = await props.api('/api/v1/admin/capacity/comparison?baselineId=' + baselineId.value + '&currentId=' + currentId.value)
  } catch (exception) {
    error.value = exception.message || '版本基线比较失败'
  } finally {
    comparing.value = false
  }
}

function metricValue(metric, value) {
  if (metric?.unit === 'bytes') return formatSize(value)
  return Number(value || 0).toLocaleString('zh-CN') + (metric?.unit || '')
}
function formatSize(value) {
  const bytes = Number(value || 0)
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 ** 2) return (bytes / 1024).toFixed(1) + ' KB'
  if (bytes < 1024 ** 3) return (bytes / 1024 ** 2).toFixed(1) + ' MB'
  return (bytes / 1024 ** 3).toFixed(2) + ' GB'
}
function formatDate(value) {
  if (!value) return '—'
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value))
}
function releaseLabel(id) {
  const release = releases.value.find(item => String(item.id) === String(id))
  return release ? release.releaseNo + ' · ' + release.name : id ? '发布 #' + id : '未关联发布'
}
function snapshotLabel(item) {
  return item.appVersion + ' · ' + item.label + ' · ' + formatDate(item.capturedAt)
}
function signed(value) {
  const number = Number(value || 0)
  return number > 0 ? '+' + number : String(number)
}

onMounted(() => load())
</script>

<template>
  <section class="capacity-center">
    <header class="capacity-heading">
      <div><p>CAPACITY &amp; RELEASE BASELINE</p><h2>容量规划中心</h2><span>持续采集业务与运行指标，预测阈值风险，并量化比较发布前后的容量变化。</span></div>
      <div class="heading-actions"><select v-model="days" @change="load()"><option :value="7">近 7 天</option><option :value="30">近 30 天</option><option :value="90">近 90 天</option><option :value="180">近 180 天</option></select><button class="secondary" :disabled="loading" @click="load()">{{ loading ? '同步中…' : '刷新' }}</button><button @click="showCapture = true">＋ 创建快照</button></div>
    </header>

    <p v-if="error" class="capacity-error">{{ error }} <button @click="error = ''">×</button></p>
    <p v-if="notice" class="capacity-notice">✓ {{ notice }}</p>

    <div v-if="loading && !report.latest" class="capacity-empty">正在汇总容量指标…</div>
    <div v-else-if="!report.latest" class="capacity-empty"><strong>尚无容量快照</strong><span>点击“创建快照”立即建立第一份基线；系统之后会按小时自动采样。</span><button @click="showCapture = true">创建第一份快照</button></div>
    <template v-else>
      <div class="capacity-stats">
        <article><span>活跃工单</span><strong>{{ report.latest.activeTickets }}</strong><small>总计 {{ report.latest.totalTickets }} 张</small></article>
        <article :class="{ warning: report.latest.overdueTickets > 0 }"><span>逾期工单</span><strong>{{ report.latest.overdueTickets }}</strong><small>需要优先清理</small></article>
        <article><span>24h 吞吐</span><strong>{{ report.latest.ticketsResolved24h }}<i>/ {{ report.latest.ticketsCreated24h }}</i></strong><small>解决 / 新增</small></article>
        <article><span>运行风险</span><strong>{{ report.latest.activeIncidents + report.latest.firingAlerts }}</strong><small>{{ report.latest.activeIncidents }} 起事件 · {{ report.latest.firingAlerts }} 条告警</small></article>
        <article><span>消息 / 扫描积压</span><strong>{{ report.latest.asyncBacklog }}<i>/ {{ report.latest.attachmentBacklog }}</i></strong><small>异步消息 / 附件</small></article>
        <article><span>数据占用</span><strong class="size">{{ formatSize(report.latest.databaseBytes) }}</strong><small>附件 {{ formatSize(report.latest.attachmentBytes) }}</small></article>
      </div>

      <div class="capacity-layout">
        <section class="panel trend-panel">
          <div class="panel-heading"><div><p>TREND WINDOW</p><h3>连续容量趋势</h3><small>{{ report.totalSnapshots }} 份原始快照 · 图表最多抽样 180 个点</small></div><select v-model="chartMetric"><option v-for="metric in report.trends" :key="metric.key" :value="metric.key">{{ metric.label }}</option></select></div>
          <div class="trend-summary" v-if="selectedTrend"><div><span>窗口起点</span><b>{{ metricValue(selectedTrend, selectedTrend.startValue) }}</b></div><div><span>当前值</span><b>{{ metricValue(selectedTrend, selectedTrend.currentValue) }}</b></div><div><span>变化</span><b :class="{ negative: selectedTrend.delta < 0, positive: selectedTrend.delta > 0 }">{{ signed(selectedTrend.delta) }} {{ selectedTrend.unit === 'bytes' ? 'bytes' : selectedTrend.unit }}</b></div><div><span>每日增速</span><b>{{ signed(selectedTrend.dailyGrowth) }}</b></div></div>
          <div class="capacity-chart"><div v-for="point in chartPoints" :key="point.id" class="chart-point" :title="formatDate(point.time) + ' · ' + metricValue(selectedTrend, point.value)"><i :style="{ height: point.height + '%' }"></i></div></div>
          <div class="chart-axis"><span>{{ formatDate(report.snapshots[0]?.capturedAt) }}</span><span>{{ formatDate(report.latest.capturedAt) }}</span></div>
        </section>

        <section class="panel risk-panel">
          <div class="panel-heading"><div><p>THRESHOLD FORECAST</p><h3>阈值与容量预测</h3><small>按当前窗口的线性增速估算</small></div></div>
          <div class="risk-list"><article v-for="metric in riskTrends" :key="metric.key"><div><strong>{{ metric.label }}</strong><small>{{ metricValue(metric, metric.currentValue) }} / {{ metricValue(metric, metric.threshold) }}</small></div><span :class="['level', String(metric.level).toLowerCase()]">{{ levelLabels[metric.level] }}</span><div class="risk-progress"><i :style="{ width: Math.min(100, metric.utilizationPercent || 0) + '%' }"></i></div><footer><b>{{ metric.utilizationPercent || 0 }}%</b><small>{{ metric.daysToLimit == null ? '当前趋势暂无可预测触顶时间' : '预计 ' + metric.daysToLimit + ' 天后触顶' }}</small></footer></article></div>
        </section>
      </div>

      <section class="panel comparison-panel">
        <div class="panel-heading"><div><p>BASELINE DIFF</p><h3>版本基线对比</h3><small>选择发布基线与当前快照，识别吞吐改善或积压退化</small></div><div class="compare-controls"><select v-model="baselineId"><option value="">选择基线</option><option v-for="item in snapshotsDesc" :key="item.id" :value="String(item.id)">{{ snapshotLabel(item) }}</option></select><span>→</span><select v-model="currentId"><option value="">选择当前快照</option><option v-for="item in snapshotsDesc" :key="item.id" :value="String(item.id)">{{ snapshotLabel(item) }}</option></select><button :disabled="comparing" @click="compare">{{ comparing ? '比较中…' : '开始比较' }}</button></div></div>
        <div v-if="!comparison" class="comparison-empty">选择两个不同时间点，生成逐项容量变化报告。</div>
        <template v-else><div class="comparison-banner"><div><span>基线</span><strong>{{ comparison.baseline.appVersion }}</strong><small>{{ formatDate(comparison.baseline.capturedAt) }}</small></div><b>对比</b><div><span>当前</span><strong>{{ comparison.current.appVersion }}</strong><small>{{ formatDate(comparison.current.capturedAt) }}</small></div><aside><span class="improved">{{ comparison.improvedMetrics }} 项改善</span><span class="regressed">{{ comparison.regressedMetrics }} 项退化</span></aside></div><div class="comparison-table"><div class="comparison-row heading"><span>指标</span><span>基线</span><span>当前</span><span>变化</span><span>判断</span></div><div v-for="metric in comparison.metrics" :key="metric.key" class="comparison-row"><strong>{{ metric.label }}</strong><span>{{ metricValue(metric, metric.baselineValue) }}</span><span>{{ metricValue(metric, metric.currentValue) }}</span><b>{{ signed(metric.delta) }}<small v-if="metric.changePercent != null">{{ signed(metric.changePercent) }}%</small></b><em :class="String(metric.impact).toLowerCase()">{{ impactLabels[metric.impact] }}</em></div></div></template>
      </section>

      <section class="panel snapshot-panel">
        <div class="panel-heading"><div><p>SNAPSHOT LEDGER</p><h3>容量快照台账</h3><small>发布基线长期保留，普通采样按保留周期自动清理</small></div><span>{{ report.totalSnapshots }} 份</span></div>
        <div class="snapshot-table"><table><thead><tr><th>时间 / 版本</th><th>类型</th><th>业务负载</th><th>运行积压</th><th>存储</th><th>关联发布</th></tr></thead><tbody><tr v-for="item in snapshotsDesc.slice(0, 50)" :key="item.id"><td><strong>{{ item.appVersion }}</strong><small>{{ item.label }} · {{ formatDate(item.capturedAt) }}</small></td><td><span :class="['snapshot-type', String(item.captureType).toLowerCase()]">{{ typeLabels[item.captureType] }}</span></td><td><b>{{ item.activeTickets }} 活跃 / {{ item.overdueTickets }} 逾期</b><small>24h 新增 {{ item.ticketsCreated24h }} · 解决 {{ item.ticketsResolved24h }}</small></td><td><b>{{ item.activeIncidents }} 事件 / {{ item.firingAlerts }} 告警</b><small>{{ item.asyncBacklog }} 消息 · {{ item.attachmentBacklog }} 扫描</small></td><td><b>{{ formatSize(item.databaseBytes) }}</b><small>附件 {{ formatSize(item.attachmentBytes) }}</small></td><td>{{ releaseLabel(item.releaseId) }}</td></tr></tbody></table></div>
      </section>
    </template>

    <div v-if="showCapture" class="capacity-backdrop" @click.self="showCapture = false"><form @submit.prevent="capture"><header><div><p>CAPTURE BASELINE</p><h3>创建容量快照</h3><span>关联发布后将作为长期保留的发布基线。</span></div><button type="button" @click="showCapture = false">×</button></header><label>关联发布（可选）<select v-model="captureForm.releaseId"><option value="">普通手工快照</option><option v-for="release in releases" :key="release.id" :value="String(release.id)">{{ release.releaseNo }} · {{ release.name }}</option></select></label><label>快照名称<input v-model="captureForm.label" maxlength="120" placeholder="例如：双十一扩容前基线" /></label><label>应用版本<input v-model="captureForm.appVersion" maxlength="64" placeholder="例如：v1.8.0；留空则使用环境版本" /></label><aside>快照会记录工单吞吐、SLA 积压、事件告警、消息队列、附件扫描、数据库与 JVM 内存等指标。</aside><footer><button type="button" class="cancel" @click="showCapture = false">取消</button><button :disabled="saving">{{ saving ? '采集中…' : '创建快照' }}</button></footer></form></div>
  </section>
</template>

<style scoped>
.capacity-center{overflow:hidden;border:1px solid #e1e8ef;border-radius:14px;background:#f7f9fc;color:#2c3d53}.capacity-heading{display:flex;align-items:center;justify-content:space-between;gap:20px;padding:22px;background:linear-gradient(120deg,#f0f6ff,#f2fbf8)}.capacity-heading p,.panel-heading p,.capacity-backdrop header p{margin:0 0 5px;color:#4b6fc5;font-size:9px;font-weight:900;letter-spacing:1px}.capacity-heading h2,.panel-heading h3,.capacity-backdrop h3{margin:0 0 5px}.capacity-heading h2{font-size:18px}.capacity-heading span,.panel-heading small,.capacity-backdrop header span{color:#7e8c9e;font-size:10px}.heading-actions,.compare-controls{display:flex;align-items:center;gap:8px}.heading-actions select,.heading-actions button,.compare-controls select,.compare-controls button{padding:9px 11px;border:1px solid #d3dce6;border-radius:8px;background:#fff;color:#42536b;font-size:10px}.heading-actions button,.compare-controls button{border-color:#5570c8;background:#5570c8;color:#fff;font-weight:900}.heading-actions .secondary{background:#fff;color:#5570c8}.capacity-error,.capacity-notice{margin:12px 14px 0;padding:9px 11px;border-radius:8px;font-size:10px}.capacity-error{background:#fff0f2;color:#a42e43}.capacity-error button{float:right;border:0;background:transparent;color:inherit}.capacity-notice{background:#e8f8f2;color:#14765f}.capacity-empty{display:grid;place-items:center;gap:8px;min-height:310px;color:#8491a2;font-size:11px}.capacity-empty strong{color:#40526a;font-size:16px}.capacity-empty button{padding:9px 13px;border:0;border-radius:8px;background:#5872c9;color:#fff}.capacity-stats{display:grid;grid-template-columns:repeat(6,1fr);gap:10px;padding:14px}.capacity-stats article{padding:13px;border:1px solid #e1e8ef;border-radius:10px;background:#fff}.capacity-stats span,.capacity-stats small{display:block;color:#8491a2;font-size:9px}.capacity-stats strong{display:block;margin:7px 0;color:#304158;font-size:21px}.capacity-stats strong i{color:#8997a8;font-size:12px;font-style:normal}.capacity-stats .size{font-size:15px;line-height:25px}.capacity-stats .warning strong{color:#b14a35}.capacity-layout{display:grid;grid-template-columns:minmax(0,1.25fr) minmax(360px,.75fr);gap:12px;padding:0 14px 12px}.panel{border:1px solid #e1e8ef;border-radius:11px;background:#fff}.panel-heading{display:flex;align-items:center;justify-content:space-between;gap:14px;padding:15px;border-bottom:1px solid #e9eef3}.panel-heading h3{font-size:14px}.panel-heading>select{padding:8px;border:1px solid #d5dee7;border-radius:7px;font-size:10px}.trend-summary{display:grid;grid-template-columns:repeat(4,1fr);gap:8px;padding:13px}.trend-summary div{padding:10px;border-radius:8px;background:#f7f9fc}.trend-summary span,.trend-summary b{display:block}.trend-summary span{color:#8693a3;font-size:9px}.trend-summary b{margin-top:5px;font-size:12px}.trend-summary .negative{color:#17806a}.trend-summary .positive{color:#a65c28}.capacity-chart{display:flex;align-items:flex-end;gap:2px;height:150px;padding:15px 15px 0}.chart-point{display:flex;align-items:flex-end;flex:1;height:100%;min-width:2px}.chart-point i{width:100%;min-height:3px;border-radius:3px 3px 0 0;background:linear-gradient(#5f7bd3,#8ca1e3)}.chart-axis{display:flex;justify-content:space-between;padding:7px 15px 13px;color:#8a97a7;font-size:9px}.risk-list{display:grid;gap:9px;padding:13px}.risk-list article{display:grid;grid-template-columns:1fr auto;gap:7px;padding:10px;border:1px solid #e8edf2;border-radius:8px}.risk-list strong,.risk-list small{display:block}.risk-list strong{font-size:10px}.risk-list small{margin-top:3px;color:#8794a5;font-size:9px}.level{padding:4px 7px;border-radius:999px;background:#eef2f6;color:#67778c;font-size:8px;font-weight:900}.level.healthy{background:#e5f7f0;color:#17765f}.level.watch{background:#eef0ff;color:#5f64a9}.level.warning{background:#fff1d9;color:#97610d}.level.critical{background:#ffe5e9;color:#a72e43}.risk-progress{grid-column:1/-1;height:5px;border-radius:6px;background:#edf1f5}.risk-progress i{display:block;height:100%;border-radius:inherit;background:#6d86d3}.risk-list footer{display:flex;grid-column:1/-1;justify-content:space-between}.comparison-panel,.snapshot-panel{margin:0 14px 12px}.compare-controls select{max-width:240px}.comparison-empty{padding:38px;color:#8a96a5;text-align:center;font-size:10px}.comparison-banner{display:grid;grid-template-columns:1fr auto 1fr 1fr;align-items:center;gap:15px;padding:14px;background:#f8fafc}.comparison-banner>div span,.comparison-banner>div small{display:block;color:#8794a4;font-size:9px}.comparison-banner>div strong{display:block;margin:4px 0}.comparison-banner>aside{display:flex;justify-content:flex-end;gap:7px}.comparison-banner aside span{padding:6px 8px;border-radius:999px;font-size:9px;font-weight:900}.improved{background:#e5f7f0;color:#14775f}.regressed{background:#ffe7ea;color:#a42f43}.comparison-table{overflow:auto}.comparison-row{display:grid;grid-template-columns:1.3fr repeat(4,1fr);align-items:center;min-width:760px;padding:10px 14px;border-top:1px solid #edf1f4;font-size:10px}.comparison-row.heading{border:0;background:#fbfcfd;color:#8793a3;font-size:9px}.comparison-row b small{display:block;color:#8a97a7}.comparison-row em{justify-self:start;padding:4px 7px;border-radius:999px;font-size:8px;font-style:normal;font-weight:900}.comparison-row em.improved{background:#e5f7f0;color:#14775f}.comparison-row em.regressed{background:#ffe7ea;color:#a42f43}.comparison-row em.stable{background:#eef2f6;color:#65758a}.comparison-row em.neutral{background:#edf0ff;color:#5866ad}.snapshot-table{overflow:auto}.snapshot-table table{width:100%;min-width:1000px;border-collapse:collapse}.snapshot-table th,.snapshot-table td{padding:10px 13px;text-align:left}.snapshot-table th{background:#fbfcfd;color:#8793a3;font-size:9px}.snapshot-table td{border-top:1px solid #edf1f4;font-size:10px}.snapshot-table strong,.snapshot-table small,.snapshot-table b{display:block}.snapshot-table small{margin-top:4px;color:#8995a4;font-size:9px}.snapshot-type{padding:4px 7px;border-radius:999px;background:#eef2f6;color:#65758b;font-size:8px;font-weight:900}.snapshot-type.release_baseline{background:#eeeaff;color:#6b54ab}.snapshot-type.manual{background:#e8f4ff;color:#3f6f9b}.capacity-backdrop{position:fixed;z-index:1400;inset:0;display:grid;place-items:center;padding:20px;background:rgba(14,27,43,.62)}.capacity-backdrop form{width:min(540px,100%);padding:22px;border-radius:15px;background:#fff;box-shadow:0 24px 70px rgba(10,24,38,.3)}.capacity-backdrop header{display:flex;justify-content:space-between}.capacity-backdrop header button{border:0;background:transparent;color:#7f8b9a;font-size:22px}.capacity-backdrop label{display:grid;gap:6px;margin-top:13px;color:#596a7e;font-size:10px;font-weight:800}.capacity-backdrop input,.capacity-backdrop select{padding:10px;border:1px solid #d7e0e8;border-radius:8px}.capacity-backdrop aside{margin-top:14px;padding:10px;border-radius:8px;background:#f4f7fb;color:#6f7e91;font-size:9px;line-height:1.6}.capacity-backdrop footer{display:flex;justify-content:flex-end;gap:8px;margin-top:17px}.capacity-backdrop footer button{padding:9px 13px;border:0;border-radius:8px;background:#5872c9;color:#fff;font-size:10px;font-weight:900}.capacity-backdrop footer .cancel{border:1px solid #d6dee6;background:#fff;color:#647287}@media(max-width:1100px){.capacity-stats{grid-template-columns:repeat(3,1fr)}.capacity-layout{grid-template-columns:1fr}}@media(max-width:720px){.capacity-heading,.panel-heading{align-items:flex-start;flex-direction:column}.heading-actions,.compare-controls{flex-wrap:wrap}.capacity-stats{grid-template-columns:repeat(2,1fr)}.trend-summary{grid-template-columns:repeat(2,1fr)}}
</style>
