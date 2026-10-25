<script setup>
import { computed, onMounted, ref } from 'vue'

const props = defineProps({ api: { type: Function, required: true } })
const templates = ref([])
const detail = ref(null)
const preview = ref(null)
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const notice = ref('')
const eventType = ref('')
const channel = ref('')
const locale = ref('')
const editor = ref(null)
const review = ref(null)
const form = ref(emptyForm())

const channelLabels = { EMAIL: '邮件', WEBHOOK: '个人 Webhook', WECOM: '企业微信' }
const localeLabels = { 'zh-CN': '简体中文', 'en-US': 'English' }
const statusLabels = { DRAFT: '草稿', PENDING_APPROVAL: '待审核', APPROVED: '已批准', REJECTED: '已驳回', PUBLISHED: '已发布', ARCHIVED: '历史版本' }
const eventOptions = [
  ['*', '全部其他事件'], ['TICKET_CREATED', '工单创建'], ['TICKET_ASSIGNED', '工单指派'],
  ['TICKET_STATUS_CHANGED', '工单状态'], ['TICKET_GROUP_ROUTED', '支持组路由'],
  ['SLA_DUE_SOON', 'SLA 即将到期'], ['SLA_OVERDUE', 'SLA 已逾期'],
  ['INCIDENT_DECLARED', '重大事件宣布'], ['INCIDENT_STATUS_CHANGED', '重大事件状态'],
  ['CHANGE_APPROVAL_REQUIRED', '变更待审批'], ['CHANGE_STATUS_CHANGED', '变更状态'],
  ['MONITORING_ALERT', '监控告警'], ['ONCALL_ESCALATION', '值班升级'],
  ['CAPACITY_THRESHOLD', '容量风险预警'], ['REPORT_READY', '报表生成']
]
const enabledCount = computed(() => templates.value.filter(item => item.enabled).length)
const draftCount = computed(() => detail.value?.versions?.filter(item => item.status === 'DRAFT').length || 0)
const pendingCount = computed(() => detail.value?.versions?.filter(item => item.status === 'PENDING_APPROVAL').length || 0)
const approvedCount = computed(() => detail.value?.versions?.filter(item => item.status === 'APPROVED').length || 0)

function emptyForm() {
  return {
    name: '', description: '', eventType: 'INCIDENT_DECLARED', channel: 'WECOM', locale: 'zh-CN',
    titleTemplate: '[{{eventType}}] {{title}}',
    contentTemplate: '{{content}}\n\n通知 #{{notificationId}} · 工单 #{{ticketId}} · {{createdAt}}',
    changeNote: ''
  }
}

async function load(silent = false) {
  if (!silent) loading.value = true
  error.value = ''
  const params = new URLSearchParams()
  if (eventType.value.trim()) params.set('eventType', eventType.value.trim())
  if (channel.value) params.set('channel', channel.value)
  if (locale.value) params.set('locale', locale.value)
  try {
    templates.value = await props.api('/api/v1/admin/notification-templates' + (params.size ? '?' + params : '')) || []
    if (detail.value) {
      const latest = templates.value.find(item => item.id === detail.value.template.id)
      if (!latest) detail.value = null
    }
  } catch (exception) {
    error.value = exception.message || '通知模板加载失败'
  } finally {
    loading.value = false
  }
}

async function openDetail(item) {
  error.value = ''
  preview.value = null
  try {
    detail.value = await props.api('/api/v1/admin/notification-templates/' + item.id)
  } catch (exception) {
    error.value = exception.message || '通知模板详情加载失败'
  }
}

function openCreate() {
  form.value = emptyForm()
  editor.value = { type: 'create' }
}

function openVersion() {
  const source = detail.value?.versions?.[0]
  form.value = {
    ...emptyForm(),
    titleTemplate: source?.titleTemplate || '{{title}}',
    contentTemplate: source?.contentTemplate || '{{content}}',
    changeNote: ''
  }
  editor.value = { type: 'version', template: detail.value.template }
}

