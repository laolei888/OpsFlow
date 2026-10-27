<script setup>
import { computed, reactive, ref } from 'vue'
import { knowledgeQueueCount, reviewDueLabel, reviewWaitLabel } from '../app/knowledgeGovernance'

const props = defineProps({
  api: { type: Function, required: true },
  articles: { type: Array, default: () => [] },
  analytics: { type: Object, default: null },
  isAdmin: { type: Boolean, default: false },
  userId: { type: [Number, String], default: null }
})

const emit = defineEmits(['changed', 'notify', 'error', 'open-article'])
const showPanel = ref(false)
const submitTarget = ref(null)
const reviewTarget = ref(null)
const reviewDecision = ref('APPROVE')
const reviewComment = ref('')
const busy = ref(false)
const checklist = reactive({
  sensitiveDataChecked: false,
  scopeDefined: false,
  verificationStepsComplete: false
})

const drafts = computed(() => props.articles.filter(article => article.status === 'DRAFT'))
const pending = computed(() => props.articles.filter(article => article.status === 'PENDING_REVIEW'))
const overdue = computed(() => props.articles.filter(article => article.status === 'PUBLISHED'
  && article.reviewDueAt && new Date(article.reviewDueAt).getTime() <= Date.now()))
const draftCount = computed(() => knowledgeQueueCount(props.analytics, 'draftArticles', drafts.value))
const pendingCount = computed(() => knowledgeQueueCount(props.analytics, 'pendingReviewArticles', pending.value))
const overdueCount = computed(() => knowledgeQueueCount(props.analytics, 'overdueReviewArticles', overdue.value))
const governanceCount = computed(() => pendingCount.value + overdueCount.value)
const checklistComplete = computed(() => checklist.sensitiveDataChecked
  && checklist.scopeDefined && checklist.verificationStepsComplete)

function formatDate(value) {
  if (!value) return '—'
  return new Date(value).toLocaleString('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit'
  })
}

function report(error) {
  emit('error', error?.message || '知识审核操作失败')
}

function open(articleId = null) {
  showPanel.value = true
  if (!articleId) return
  const target = props.articles.find(article => String(article.id) === String(articleId))
  if (target?.status === 'DRAFT') openSubmit(target)
}

function preview(article) {
  showPanel.value = false
  emit('open-article', article)
}

function openSubmit(article) {
  if (!article || article.status !== 'DRAFT') return
  submitTarget.value = article
  Object.assign(checklist, {
    sensitiveDataChecked: false,
    scopeDefined: false,
    verificationStepsComplete: false
  })
}

async function submitReview() {
  if (!submitTarget.value || !checklistComplete.value || busy.value) return
  busy.value = true
  try {
    const article = await props.api(`/api/v1/knowledge/articles/${submitTarget.value.id}/submit-review`, {
      method: 'POST',
      body: JSON.stringify(checklist)
    })
    submitTarget.value = null
    emit('changed', article)
    emit('notify', '知识文章已提交管理员审核')
  } catch (error) {
    report(error)
  } finally {
    busy.value = false
  }
}

function openReview(article, decision) {
  reviewTarget.value = article
  reviewDecision.value = decision
  reviewComment.value = ''
}

async function decideReview() {
  if (!reviewTarget.value || busy.value) return
  if (reviewDecision.value === 'REJECT' && !reviewComment.value.trim()) return
  busy.value = true
  try {
    const article = await props.api(`/api/v1/knowledge/articles/${reviewTarget.value.id}/review`, {
      method: 'POST',
      body: JSON.stringify({
        decision: reviewDecision.value,
        comment: reviewComment.value.trim() || null
      })
    })
    reviewTarget.value = null
    emit('changed', article)
    emit('notify', reviewDecision.value === 'APPROVE' ? '知识文章已审核发布' : '知识文章已驳回草稿')
  } catch (error) {
    report(error)
  } finally {
    busy.value = false
  }
}

function canWithdraw(article) {
  return props.isAdmin || String(article.reviewRequestedBy) === String(props.userId)
}

async function withdraw(article) {
  if (!canWithdraw(article) || busy.value) return
  busy.value = true
  try {
    const updated = await props.api(`/api/v1/knowledge/articles/${article.id}/withdraw-review`, { method: 'POST' })
    emit('changed', updated)
    emit('notify', '知识文章已撤回为草稿')
  } catch (error) {
    report(error)
  } finally {
    busy.value = false
  }
}

defineExpose({ openSubmit, open })
</script>

