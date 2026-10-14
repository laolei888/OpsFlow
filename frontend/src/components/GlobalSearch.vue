<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'

const props = defineProps({ api: { type: Function, required: true } })
const emit = defineEmits(['select'])

const opened = ref(false)
const query = ref('')
const loading = ref(false)
const error = ref('')
const resultView = ref(null)
const activeType = ref('')
const activeIndex = ref(0)
const searchInput = ref(null)
let debounceTimer
let requestSequence = 0

const results = computed(() => resultView.value?.results || [])
const displayedResults = computed(() => activeType.value
  ? results.value.filter(item => item.type === activeType.value)
  : results.value)
const groups = computed(() => resultView.value?.groups || [])

const statusLabels = {
  OPEN: '待处理', IN_PROGRESS: '处理中', WAITING_CONFIRMATION: '待确认', RESOLVED: '已解决', CLOSED: '已关闭', REJECTED: '已驳回',
  DRAFT: '草稿', PUBLISHED: '已发布', ARCHIVED: '已归档', ACTIVE: '运行中', MAINTENANCE: '维护中', RETIRED: '已退役',
  PENDING_APPROVAL: '待审批', APPROVED: '已批准', FAILED: '失败', CANCELLED: '已取消',
  FIRING: '持续触发', ACKNOWLEDGED: '已确认', SUPPRESSED: '已抑制',
  DECLARED: '已宣布', INVESTIGATING: '调查中', MITIGATING: '缓解中', MONITORING: '观察中',
  KNOWN_ERROR: '已知错误', PENDING_CHANGE: '等待变更'
}
const typeIcons = { TICKET: '◫', KNOWLEDGE: '▤', CMDB_ASSET: '▦', CHANGE: '↗', PROBLEM: '⌁', ALERT: '!', INCIDENT: '⚑', POSTMORTEM: '◈' }

async function openSearch() {
  opened.value = true
  error.value = ''
  activeIndex.value = 0
  await nextTick()
  searchInput.value?.focus()
}

function closeSearch() {
  opened.value = false
  activeType.value = ''
  activeIndex.value = 0
}

function scheduleSearch() {
  clearTimeout(debounceTimer)
  activeType.value = ''
  activeIndex.value = 0
  if (query.value.trim().length < 2) {
    resultView.value = null
    loading.value = false
    error.value = ''
    return
  }
  debounceTimer = setTimeout(runSearch, 260)
}

async function runSearch() {
  const keyword = query.value.trim()
  if (keyword.length < 2) return
  const sequence = ++requestSequence
  loading.value = true
  error.value = ''
  try {
    const response = await props.api('/api/v1/search?q=' + encodeURIComponent(keyword) + '&limit=40')
    if (sequence !== requestSequence) return
    resultView.value = response
    activeIndex.value = 0
  } catch (err) {
    if (sequence === requestSequence) error.value = err.message
  } finally {
    if (sequence === requestSequence) loading.value = false
  }
}

function chooseType(type) {
  activeType.value = activeType.value === type ? '' : type
  activeIndex.value = 0
}

function selectResult(result) {
  closeSearch()
  emit('select', result)
}

function moveSelection(delta) {
  if (!displayedResults.value.length) return
  activeIndex.value = (activeIndex.value + delta + displayedResults.value.length) % displayedResults.value.length
}

function chooseActive() {
  const result = displayedResults.value[activeIndex.value]
  if (result) selectResult(result)
}

function handleGlobalShortcut(event) {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault()
    opened.value ? closeSearch() : openSearch()
  } else if (opened.value && event.key === 'Escape') {
    closeSearch()
  }
}

function formatDate(value) {
  if (!value) return '-'
  return new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}

onMounted(() => window.addEventListener('keydown', handleGlobalShortcut))
onUnmounted(() => {
  window.removeEventListener('keydown', handleGlobalShortcut)
  clearTimeout(debounceTimer)
})

defineExpose({ openSearch })
</script>

<template>
  <div v-if="opened" class="search-backdrop" @click.self="closeSearch">
    <section class="search-dialog" role="dialog" aria-modal="true" aria-label="全局搜索">
      <header class="search-box">
        <span>⌕</span>
        <input ref="searchInput" v-model="query" placeholder="搜索工单、知识、资产、变更、问题、告警、复盘…"
               @input="scheduleSearch" @keydown.down.prevent="moveSelection(1)"
               @keydown.up.prevent="moveSelection(-1)" @keydown.enter.prevent="chooseActive" />
        <i v-if="loading"></i>
        <kbd>ESC</kbd>
      </header>

      <div v-if="groups.length" class="search-groups">
        <button :class="{ active: !activeType }" @click="chooseType('')">全部 <b>{{ resultView.total }}</b></button>
        <button v-for="group in groups" :key="group.type" :class="{ active: activeType === group.type }"
                @click="chooseType(group.type)">{{ group.label }} <b>{{ group.count }}</b></button>
      </div>

      <main class="search-results">
        <div v-if="error" class="search-message error">{{ error }}<button @click="runSearch">重试</button></div>
        <div v-else-if="query.trim().length < 2" class="search-welcome">
          <div class="welcome-mark">OF</div>
          <strong>搜索整个 OpsFlow 工作区</strong>
          <p>输入至少两个字符。系统会根据标题、编号、内容和更新时间计算相关度。</p>
          <div><span>普通用户</span>仅自己的工单与已发布知识</div>
          <div><span>客服 / 管理员</span>扩展到运维领域数据</div>
        </div>
        <div v-else-if="!loading && resultView && !displayedResults.length" class="search-message">
          没有找到“{{ query.trim() }}”相关记录
        </div>
        <div v-else class="result-list">
          <button v-for="(result, index) in displayedResults" :key="result.type + '-' + result.id"
                  :class="['search-result', { active: activeIndex === index }]"
                  @mouseenter="activeIndex = index" @click="selectResult(result)">
            <span class="result-icon">{{ typeIcons[result.type] || '·' }}</span>
            <span class="result-main">
              <span class="result-meta"><b>{{ result.typeLabel }}</b><code>{{ result.referenceNo }}</code><em>{{ statusLabels[result.status] || result.status }}</em></span>
              <strong>{{ result.title }}</strong>
              <small>{{ result.snippet }}</small>
            </span>
            <span class="result-side"><time>{{ formatDate(result.updatedAt) }}</time><i>打开 →</i></span>
          </button>
        </div>
      </main>

      <footer class="search-footer">
        <span><kbd>↑</kbd><kbd>↓</kbd> 选择</span><span><kbd>Enter</kbd> 打开</span><span><kbd>Ctrl K</kbd> 搜索</span>
        <b v-if="resultView">共匹配 {{ resultView.total }} 项，当前展示 {{ displayedResults.length }} 项</b>
      </footer>
    </section>
  </div>
