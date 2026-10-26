<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import {
  assetTypeLabels,
  attachmentScanStatusLabels,
  priorityLabels,
  resolutionCodeLabels,
  statusLabels
} from '../constants/domain'
import {
  formatSlaRemaining,
  slaState,
  TICKET_STEPS,
  ticketActions,
  ticketStepState
} from '../app/ticketWorkflow'

const props = defineProps({
  ticketId: { type: Number, required: true },
  api: { type: Function, required: true },
  downloadFile: { type: Function, required: true },
  user: { type: Object, default: null },
  staffMembers: { type: Array, default: () => [] },
  assets: { type: Array, default: () => [] }
})

const emit = defineEmits(['back', 'changed', 'notify', 'error', 'navigate', 'knowledge-created', 'open-knowledge'])

const ticket = ref(null)
const comments = ref([])
const auditLogs = ref([])
const attachments = ref([])
const recommendations = ref([])
const knowledgeDraft = ref(null)
const assetLinks = ref([])
const uploadCapabilities = ref(null)
const loading = ref(true)
const busyAction = ref('')
const submittingComment = ref(false)
const uploading = ref(false)
const deletingAttachmentId = ref(null)
const selectedAssigneeId = ref('')
const assigning = ref(false)
const assetSelection = ref('')
const assetLinkType = ref('AFFECTED')
const linkingAsset = ref(false)
const commentContent = ref('')
const commentVisibility = ref('PUBLIC')
const showEdit = ref(false)
const savingEdit = ref(false)
const pendingTransition = ref(null)
const transitionReason = ref('')
const showResolutionModal = ref(false)
const submittingResolution = ref(false)
const creatingKnowledgeDraft = ref(false)
const resolutionForm = ref({ resolutionCode: 'FIXED', resolutionSummary: '', verificationResult: '' })
const now = ref(Date.now())
const editForm = ref({
  title: '', description: '', impactScope: '', expectedOutcome: '', attemptedActions: '',
  category: '', priority: 'MEDIUM'
})

const roles = computed(() => props.user?.roles || [])
const isStaff = computed(() => roles.value.some(role => ['ADMIN', 'AGENT'].includes(role)))
const availableActions = computed(() => ticketActions(ticket.value?.status, isStaff.value))
const sla = computed(() => slaState(ticket.value, now.value))
const slaRemaining = computed(() => formatSlaRemaining(sla.value.diffMs))
const isTerminal = computed(() => ['CLOSED', 'REJECTED'].includes(ticket.value?.status))
const canCreateKnowledgeDraft = computed(() => isStaff.value
  && ['RESOLVED', 'CLOSED'].includes(ticket.value?.status)
  && Boolean(ticket.value?.confirmedAt && ticket.value?.resolutionSummary))
const assigneeName = computed(() => {
  if (!ticket.value?.assigneeId) return '尚未分配'
  return props.staffMembers.find(member => String(member.id) === String(ticket.value.assigneeId))?.displayName
    || `用户 #${ticket.value.assigneeId}`
})
const availableAssets = computed(() => {
  const linkedIds = new Set(assetLinks.value.map(link => String(link.assetId)))
  return props.assets.filter(asset => asset.status !== 'RETIRED' && !linkedIds.has(String(asset.id)))
})
const timeline = computed(() => [
  ...auditLogs.value.map(item => ({ ...item, type: 'audit', sortAt: item.createdAt })),
  ...comments.value.map(item => ({ ...item, type: 'comment', sortAt: item.createdAt }))
].sort((a, b) => new Date(b.sortAt).getTime() - new Date(a.sortAt).getTime()))

const auditLabels = Object.freeze({
  CREATED: '创建工单',
  TICKET_UPDATED: '更新工单信息',
  STATUS_CHANGED: '更新处理状态',
  COMMENT_ADDED: '补充协作记录',
  ASSIGNED: '分配处理人',
  UNASSIGNED: '释放工单',
  ATTACHMENT_ADDED: '上传附件',
  ATTACHMENT_DELETED: '删除附件',
  ATTACHMENT_SCAN_CLEAN: '附件安全扫描通过',
  ATTACHMENT_SCAN_BLOCKED: '附件已被安全隔离',
  ATTACHMENT_SCAN_FAILED: '附件扫描失败',
  CMDB_ASSET_LINKED: '关联配置项',
  CMDB_ASSET_UNLINKED: '解除配置项关联',
  AUTOMATION_APPLIED: '自动化规则已执行',
  RESOLUTION_SUBMITTED: '提交解决方案',
  RESOLUTION_CONFIRMED: '申请人确认解决',
  RESOLUTION_REOPENED: '申请人退回处理',
  KNOWLEDGE_DRAFT_CREATED: '沉淀知识草稿'
})

const timer = window.setInterval(() => { now.value = Date.now() }, 60000)
onBeforeUnmount(() => window.clearInterval(timer))

watch(() => props.ticketId, loadDetail, { immediate: true })

function reportError(error) {
  emit('error', error?.message || '操作失败，请稍后重试')
}

function formatDate(value) {
  if (!value) return '—'
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit'
  }).format(new Date(value))
}

