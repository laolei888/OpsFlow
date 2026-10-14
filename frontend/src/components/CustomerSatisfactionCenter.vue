<script setup>
import { computed, onMounted, reactive, ref } from 'vue'

const props = defineProps({
  api: { type: Function, required: true },
  isStaff: { type: Boolean, default: false }
})

const loading = ref(false)
const error = ref('')
const toast = ref('')
const surveys = ref([])
const analytics = ref(null)
const days = ref(30)
const submittingTicketId = ref(null)
const forms = reactive({})

const tagOptions = [
  { value: 'FAST_RESPONSE', label: '响应迅速', tone: 'positive' },
  { value: 'PROFESSIONAL', label: '处理专业', tone: 'positive' },
  { value: 'CLEAR_COMMUNICATION', label: '沟通清晰', tone: 'positive' },
  { value: 'SOLVED_COMPLETELY', label: '彻底解决', tone: 'positive' },
  { value: 'SLOW_RESPONSE', label: '响应较慢', tone: 'negative' },
  { value: 'POOR_COMMUNICATION', label: '沟通不足', tone: 'negative' },
  { value: 'NOT_RESOLVED', label: '问题未解决', tone: 'negative' },
  { value: 'REPEATED_ISSUE', label: '问题反复', tone: 'negative' }
]

const pendingSurveys = computed(() => surveys.value.filter(item => item.eligible))
const completedSurveys = computed(() => surveys.value.filter(item => !item.eligible))
const maxDistribution = computed(() => Math.max(1, ...(Object.values(analytics.value?.distribution || {}))))

function formFor(ticketId) {
  if (!forms[ticketId]) forms[ticketId] = { rating: 0, tags: [], comment: '' }
  return forms[ticketId]
}

function toggleTag(ticketId, tag) {
  const form = formFor(ticketId)
  const index = form.tags.indexOf(tag)
  if (index >= 0) form.tags.splice(index, 1)
  else if (form.tags.length < 5) form.tags.push(tag)
}

function tagLabel(tag) {
  return tagOptions.find(item => item.value === tag)?.label || tag
}

function formatDate(value) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '-'
}

function distributionWidth(count) {
  return `${Math.max(count ? 8 : 0, Math.round((count / maxDistribution.value) * 100))}%`
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const requests = [props.api('/api/v1/satisfaction/my-surveys?limit=100')]
    if (props.isStaff) requests.push(props.api(`/api/v1/satisfaction/analytics?days=${days.value}&limit=50`))
    const [mine, quality] = await Promise.all(requests)
    surveys.value = mine || []
    analytics.value = quality || null
    pendingSurveys.value.forEach(item => formFor(item.ticketId))
  } catch (err) {
    error.value = err.message
  } finally {
    loading.value = false
  }
}

async function changePeriod() {
  if (!props.isStaff) return
  loading.value = true
  error.value = ''
  try {
    analytics.value = await props.api(`/api/v1/satisfaction/analytics?days=${days.value}&limit=50`)
  } catch (err) {
    error.value = err.message
  } finally {
    loading.value = false
  }
}

async function submitSurvey(survey) {
  const form = formFor(survey.ticketId)
  if (!form.rating) {
    error.value = '请先选择 1-5 星评分'
    return
  }
  submittingTicketId.value = survey.ticketId
  error.value = ''
  try {
    await props.api(`/api/v1/satisfaction/tickets/${survey.ticketId}`, {
      method: 'POST',
      body: JSON.stringify({ rating: form.rating, tags: form.tags, comment: form.comment })
    })
    toast.value = '评价已提交，感谢你的反馈'
    setTimeout(() => { toast.value = '' }, 2800)
    await load()
  } catch (err) {
    error.value = err.message
  } finally {
    submittingTicketId.value = null
  }
}

onMounted(load)
</script>

