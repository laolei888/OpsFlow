<script setup>
import { computed, onMounted, ref } from 'vue'

const props = defineProps({
  api: { type: Function, required: true },
  isAdmin: { type: Boolean, default: false }
})
const emit = defineEmits(['navigate'])

const loading = ref(false)
const error = ref('')
const workbench = ref(null)
const selectedTypes = ref([])
const priorityFilter = ref('')

const typeOptions = computed(() => [
  ...(props.isAdmin ? [
    { value: 'SERVICE_APPROVAL', label: '服务审批' },
    { value: 'CHANGE_APPROVAL', label: '变更审批' },
    { value: 'POSTMORTEM_REVIEW', label: '复盘评审' }
  ] : []),
  { value: 'FIRING_ALERT', label: '监控告警' },
  { value: 'ACTIVE_INCIDENT', label: '重大事件' },
  { value: 'SLO_BREACH', label: '可靠性违规' },
  ...(props.isAdmin ? [{ value: 'STATUS_DRAFT', label: '公告待发布' }] : [])
])
const summary = computed(() => workbench.value?.summary || {
  total: 0, critical: 0, high: 0, approvals: 0,
  firingAlerts: 0, activeIncidents: 0, reliabilityBreaches: 0, unpublishedNotices: 0, postmortemReviews: 0
})
const items = computed(() => workbench.value?.items || [])
const hasFilters = computed(() => selectedTypes.value.length > 0 || Boolean(priorityFilter.value))

const priorityLabels = { CRITICAL: '紧急', HIGH: '高', MEDIUM: '中', LOW: '低' }
const statusLabels = {
  PENDING_APPROVAL: '待审批', FIRING: '持续触发', DECLARED: '已宣布', INVESTIGATING: '调查中',
  MITIGATING: '缓解中', MONITORING: '观察中', OPEN: '待确认', ACKNOWLEDGED: '处理中',
  IDENTIFIED: '已定位', RESOLVED: '已解决', IN_REVIEW: '待评审'
}

async function loadWorkbench() {
  loading.value = true
  error.value = ''
  try {
    const params = new URLSearchParams({ limit: '150' })
    if (selectedTypes.value.length) params.set('types', selectedTypes.value.join(','))
    if (priorityFilter.value) params.set('priorities', priorityFilter.value)
    workbench.value = await props.api('/api/v1/operations-workbench?' + params.toString())
  } catch (err) {
    error.value = err.message
  } finally {
    loading.value = false
  }
}

function toggleType(type) {
  selectedTypes.value = selectedTypes.value.includes(type)
    ? selectedTypes.value.filter(item => item !== type)
    : [...selectedTypes.value, type]
  loadWorkbench()
}

function resetFilters() {
  selectedTypes.value = []
  priorityFilter.value = ''
  loadWorkbench()
}

function formatDate(value) {
  if (!value) return '-'
  return new Date(value).toLocaleString('zh-CN', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit'
  })
}

function ageLabel(value) {
  if (!value) return '时间未知'
  const minutes = Math.max(0, Math.floor((Date.now() - new Date(value).getTime()) / 60000))
  if (minutes < 60) return minutes + ' 分钟'
  if (minutes < 1440) return Math.floor(minutes / 60) + ' 小时'
  return Math.floor(minutes / 1440) + ' 天'
}

function openItem(item) {
  emit('navigate', item.targetView)
}

onMounted(loadWorkbench)
</script>