function formatFileSize(bytes) {
  if (!bytes) return '0 B'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

function normalizeRecords(result) {
  if (Array.isArray(result)) return result
  return result?.records || []
}

async function loadDetail() {
  if (!props.ticketId) return
  loading.value = true
  try {
    const current = await props.api(`/api/v1/tickets/${props.ticketId}`)
    ticket.value = current
    knowledgeDraft.value = null
    selectedAssigneeId.value = current.assigneeId ? String(current.assigneeId) : ''
    const recommendationQuery = current.category
      ? `category=${encodeURIComponent(current.category)}`
      : `keyword=${encodeURIComponent(current.title || '')}`
    const [commentList, logList, attachmentList, articlePage, links, capabilities, draft] = await Promise.all([
      props.api(`/api/v1/tickets/${props.ticketId}/comments`),
      props.api(`/api/v1/tickets/${props.ticketId}/audit-logs`),
      props.api(`/api/v1/tickets/${props.ticketId}/attachments`),
      props.api(`/api/v1/knowledge/articles?page=1&size=3&${recommendationQuery}`).catch(() => ({ records: [] })),
      props.api(`/api/v1/cmdb/tickets/${props.ticketId}/assets`).catch(() => []),
      props.api(`/api/v1/tickets/${props.ticketId}/attachments/upload-capabilities`).catch(() => null),
      isStaff.value
        ? props.api(`/api/v1/tickets/${props.ticketId}/knowledge-draft`).catch(() => null)
        : Promise.resolve(null)
    ])
    comments.value = normalizeRecords(commentList)
    auditLogs.value = normalizeRecords(logList)
    attachments.value = normalizeRecords(attachmentList)
    recommendations.value = normalizeRecords(articlePage)
    assetLinks.value = normalizeRecords(links)
    uploadCapabilities.value = capabilities
    knowledgeDraft.value = draft
    emit('changed', current)
  } catch (error) {
    reportError(error)
  } finally {
    loading.value = false
  }
}

async function refreshActivity() {
  const [commentList, logList] = await Promise.all([
    props.api(`/api/v1/tickets/${props.ticketId}/comments`),
    props.api(`/api/v1/tickets/${props.ticketId}/audit-logs`)
  ])
  comments.value = normalizeRecords(commentList)
  auditLogs.value = normalizeRecords(logList)
}

function requestTransition(action) {
  if (action.resolution) {
    resolutionForm.value = {
      resolutionCode: ticket.value?.resolutionCode || 'FIXED',
      resolutionSummary: ticket.value?.resolutionSummary || '',
      verificationResult: ticket.value?.verificationResult || ''
    }
    showResolutionModal.value = true
    return
  }
  if (action.decision === 'CONFIRM') {
    performResolutionDecision(action)
    return
  }
  if (action.confirm) {
    pendingTransition.value = action
    transitionReason.value = ''
    return
  }
  performTransition(action)
}

async function confirmTransition() {
  if (!pendingTransition.value || !transitionReason.value.trim()) return
  const action = pendingTransition.value
  if (action.decision) {
    pendingTransition.value = null
    const reason = transitionReason.value.trim()
    transitionReason.value = ''
    await performResolutionDecision(action, reason)
    return
  }
  try {
    await props.api(`/api/v1/tickets/${props.ticketId}/comments`, {
      method: 'POST',
      body: JSON.stringify({
        content: transitionReason.value.trim(),
        visibility: action.target === 'REJECTED' ? 'INTERNAL' : 'PUBLIC'
      })
    })
    pendingTransition.value = null
    transitionReason.value = ''
    await performTransition(action)
  } catch (error) {
    reportError(error)
  }
}

async function submitResolution() {
  submittingResolution.value = true
  try {
    const updated = await props.api(`/api/v1/tickets/${props.ticketId}/resolution/submit`, {
      method: 'POST',
      body: JSON.stringify(resolutionForm.value)
    })
    ticket.value = updated
    showResolutionModal.value = false
    emit('changed', updated)
    await refreshActivity()
    emit('notify', '解决方案已提交，正在等待申请人确认')
  } catch (error) {
    reportError(error)
  } finally {
    submittingResolution.value = false
  }
}

async function performResolutionDecision(action, comment = '') {
  busyAction.value = action.target
  try {
    const updated = await props.api(`/api/v1/tickets/${props.ticketId}/resolution/decision`, {
      method: 'POST',
      body: JSON.stringify({ decision: action.decision, comment: comment || null })
    })
    ticket.value = updated
    emit('changed', updated)
    await refreshActivity()
    emit('notify', action.decision === 'CONFIRM' ? '已确认问题解决' : '工单已退回处理团队')
  } catch (error) {
    reportError(error)
  } finally {
    busyAction.value = ''
  }
}

async function createKnowledgeDraft() {
  if (!canCreateKnowledgeDraft.value || creatingKnowledgeDraft.value) return
  creatingKnowledgeDraft.value = true
  try {
    const article = await props.api(`/api/v1/tickets/${props.ticketId}/knowledge-draft`, { method: 'POST' })
    knowledgeDraft.value = article
    emit('knowledge-created', article)
    await refreshActivity()
    emit('notify', '解决方案已生成知识草稿，可继续编辑后发布')
  } catch (error) {
    reportError(error)
  } finally {
    creatingKnowledgeDraft.value = false
  }
}

function openKnowledgeDraft() {
  if (knowledgeDraft.value) emit('open-knowledge', knowledgeDraft.value)
}

async function performTransition(action) {
  busyAction.value = action.target
  try {
    await props.api(`/api/v1/tickets/${props.ticketId}/transition?target=${action.target}`, { method: 'POST' })
    await loadDetail()
    emit('notify', `工单已更新为“${statusLabels[action.target] || action.target}”`)
  } catch (error) {
    reportError(error)
  } finally {
    busyAction.value = ''
  }
}

async function addComment() {
  const content = commentContent.value.trim()
  if (!content) return
  submittingComment.value = true
  try {
    await props.api(`/api/v1/tickets/${props.ticketId}/comments`, {
      method: 'POST',
      body: JSON.stringify({ content, visibility: commentVisibility.value })
    })
    commentContent.value = ''
    commentVisibility.value = 'PUBLIC'
    await refreshActivity()
    emit('notify', '协作记录已添加')
  } catch (error) {
    reportError(error)
  } finally {
    submittingComment.value = false
  }
}

async function claimTicket() {
  busyAction.value = 'claim'
  try {
    await props.api(`/api/v1/tickets/${props.ticketId}/claim`, { method: 'POST' })
    await loadDetail()
    emit('notify', '工单已领取')
  } catch (error) { reportError(error) }
  finally { busyAction.value = '' }
}

async function releaseTicket() {
  busyAction.value = 'release'
  try {
    await props.api(`/api/v1/tickets/${props.ticketId}/release`, { method: 'POST' })
    await loadDetail()
    emit('notify', '工单已释放')
  } catch (error) { reportError(error) }
  finally { busyAction.value = '' }
}

async function assignTicket() {
  if (!selectedAssigneeId.value) return
  assigning.value = true
  try {
    await props.api(`/api/v1/tickets/${props.ticketId}/assign`, {
      method: 'POST',
      body: JSON.stringify({ assigneeId: Number(selectedAssigneeId.value) })
    })
    await loadDetail()
    emit('notify', `工单已指派给 ${assigneeName.value}`)
  } catch (error) { reportError(error) }
  finally { assigning.value = false }
}

function openEditModal() {
  editForm.value = {
    title: ticket.value.title || '',
    description: ticket.value.description || '',
    impactScope: ticket.value.impactScope || '',
    expectedOutcome: ticket.value.expectedOutcome || '',
    attemptedActions: ticket.value.attemptedActions || '',
    category: ticket.value.category || '',
    priority: ticket.value.priority || 'MEDIUM'
  }
  showEdit.value = true
}

async function saveEdit() {
  savingEdit.value = true
  try {
    const updated = await props.api(`/api/v1/tickets/${props.ticketId}`, {
      method: 'PUT',
      body: JSON.stringify(editForm.value)
    })
    ticket.value = updated
    showEdit.value = false
    emit('changed', updated)
    await refreshActivity()
    emit('notify', '工单信息已更新')
  } catch (error) { reportError(error) }
  finally { savingEdit.value = false }
}

async function uploadAttachment(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  const maxBytes = uploadCapabilities.value?.simpleUploadMaxBytes || 20 * 1024 * 1024
  if (file.size > maxBytes) {
    reportError(new Error(`此页面单文件上传上限为 ${formatFileSize(maxBytes)}，更大的文件请使用原分片上传入口`))
    return
  }
  uploading.value = true
  try {
    const body = new FormData()
    body.append('file', file)
    await props.api(`/api/v1/tickets/${props.ticketId}/attachments`, { method: 'POST', body })
    attachments.value = normalizeRecords(await props.api(`/api/v1/tickets/${props.ticketId}/attachments`))
    await refreshActivity()
    emit('notify', '附件已上传，安全扫描完成后可下载')
  } catch (error) { reportError(error) }
  finally { uploading.value = false }
}

function canDownload(attachment) {
  return ['CLEAN', 'RELEASED'].includes(attachment.scanStatus)
}

function canDelete(attachment) {
  return isStaff.value || String(attachment.uploaderId) === String(props.user?.id)
}

async function downloadAttachment(attachment) {
  try {
    await props.downloadFile(
      `/api/v1/tickets/${props.ticketId}/attachments/${attachment.id}/download`,
      attachment.originalName
    )
  } catch (error) { reportError(error) }
}

async function deleteAttachment(attachment) {
  deletingAttachmentId.value = attachment.id
  try {
    await props.api(`/api/v1/tickets/${props.ticketId}/attachments/${attachment.id}`, { method: 'DELETE' })
    attachments.value = attachments.value.filter(item => item.id !== attachment.id)
    await refreshActivity()
    emit('notify', '附件已删除')
  } catch (error) { reportError(error) }
  finally { deletingAttachmentId.value = null }
}

async function linkAsset() {
  if (!assetSelection.value) return
  linkingAsset.value = true
  try {
    await props.api(`/api/v1/cmdb/tickets/${props.ticketId}/assets`, {
      method: 'POST',
      body: JSON.stringify({ assetId: Number(assetSelection.value), linkType: assetLinkType.value, note: '' })
    })
    assetLinks.value = normalizeRecords(await props.api(`/api/v1/cmdb/tickets/${props.ticketId}/assets`))
    assetSelection.value = ''
    await refreshActivity()
    emit('notify', '配置项已关联')
  } catch (error) { reportError(error) }
  finally { linkingAsset.value = false }
}

async function unlinkAsset(link) {
  try {
    await props.api(`/api/v1/cmdb/tickets/${props.ticketId}/assets/${link.assetId}`, { method: 'DELETE' })
    assetLinks.value = assetLinks.value.filter(item => String(item.assetId) !== String(link.assetId))
    await refreshActivity()
    emit('notify', '配置项关联已解除')
  } catch (error) { reportError(error) }
}

function stepState(status) {
  return ticketStepState(ticket.value?.status, status)
}

function timelineTitle(item) {
  if (item.type === 'comment') {
    return `${item.visibility === 'INTERNAL' ? '内部备注' : '公开回复'} · 用户 #${item.authorId}`
  }
  return auditLabels[item.action] || item.action || '系统记录'
}

function linkTypeLabel(type) {
  return { AFFECTED: '受影响', HOSTED_ON: '运行于', RELATED: '相关' }[type] || type
}
</script>

<template>
  <section class="ticket-detail-page">
    <div v-if="loading" class="detail-loading panel-card">
      <span class="loading-orbit"></span>
      <strong>正在建立工单协作上下文…</strong>
      <small>同步流程、SLA、附件和时间线</small>
    </div>

    <template v-else-if="ticket">
      <header class="ticket-hero panel-card">
        <div class="hero-main">
          <button type="button" class="back-button" @click="emit('back')">← 返回工单队列</button>
          <div class="ticket-identity">
            <code>{{ ticket.ticketNo }}</code>
            <span :class="['hero-status', `status-${String(ticket.status).toLowerCase()}`]">
              {{ statusLabels[ticket.status] || ticket.status }}
            </span>
            <span :class="['priority-badge', `priority-${String(ticket.priority).toLowerCase()}`]">
              {{ priorityLabels[ticket.priority] || ticket.priority }}优先级
            </span>
          </div>
          <h2>{{ ticket.title }}</h2>
          <p>{{ ticket.category || '未分类' }} · 由用户 #{{ ticket.requesterId }} 提交 · {{ formatDate(ticket.createdAt) }}</p>
        </div>
        <div class="hero-actions">
          <button v-if="isStaff" type="button" class="secondary-button" @click="openEditModal">编辑信息</button>
          <button
            v-for="action in availableActions"
            :key="action.target"
            type="button"
            :class="action.tone === 'danger' ? 'danger-action' : action.tone === 'secondary' ? 'secondary-button' : 'primary-button'"
            :disabled="Boolean(busyAction)"
            @click="requestTransition(action)"
          >
            {{ busyAction === action.target ? '处理中…' : action.label }}
          </button>
          <span v-if="!availableActions.length && isTerminal" class="terminal-note">该流程已结束</span>
        </div>
      </header>

      <div class="workflow-card panel-card">
        <div class="workflow-title">
          <div><span>STANDARD WORKFLOW</span><strong>标准化处理流程</strong></div>
          <small v-if="ticket.status === 'REJECTED'">此工单已被驳回，流程停止</small>
          <small v-else>当前处于“{{ statusLabels[ticket.status] || ticket.status }}”阶段</small>
        </div>
        <ol :class="['workflow-steps', { rejected: ticket.status === 'REJECTED' }]">
          <li v-for="(step, index) in TICKET_STEPS" :key="step.status" :class="stepState(step.status)">
            <div class="step-marker"><span>{{ stepState(step.status) === 'complete' ? '✓' : index + 1 }}</span></div>
            <div><strong>{{ step.label }}</strong><small>{{ step.caption }}</small></div>
          </li>
        </ol>
      </div>

      <div class="ticket-detail-grid">
        <main class="detail-primary">
          <article class="panel-card detail-section description-card">
            <div class="section-heading">
              <div><span>REQUEST CONTEXT</span><h3>问题描述</h3></div>
              <span>{{ ticket.category || '未分类' }}</span>
            </div>
            <p>{{ ticket.description || '提交人没有补充问题描述。' }}</p>
            <div class="request-context-grid">
              <section>
                <span>影响范围</span>
                <p>{{ ticket.impactScope || '未单独说明影响范围' }}</p>
              </section>
              <section>
                <span>期望结果</span>
                <p>{{ ticket.expectedOutcome || '未单独说明期望结果' }}</p>
              </section>
              <section class="attempted-actions">
                <span>已尝试措施</span>
                <p>{{ ticket.attemptedActions || '提交前暂未记录排查动作' }}</p>
              </section>
            </div>
          </article>

          <article v-if="ticket.resolutionSummary" class="panel-card detail-section resolution-card">
            <div class="section-heading">
              <div><span>RESOLUTION PACKAGE</span><h3>解决方案与验收</h3></div>
              <div class="resolution-heading-actions">
                <span>{{ resolutionCodeLabels[ticket.resolutionCode] || ticket.resolutionCode }}</span>
                <button v-if="knowledgeDraft" type="button" class="inline-action knowledge-draft-action" @click="openKnowledgeDraft">{{ knowledgeDraft.status === 'DRAFT' ? '查看知识草稿' : '查看知识文章' }}</button>
                <button v-else-if="canCreateKnowledgeDraft" type="button" class="inline-action knowledge-draft-action" :disabled="creatingKnowledgeDraft" @click="createKnowledgeDraft">{{ creatingKnowledgeDraft ? '生成中…' : '沉淀为知识' }}</button>
              </div>
            </div>
            <div class="resolution-summary">
              <span>解决摘要</span>
              <p>{{ ticket.resolutionSummary }}</p>
            </div>
            <div class="resolution-verification">
              <span>验证结果</span>
              <p>{{ ticket.verificationResult }}</p>
            </div>
            <footer>
              <small>提交于 {{ formatDate(ticket.resolutionSubmittedAt) }}</small>
              <strong v-if="ticket.confirmedAt">✓ 申请人已于 {{ formatDate(ticket.confirmedAt) }} 确认</strong>
              <strong v-else-if="ticket.status === 'WAITING_CONFIRMATION'">等待申请人验收</strong>
              <strong v-else-if="ticket.status === 'IN_PROGRESS'" class="reopened">方案已返回处理中</strong>
            </footer>
          </article>

          <article class="panel-card detail-section">
            <div class="section-heading">
              <div><span>FILES &amp; EVIDENCE</span><h3>附件与证据</h3></div>
              <b>{{ attachments.length }}</b>
            </div>
            <label class="attachment-uploader">
              <span>{{ uploading ? '上传中…' : '＋ 上传附件' }}</span>
              <small>日志、截图或相关文档</small>
              <input type="file" hidden :disabled="uploading" @change="uploadAttachment" />
            </label>
            <div v-if="!attachments.length" class="detail-empty">暂无附件，可上传日志或截图帮助团队定位。</div>
            <div v-else class="attachment-stack">
              <div v-for="attachment in attachments" :key="attachment.id" class="attachment-row">
                <div class="file-icon">{{ attachment.contentType?.startsWith('image/') ? 'IMG' : 'FILE' }}</div>
                <div class="file-info">
                  <strong>{{ attachment.originalName }}</strong>
                  <small>{{ formatFileSize(attachment.sizeBytes) }} · {{ formatDate(attachment.createdAt) }}</small>
                </div>
                <span :class="['scan-badge', `scan-${String(attachment.scanStatus).toLowerCase()}`]">
                  {{ attachmentScanStatusLabels[attachment.scanStatus] || '等待扫描' }}
                </span>
                <button type="button" class="inline-action" :disabled="!canDownload(attachment)" @click="downloadAttachment(attachment)">
                  下载
                </button>
                <button
                  v-if="canDelete(attachment)"
                  type="button"
                  class="inline-action danger-text"
                  :disabled="deletingAttachmentId === attachment.id"
                  @click="deleteAttachment(attachment)"
                >删除</button>
              </div>
            </div>
          </article>

          <article v-if="recommendations.length" class="panel-card detail-section knowledge-section">
            <div class="section-heading">
              <div><span>KNOWLEDGE SUGGESTIONS</span><h3>推荐解决方案</h3></div>
              <button type="button" class="inline-action" @click="emit('navigate', 'knowledge')">知识库 →</button>
            </div>
            <div class="knowledge-stack">
              <button v-for="article in recommendations" :key="article.id" type="button" @click="emit('navigate', 'knowledge')">
                <span>{{ article.category }}</span>
                <strong>{{ article.title }}</strong>
                <p>{{ String(article.content || '').slice(0, 110) }}{{ String(article.content || '').length > 110 ? '…' : '' }}</p>
              </button>
            </div>
          </article>
        </main>

        <section class="detail-activity panel-card">
          <div class="section-heading activity-heading">
            <div><span>COLLABORATION LOG</span><h3>协作时间线</h3></div>
            <b>{{ timeline.length }}</b>
          </div>
          <form class="activity-composer" @submit.prevent="addComment">
            <textarea v-model="commentContent" required rows="4" placeholder="记录处理进展、验证结果或下一步计划…"></textarea>
            <div>
              <select v-if="isStaff" v-model="commentVisibility" aria-label="评论可见性">
                <option value="PUBLIC">公开回复</option>
                <option value="INTERNAL">内部备注</option>
              </select>
              <span v-else>回复将对处理团队可见</span>
              <button class="primary-button" :disabled="submittingComment">
                {{ submittingComment ? '提交中…' : '发布记录' }}
              </button>
            </div>
          </form>
          <div v-if="!timeline.length" class="detail-empty">还没有协作记录。</div>
          <div v-else class="timeline-list">
            <article v-for="item in timeline" :key="`${item.type}-${item.id}`" :class="['timeline-entry', item.type]">
              <div class="timeline-dot">{{ item.type === 'comment' ? '↳' : '•' }}</div>
              <div>
                <header><strong>{{ timelineTitle(item) }}</strong><time>{{ formatDate(item.sortAt) }}</time></header>
                <p>{{ item.type === 'comment' ? item.content : (item.detail || '系统已记录本次操作') }}</p>
              </div>
            </article>
          </div>
        </section>

        <aside class="detail-sidebar">
          <article :class="['panel-card', 'sla-card', `sla-${sla.key}`]">
            <div class="sla-head"><span>SLA RESPONSE</span><b>{{ sla.label }}</b></div>
            <strong>{{ slaRemaining }}</strong>
            <div class="sla-track"><i></i></div>
            <small>截止时间 {{ formatDate(ticket.dueAt) }}</small>
          </article>

          <article class="panel-card side-card">
            <div class="side-heading"><span>OWNERSHIP</span><h3>负责人与分派</h3></div>
            <div class="owner-summary">
              <span class="owner-avatar">{{ assigneeName.slice(0, 1) }}</span>
              <div><strong>{{ assigneeName }}</strong><small>{{ ticket.assignmentGroupId ? `支持组 #${ticket.assignmentGroupId}` : '尚未加入支持组' }}</small></div>
            </div>
            <template v-if="isStaff && !isTerminal">
              <div class="assign-control">
                <select v-model="selectedAssigneeId" aria-label="选择处理人">
                  <option value="">请选择处理人</option>
                  <option v-for="member in staffMembers" :key="member.id" :value="String(member.id)">{{ member.displayName }}</option>
                </select>
                <button type="button" class="secondary-button" :disabled="!selectedAssigneeId || assigning" @click="assignTicket">
                  {{ assigning ? '指派中…' : '确认指派' }}
                </button>
              </div>
              <div class="ownership-actions">
                <button v-if="!ticket.assigneeId" type="button" :disabled="busyAction === 'claim'" @click="claimTicket">领取工单</button>
                <button v-if="ticket.assigneeId" type="button" :disabled="busyAction === 'release'" @click="releaseTicket">释放工单</button>
              </div>
            </template>
          </article>

          <article class="panel-card side-card">
            <div class="side-heading"><span>TICKET FACTS</span><h3>工单信息</h3></div>
            <dl class="ticket-facts">
              <div><dt>工单编号</dt><dd>{{ ticket.ticketNo }}</dd></div>
              <div><dt>分类</dt><dd>{{ ticket.category || '未分类' }}</dd></div>
              <div><dt>优先级</dt><dd>{{ priorityLabels[ticket.priority] || ticket.priority }}</dd></div>
              <div><dt>提交人</dt><dd>用户 #{{ ticket.requesterId }}</dd></div>
              <div><dt>创建时间</dt><dd>{{ formatDate(ticket.createdAt) }}</dd></div>
              <div><dt>最近更新</dt><dd>{{ formatDate(ticket.updatedAt) }}</dd></div>
            </dl>
          </article>

          <article class="panel-card side-card">
            <div class="side-heading"><span>CONFIGURATION CONTEXT</span><h3>关联配置项</h3></div>
            <div v-if="!assetLinks.length" class="side-empty">尚未关联资产或业务服务。</div>
            <div v-else class="asset-stack">
              <div v-for="link in assetLinks" :key="link.assetId" class="asset-row">
                <div><strong>{{ link.asset?.name || `配置项 #${link.assetId}` }}</strong><small>{{ link.asset?.assetNo }} · {{ assetTypeLabels[link.asset?.assetType] || link.asset?.assetType }} · {{ linkTypeLabel(link.linkType) }}</small></div>
                <button v-if="isStaff" type="button" class="inline-action danger-text" @click="unlinkAsset(link)">解除</button>
              </div>
            </div>
            <div v-if="isStaff && availableAssets.length" class="asset-linker">
              <select v-model="assetSelection"><option value="">选择配置项</option><option v-for="asset in availableAssets" :key="asset.id" :value="String(asset.id)">{{ asset.assetNo }} · {{ asset.name }}</option></select>
              <select v-model="assetLinkType"><option value="AFFECTED">受影响</option><option value="HOSTED_ON">运行于</option><option value="RELATED">相关</option></select>
              <button type="button" class="secondary-button" :disabled="!assetSelection || linkingAsset" @click="linkAsset">{{ linkingAsset ? '关联中…' : '关联配置项' }}</button>
            </div>
          </article>
        </aside>
      </div>
    </template>

    <div v-if="showEdit" class="detail-overlay" @click.self="showEdit = false">
      <form class="detail-dialog" @submit.prevent="saveEdit">
        <header><div><span>EDIT TICKET</span><h2>编辑工单信息</h2></div><button type="button" @click="showEdit = false">×</button></header>
        <label>标题<input v-model="editForm.title" required maxlength="160" /></label>
        <label>问题描述<textarea v-model="editForm.description" required rows="7" maxlength="10000"></textarea></label>
        <div class="dialog-grid">
          <label>影响范围<textarea v-model="editForm.impactScope" rows="3" maxlength="1000"></textarea></label>
          <label>期望结果<textarea v-model="editForm.expectedOutcome" rows="3" maxlength="1000"></textarea></label>
        </div>
        <label>已尝试措施<textarea v-model="editForm.attemptedActions" rows="3" maxlength="3000"></textarea></label>
        <div class="dialog-grid">
          <label>分类<input v-model="editForm.category" maxlength="64" /></label>
          <label>优先级<select v-model="editForm.priority"><option value="LOW">低</option><option value="MEDIUM">中</option><option value="HIGH">高</option><option value="URGENT">紧急</option></select></label>
        </div>
        <footer><button type="button" class="secondary-button" @click="showEdit = false">取消</button><button class="primary-button" :disabled="savingEdit">{{ savingEdit ? '保存中…' : '保存修改' }}</button></footer>
      </form>
    </div>

    <div v-if="pendingTransition" class="detail-overlay" @click.self="pendingTransition = null">
      <form class="detail-dialog transition-dialog" @submit.prevent="confirmTransition">
        <header><div><span>WORKFLOW CONFIRMATION</span><h2>{{ pendingTransition.label }}</h2></div><button type="button" @click="pendingTransition = null">×</button></header>
        <p>{{ pendingTransition.decision === 'REOPEN' ? '请说明哪些问题仍然存在，工单会退回处理团队继续跟进。' : '此操作会改变工单流程。请留下原因，便于后续审计和团队交接。' }}</p>
        <label>{{ pendingTransition.decision === 'REOPEN' ? '未解决原因' : '操作原因' }}<textarea v-model="transitionReason" required rows="4" maxlength="1000" :placeholder="pendingTransition.decision === 'REOPEN' ? '例如：核心功能已恢复，但导出仍然报错…' : '说明原因、处理结论或重新打开的依据…'"></textarea></label>
        <footer><button type="button" class="secondary-button" @click="pendingTransition = null">取消</button><button :class="pendingTransition.tone === 'danger' ? 'danger-action' : 'primary-button'">确认{{ pendingTransition.label }}</button></footer>
      </form>
    </div>

    <div v-if="showResolutionModal" class="detail-overlay" @click.self="showResolutionModal = false">
      <form class="detail-dialog resolution-dialog" @submit.prevent="submitResolution">
        <header><div><span>RESOLUTION HANDOFF</span><h2>提交解决方案</h2></div><button type="button" @click="showResolutionModal = false">×</button></header>
        <p>解决包会展示给申请人。提交后工单进入待确认阶段，申请人可以确认解决或带原因退回。</p>
        <label>解决类型<select v-model="resolutionForm.resolutionCode" required><option v-for="(label, key) in resolutionCodeLabels" :key="key" :value="key">{{ label }}</option></select></label>
        <label>解决摘要<textarea v-model="resolutionForm.resolutionSummary" required rows="6" maxlength="10000" placeholder="说明根因、采取的处理动作以及最终结果…"></textarea></label>
        <label>验证结果<textarea v-model="resolutionForm.verificationResult" required rows="4" maxlength="2000" placeholder="说明如何验证恢复，例如关键功能、监控指标或用户复测结果…"></textarea></label>
        <div class="resolution-guidance"><b>交接检查</b><span>✓ 结论可被申请人理解</span><span>✓ 验证步骤可以复现</span><span>✓ 临时方案注明后续安排</span></div>
        <footer><button type="button" class="secondary-button" @click="showResolutionModal = false">取消</button><button class="primary-button" :disabled="submittingResolution">{{ submittingResolution ? '提交中…' : '提交并请求确认' }}</button></footer>
      </form>
    </div>
  </section>
</template>

<style scoped>
.ticket-detail-page{display:flex;flex-direction:column;gap:16px;min-width:0}.panel-card{border:1px solid #e7eaf0;box-shadow:0 10px 30px rgba(25,37,56,.05)}
.detail-loading{min-height:360px;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:8px;color:#667085}.detail-loading strong{color:#182230;font-size:18px}.loading-orbit{width:34px;height:34px;border:3px solid #e6ebf4;border-top-color:#2f6fed;border-radius:50%;animation:spin .8s linear infinite}@keyframes spin{to{transform:rotate(360deg)}}
.ticket-hero{display:flex;justify-content:space-between;gap:30px;padding:26px 28px;background:linear-gradient(130deg,#fff 45%,#f5f8ff)}.hero-main{min-width:0}.back-button{border:0;background:none;color:#637083;padding:0;margin-bottom:18px;cursor:pointer;font-weight:650}.back-button:hover{color:#255ec7}.ticket-identity{display:flex;align-items:center;gap:9px;flex-wrap:wrap}.ticket-identity code{color:#255ec7;font-weight:800}.ticket-hero h2{font-size:27px;line-height:1.25;margin:12px 0 8px;color:#152033}.ticket-hero p{margin:0;color:#6d7787;font-size:13px}.hero-status,.priority-badge{padding:5px 9px;border-radius:999px;font-size:11px;font-weight:800}.hero-status{background:#eaf1ff;color:#2c65c8}.status-rejected{background:#fff0f0;color:#c33}.status-closed,.status-resolved{background:#eaf8f0;color:#198754}.priority-badge{background:#f2f4f7;color:#475467}.priority-urgent{background:#fff0ed;color:#c4320a}.priority-high{background:#fff6df;color:#9d6600}.hero-actions{display:flex;align-items:flex-start;justify-content:flex-end;gap:8px;flex-wrap:wrap;max-width:460px}.hero-actions button,.danger-action{white-space:nowrap}.danger-action{border:1px solid #f0b4b4;background:#fff5f5;color:#b42318;border-radius:8px;padding:10px 14px;font-weight:700;cursor:pointer}.danger-action:hover{background:#ffe8e8}.terminal-note{padding:9px 12px;background:#f2f4f7;border-radius:8px;color:#667085;font-size:13px}
.workflow-card{padding:20px 24px}.workflow-title{display:flex;align-items:flex-end;justify-content:space-between;margin-bottom:18px}.workflow-title div{display:flex;flex-direction:column;gap:4px}.workflow-title span,.section-heading>div>span,.side-heading>span,.sla-head>span,.detail-dialog header span{color:#8a95a6;font-size:10px;font-weight:850;letter-spacing:.12em}.workflow-title strong{font-size:16px;color:#1b2537}.workflow-title small{color:#697386}.workflow-steps{list-style:none;margin:0;padding:0;display:grid;grid-template-columns:repeat(5,1fr)}.workflow-steps li{position:relative;display:flex;gap:10px;align-items:center;color:#98a2b3}.workflow-steps li:not(:last-child)::after{content:"";position:absolute;height:2px;left:38px;right:5px;top:16px;background:#e7eaf0}.workflow-steps li.complete:not(:last-child)::after{background:#6da684}.step-marker{position:relative;z-index:1;background:#fff;padding-right:7px}.step-marker span{display:grid;place-items:center;width:32px;height:32px;border:2px solid #d8dde6;border-radius:50%;font-size:12px;font-weight:850}.workflow-steps li>div:last-child{display:flex;flex-direction:column;gap:2px;z-index:1;background:#fff;padding-right:8px}.workflow-steps strong{font-size:12px}.workflow-steps small{font-size:10px}.workflow-steps li.complete{color:#28754a}.workflow-steps li.complete .step-marker span{border-color:#5c9d75;background:#eaf6ef}.workflow-steps li.current{color:#255ec7}.workflow-steps li.current .step-marker span{border-color:#2f6fed;background:#eaf1ff;box-shadow:0 0 0 5px #f2f6ff}.workflow-steps.rejected li{color:#b54747}.workflow-steps.rejected .step-marker span{border-color:#efc3c3;background:#fff5f5}
.ticket-detail-grid{display:grid;grid-template-columns:minmax(0,1.15fr) minmax(340px,.95fr) minmax(270px,.62fr);gap:16px;align-items:start}.detail-primary,.detail-sidebar{display:flex;flex-direction:column;gap:16px}.detail-section,.detail-activity,.side-card,.sla-card{padding:20px}.section-heading{display:flex;justify-content:space-between;align-items:center;gap:12px;margin-bottom:16px}.section-heading h3,.side-heading h3{margin:3px 0 0;color:#1c2738;font-size:16px}.section-heading>b{display:grid;place-items:center;min-width:28px;height:28px;border-radius:8px;background:#f0f4fa;color:#41628f}.section-heading>span{font-size:11px;color:#64748b;background:#f5f7fa;border-radius:999px;padding:5px 9px}.description-card>p{margin:0;color:#475467;line-height:1.8;white-space:pre-wrap}.attachment-uploader{display:flex;align-items:center;justify-content:space-between;padding:12px 14px;border:1px dashed #b9c7dc;border-radius:10px;background:#f8fbff;cursor:pointer;margin-bottom:12px}.attachment-uploader span{font-weight:750;color:#2f65bc}.attachment-uploader small{color:#8792a2}.attachment-stack{display:flex;flex-direction:column}.attachment-row{display:grid;grid-template-columns:38px minmax(0,1fr) auto auto auto;align-items:center;gap:10px;padding:11px 0;border-top:1px solid #edf0f4}.file-icon{display:grid;place-items:center;width:34px;height:34px;border-radius:8px;background:#eef3fb;color:#53709c;font-size:9px;font-weight:850}.file-info{display:flex;flex-direction:column;min-width:0}.file-info strong{white-space:nowrap;overflow:hidden;text-overflow:ellipsis;font-size:12px}.file-info small{color:#8a94a4;font-size:10px}.scan-badge{padding:4px 7px;border-radius:999px;background:#fff5df;color:#8b6500;font-size:10px;font-weight:750}.scan-clean,.scan-released{background:#eaf8f0;color:#16764a}.scan-infected,.scan-failed{background:#fff0f0;color:#b42318}.inline-action{border:0;background:none;color:#2b64c0;font-size:11px;font-weight:750;padding:4px;cursor:pointer}.inline-action:disabled{color:#adb5c2;cursor:not-allowed}.danger-text{color:#bd3b34}.detail-empty,.side-empty{padding:20px 10px;text-align:center;color:#8b95a4;font-size:12px}.knowledge-stack{display:grid;gap:10px}.knowledge-stack button{border:1px solid #e5e9ef;border-radius:10px;background:#fff;padding:13px;text-align:left;cursor:pointer;transition:.18s}.knowledge-stack button:hover{border-color:#a9c2eb;transform:translateY(-1px)}.knowledge-stack span{font-size:10px;color:#2c66c3;font-weight:750}.knowledge-stack strong{display:block;margin:4px 0;color:#253044}.knowledge-stack p{margin:0;color:#7b8493;font-size:11px;line-height:1.5}
.request-context-grid{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-top:18px}.request-context-grid section{border:1px solid #e4e8ee;border-radius:10px;background:#fafbfd;padding:12px}.request-context-grid .attempted-actions{grid-column:1/-1}.request-context-grid span{font-size:9px;font-weight:850;letter-spacing:.1em;color:#8792a2}.request-context-grid p{margin:6px 0 0;color:#536072;font-size:11px;line-height:1.6;white-space:pre-wrap}
.resolution-card{border-color:#cfdced;background:linear-gradient(145deg,#fff 58%,#f2f7ff)}.resolution-summary,.resolution-verification{padding:13px 14px;border:1px solid #e0e7f0;border-radius:10px;background:rgba(255,255,255,.82);margin-top:10px}.resolution-summary>span,.resolution-verification>span{font-size:9px;font-weight:850;letter-spacing:.1em;color:#71819a}.resolution-summary p,.resolution-verification p{margin:7px 0 0;white-space:pre-wrap;color:#46566c;font-size:12px;line-height:1.65}.resolution-card footer{display:flex;justify-content:space-between;align-items:center;gap:10px;margin-top:13px}.resolution-card footer small{color:#8994a4}.resolution-card footer strong{font-size:10px;color:#26764b;background:#e9f7ef;padding:5px 8px;border-radius:999px}.resolution-card footer strong.reopened{color:#b54708;background:#fff4e5}
.resolution-heading-actions{display:flex;align-items:center;gap:8px;flex-wrap:wrap;justify-content:flex-end}.resolution-heading-actions>span{font-size:11px;color:#64748b;background:#f5f7fa;border-radius:999px;padding:5px 9px}.knowledge-draft-action{border:1px solid #b9cae5!important;border-radius:999px!important;padding:5px 9px!important;background:#fff!important}
.detail-activity{position:sticky;top:12px;max-height:calc(100vh - 115px);overflow:hidden;display:flex;flex-direction:column}.activity-heading{flex:0 0 auto}.activity-composer{border:1px solid #dfe4eb;border-radius:10px;overflow:hidden;margin-bottom:14px;flex:0 0 auto}.activity-composer textarea{display:block;width:100%;border:0;padding:12px;resize:vertical;outline:0;font:inherit;box-sizing:border-box}.activity-composer>div{display:flex;align-items:center;justify-content:space-between;gap:8px;border-top:1px solid #edf0f4;background:#fafbfc;padding:8px}.activity-composer select{border:0;background:transparent;color:#566173}.activity-composer span{font-size:11px;color:#7f8998}.activity-composer button{padding:8px 11px}.timeline-list{overflow:auto;padding-right:4px}.timeline-entry{display:grid;grid-template-columns:26px 1fr;gap:8px;position:relative;padding:0 0 18px}.timeline-entry:not(:last-child)::before{content:"";position:absolute;left:11px;top:23px;bottom:0;width:1px;background:#e2e7ee}.timeline-dot{width:23px;height:23px;border-radius:50%;display:grid;place-items:center;background:#eef3fb;color:#3867ad;font-weight:800;z-index:1}.timeline-entry.comment .timeline-dot{background:#eaf7f0;color:#28754a}.timeline-entry header{display:flex;justify-content:space-between;gap:10px}.timeline-entry header strong{font-size:12px;color:#344054}.timeline-entry time{font-size:9px;color:#98a2b3;white-space:nowrap}.timeline-entry p{margin:6px 0 0;color:#667085;font-size:11px;line-height:1.55;white-space:pre-wrap}
.sla-card{overflow:hidden}.sla-head{display:flex;align-items:center;justify-content:space-between}.sla-head b{font-size:11px;border-radius:999px;padding:4px 7px;background:#eaf8f0;color:#18764a}.sla-card>strong{display:block;font-size:22px;margin:14px 0 12px;color:#1d2939}.sla-track{height:5px;border-radius:9px;background:#e8edf2;overflow:hidden}.sla-track i{display:block;height:100%;width:68%;background:#36a269}.sla-card>small{display:block;margin-top:9px;color:#7b8493}.sla-due-soon .sla-head b{background:#fff5df;color:#946200}.sla-due-soon .sla-track i{width:88%;background:#e9a52f}.sla-overdue .sla-head b{background:#fff0f0;color:#b42318}.sla-overdue .sla-track i{width:100%;background:#d94b45}.sla-completed .sla-track i{width:100%;background:#7892b7}.sla-unknown .sla-track i{width:0}.side-heading{margin-bottom:15px}.owner-summary{display:flex;gap:11px;align-items:center;margin-bottom:14px}.owner-avatar{display:grid;place-items:center;width:38px;height:38px;border-radius:11px;background:#162b4e;color:#fff;font-weight:800}.owner-summary div{display:flex;flex-direction:column}.owner-summary strong{font-size:13px}.owner-summary small{color:#8892a2;font-size:10px}.assign-control,.asset-linker{display:flex;flex-direction:column;gap:8px}.assign-control select,.asset-linker select{width:100%;padding:9px;border:1px solid #dfe4eb;border-radius:8px;background:#fff}.ownership-actions{display:flex;gap:8px;margin-top:8px}.ownership-actions button{flex:1;border:1px solid #dce2ea;background:#fff;border-radius:8px;padding:8px;color:#526070;cursor:pointer}.ticket-facts{margin:0}.ticket-facts>div{display:flex;justify-content:space-between;gap:12px;padding:9px 0;border-top:1px solid #edf0f4}.ticket-facts dt{font-size:11px;color:#8b95a3}.ticket-facts dd{margin:0;font-size:11px;color:#344054;text-align:right;font-weight:650}.asset-stack{display:flex;flex-direction:column;margin-bottom:12px}.asset-row{display:flex;justify-content:space-between;gap:8px;padding:9px 0;border-top:1px solid #edf0f4}.asset-row>div{display:flex;flex-direction:column}.asset-row strong{font-size:11px}.asset-row small{font-size:9px;color:#8b95a4}
.detail-overlay{position:fixed;inset:0;background:rgba(13,22,38,.48);display:grid;place-items:center;z-index:200;padding:20px}.detail-dialog{width:min(620px,100%);max-height:90vh;overflow:auto;background:#fff;border-radius:16px;padding:24px;box-shadow:0 25px 70px rgba(14,27,49,.25);display:flex;flex-direction:column;gap:15px}.detail-dialog header{display:flex;align-items:flex-start;justify-content:space-between}.detail-dialog h2{margin:4px 0 0}.detail-dialog header button{border:0;background:none;font-size:24px;color:#7a8493;cursor:pointer}.detail-dialog label{display:flex;flex-direction:column;gap:6px;font-size:12px;color:#475467;font-weight:700}.detail-dialog input,.detail-dialog textarea,.detail-dialog select{border:1px solid #d9dfe8;border-radius:9px;padding:10px;font:inherit}.dialog-grid{display:grid;grid-template-columns:1fr 1fr;gap:12px}.detail-dialog footer{display:flex;justify-content:flex-end;gap:8px}.transition-dialog{width:min(500px,100%)}.transition-dialog>p{margin:0;color:#667085;line-height:1.6}
.resolution-dialog>p{margin:0;color:#667085;line-height:1.6}.resolution-guidance{display:flex;gap:8px;flex-wrap:wrap;padding:11px;border-radius:9px;background:#f4f7fb}.resolution-guidance b{width:100%;font-size:10px;color:#53637a}.resolution-guidance span{font-size:9px;color:#66758a}
@media(max-width:1280px){.ticket-detail-grid{grid-template-columns:minmax(0,1fr) minmax(330px,.82fr)}.detail-sidebar{grid-column:1/-1;display:grid;grid-template-columns:repeat(3,1fr)}.detail-activity{position:static;max-height:none}.timeline-list{max-height:560px}}
@media(max-width:900px){.ticket-hero{flex-direction:column}.hero-actions{justify-content:flex-start;max-width:none}.ticket-detail-grid{grid-template-columns:1fr}.detail-sidebar{grid-column:auto;grid-template-columns:1fr}.workflow-steps{grid-template-columns:1fr;gap:8px}.workflow-steps li:not(:last-child)::after{left:16px;right:auto;top:32px;bottom:-8px;width:2px;height:auto}.ticket-detail-page{min-width:0}.dialog-grid,.request-context-grid{grid-template-columns:1fr}.request-context-grid .attempted-actions{grid-column:auto}}
</style>