async function save() {
  if (!form.value.titleTemplate.trim() || !form.value.contentTemplate.trim()) {
    error.value = '标题模板和正文模板不能为空'
    return
  }
  saving.value = true
  error.value = ''
  try {
    let result
    if (editor.value.type === 'create') {
      result = await props.api('/api/v1/admin/notification-templates', {
        method: 'POST', body: JSON.stringify({
          name: form.value.name.trim(), description: form.value.description.trim() || null,
          eventType: form.value.eventType.trim().toUpperCase(), channel: form.value.channel, locale: form.value.locale,
          titleTemplate: form.value.titleTemplate.trim(), contentTemplate: form.value.contentTemplate.trim(),
          changeNote: form.value.changeNote.trim() || null
        })
      })
      detail.value = result
      notice.value = '模板草稿 v1 已创建，请提交给另一位管理员审核'
    } else {
      await props.api('/api/v1/admin/notification-templates/' + editor.value.template.id + '/versions', {
        method: 'POST', body: JSON.stringify({
          titleTemplate: form.value.titleTemplate.trim(), contentTemplate: form.value.contentTemplate.trim(),
          changeNote: form.value.changeNote.trim() || null
        })
      })
      await openDetail(editor.value.template)
      notice.value = '新草稿版本已创建'
    }
    editor.value = null
    await load(true)
    window.setTimeout(() => { notice.value = '' }, 3000)
  } catch (exception) {
    error.value = exception.message || '通知模板保存失败'
  } finally {
    saving.value = false
  }
}

async function showPreview(version) {
  error.value = ''
  try {
    preview.value = await props.api('/api/v1/admin/notification-templates/versions/' + version.id + '/preview')
  } catch (exception) {
    error.value = exception.message || '模板预览生成失败'
  }
}

async function submitVersion(version) {
  error.value = ''
  try {
    await props.api('/api/v1/admin/notification-templates/versions/' + version.id + '/submit', { method: 'POST' })
    await openDetail(detail.value.template)
    notice.value = '模板 v' + version.versionNo + ' 已提交审核；创建人不能审核自己的版本'
    await load(true)
    window.setTimeout(() => { notice.value = '' }, 3000)
  } catch (exception) {
    error.value = exception.message || '提交审核失败'
  }
}

function openReview(version, decision) {
  review.value = { version, decision, comment: '' }
}

async function saveReview() {
  if (review.value.decision === 'REJECT' && review.value.comment.trim().length < 5) {
    error.value = '驳回时请填写至少 5 个字符的审核意见'
    return
  }
  saving.value = true
  error.value = ''
  try {
    await props.api('/api/v1/admin/notification-templates/versions/' + review.value.version.id + '/review', {
      method: 'POST', body: JSON.stringify({ decision: review.value.decision, comment: review.value.comment.trim() || null })
    })
    const approved = review.value.decision === 'APPROVE'
    review.value = null
    await openDetail(detail.value.template)
    notice.value = approved ? '版本审核通过，现在可以发布' : '版本已驳回并保留审核证据'
    await load(true)
  } catch (exception) {
    error.value = exception.message || '模板审核失败'
  } finally {
    saving.value = false
  }
}

async function publish(version) {
  if (!window.confirm('发布已批准的 v' + version.versionNo + '，是否继续？')) return
  error.value = ''
  try {
    detail.value = await props.api('/api/v1/admin/notification-templates/versions/' + version.id + '/publish', { method: 'POST' })
    notice.value = '模板 v' + version.versionNo + ' 已发布，后续投递将冻结版本、语言与渲染证据'
    await load(true)
  } catch (exception) {
    error.value = exception.message || '模板发布失败'
  }
}

async function toggleTemplate(item) {
  error.value = ''
  try {
    await props.api('/api/v1/admin/notification-templates/' + item.id + '/toggle?enabled=' + !item.enabled, { method: 'POST' })
    notice.value = item.enabled ? '模板已停用，后续通知回退到业务原文' : '模板已启用'
    await load(true)
    if (detail.value?.template?.id === item.id) await openDetail(item)
    window.setTimeout(() => { notice.value = '' }, 2500)
  } catch (exception) {
    error.value = exception.message || '模板状态更新失败'
  }
}

function eventLabel(value) {
  return eventOptions.find(item => item[0] === value)?.[1] || value
}

