<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { intakeStepErrors, recommendPriority, TICKET_CATEGORIES } from '../../app/ticketIntake'
import { priorityLabels } from '../../constants/domain'

const props = defineProps({ api: { type: Function, required: true } })
const emit = defineEmits(['close', 'created', 'error', 'notify', 'openArticle'])

const step = ref(1)
const submitting = ref(false)
const loadingKnowledge = ref(false)
const suggestions = ref([])
const validationErrors = ref([])
const evidenceFile = ref(null)
const priorityTouched = ref(false)
const submissionKey = window.crypto?.randomUUID?.() || `ticket-${Date.now()}-${Math.random().toString(36).slice(2)}`

const form = reactive({
  category: '',
  title: '',
  description: '',
  impactScope: '',
  expectedOutcome: '',
  attemptedActions: '',
  serviceUnavailable: null,
  affectedUsers: '',
  workaround: '',
  priority: 'MEDIUM'
})

const selectedCategory = computed(() => TICKET_CATEGORIES.find(item => item.value === form.category))
const priorityRecommendation = computed(() => recommendPriority(form))
const stepLabels = ['选择场景', '补充上下文', '确认并提交']
const canGoBack = computed(() => step.value > 1 && !submitting.value)

watch(priorityRecommendation, recommendation => {
  if (!priorityTouched.value) form.priority = recommendation.value
})

function close() {
  if (!submitting.value) emit('close')
}

function selectCategory(category) {
  form.category = category.value
  validationErrors.value = []
}

function choosePriority(value) {
  form.priority = value
  priorityTouched.value = true
}

async function nextStep() {
  validationErrors.value = intakeStepErrors(step.value, form)
  if (validationErrors.value.length) return
  if (step.value === 1) await loadSuggestions()
  if (step.value < 3) step.value++
}

function previousStep() {
  validationErrors.value = []
  if (step.value > 1) step.value--
}

async function loadSuggestions() {
  loadingKnowledge.value = true
  try {
    const query = new URLSearchParams({ page: '1', size: '4' })
    if (form.title.trim()) query.set('keyword', form.title.trim())
    else if (form.category) query.set('category', form.category)
    const result = await props.api(`/api/v1/knowledge/articles?${query.toString()}`)
    suggestions.value = result?.records || []
    if (!suggestions.value.length && form.category) {
      const fallback = await props.api(`/api/v1/knowledge/articles?page=1&size=4&category=${encodeURIComponent(form.category)}`)
      suggestions.value = fallback?.records || []
    }
  } catch (_) {
    suggestions.value = []
  } finally {
    loadingKnowledge.value = false
  }
}

function chooseEvidence(event) {
  const file = event.target.files?.[0] || null
  if (file && file.size > 10 * 1024 * 1024) {
    validationErrors.value = ['向导内附件不能超过 10 MB；创建后可在详情页使用大文件分片上传']
    event.target.value = ''
    evidenceFile.value = null
    return
  }
  evidenceFile.value = file
  validationErrors.value = []
}