<template>
  <section class="view-panel satisfaction-view">
    <div class="satisfaction-hero">
      <div>
        <span class="satisfaction-kicker">CUSTOMER SATISFACTION · CSAT</span>
        <h2>让每一次解决，都能被<span>衡量与改进</span></h2>
        <p>{{ isStaff ? '从评分、标签和原始反馈中识别服务短板，按支持组与处理人持续改进。' : '工单解决后，用一分钟告诉我们这次服务是否真正解决了问题。' }}</p>
      </div>
      <button class="satisfaction-refresh" :disabled="loading" @click="load">{{ loading ? '同步中…' : '刷新数据' }}</button>
    </div>

    <div v-if="error" class="satisfaction-message error">{{ error }}<button @click="error = ''">×</button></div>
    <div v-if="toast" class="satisfaction-message success">{{ toast }}</div>

    <template v-if="isStaff && analytics">
      <div class="quality-toolbar">
        <div><strong>服务质量总览</strong><small>评分在 4-5 星计为正向反馈</small></div>
        <select v-model.number="days" aria-label="统计周期" @change="changePeriod">
          <option :value="7">最近 7 天</option>
          <option :value="30">最近 30 天</option>
          <option :value="90">最近 90 天</option>
          <option :value="365">最近 365 天</option>
        </select>
      </div>
      <div class="quality-summary">
        <article class="featured"><span>平均满意度</span><strong>{{ analytics.averageRating.toFixed(2) }}<small>/ 5</small></strong><p>★ {{ analytics.totalResponses ? '来自真实申请人评价' : '等待第一条评价' }}</p></article>
        <article><span>正向反馈率</span><strong>{{ analytics.positiveRate }}<small>%</small></strong><p>{{ analytics.positiveCount }} 条 4-5 星评价</p></article>
        <article><span>有效评价</span><strong>{{ analytics.totalResponses }}</strong><p>统计周期 {{ analytics.days }} 天</p></article>
        <article :class="{ attention: analytics.lowRatingCount }"><span>低分预警</span><strong>{{ analytics.lowRatingCount }}</strong><p>1-2 星，建议优先复盘</p></article>
      </div>

      <div class="quality-grid">
        <section class="quality-panel distribution-panel">
          <div class="quality-heading"><div><small>RATING DISTRIBUTION</small><h3>评分分布</h3></div><span>{{ analytics.totalResponses }} 份</span></div>
          <div class="rating-bars">
            <div v-for="score in [5, 4, 3, 2, 1]" :key="score" class="rating-bar-row">
              <b>{{ score }} 星</b><div><i :style="{ width: distributionWidth(analytics.distribution[score] || 0) }"></i></div><span>{{ analytics.distribution[score] || 0 }}</span>
            </div>
          </div>
        </section>
        <section class="quality-panel ranking-panel">
          <div class="quality-heading"><div><small>TEAM PERFORMANCE</small><h3>支持组表现</h3></div><span>按均分排序</span></div>
          <div v-if="!analytics.groups.length" class="quality-empty">统计周期内暂无支持组评价。</div>
          <div v-else class="quality-ranking">
            <div v-for="(group, index) in analytics.groups" :key="group.id || 'none'">
              <em>{{ String(index + 1).padStart(2, '0') }}</em><div><strong>{{ group.name }}</strong><small>{{ group.responses }} 份 · 正向率 {{ group.positiveRate }}%</small></div><b>{{ group.averageRating.toFixed(2) }}</b>
            </div>
          </div>
        </section>
        <section class="quality-panel ranking-panel">
          <div class="quality-heading"><div><small>AGENT PERFORMANCE</small><h3>处理人表现</h3></div><span>服务质量快照</span></div>
          <div v-if="!analytics.assignees.length" class="quality-empty">统计周期内暂无处理人评价。</div>
          <div v-else class="quality-ranking">
            <div v-for="(agent, index) in analytics.assignees" :key="agent.id || 'none'">
              <em>{{ String(index + 1).padStart(2, '0') }}</em><div><strong>{{ agent.name }}</strong><small>{{ agent.responses }} 份 · 正向率 {{ agent.positiveRate }}%</small></div><b>{{ agent.averageRating.toFixed(2) }}</b>
            </div>
          </div>
        </section>
      </div>

      <section class="quality-panel feedback-panel">
        <div class="quality-heading"><div><small>VOICE OF CUSTOMER</small><h3>最近评价</h3></div><span>最多 50 条</span></div>
        <div v-if="!analytics.recent.length" class="quality-empty">还没有收到评价。工单解决后，系统会自动邀请申请人反馈。</div>
        <div v-else class="feedback-list">
          <article v-for="item in analytics.recent" :key="item.id" :class="{ low: item.rating <= 2 }">
            <div class="feedback-score"><strong>{{ item.rating }}</strong><span>★★★★★</span></div>
            <div class="feedback-main"><div><code>{{ item.ticketNo }}</code><strong>{{ item.ticketTitle }}</strong></div><p>{{ item.comment || '申请人未填写文字评价。' }}</p><div class="feedback-tags"><span v-for="tag in item.tags" :key="tag">{{ tagLabel(tag) }}</span></div><small>{{ item.assignmentGroupName }} · {{ item.assigneeName }} · {{ formatDate(item.submittedAt) }}</small></div>
          </article>
        </div>
      </section>
    </template>

    <section class="survey-section">
      <div class="survey-section-heading"><div><small>MY SERVICE REVIEWS</small><h2>我的服务评价</h2><p>仅工单申请人可评价，且每张工单只能提交一次。</p></div><span v-if="pendingSurveys.length">{{ pendingSurveys.length }} 张待评价</span></div>
      <div v-if="loading && !surveys.length" class="quality-panel quality-empty">正在读取可评价工单…</div>
      <div v-else-if="!pendingSurveys.length" class="quality-panel survey-empty"><b>✓</b><div><strong>当前没有待评价工单</strong><p>工单进入“已解决”后，评价入口会自动出现在这里。</p></div></div>
      <div v-else class="survey-list">
        <article v-for="survey in pendingSurveys" :key="survey.ticketId" class="survey-card">
          <header><div><code>{{ survey.ticketNo }}</code><h3>{{ survey.title }}</h3><small>解决于 {{ formatDate(survey.resolvedAt) }}</small></div><span>待评价</span></header>
          <div class="star-question"><strong>这次服务体验如何？</strong><div class="star-input" role="radiogroup" aria-label="满意度评分"><button v-for="score in 5" :key="score" type="button" :class="{ active: score <= formFor(survey.ticketId).rating }" :aria-label="score + ' 星'" @click="formFor(survey.ticketId).rating = score">★</button><b v-if="formFor(survey.ticketId).rating">{{ formFor(survey.ticketId).rating }}.0</b></div></div>
          <div class="tag-question"><strong>哪些方面最符合你的感受？<small>最多 5 项</small></strong><div><button v-for="tag in tagOptions" :key="tag.value" type="button" :class="['survey-tag', tag.tone, { active: formFor(survey.ticketId).tags.includes(tag.value) }]" @click="toggleTag(survey.ticketId, tag.value)">{{ tag.label }}</button></div></div>
          <textarea v-model="formFor(survey.ticketId).comment" rows="3" maxlength="2000" placeholder="补充你的感受或改进建议（选填）"></textarea>
          <footer><small>评价将用于团队服务质量改进</small><button class="submit-survey" :disabled="submittingTicketId === survey.ticketId" @click="submitSurvey(survey)">{{ submittingTicketId === survey.ticketId ? '提交中…' : '提交评价' }}</button></footer>
        </article>
      </div>

      <section v-if="completedSurveys.length" class="completed-reviews">
        <div class="quality-heading"><div><small>REVIEW HISTORY</small><h3>已提交评价</h3></div><span>{{ completedSurveys.length }} 条</span></div>
        <div class="completed-review-list"><article v-for="survey in completedSurveys" :key="survey.ticketId"><div><code>{{ survey.ticketNo }}</code><strong>{{ survey.title }}</strong><small>{{ formatDate(survey.submittedAt) }}</small></div><div class="completed-score"><span>★★★★★</span><b>{{ survey.rating }}.0</b></div><p v-if="survey.comment">{{ survey.comment }}</p><div class="feedback-tags"><span v-for="tag in survey.tags" :key="tag">{{ tagLabel(tag) }}</span></div></article></div>
      </section>
    </section>
  </section>