<template>
  <button type="button" class="review-launcher" @click="showPanel = true">
    <span>知识治理</span>
    <b v-if="governanceCount">{{ governanceCount }}</b>
  </button>

  <div v-if="showPanel" class="review-overlay" @click.self="showPanel = false">
    <aside class="review-drawer">
      <header>
        <div><span>KNOWLEDGE GOVERNANCE</span><h2>知识审核与质量门禁</h2></div>
        <button type="button" @click="showPanel = false">×</button>
      </header>

      <div class="review-summary">
        <article><span>待完善草稿</span><strong>{{ draftCount }}</strong></article>
        <article :class="{ attention: pendingCount }"><span>等待审核</span><strong>{{ pendingCount }}</strong></article>
        <article :class="{ attention: overdueCount }"><span>到期复核</span><strong>{{ overdueCount }}</strong></article>
      </div>

      <section>
        <div class="queue-heading"><div><span>DRAFT QUALITY</span><h3>待提交草稿</h3></div><small>完成三项检查后进入审核</small></div>
        <p v-if="!drafts.length" class="empty-note">当前没有待提交草稿。</p>
        <div v-else class="review-list">
          <article v-for="article in drafts" :key="article.id">
            <div><strong>{{ article.title }}</strong><small>{{ article.category }} · {{ article.content?.length || 0 }} 字</small></div>
            <div class="row-buttons"><button type="button" class="secondary" @click="preview(article)">预览</button><button type="button" @click="openSubmit(article)">提交审核</button></div>
          </article>
        </div>
      </section>

      <section>
        <div class="queue-heading"><div><span>REVIEW QUEUE</span><h3>管理员审核队列</h3></div><small>{{ isAdmin ? '批准后正式公开' : '等待管理员处理' }}</small></div>
        <p v-if="!pending.length" class="empty-note">当前没有等待审核的文章。</p>
        <div v-else class="review-list pending-list">
          <article v-for="article in pending" :key="article.id">
            <div><strong>{{ article.title }}</strong><small>{{ reviewWaitLabel(article.reviewRequestedAt) }} · 提交于 {{ formatDate(article.reviewRequestedAt) }} · 用户 #{{ article.reviewRequestedBy }}</small></div>
            <span class="quality-pass">3 / 3 已检查</span>
            <div class="row-buttons">
              <button type="button" class="secondary" @click="preview(article)">预览</button>
              <button v-if="canWithdraw(article)" type="button" class="secondary" @click="withdraw(article)">撤回</button>
              <button v-if="isAdmin" type="button" class="danger" @click="openReview(article, 'REJECT')">驳回</button>
              <button v-if="isAdmin" type="button" @click="openReview(article, 'APPROVE')">批准</button>
            </div>
          </article>
        </div>
      </section>

      <section>
        <div class="queue-heading"><div><span>PERIODIC REVIEW</span><h3>到期复核</h3></div><small>发布后每 180 天复核</small></div>
        <p v-if="!overdue.length" class="empty-note">当前没有到期文章。</p>
        <div v-else class="review-list overdue-list">
          <article v-for="article in overdue" :key="article.id">
            <div><strong>{{ article.title }}</strong><small>复核日期 {{ formatDate(article.reviewDueAt) }}</small></div>
            <div class="overdue-actions"><span>{{ reviewDueLabel(article.reviewDueAt) }}</span><button type="button" class="secondary" @click="preview(article)">查看</button></div>
          </article>
        </div>
      </section>
    </aside>
  </div>

  <div v-if="submitTarget" class="review-modal-overlay" @click.self="submitTarget = null">
    <form class="review-modal" @submit.prevent="submitReview">
      <header><div><span>QUALITY CHECKLIST</span><h2>提交知识审核</h2></div><button type="button" @click="submitTarget = null">×</button></header>
      <p>{{ submitTarget.title }}</p>
      <label><input v-model="checklist.sensitiveDataChecked" type="checkbox" /> 已移除账号、地址、令牌和其他敏感信息</label>
      <label><input v-model="checklist.scopeDefined" type="checkbox" /> 已说明适用范围、前置条件和限制</label>
      <label><input v-model="checklist.verificationStepsComplete" type="checkbox" /> 验证步骤完整、可复现且结果明确</label>
      <small>内容不足 80 个字符时，服务端会阻止提交。</small>
      <footer><button type="button" class="secondary" @click="submitTarget = null">取消</button><button :disabled="!checklistComplete || busy">{{ busy ? '提交中…' : '提交管理员审核' }}</button></footer>
    </form>
  </div>

  <div v-if="reviewTarget" class="review-modal-overlay" @click.self="reviewTarget = null">
    <form class="review-modal" @submit.prevent="decideReview">
      <header><div><span>REVIEW DECISION</span><h2>{{ reviewDecision === 'APPROVE' ? '批准并发布' : '驳回为草稿' }}</h2></div><button type="button" @click="reviewTarget = null">×</button></header>
      <p>{{ reviewTarget.title }}</p>
      <label>审核意见<textarea v-model="reviewComment" :required="reviewDecision === 'REJECT'" rows="5" maxlength="1000" :placeholder="reviewDecision === 'REJECT' ? '说明需要修改的内容…' : '可选：记录批准依据或后续复核要求…'"></textarea></label>
      <small v-if="reviewDecision === 'APPROVE'">批准后文章立即公开，并设置 180 天后的复核日期。</small>
      <footer><button type="button" class="secondary" @click="reviewTarget = null">取消</button><button :class="{ danger: reviewDecision === 'REJECT' }" :disabled="busy || (reviewDecision === 'REJECT' && !reviewComment.trim())">{{ busy ? '处理中…' : reviewDecision === 'APPROVE' ? '批准发布' : '确认驳回' }}</button></footer>
    </form>
  </div>