function formatDate(value) {
  if (!value) return '—'
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value))
}

onMounted(load)
</script>

<template>
  <section class="template-center">
    <header class="template-heading">
      <div><p>APPROVED · LOCALIZED · SCHEDULED</p><h3>通知模板治理</h3><span>双人复核、多语言路由和发送时间窗，共同约束外部通知。</span></div>
      <button @click="openCreate">＋ 新建语言模板</button>
    </header>
    <div class="template-summary">
      <article><span>模板路由</span><strong>{{ templates.length }}</strong><small>事件 × 外部渠道</small></article>
      <article class="enabled"><span>当前启用</span><strong>{{ enabledCount }}</strong><small>已发布并参与渲染</small></article>
      <article><span>当前待审核</span><strong>{{ pendingCount }}</strong><small>{{ draftCount }} 个草稿 · {{ approvedCount }} 个待发布</small></article>
      <article class="evidence"><span>证据冻结</span><strong>ON</strong><small>版本与渲染结果随投递保存</small></article>
    </div>
    <p v-if="error" class="template-error">{{ error }} <button @click="error = ''">×</button></p>
    <p v-if="notice" class="template-notice">✓ {{ notice }}</p>
    <div class="template-toolbar">
      <input v-model="eventType" list="template-event-types" placeholder="事件类型，例如 INCIDENT_DECLARED" @keyup.enter="load()" />
      <datalist id="template-event-types"><option v-for="item in eventOptions" :key="item[0]" :value="item[0]">{{ item[1] }}</option></datalist>
      <select v-model="channel" @change="load()"><option value="">全部外部渠道</option><option v-for="(label, value) in channelLabels" :key="value" :value="value">{{ label }}</option></select>
      <select v-model="locale" @change="load()"><option value="">全部语言</option><option v-for="(label, value) in localeLabels" :key="value" :value="value">{{ label }}</option></select>
      <button @click="load()">查询</button>
    </div>
    <div class="template-layout">
      <div class="template-list">
        <div v-if="loading" class="template-empty">正在读取模板路由…</div>
        <div v-else-if="!templates.length" class="template-empty">还没有通知模板。业务原始标题和正文会直接用于外部投递。</div>
        <article v-for="item in templates" v-else :key="item.id" :class="['template-card', { selected: detail?.template?.id === item.id }]" @click="openDetail(item)">
          <div><span>{{ channelLabels[item.channel] || item.channel }}</span><span class="locale-badge">{{ localeLabels[item.locale] || item.locale }}</span><b :class="{ enabled: item.enabled }">{{ item.enabled ? '启用' : '停用' }}</b></div>
          <strong>{{ item.name }}</strong><code>{{ eventLabel(item.eventType) }} · {{ item.eventType }}</code>
          <p>{{ item.description || '暂无模板说明' }}</p>
          <footer><small>{{ item.versionCount }} 个版本 · 活动 v{{ item.activeVersionNo || '—' }}</small><button @click.stop="toggleTemplate(item)">{{ item.enabled ? '停用' : '启用' }}</button></footer>
        </article>
      </div>
      <aside v-if="detail" class="template-detail">
        <header><div><p>{{ detail.template.eventType }} · {{ detail.template.locale }}</p><h3>{{ detail.template.name }}</h3><span>{{ channelLabels[detail.template.channel] }} · {{ detail.template.enabled ? '活动路由' : '当前停用' }}</span></div><button @click="detail = null; preview = null">×</button></header>
        <div class="version-heading"><div><strong>审批与版本历史</strong><small>{{ pendingCount }} 个待审核 · {{ approvedCount }} 个待发布</small></div><button @click="openVersion">＋ 新草稿版本</button></div>
        <div class="version-list">
          <article v-for="version in detail.versions" :key="version.id" :class="['version-card', String(version.status).toLowerCase()]">
            <div class="version-top"><div><strong>v{{ version.versionNo }}</strong><span>{{ statusLabels[version.status] || version.status }}</span></div><time>{{ formatDate(version.reviewedAt || version.submittedAt || version.publishedAt || version.createdAt) }}</time></div>
            <b>{{ version.titleTemplate }}</b><p>{{ version.contentTemplate }}</p><small>{{ version.changeNote || '未填写版本说明' }}</small>
            <div v-if="version.reviewedBy" class="review-evidence"><b>审核人 #{{ version.reviewedBy }}</b><span>{{ version.reviewComment || '审核通过，未附加意见' }}</span></div>
            <footer><button @click="showPreview(version)">样例预览</button><button v-if="version.status === 'DRAFT'" class="publish" @click="submitVersion(version)">提交审核</button><template v-if="version.status === 'PENDING_APPROVAL'"><button class="reject" @click="openReview(version, 'REJECT')">驳回</button><button class="approve" @click="openReview(version, 'APPROVE')">批准</button></template><button v-if="version.status === 'APPROVED'" class="publish" @click="publish(version)">发布此版本</button></footer>
          </article>
        </div>
        <section v-if="preview" class="preview-card"><span>RENDERED SAMPLE · v{{ preview.versionNo }}</span><h4>{{ preview.title }}</h4><p>{{ preview.content }}</p></section>
      </aside>
      <aside v-else class="template-detail template-guide">
        <h3>企业级模板发布链路</h3><ol><li>按事件、渠道和语言创建唯一模板路由。</li><li>创建人提交草稿，由另一位管理员批准或驳回。</li><li>只有已批准版本可以发布，旧版本自动归档。</li><li>按接收人语言匹配，缺失时回退简体中文。</li><li>投递保存版本、语言和渲染快照。</li></ol>
        <div><strong>允许的变量</strong><code v-pre>{{title}} · {{content}} · {{eventType}} · {{notificationId}} · {{tenantId}} · {{recipientId}} · {{ticketId}} · {{createdAt}}</code></div>
      </aside>
    </div>

    <div v-if="editor" class="editor-backdrop" @click.self="editor = null"><form class="template-editor" @submit.prevent="save">
      <header><div><p>TEMPLATE DRAFT</p><h3>{{ editor.type === 'create' ? '创建通知模板' : '创建新草稿版本' }}</h3><span>保存草稿不会影响当前线上投递。</span></div><button type="button" @click="editor = null">×</button></header>
      <template v-if="editor.type === 'create'"><div class="editor-grid"><label>模板名称<input v-model="form.name" required maxlength="120" placeholder="例如：重大事件企业微信通知" /></label><label>渠道<select v-model="form.channel"><option v-for="(label, value) in channelLabels" :key="value" :value="value">{{ label }}</option></select></label></div><div class="editor-grid"><label>事件类型<input v-model="form.eventType" list="template-event-types" required maxlength="64" /></label><label>语言<select v-model="form.locale"><option v-for="(label, value) in localeLabels" :key="value" :value="value">{{ label }}</option></select></label></div><label>模板说明<textarea v-model="form.description" maxlength="500" rows="2" placeholder="说明使用场景和接收人范围"></textarea></label></template>
      <label>标题模板<input v-model="form.titleTemplate" required maxlength="300" /></label>
      <label>正文模板<textarea v-model="form.contentTemplate" required maxlength="20000" rows="8"></textarea></label>
      <label>版本说明<textarea v-model="form.changeNote" maxlength="500" rows="2" placeholder="本版本调整了什么"></textarea></label>
      <p class="variable-hint">变量使用双花括号。草稿必须由另一位管理员审核通过后才能发布。</p>
      <footer><button type="button" class="cancel" @click="editor = null">取消</button><button class="save" :disabled="saving">{{ saving ? '保存中…' : '保存草稿' }}</button></footer>
    </form></div>
    <div v-if="review" class="editor-backdrop" @click.self="review = null"><form class="template-editor review-editor" @submit.prevent="saveReview">
      <header><div><p>MAKER-CHECKER REVIEW</p><h3>{{ review.decision === 'APPROVE' ? '批准模板版本' : '驳回模板版本' }}</h3><span>正在审核 v{{ review.version.versionNo }}，创建人不能审核自己的版本。</span></div><button type="button" @click="review = null">×</button></header>
      <label>审核意见<textarea v-model="review.comment" maxlength="500" rows="5" :required="review.decision === 'REJECT'" :placeholder="review.decision === 'REJECT' ? '驳回时至少填写 5 个字符' : '可选：记录审核依据'"></textarea></label>
      <footer><button type="button" class="cancel" @click="review = null">取消</button><button :class="review.decision === 'APPROVE' ? 'approve' : 'reject'" :disabled="saving">{{ saving ? '提交中…' : review.decision === 'APPROVE' ? '确认批准' : '确认驳回' }}</button></footer>
    </form></div>
  </section>