<template>
  <section class="operations-workbench">
    <header class="workbench-head">
      <div>
        <p class="eyebrow">UNIFIED OPERATIONS QUEUE</p>
        <h2>运营待办中心</h2>
        <p>跨模块聚合当前真正需要人工处理的事项，按风险和等待时间统一排序。</p>
      </div>
      <button type="button" class="refresh-button" :disabled="loading" @click="loadWorkbench">
        {{ loading ? '同步中…' : '刷新队列' }}
      </button>
    </header>

    <p v-if="error" class="workbench-error">{{ error }}<button type="button" @click="loadWorkbench">重试</button></p>

    <section class="summary-grid">
      <article><span>全部待办</span><strong>{{ summary.total }}</strong><small>当前租户实时聚合</small></article>
      <article class="critical"><span>紧急风险</span><strong>{{ summary.critical }}</strong><small>需要首先响应</small></article>
      <article class="high"><span>高风险</span><strong>{{ summary.high }}</strong><small>尽快纳入处理</small></article>
      <article v-if="isAdmin"><span>等待审批</span><strong>{{ summary.approvals }}</strong><small>服务、变更与复盘评审</small></article>
      <article><span>告警 / 事件</span><strong>{{ summary.firingAlerts + summary.activeIncidents }}</strong><small>技术响应队列</small></article>
      <article><span>可靠性违规</span><strong>{{ summary.reliabilityBreaches }}</strong><small>错误预算待处置</small></article>
    </section>

    <section class="queue-card">
      <div class="queue-toolbar">
        <div>
          <p class="eyebrow">ACTION QUEUE</p>
          <h3>跨模块处理队列</h3>
          <small>严重度相同时，等待时间更长的事项排在前面。</small>
        </div>
        <div class="priority-filter">
          <label>风险等级
            <select v-model="priorityFilter" @change="loadWorkbench">
              <option value="">全部</option>
              <option value="CRITICAL">紧急</option>
              <option value="HIGH">高</option>
              <option value="MEDIUM">中</option>
              <option value="LOW">低</option>
            </select>
          </label>
          <button v-if="hasFilters" type="button" @click="resetFilters">清空筛选</button>
        </div>
      </div>

      <div class="type-filters">
        <button v-for="option in typeOptions" :key="option.value" type="button"
                :class="{ active: selectedTypes.includes(option.value) }" @click="toggleType(option.value)">
          {{ option.label }}
        </button>
      </div>

      <div v-if="loading && !workbench" class="empty-queue">正在聚合各业务模块的待办事项…</div>
      <div v-else-if="!items.length" class="empty-queue">
        <strong>当前筛选范围没有待办</strong>
        <span>所有风险和审批都已进入可控状态。</span>
      </div>
      <div v-else class="work-item-list">
        <article v-for="item in items" :key="item.type + '-' + item.id" :class="['work-item', item.priority.toLowerCase()]">
          <div class="priority-rail"><i></i><span>{{ priorityLabels[item.priority] || item.priority }}</span></div>
          <div class="work-main">
            <div class="work-meta">
              <span class="type-badge">{{ item.typeLabel }}</span>
              <code>{{ item.referenceNo }}</code>
              <span class="status-badge">{{ statusLabels[item.status] || item.status }}</span>
              <span v-if="item.requiresAdmin" class="admin-badge">管理员</span>
            </div>
            <h4>{{ item.title }}</h4>
            <p>{{ item.description }}</p>
            <small>已等待 {{ ageLabel(item.createdAt) }} · 更新于 {{ formatDate(item.updatedAt) }}</small>
          </div>
          <button type="button" class="action-button" @click="openItem(item)">{{ item.actionLabel }} →</button>
        </article>
      </div>
    </section>

    <p class="scope-note">
      {{ isAdmin ? '管理员队列包含审批、技术响应、可靠性违规和公告发布。' : '客服队列只展示当前角色有权处理的技术响应事项。' }}
      所有数据均在服务端按 tenantId 隔离。
    </p>
  </section>
</template>