</template>

<style scoped>
.review-launcher{position:fixed;right:28px;bottom:28px;z-index:120;display:flex;align-items:center;gap:8px;border:0;border-radius:999px;background:#17345f;color:#fff;padding:11px 16px;box-shadow:0 12px 32px rgba(23,52,95,.28);font-weight:750;cursor:pointer}.review-launcher b{display:grid;place-items:center;min-width:21px;height:21px;border-radius:999px;background:#ffdf91;color:#704d00;font-size:10px}.review-overlay,.review-modal-overlay{position:fixed;inset:0;background:rgba(13,22,38,.48);z-index:210}.review-overlay{display:flex;justify-content:flex-end}.review-drawer{width:min(680px,94vw);height:100%;overflow:auto;background:#f6f8fb;padding:24px;box-shadow:-24px 0 60px rgba(14,27,49,.2)}header{display:flex;justify-content:space-between;align-items:flex-start}header span,.queue-heading span{color:#8490a2;font-size:9px;font-weight:850;letter-spacing:.12em}header h2,.queue-heading h3{margin:4px 0 0;color:#1f2c40}header>button{border:0;background:none;font-size:25px;color:#768298;cursor:pointer}.review-summary{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;margin:22px 0}.review-summary article{padding:14px;border:1px solid #e1e6ee;border-radius:12px;background:#fff}.review-summary article.attention{border-color:#f2cf91;background:#fffaf0}.review-summary span{display:block;color:#78869a;font-size:10px}.review-summary strong{display:block;margin-top:5px;font-size:24px;color:#20324d}.review-drawer>section{margin-top:14px;padding:17px;border:1px solid #e2e7ef;border-radius:14px;background:#fff}.queue-heading{display:flex;justify-content:space-between;gap:14px;align-items:flex-end}.queue-heading small,.empty-note{color:#8c97a7;font-size:10px}.empty-note{text-align:center;padding:18px}.review-list{display:grid;gap:9px;margin-top:14px}.review-list article{display:flex;align-items:center;gap:10px;border-top:1px solid #edf0f4;padding-top:10px}.review-list article>div:first-child{display:flex;flex:1;min-width:0;flex-direction:column}.review-list strong{font-size:12px;color:#344054}.review-list small{margin-top:3px;color:#8b95a5;font-size:9px}.review-list button,.review-modal footer button{border:0;border-radius:8px;background:#2f6fed;color:#fff;padding:8px 11px;font-weight:700;cursor:pointer}.row-buttons{display:flex;gap:6px}.review-list button.secondary,.review-modal footer button.secondary{background:#edf1f6;color:#536176}.review-list button.danger,.review-modal footer button.danger{background:#c64d45}.quality-pass{white-space:nowrap;border-radius:999px;background:#eaf7ef;color:#23764a;padding:4px 7px;font-size:9px}.overdue-list article>span{color:#b54708;background:#fff4e5;border-radius:999px;padding:4px 7px;font-size:9px}.review-modal-overlay{z-index:230;display:grid;place-items:center;padding:20px}.review-modal{width:min(540px,100%);display:flex;flex-direction:column;gap:14px;border-radius:16px;background:#fff;padding:24px;box-shadow:0 25px 70px rgba(14,27,49,.28)}.review-modal>p{margin:0;color:#344054;font-weight:750}.review-modal>label{display:flex;align-items:flex-start;gap:9px;color:#4e5b6f;font-size:12px;line-height:1.5}.review-modal>label:has(textarea){flex-direction:column}.review-modal textarea{width:100%;box-sizing:border-box;border:1px solid #dbe1e9;border-radius:9px;padding:10px;font:inherit;resize:vertical}.review-modal>small{color:#8792a2}.review-modal footer{display:flex;justify-content:flex-end;gap:8px}.review-modal footer button:disabled{opacity:.55;cursor:not-allowed}@media(max-width:700px){.review-summary{grid-template-columns:1fr}.review-list article{align-items:flex-start;flex-wrap:wrap}.row-buttons{width:100%;justify-content:flex-end}}
.overdue-actions{display:flex!important;flex:0 0 auto!important;flex-direction:row!important;align-items:center;gap:7px}.overdue-actions>span{color:#b54708;background:#fff4e5;border-radius:999px;padding:4px 7px;font-size:9px;white-space:nowrap}
</style>