</template>

<style scoped>
.template-center{color:#263449}.template-heading{display:flex;align-items:center;justify-content:space-between;gap:20px;padding:19px 21px;border-bottom:1px solid #e8edf2;background:linear-gradient(120deg,#f7f5ff,#f8fbfc)}.template-heading p,.template-detail>header p,.template-editor header p{margin:0 0 4px;color:#7359bd;font-size:9px;font-weight:900;letter-spacing:1px}.template-heading h3,.template-detail h3,.template-editor h3{margin:0 0 4px}.template-heading span,.template-detail header span,.template-editor header span{color:#7e8b9d;font-size:10px}.template-heading>button,.template-toolbar button{padding:9px 12px;border:0;border-radius:8px;background:#6955b6;color:#fff;font-size:10px;font-weight:900;cursor:pointer}.template-summary{display:grid;grid-template-columns:repeat(4,1fr);gap:10px;padding:14px;background:#f8fafc}.template-summary article{padding:13px;border:1px solid #e1e8ef;border-radius:10px;background:#fff}.template-summary span,.template-summary small{display:block;color:#8491a2;font-size:9px}.template-summary strong{display:block;margin:6px 0;color:#304158;font-size:21px}.template-summary .enabled strong{color:#16826c}.template-summary .evidence strong{color:#6955b6}.template-error,.template-notice{margin:12px 14px 0;padding:9px 11px;border-radius:8px;font-size:10px}.template-error{background:#fff0f2;color:#a52e43}.template-error button{float:right;border:0;background:transparent;color:inherit}.template-notice{background:#eaf8f3;color:#147560}.template-toolbar{display:grid;grid-template-columns:1fr 170px 140px 70px;gap:9px;padding:14px;border-bottom:1px solid #e8edf2}.template-toolbar input,.template-toolbar select{padding:10px;border:1px solid #d7e0e8;border-radius:8px;color:#34445a;font-size:10px}.template-layout{display:grid;grid-template-columns:minmax(330px,.8fr) minmax(480px,1.2fr);min-height:510px}.template-list{padding:14px;border-right:1px solid #e8edf2;background:#fbfcfd}.template-empty{padding:60px 20px;color:#8a96a5;text-align:center;font-size:11px}.template-card{margin-bottom:10px;padding:14px;border:1px solid #e0e7ed;border-radius:11px;background:#fff;cursor:pointer}.template-card.selected{border-color:#806bc8;box-shadow:0 0 0 3px rgba(105,85,182,.08)}.template-card>div,.template-card footer,.version-top,.version-card footer,.version-heading{display:flex;align-items:center;justify-content:space-between;gap:9px}.template-card span,.template-card b{padding:3px 7px;border-radius:999px;background:#edf1f5;color:#65748a;font-size:8px}.template-card .locale-badge{margin-right:auto;background:#f0ebff;color:#6955b6}.template-card b.enabled{background:#e4f7f0;color:#14745f}.template-card>strong{display:block;margin:10px 0 5px;font-size:12px}.template-card code{color:#6f5ab7;font-size:9px}.template-card p{min-height:26px;margin:8px 0;color:#7d8999;font-size:9px;line-height:1.45}.template-card footer small{color:#8d98a7;font-size:9px}.template-card footer button,.version-heading button,.version-card footer button{padding:6px 8px;border:1px solid #cfc5eb;border-radius:7px;background:#fff;color:#6552ad;font-size:9px;font-weight:800;cursor:pointer}.template-detail{max-height:680px;padding:18px;overflow:auto}.template-detail>header{display:flex;justify-content:space-between;padding-bottom:14px;border-bottom:1px solid #e8edf2}.template-detail>header>button,.template-editor header>button{border:0;background:transparent;color:#8491a1;font-size:22px}.version-heading{margin:15px 0}.version-heading strong,.version-heading small{display:block}.version-heading small{margin-top:3px;color:#8b96a5;font-size:9px}.version-list{display:grid;gap:10px}.version-card{padding:13px;border:1px solid #e0e7ed;border-radius:10px;background:#fff}.version-card.published{border-color:#a9d9ca;background:#f8fdfb}.version-card.pending_approval{border-color:#eed39c;background:#fffdf7}.version-card.approved{border-color:#9fc9ed;background:#f8fbff}.version-card.rejected{border-color:#e7b7bf;background:#fffafb}.version-top strong{font-size:13px}.version-top span{margin-left:7px;padding:3px 6px;border-radius:999px;background:#eef1f5;color:#68778b;font-size:8px}.version-card.published .version-top span{background:#dcf3eb;color:#14745f}.version-top time{color:#929dac;font-size:9px}.version-card>b{display:block;margin:10px 0 5px;color:#405169;font-size:11px}.version-card>p{max-height:70px;margin:0 0 8px;overflow:auto;color:#6f7e91;font-size:9px;white-space:pre-wrap}.version-card>small{color:#929dab;font-size:8px}.review-evidence{display:grid;gap:3px;margin-top:9px;padding:8px;border-radius:7px;background:#f3f6f9;font-size:8px}.review-evidence span{color:#7d8999}.version-card footer{justify-content:flex-end;margin-top:10px}.version-card footer .publish,.version-card footer .approve{border:0;background:#6955b6;color:#fff}.version-card footer .approve{background:#16826c}.version-card footer .reject{border-color:#dfb0b8;color:#a2384b}.preview-card{margin-top:14px;padding:15px;border-radius:11px;background:#202c43;color:#e9eef6}.preview-card span{color:#9caee7;font-size:8px;font-weight:900;letter-spacing:1px}.preview-card h4{margin:10px 0 7px}.preview-card p{margin:0;color:#c8d2df;font-size:10px;line-height:1.7;white-space:pre-wrap}.template-guide{color:#5d6d82}.template-guide ol{padding-left:20px;font-size:10px;line-height:2}.template-guide>div{padding:13px;border-radius:10px;background:#f4f1fd}.template-guide strong,.template-guide code{display:block}.template-guide code{margin-top:8px;color:#6451a9;font-size:9px;line-height:1.8}.editor-backdrop{position:fixed;z-index:1300;inset:0;display:grid;place-items:center;padding:20px;background:rgba(15,28,44,.6)}.template-editor{width:min(720px,100%);max-height:92vh;padding:22px;overflow:auto;border-radius:16px;background:#fff}.review-editor{width:min(540px,100%)}.template-editor header{display:flex;justify-content:space-between;margin-bottom:17px}.template-editor label{display:grid;gap:6px;margin-top:11px;color:#59697d;font-size:10px;font-weight:800}.template-editor input,.template-editor select,.template-editor textarea{padding:10px;border:1px solid #d7e0e8;border-radius:8px;color:#34445a;font:inherit;resize:vertical}.editor-grid{display:grid;grid-template-columns:1fr 180px;gap:11px}.variable-hint{padding:10px;border-radius:8px;background:#f5f2fd;color:#6755a8;font-size:9px}.variable-hint code{font-weight:900}.template-editor>footer{display:flex;justify-content:flex-end;gap:8px;margin-top:16px}.template-editor>footer button{padding:9px 14px;border-radius:8px;font-size:10px;font-weight:900}.template-editor .cancel{border:1px solid #d6dee6;background:#fff;color:#647287}.template-editor .save,.template-editor .approve{border:0;background:#16826c;color:#fff}.template-editor .reject{border:0;background:#a2384b;color:#fff}@media(max-width:950px){.template-layout{grid-template-columns:1fr}.template-list{border-right:0;border-bottom:1px solid #e8edf2}.template-summary{grid-template-columns:repeat(2,1fr)}}@media(max-width:700px){.template-heading{align-items:flex-start;flex-direction:column}.template-toolbar,.editor-grid{grid-template-columns:1fr}.template-summary{grid-template-columns:1fr}}
</style>