<style scoped>
.operations-workbench{padding:30px 34px 48px;color:#1d2b40}.workbench-head{display:flex;align-items:flex-end;justify-content:space-between;gap:24px;margin-bottom:22px}.eyebrow{margin:0;color:#6b7d94;font-size:10px;font-weight:800;letter-spacing:.13em}.workbench-head h2{margin:6px 0;font-size:25px;letter-spacing:-.04em}.workbench-head>div>p:last-child{max-width:680px;margin:0;color:#758399;font-size:13px}.refresh-button,.priority-filter button{padding:9px 13px;border:1px solid #d4deea;border-radius:8px;background:#fff;color:#4f6682;font-weight:700;cursor:pointer}.refresh-button:disabled{opacity:.55;cursor:wait}.workbench-error{margin:0 0 16px;padding:11px 13px;border-radius:8px;background:#fff0f1;color:#aa3e4a;font-size:13px}.workbench-error button{float:right;border:0;background:transparent;color:inherit;cursor:pointer}.summary-grid{display:grid;grid-template-columns:repeat(6,1fr);gap:12px;margin-bottom:18px}.summary-grid article{padding:15px;border:1px solid #e1e8f0;border-radius:10px;background:#fff}.summary-grid span,.summary-grid small{display:block;color:#7b899c;font-size:11px}.summary-grid strong{display:block;margin:5px 0;color:#263951;font-size:24px}.summary-grid .critical{border-color:#edc7cd;background:#fff8f9}.summary-grid .critical strong{color:#b53c49}.summary-grid .high{border-color:#efddba;background:#fffaf1}.summary-grid .high strong{color:#a4680c}.queue-card{padding:20px;border:1px solid #e1e8f0;border-radius:12px;background:#fff}.queue-toolbar{display:flex;align-items:flex-start;justify-content:space-between;gap:20px}.queue-toolbar h3{margin:5px 0 2px;font-size:17px}.queue-toolbar small{color:#8491a3;font-size:11px}.priority-filter{display:flex;align-items:flex-end;gap:8px}.priority-filter label{color:#677991;font-size:11px;font-weight:700}.priority-filter select{display:block;margin-top:4px;min-width:110px;padding:7px 9px;border:1px solid #d7e0ea;border-radius:7px;background:#fff;color:#3f536c}.priority-filter button{padding:8px 10px}.type-filters{display:flex;flex-wrap:wrap;gap:7px;margin:17px 0;padding-bottom:15px;border-bottom:1px solid #edf1f5}.type-filters button{padding:6px 10px;border:1px solid #dce4ed;border-radius:999px;background:#f8fafc;color:#677a92;font-size:11px;cursor:pointer}.type-filters button.active{border-color:#7fa5cf;background:#eaf2fb;color:#2d6198;font-weight:800}.empty-queue{display:grid;gap:5px;place-items:center;padding:48px 16px;color:#8290a3;font-size:13px}.empty-queue strong{color:#536780;font-size:15px}.work-item{display:grid;grid-template-columns:64px 1fr auto;gap:14px;align-items:center;padding:16px 0;border-bottom:1px solid #edf1f5}.work-item:last-child{border-bottom:0}.priority-rail{align-self:stretch;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:6px;border-right:1px solid #edf1f5}.priority-rail i{width:9px;height:9px;border-radius:50%;background:#8e9caf}.priority-rail span{color:#77879b;font-size:10px;font-weight:800}.work-item.critical .priority-rail i{background:#c74451}.work-item.critical .priority-rail span{color:#b23946}.work-item.high .priority-rail i{background:#d39223}.work-item.high .priority-rail span{color:#9f6509}.work-item.medium .priority-rail i{background:#4e7cae}.work-main{min-width:0}.work-meta{display:flex;align-items:center;gap:6px;flex-wrap:wrap}.work-meta code{color:#7e8ba0;font-size:10px}.type-badge,.status-badge,.admin-badge{padding:3px 6px;border-radius:5px;font-size:10px;font-weight:800}.type-badge{background:#edf3fa;color:#416b98}.status-badge{background:#f1f4f7;color:#65758b}.admin-badge{background:#f5eefb;color:#77509c}.work-main h4{margin:7px 0 4px;font-size:14px}.work-main p{max-width:780px;margin:0 0 7px;color:#6e7d91;font-size:12px;line-height:1.5}.work-main>small{color:#8a96a6;font-size:10px}.action-button{min-width:105px;padding:8px 11px;border:1px solid #cfdceb;border-radius:7px;background:#f7fafc;color:#3e638d;font-size:12px;font-weight:700;cursor:pointer}.action-button:hover{border-color:#8eafd2;background:#edf4fb}.scope-note{margin:13px 2px 0;color:#8995a5;font-size:11px}.scope-note::before{content:'i';display:inline-grid;place-items:center;width:16px;height:16px;margin-right:6px;border-radius:50%;background:#e8eef5;color:#60748e;font-weight:800}@media(max-width:1180px){.summary-grid{grid-template-columns:repeat(3,1fr)}}@media(max-width:760px){.operations-workbench{padding:22px 16px 36px}.workbench-head,.queue-toolbar{display:block}.refresh-button{margin-top:14px}.summary-grid{grid-template-columns:repeat(2,1fr)}.priority-filter{margin-top:13px}.work-item{grid-template-columns:46px 1fr}.action-button{grid-column:2;justify-self:start}.summary-grid article{padding:12px}}
</style>