</template>

<style scoped>
.satisfaction-view { display: grid; gap: 18px; }
.satisfaction-hero { position: relative; display: flex; align-items: flex-end; justify-content: space-between; gap: 28px; overflow: hidden; padding: 34px 38px; border-radius: 19px; color: #fff; background: radial-gradient(circle at 78% 10%, #7b8cff55, transparent 34%), linear-gradient(135deg, #111d38, #243763 70%, #334b84); box-shadow: 0 24px 54px #17254822; }
.satisfaction-hero:after { content: ''; position: absolute; width: 280px; height: 280px; right: -65px; bottom: -185px; border: 1px solid #d9ff6355; border-radius: 50%; box-shadow: 0 0 0 38px #d9ff630a, 0 0 0 76px #d9ff6308; }
.satisfaction-hero > * { position: relative; z-index: 1; }
.satisfaction-kicker { color: #d9ff63; font-family: Consolas, monospace; font-size: 10px; letter-spacing: 1.4px; }
.satisfaction-hero h2 { margin: 12px 0 9px; font-size: clamp(25px, 3vw, 38px); letter-spacing: -1.5px; }
.satisfaction-hero h2 span { margin-left: 8px; color: #d9ff63; }
.satisfaction-hero p { max-width: 720px; margin: 0; color: #b5c2db; font-size: 12px; line-height: 1.7; }
.satisfaction-refresh { flex: 0 0 auto; padding: 10px 15px; border: 1px solid #ffffff24; border-radius: 9px; color: #dce5f6; background: #ffffff0d; font-size: 11px; font-weight: 700; }
.satisfaction-refresh:hover { background: #ffffff18; }
.satisfaction-refresh:disabled { cursor: wait; opacity: .6; }
.satisfaction-message { display: flex; align-items: center; justify-content: space-between; padding: 11px 14px; border-radius: 10px; font-size: 11px; }
.satisfaction-message.error { color: #ac4f5a; border: 1px solid #f0cfd3; background: #fff1f2; }
.satisfaction-message.success { color: #367b5a; border: 1px solid #cce8d7; background: #edf9f2; }
.satisfaction-message button { color: inherit; background: transparent; }
.quality-toolbar { display: flex; align-items: center; justify-content: space-between; margin-top: 6px; }
.quality-toolbar strong, .quality-toolbar small { display: block; }
.quality-toolbar strong { color: #2e3b54; font-size: 17px; }
.quality-toolbar small { margin-top: 5px; color: #939fb2; font-size: 10px; }
.quality-toolbar select { width: 145px; padding: 9px 11px; font-size: 10px; }
.quality-summary { display: grid; grid-template-columns: 1.15fr repeat(3, 1fr); gap: 12px; }
.quality-summary article { min-width: 0; padding: 19px 20px; border: 1px solid #e3e8f1; border-radius: 14px; background: #fff; box-shadow: 0 10px 28px #21366008; }
.quality-summary article.featured { color: #fff; border: 0; background: linear-gradient(145deg, #596fd0, #7287e5); }
.quality-summary article.attention { border-color: #efcdd1; background: #fff9f9; }
.quality-summary span, .quality-summary p { display: block; margin: 0; color: #8e9bad; font-size: 10px; }
.quality-summary strong { display: block; margin: 8px 0 6px; color: #2d3a53; font-size: 29px; }
.quality-summary strong small { display: inline; margin-left: 4px; font-size: 11px; font-weight: 500; }
.quality-summary .featured span, .quality-summary .featured p { color: #dbe2ff; }
.quality-summary .featured strong { color: #fff; }
.quality-summary .attention strong { color: #bd5c67; }
.quality-grid { display: grid; grid-template-columns: .9fr 1.05fr 1.05fr; gap: 12px; }
.quality-panel, .completed-reviews { min-width: 0; padding: 21px; border: 1px solid #e3e8f1; border-radius: 14px; background: #fff; box-shadow: 0 10px 28px #21366008; }
.quality-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.quality-heading small { color: #8795aa; font-family: Consolas, monospace; font-size: 8px; letter-spacing: .8px; }
.quality-heading h3 { margin: 5px 0 0; color: #344159; font-size: 14px; }
.quality-heading > span { color: #9aa5b6; font-size: 9px; }
.rating-bars { display: grid; gap: 12px; margin-top: 20px; }
.rating-bar-row { display: grid; grid-template-columns: 32px 1fr 24px; align-items: center; gap: 9px; }
.rating-bar-row b, .rating-bar-row span { color: #78859a; font-size: 9px; }
.rating-bar-row span { text-align: right; }
.rating-bar-row > div { height: 7px; overflow: hidden; border-radius: 20px; background: #edf0f6; }
.rating-bar-row i { display: block; height: 100%; border-radius: inherit; background: linear-gradient(90deg, #6276d1, #8da0f3); transition: width .35s; }
.quality-ranking { display: grid; gap: 7px; margin-top: 15px; }
.quality-ranking > div { display: flex; align-items: center; gap: 9px; padding: 9px 10px; border-radius: 9px; background: #f8f9fc; }
.quality-ranking em { color: #98a4b7; font-family: Consolas, monospace; font-size: 9px; font-style: normal; }
.quality-ranking div div { min-width: 0; flex: 1; }
.quality-ranking strong, .quality-ranking small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.quality-ranking strong { color: #455169; font-size: 10px; }
.quality-ranking small { margin-top: 4px; color: #9aa5b6; font-size: 8px; }
.quality-ranking > div > b { color: #6074cf; font-size: 14px; }
.quality-empty { padding: 28px 10px; color: #99a4b5; font-size: 10px; text-align: center; }
.feedback-panel { padding: 22px; }
.feedback-list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 9px; margin-top: 16px; }
.feedback-list article { display: flex; gap: 13px; min-width: 0; padding: 14px; border: 1px solid #e7eaf1; border-radius: 11px; background: #fbfcff; }
.feedback-list article.low { border-color: #f0d3d6; background: #fffafb; }
.feedback-score { flex: 0 0 42px; text-align: center; }
.feedback-score strong { display: block; color: #344159; font-size: 24px; }
.feedback-score span { display: block; overflow: hidden; width: 32px; color: #f2b744; font-size: 7px; white-space: nowrap; }
.feedback-main { min-width: 0; flex: 1; }
.feedback-main > div:first-child { display: flex; gap: 8px; min-width: 0; }
.feedback-main code { flex: 0 0 auto; color: #7384a2; font-size: 8px; }
.feedback-main strong { overflow: hidden; color: #455169; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.feedback-main p { margin: 9px 0; color: #657289; font-size: 10px; line-height: 1.55; }
.feedback-main > small { display: block; margin-top: 9px; color: #a0aabb; font-size: 8px; }
.feedback-tags { display: flex; flex-wrap: wrap; gap: 5px; }
.feedback-tags span { padding: 4px 6px; border-radius: 999px; color: #687ac8; background: #ebeeff; font-size: 8px; }
.survey-section { display: grid; gap: 13px; margin-top: 8px; }
.survey-section-heading { display: flex; align-items: flex-end; justify-content: space-between; }
.survey-section-heading small { color: #8593a8; font-family: Consolas, monospace; font-size: 9px; letter-spacing: 1px; }
.survey-section-heading h2 { margin: 7px 0 4px; color: #2f3c55; font-size: 20px; }
.survey-section-heading p { margin: 0; color: #97a2b3; font-size: 10px; }
.survey-section-heading > span { padding: 6px 9px; border-radius: 999px; color: #ad7622; background: #fff2d5; font-size: 9px; }
.survey-list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.survey-card { min-width: 0; padding: 23px; border: 1px solid #e1e7f0; border-radius: 15px; background: #fff; box-shadow: 0 12px 32px #2136600a; }
.survey-card header { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; padding-bottom: 16px; border-bottom: 1px solid #edf0f5; }
.survey-card header code, .survey-card header small { display: block; color: #8c99ad; font-size: 9px; }
.survey-card header h3 { margin: 6px 0; color: #36435b; font-size: 14px; }
.survey-card header > span { flex: 0 0 auto; padding: 5px 8px; border-radius: 999px; color: #a96f19; background: #fff2d7; font-size: 8px; font-weight: 700; }
.star-question, .tag-question { margin-top: 17px; }
.star-question > strong, .tag-question > strong { display: block; color: #59667c; font-size: 10px; }
.tag-question > strong small { margin-left: 6px; color: #a3adbd; font-size: 8px; font-weight: 400; }
.star-input { display: flex; align-items: center; gap: 4px; margin-top: 9px; }
.star-input button { padding: 0 2px; color: #dfe3eb; background: transparent; font-size: 28px; transition: color .15s, transform .15s; }
.star-input button:hover, .star-input button.active { color: #f2b744; transform: translateY(-1px); }
.star-input b { margin-left: 8px; color: #b78326; font-size: 14px; }
.tag-question > div { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 10px; }
.survey-tag { padding: 6px 9px; border: 1px solid #e2e7ef; border-radius: 999px; color: #778398; background: #fff; font-size: 9px; }
.survey-tag.positive.active { color: #357b59; border-color: #bde0ca; background: #eaf7ef; }
.survey-tag.negative.active { color: #ac5862; border-color: #ecc8cc; background: #fff0f1; }
.survey-card textarea { margin-top: 16px; resize: vertical; font-size: 10px; line-height: 1.6; }
.survey-card footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 13px; }
.survey-card footer small { color: #a0aabb; font-size: 8px; }
.submit-survey { padding: 9px 14px; border-radius: 9px; color: #fff; background: #182744; font-size: 10px; font-weight: 700; }
.submit-survey:disabled { cursor: wait; opacity: .6; }
.survey-empty { display: flex; align-items: center; justify-content: center; gap: 14px; padding: 34px; }
.survey-empty > b { display: grid; width: 37px; height: 37px; place-items: center; border-radius: 50%; color: #438763; background: #e7f5ec; }
.survey-empty strong { color: #4b5870; font-size: 12px; }
.survey-empty p { margin: 5px 0 0; color: #9aa5b6; font-size: 9px; }
.completed-reviews { margin-top: 7px; }
.completed-review-list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; margin-top: 15px; }
.completed-review-list article { display: grid; grid-template-columns: 1fr auto; gap: 7px 12px; padding: 13px; border-radius: 10px; background: #f8f9fc; }
.completed-review-list code, .completed-review-list strong, .completed-review-list small { display: block; }
.completed-review-list code { color: #8190a8; font-size: 8px; }
.completed-review-list strong { margin: 4px 0; overflow: hidden; color: #4a566d; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.completed-review-list small { color: #a0aabb; font-size: 8px; }
.completed-review-list p { grid-column: 1 / -1; margin: 2px 0; color: #68758a; font-size: 9px; line-height: 1.5; }
.completed-review-list .feedback-tags { grid-column: 1 / -1; }
.completed-score { text-align: right; }
.completed-score span { display: block; color: #f2b744; font-size: 8px; }
.completed-score b { color: #9a7228; font-size: 12px; }
@media (max-width: 1050px) { .quality-summary { grid-template-columns: repeat(2, 1fr); } .quality-grid { grid-template-columns: 1fr; } }
@media (max-width: 760px) { .satisfaction-hero { align-items: stretch; flex-direction: column; padding: 26px; } .satisfaction-refresh { align-self: flex-start; } .quality-summary, .survey-list, .feedback-list, .completed-review-list { grid-template-columns: 1fr; } .quality-toolbar { align-items: flex-start; gap: 12px; flex-direction: column; } .quality-toolbar select { width: 100%; } }
</style>