async function submitTicket() {
  validationErrors.value = intakeStepErrors(3, form)
  if (validationErrors.value.length) return
  submitting.value = true
  try {
    const created = await props.api('/api/v1/tickets', {
      method: 'POST',
      headers: { 'Idempotency-Key': submissionKey },
      body: JSON.stringify({
        title: form.title.trim(),
        description: form.description.trim(),
        impactScope: form.impactScope.trim(),
        expectedOutcome: form.expectedOutcome.trim(),
        attemptedActions: form.attemptedActions.trim() || null,
        category: form.category,
        priority: form.priority
      })
    })
    let attachmentWarning = ''
    if (evidenceFile.value) {
      try {
        const body = new FormData()
        body.append('file', evidenceFile.value)
        await props.api(`/api/v1/tickets/${created.id}/attachments`, { method: 'POST', body })
      } catch (error) {
        attachmentWarning = `工单已创建，但附件上传失败：${error.message}`
      }
    }
    emit('created', created)
    emit('notify', attachmentWarning || `工单 ${created.ticketNo} 已创建并进入处理队列`)
  } catch (error) {
    emit('error', error.message)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="wizard-backdrop" @click.self="close">
    <section class="ticket-wizard" role="dialog" aria-modal="true" aria-labelledby="ticket-wizard-title">
      <header class="wizard-header">
        <div>
          <span>GUIDED REQUEST</span>
          <h2 id="ticket-wizard-title">创建支持工单</h2>
          <p>用清晰的上下文帮助处理团队更快定位并解决问题。</p>
        </div>
        <button type="button" class="wizard-close" :disabled="submitting" aria-label="关闭" @click="close">×</button>
      </header>

      <nav class="wizard-progress" aria-label="创建工单进度">
        <div v-for="(label, index) in stepLabels" :key="label" :class="{ active: step === index + 1, complete: step > index + 1 }">
          <span>{{ step > index + 1 ? '✓' : index + 1 }}</span>
          <strong>{{ label }}</strong>
        </div>
      </nav>

      <div class="wizard-body">
        <main class="wizard-main">
          <section v-if="step === 1" class="wizard-step">
            <div class="step-heading"><span>STEP 01</span><h3>你遇到了哪类问题？</h3><p>选择最接近的场景，系统会据此匹配知识和处理规则。</p></div>
            <div class="category-grid">
              <button v-for="category in TICKET_CATEGORIES" :key="category.value" type="button" :class="{ selected: form.category === category.value }" @click="selectCategory(category)">
                <span>{{ category.code }}</span>
                <strong>{{ category.label }}</strong>
                <small>{{ category.description }}</small>
              </button>
            </div>
            <label class="wizard-field title-field">
              <span>一句话概括问题</span>
              <input v-model="form.title" maxlength="160" :placeholder="selectedCategory?.placeholder || '例如：登录内部系统时持续提示连接超时'" @input="validationErrors = []" />
              <small>{{ form.title.trim().length }}/160 · 建议包含对象、现象和错误信息</small>
            </label>
          </section>

          <section v-else-if="step === 2" class="wizard-step">
            <div class="step-heading"><span>STEP 02</span><h3>补充可直接行动的上下文</h3><p>结构化信息会独立保存，不再埋在一大段描述里。</p></div>
            <label class="wizard-field">
              <span>问题现象 <b>必填</b></span>
              <textarea v-model="form.description" rows="5" maxlength="10000" placeholder="发生了什么？何时开始？是否有错误提示？如何稳定复现？"></textarea>
            </label>
            <div class="field-pair">
              <label class="wizard-field">
                <span>影响范围 <b>必填</b></span>
                <textarea v-model="form.impactScope" rows="3" maxlength="1000" placeholder="受影响的人员、设备、系统或业务环节"></textarea>
              </label>
              <label class="wizard-field">
                <span>期望结果 <b>必填</b></span>
                <textarea v-model="form.expectedOutcome" rows="3" maxlength="1000" placeholder="希望恢复到什么状态，或需要团队提供什么"></textarea>
              </label>
            </div>
            <label class="wizard-field">
              <span>已经尝试过什么 <em>选填</em></span>
              <textarea v-model="form.attemptedActions" rows="3" maxlength="3000" placeholder="例如：重新登录、切换网络、清理缓存；请说明结果"></textarea>
            </label>
            <label class="evidence-picker">
              <input type="file" hidden @change="chooseEvidence" />
              <span>{{ evidenceFile ? '✓' : '＋' }}</span>
              <div><strong>{{ evidenceFile?.name || '添加截图或日志' }}</strong><small>{{ evidenceFile ? '提交后自动上传并进行安全扫描' : '可选，单个文件不超过 10 MB' }}</small></div>
              <button v-if="evidenceFile" type="button" @click.prevent="evidenceFile = null">移除</button>
            </label>
          </section>

          <section v-else class="wizard-step">
            <div class="step-heading"><span>STEP 03</span><h3>确认业务影响和优先级</h3><p>系统会给出建议，最终优先级仍由你确认。</p></div>
            <div class="impact-question">
              <div><strong>相关服务现在是否完全不可用？</strong><small>无法继续主要工作流程才算完全不可用</small></div>
              <div class="choice-group"><button type="button" :class="{ selected: form.serviceUnavailable === true }" @click="form.serviceUnavailable = true">是</button><button type="button" :class="{ selected: form.serviceUnavailable === false }" @click="form.serviceUnavailable = false">否</button></div>
            </div>
            <div class="impact-question">
              <div><strong>影响了多少人？</strong><small>请选择当前已知的最大影响范围</small></div>
              <div class="choice-group"><button type="button" :class="{ selected: form.affectedUsers === 'SINGLE' }" @click="form.affectedUsers = 'SINGLE'">仅我自己</button><button type="button" :class="{ selected: form.affectedUsers === 'TEAM' }" @click="form.affectedUsers = 'TEAM'">一个团队</button><button type="button" :class="{ selected: form.affectedUsers === 'MANY' }" @click="form.affectedUsers = 'MANY'">多个团队/客户</button></div>
            </div>
            <div class="impact-question">
              <div><strong>是否有可用的临时替代方案？</strong><small>例如切换设备、备用入口或人工流程</small></div>
              <div class="choice-group"><button type="button" :class="{ selected: form.workaround === 'YES' }" @click="form.workaround = 'YES'">有</button><button type="button" :class="{ selected: form.workaround === 'NO' }" @click="form.workaround = 'NO'">没有</button><button type="button" :class="{ selected: form.workaround === 'UNKNOWN' }" @click="form.workaround = 'UNKNOWN'">不确定</button></div>
            </div>

            <div :class="['priority-recommendation', `priority-${priorityRecommendation.value.toLowerCase()}`]">
              <div><span>系统建议</span><strong>{{ priorityRecommendation.label }}优先级</strong><small>{{ priorityRecommendation.reason }}</small></div>
              <b v-if="form.priority !== priorityRecommendation.value">你已手动调整为{{ priorityLabels[form.priority] }}</b>
            </div>
            <div class="priority-options">
              <button v-for="value in ['LOW', 'MEDIUM', 'HIGH', 'URGENT']" :key="value" type="button" :class="{ selected: form.priority === value }" @click="choosePriority(value)">
                <span>{{ priorityLabels[value] }}</span><small>{{ { LOW: '可计划处理', MEDIUM: '正常影响', HIGH: '明显受阻', URGENT: '业务中断' }[value] }}</small>
              </button>
            </div>

            <div class="ticket-review">
              <header><span>REQUEST REVIEW</span><strong>提交前确认</strong></header>
              <dl>
                <div><dt>分类</dt><dd>{{ selectedCategory?.label }}</dd></div>
                <div><dt>标题</dt><dd>{{ form.title }}</dd></div>
                <div><dt>影响范围</dt><dd>{{ form.impactScope }}</dd></div>
                <div><dt>期望结果</dt><dd>{{ form.expectedOutcome }}</dd></div>
                <div><dt>附件</dt><dd>{{ evidenceFile?.name || '无' }}</dd></div>
              </dl>
            </div>
          </section>

          <div v-if="validationErrors.length" class="wizard-errors" role="alert">
            <strong>请先补充以下信息</strong>
            <span v-for="message in validationErrors" :key="message">{{ message }}</span>
          </div>
        </main>

        <aside class="wizard-aside">
          <div class="aside-heading"><span>SELF-SERVICE FIRST</span><h3>可能已经有答案</h3><p>提交前先看看团队沉淀的解决方案。</p></div>
          <div v-if="step === 1" class="knowledge-placeholder"><b>01</b><p>填写分类和标题后，我们会自动匹配最相关的知识文章。</p></div>
          <div v-else-if="loadingKnowledge" class="knowledge-placeholder"><i></i><p>正在搜索相关解决方案…</p></div>
          <div v-else-if="!suggestions.length" class="knowledge-placeholder"><b>—</b><p>暂未找到直接匹配的文章，可以继续提交给支持团队。</p></div>
          <div v-else class="wizard-knowledge-list">
            <button v-for="article in suggestions" :key="article.id" type="button" @click="emit('openArticle', article)">
              <span>{{ article.category }}</span>
              <strong>{{ article.title }}</strong>
              <p>{{ String(article.content || '').slice(0, 100) }}{{ String(article.content || '').length > 100 ? '…' : '' }}</p>
              <small>打开解决方案 →</small>
            </button>
          </div>
          <div v-if="step > 1" class="aside-context">
            <span>当前请求</span>
            <strong>{{ form.title }}</strong>
            <small>{{ selectedCategory?.label }} · {{ priorityLabels[form.priority] }}优先级</small>
          </div>
        </aside>
      </div>

      <footer class="wizard-footer">
        <button v-if="canGoBack" type="button" class="wizard-back" @click="previousStep">← 上一步</button>
        <span v-else></span>
        <div>
          <button type="button" class="wizard-cancel" :disabled="submitting" @click="close">取消</button>
          <button v-if="step < 3" type="button" class="wizard-next" @click="nextStep">继续：{{ stepLabels[step] }} →</button>
          <button v-else type="button" class="wizard-submit" :disabled="submitting" @click="submitTicket">{{ submitting ? '正在创建…' : '确认提交工单' }}</button>
        </div>
      </footer>
    </section>
  </div>
</template>

<style scoped>
.wizard-backdrop{position:fixed;inset:0;z-index:220;background:rgba(10,20,36,.58);display:grid;place-items:center;padding:20px}.ticket-wizard{width:min(1120px,100%);height:min(820px,calc(100vh - 40px));display:flex;flex-direction:column;background:#f8fafc;border-radius:20px;overflow:hidden;box-shadow:0 30px 90px rgba(9,23,45,.34)}
.wizard-header{display:flex;justify-content:space-between;align-items:flex-start;padding:23px 28px 18px;background:#fff;border-bottom:1px solid #e8ebf0}.wizard-header>div>span,.step-heading>span,.aside-heading>span,.ticket-review header span{font-size:10px;font-weight:850;letter-spacing:.14em;color:#7d8999}.wizard-header h2{margin:4px 0;font-size:23px;color:#182230}.wizard-header p{margin:0;color:#748092;font-size:12px}.wizard-close{border:0;background:#f2f4f7;width:34px;height:34px;border-radius:10px;color:#637083;font-size:21px;cursor:pointer}
.wizard-progress{display:grid;grid-template-columns:repeat(3,1fr);padding:14px 28px;background:#fff;border-bottom:1px solid #e8ebf0}.wizard-progress>div{position:relative;display:flex;align-items:center;gap:9px;color:#98a2b3}.wizard-progress>div:not(:last-child)::after{content:"";position:absolute;height:2px;left:32px;right:16px;top:13px;background:#e5e9ef}.wizard-progress span{display:grid;place-items:center;width:27px;height:27px;border-radius:50%;border:2px solid #d8dde6;background:#fff;font-size:10px;font-weight:800;z-index:1}.wizard-progress strong{font-size:11px;z-index:1;background:#fff;padding-right:8px}.wizard-progress .active{color:#2d65c4}.wizard-progress .active span{border-color:#2f6fed;background:#eaf1ff}.wizard-progress .complete{color:#238052}.wizard-progress .complete span{border-color:#65a980;background:#e9f7ef}.wizard-progress .complete::after{background:#76b18c!important}
.wizard-body{display:grid;grid-template-columns:minmax(0,1fr) 300px;min-height:0;flex:1}.wizard-main{overflow:auto;padding:26px 30px}.wizard-aside{overflow:auto;padding:26px 22px;background:#f1f5fa;border-left:1px solid #e2e7ee}.wizard-step{display:flex;flex-direction:column;gap:18px}.step-heading h3,.aside-heading h3{margin:5px 0 3px;color:#1d2939}.step-heading h3{font-size:20px}.step-heading p,.aside-heading p{margin:0;color:#7a8595;font-size:12px}.category-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:10px}.category-grid button{display:flex;flex-direction:column;text-align:left;border:1px solid #dfe4eb;border-radius:12px;background:#fff;padding:14px;cursor:pointer;min-height:116px;transition:.18s}.category-grid button:hover{border-color:#9db9e5;transform:translateY(-1px)}.category-grid button.selected{border-color:#2f6fed;background:#f3f7ff;box-shadow:0 0 0 2px rgba(47,111,237,.1)}.category-grid button>span{font-size:9px;font-weight:850;color:#2c65c3;background:#edf3ff;border-radius:5px;padding:3px 5px;align-self:flex-start}.category-grid strong{margin:10px 0 5px;font-size:13px;color:#253044}.category-grid small{font-size:10px;line-height:1.45;color:#7c8797}.wizard-field{display:flex;flex-direction:column;gap:7px}.wizard-field>span{font-size:12px;font-weight:750;color:#344054}.wizard-field b{font-size:9px;color:#c4320a;background:#fff0ed;padding:2px 5px;border-radius:4px}.wizard-field em{font-style:normal;font-size:9px;color:#667085;background:#eef1f5;padding:2px 5px;border-radius:4px}.wizard-field input,.wizard-field textarea{width:100%;box-sizing:border-box;border:1px solid #d8dee8;border-radius:10px;background:#fff;padding:11px 12px;outline:0;font:inherit;color:#263142}.wizard-field input:focus,.wizard-field textarea:focus{border-color:#6b98df;box-shadow:0 0 0 3px #edf4ff}.wizard-field>small{align-self:flex-end;color:#939cab;font-size:9px}.title-field input{font-size:14px}.field-pair{display:grid;grid-template-columns:1fr 1fr;gap:12px}.evidence-picker{display:flex;align-items:center;gap:12px;border:1px dashed #aebdd1;border-radius:11px;background:#f8fbff;padding:13px;cursor:pointer}.evidence-picker>span{display:grid;place-items:center;width:34px;height:34px;border-radius:9px;background:#e7effc;color:#2f66be;font-weight:850}.evidence-picker>div{display:flex;flex-direction:column;min-width:0}.evidence-picker strong{font-size:12px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.evidence-picker small{font-size:10px;color:#8993a2}.evidence-picker button{margin-left:auto;border:0;background:none;color:#b42318;cursor:pointer}
.impact-question{display:flex;justify-content:space-between;gap:18px;align-items:center;border-bottom:1px solid #e4e8ee;padding-bottom:15px}.impact-question>div:first-child{display:flex;flex-direction:column;gap:4px}.impact-question strong{font-size:12px;color:#344054}.impact-question small{font-size:10px;color:#8892a1}.choice-group{display:flex;gap:6px;flex-wrap:wrap;justify-content:flex-end}.choice-group button{border:1px solid #d8dfe8;background:#fff;border-radius:8px;padding:8px 10px;color:#596578;font-size:11px;cursor:pointer}.choice-group button.selected{border-color:#3973d3;background:#edf4ff;color:#245ebd;font-weight:750}.priority-recommendation{display:flex;justify-content:space-between;align-items:center;border-left:4px solid #efa72e;background:#fff9eb;padding:13px 15px;border-radius:0 10px 10px 0}.priority-recommendation>div{display:flex;flex-direction:column}.priority-recommendation span{font-size:9px;color:#8b6a26}.priority-recommendation strong{font-size:15px;color:#604407}.priority-recommendation small{font-size:10px;color:#826a39}.priority-recommendation>b{font-size:10px;color:#785b1d}.priority-recommendation.priority-urgent{border-color:#d64a42;background:#fff3f2}.priority-recommendation.priority-urgent strong{color:#a12c26}.priority-recommendation.priority-high{border-color:#ef8a32;background:#fff6ed}.priority-recommendation.priority-low{border-color:#559477;background:#f0faf5}.priority-options{display:grid;grid-template-columns:repeat(4,1fr);gap:8px}.priority-options button{display:flex;flex-direction:column;gap:3px;border:1px solid #dce2e9;border-radius:9px;background:#fff;padding:10px;cursor:pointer}.priority-options button.selected{border-color:#2f6fed;background:#eff5ff;box-shadow:0 0 0 2px rgba(47,111,237,.1)}.priority-options span{font-weight:800;color:#344054}.priority-options small{font-size:9px;color:#8b95a4}.ticket-review{border:1px solid #dfe4eb;border-radius:11px;background:#fff;overflow:hidden}.ticket-review header{display:flex;flex-direction:column;padding:12px 14px;background:#f6f8fb}.ticket-review dl{margin:0;padding:5px 14px}.ticket-review dl>div{display:grid;grid-template-columns:76px 1fr;gap:10px;padding:8px 0;border-bottom:1px solid #edf0f4}.ticket-review dl>div:last-child{border:0}.ticket-review dt{font-size:10px;color:#8b95a4}.ticket-review dd{margin:0;font-size:11px;color:#344054}.wizard-errors{display:flex;flex-direction:column;gap:4px;margin-top:16px;border:1px solid #f0c4bf;background:#fff5f4;color:#a33a32;padding:11px 13px;border-radius:9px}.wizard-errors strong{font-size:11px}.wizard-errors span{font-size:10px}.wizard-errors span::before{content:"• ".toString()}
.aside-heading{margin-bottom:17px}.wizard-knowledge-list{display:flex;flex-direction:column;gap:9px}.wizard-knowledge-list button{border:1px solid #dce3ed;border-radius:10px;background:#fff;padding:12px;text-align:left;cursor:pointer;transition:.18s}.wizard-knowledge-list button:hover{border-color:#97b5e2;transform:translateY(-1px)}.wizard-knowledge-list span{font-size:9px;color:#2c65c3;font-weight:750}.wizard-knowledge-list strong{display:block;margin:5px 0;font-size:12px;color:#263244}.wizard-knowledge-list p{font-size:10px;line-height:1.5;color:#798596;margin:0}.wizard-knowledge-list small{display:block;margin-top:8px;color:#2f65bd;font-size:9px;font-weight:750}.knowledge-placeholder{min-height:180px;border:1px dashed #c5cfdd;border-radius:12px;display:flex;flex-direction:column;align-items:center;justify-content:center;padding:20px;text-align:center;color:#8390a2}.knowledge-placeholder b{font-size:28px;color:#a6b4c7}.knowledge-placeholder p{font-size:11px;line-height:1.55}.knowledge-placeholder i{width:25px;height:25px;border:3px solid #d7dfeb;border-top-color:#3c74cd;border-radius:50%;animation:spin .8s linear infinite}@keyframes spin{to{transform:rotate(360deg)}}.aside-context{display:flex;flex-direction:column;border-top:1px solid #dce3eb;margin-top:18px;padding-top:15px}.aside-context span{font-size:9px;color:#8994a4;font-weight:800;letter-spacing:.1em}.aside-context strong{font-size:11px;margin:6px 0;color:#344054}.aside-context small{font-size:9px;color:#7e8999}
.wizard-footer{display:flex;align-items:center;justify-content:space-between;padding:15px 28px;background:#fff;border-top:1px solid #e5e9ef}.wizard-footer>div{display:flex;gap:8px}.wizard-footer button{border-radius:9px;padding:10px 14px;font-weight:750;cursor:pointer}.wizard-back,.wizard-cancel{border:1px solid #d9dfe7;background:#fff;color:#566173}.wizard-next,.wizard-submit{border:1px solid #2f6fed;background:#2f6fed;color:#fff}.wizard-submit{padding-inline:20px!important}.wizard-footer button:disabled{opacity:.6;cursor:not-allowed}
.wizard-errors span::before{content:"• "}
@media(max-width:850px){.wizard-backdrop{padding:0}.ticket-wizard{height:100vh;border-radius:0}.wizard-body{grid-template-columns:1fr}.wizard-aside{display:none}.category-grid{grid-template-columns:1fr 1fr}.field-pair{grid-template-columns:1fr}.impact-question{align-items:flex-start;flex-direction:column}.choice-group{justify-content:flex-start}.priority-options{grid-template-columns:1fr 1fr}.wizard-main{padding:22px}.wizard-header,.wizard-progress,.wizard-footer{padding-left:20px;padding-right:20px}}
</style>