</template>

<style scoped>
.search-backdrop{position:fixed;inset:0;z-index:80;display:grid;place-items:start center;padding:9vh 20px 20px;background:rgba(14,25,45,.55);backdrop-filter:blur(4px)}.search-dialog{width:min(760px,100%);max-height:min(760px,82vh);display:flex;flex-direction:column;overflow:hidden;border:1px solid #dce3ed;border-radius:16px;background:#fff;box-shadow:0 28px 90px rgba(10,24,48,.36)}.search-box{display:grid;grid-template-columns:28px 1fr auto auto;align-items:center;gap:10px;padding:15px 17px;border-bottom:1px solid #e8edf3}.search-box>span{color:#657ee7;font-size:24px;text-align:center}.search-box input{margin:0;padding:6px 2px;border:0;border-radius:0;box-shadow:none;color:#23324a;font-size:15px}.search-box input:focus{border:0;box-shadow:none}.search-box kbd,.search-footer kbd{padding:3px 6px;border:1px solid #dce3ec;border-bottom-width:2px;border-radius:5px;background:#f7f9fb;color:#7c899b;font-family:Consolas,monospace;font-size:9px}.search-box>i{width:15px;height:15px;border:2px solid #d6deea;border-top-color:#657ee7;border-radius:50%;animation:search-spin .8s linear infinite}@keyframes search-spin{to{transform:rotate(360deg)}}.search-groups{display:flex;gap:6px;overflow-x:auto;padding:10px 14px;border-bottom:1px solid #edf1f5;background:#fbfcfe}.search-groups button{flex:0 0 auto;padding:5px 8px;border:1px solid #e0e6ee;border-radius:999px;background:#fff;color:#6f7f94;font-size:10px}.search-groups button.active{border-color:#98acec;background:#edf0ff;color:#526bd0}.search-groups b{margin-left:3px}.search-results{min-height:280px;overflow-y:auto}.search-welcome{display:grid;justify-items:center;padding:42px 24px;color:#7e8ca0;text-align:center}.welcome-mark{width:46px;height:46px;display:grid;place-items:center;margin-bottom:15px;border-radius:13px;background:#14213d;color:#d9ff63;font-weight:900}.search-welcome strong{color:#35445b;font-size:15px}.search-welcome p{max-width:460px;margin:8px 0 18px;font-size:11px;line-height:1.7}.search-welcome>div:not(.welcome-mark){display:grid;grid-template-columns:105px 1fr;width:min(430px,100%);padding:8px 0;border-top:1px solid #edf1f5;text-align:left;font-size:10px}.search-welcome span{color:#536a87;font-weight:800}.search-message{padding:70px 20px;color:#8995a6;text-align:center;font-size:12px}.search-message.error{color:#b3434d}.search-message button{margin-left:10px;color:#657ee7;background:transparent;font-weight:700}.result-list{padding:7px}.search-result{width:100%;display:grid;grid-template-columns:38px 1fr auto;gap:11px;align-items:center;padding:12px;border-radius:10px;background:#fff;color:inherit;text-align:left}.search-result.active{background:#f1f4ff}.result-icon{width:34px;height:34px;display:grid;place-items:center;border-radius:9px;background:#e9edff;color:#5c73d5;font-size:15px;font-weight:800}.result-main{min-width:0}.result-meta{display:flex;align-items:center;gap:6px}.result-meta b,.result-meta em{padding:2px 5px;border-radius:4px;font-size:9px;font-style:normal}.result-meta b{background:#edf1f7;color:#5f728a}.result-meta code{color:#8c98a8;font-size:9px}.result-meta em{background:#f5f7fa;color:#75849a}.result-main>strong,.result-main>small{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.result-main>strong{margin-top:5px;color:#34425a;font-size:12px}.result-main>small{margin-top:4px;color:#8b97a8;font-size:10px}.result-side{display:grid;justify-items:end;gap:7px}.result-side time{color:#a0a9b7;font-size:9px}.result-side i{color:#657ee7;font-size:10px;font-style:normal;font-weight:700}.search-footer{display:flex;align-items:center;gap:14px;padding:10px 15px;border-top:1px solid #e9edf3;background:#fafbfd;color:#8a97a8;font-size:9px}.search-footer span{display:flex;align-items:center;gap:3px}.search-footer b{margin-left:auto;color:#7c899b;font-weight:500}@media(max-width:620px){.search-backdrop{padding:20px 10px}.search-dialog{max-height:calc(100vh - 40px)}.search-result{grid-template-columns:34px 1fr}.result-side{display:none}.search-footer span:nth-child(3),.search-footer b{display:none}}
</style>
