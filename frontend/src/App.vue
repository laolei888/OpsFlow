<script setup>
import { computed, defineAsyncComponent, onMounted, reactive, ref } from 'vue'

const AlertCenter = defineAsyncComponent(() => import('./components/AlertCenter.vue'))
const GlobalSearch = defineAsyncComponent(() => import('./components/GlobalSearch.vue'))
const IncidentCommandCenter = defineAsyncComponent(() => import('./components/IncidentCommandCenter.vue'))
const OnCallCenter = defineAsyncComponent(() => import('./components/OnCallCenter.vue'))
const OperationsWorkbench = defineAsyncComponent(() => import('./components/OperationsWorkbench.vue'))
const PostmortemCenter = defineAsyncComponent(() => import('./components/PostmortemCenter.vue'))
const ReliabilityCenter = defineAsyncComponent(() => import('./components/ReliabilityCenter.vue'))
const ReleaseGovernanceCenter = defineAsyncComponent(() => import('./components/ReleaseGovernanceCenter.vue'))
const RunbookCenter = defineAsyncComponent(() => import('./components/RunbookCenter.vue'))
const StatusPageCenter = defineAsyncComponent(() => import('./components/StatusPageCenter.vue'))
const StatusPagePublic = defineAsyncComponent(() => import('./components/StatusPagePublic.vue'))
const SupportGroupCenter = defineAsyncComponent(() => import('./components/SupportGroupCenter.vue'))
const CustomerSatisfactionCenter = defineAsyncComponent(() => import('./components/CustomerSatisfactionCenter.vue'))

const TOKEN_KEY = 'opsflow_token'
const USER_KEY = 'opsflow_user'
const token = ref(localStorage.getItem(TOKEN_KEY) || '')
const user = ref(JSON.parse(localStorage.getItem(USER_KEY) || 'null'))
const isPublicStatusPage = window.location.pathname === '/status'
const activeView = ref('overview')
const globalSearchRef = ref(null)
const loading = ref(false)
const error = ref('')
const toast = ref('')
const showTicketModal = ref(false)
const showExportModal = ref(false)
const showAuditExportModal = ref(false)
const showImportModal = ref(false)
const showSavedViewModal = ref(false)
const showArticleModal = ref(false)
const editingArticle = ref(null)
const savingArticle = ref(false)
const showArticleReader = ref(false)
const articleReaderLoading = ref(false)
const selectedArticle = ref(null)
const articleFeedback = ref(null)
const feedbackLoading = ref(false)
const submittingArticleFeedback = ref(false)
const showArticleVersions = ref(false)
const articleVersionsLoading = ref(false)
const articleVersions = ref([])
const selectedArticleVersion = ref(null)
const showAssignModal = ref(false)
const showPasswordModal = ref(false)
const changingPassword = ref(false)
const showNotifications = ref(false)
const loadingNotifications = ref(false)
const showDetail = ref(false)
const showEditTicketModal = ref(false)
const showPreview = ref(false)
const previewAttachmentItem = ref(null)
const previewUrl = ref('')
const previewLoading = ref(false)
const selectedTicket = ref(null)
const ticketComments = ref([])
const ticketAuditLogs = ref([])
const ticketAttachments = ref([])
const recommendedArticles = ref([])
const commentForm = reactive({ content: '', visibility: 'PUBLIC' })
const loadingDetail = ref(false)
const editingTicket = ref(null)
const savingTicketEdit = ref(false)
const uploadingAttachment = ref(false)
const ticketFilter = ref('')
const ticketKeyword = ref('')
const ticketPriority = ref('')
const ticketSort = ref('CREATED_DESC')
const ticketOverdue = ref(false)
const ticketMine = ref(false)
const ticketPageNumber = ref(1)
const ticketPageSize = ref(50)
const MAX_TICKET_SELECTION = 200
const selectedTicketIds = ref([])
const bulkForm = reactive({ status: '', priority: '' })
const exportFieldOptions = [
  { value: 'ticketNo', label: '工单编号', description: '便于与系统记录关联' },
  { value: 'title', label: '标题', description: '问题摘要与请求主题' },
  { value: 'category', label: '分类', description: '网络、账号、设备等分类' },
  { value: 'priority', label: '优先级', description: '低、中、高、紧急' },
  { value: 'status', label: '状态', description: '当前处理阶段' },
  { value: 'requesterId', label: '申请人 ID', description: '提交工单的成员编号' },
  { value: 'assigneeId', label: '处理人 ID', description: '当前负责处理的成员编号' },
  { value: 'dueAt', label: 'SLA 截止', description: '服务时限截止时间' },
  { value: 'createdAt', label: '创建时间', description: '工单创建时间' },
  { value: 'updatedAt', label: '更新时间', description: '最近一次更新的时间' },
  { value: 'description', label: '描述', description: '问题现象、影响范围和处理背景' }
]
const exportFields = ref(exportFieldOptions.map(field => field.value))

const exportingTickets = ref(false)
const exportingDashboardReport = ref(false)
const importFile = ref(null)
const importingTickets = ref(false)
const importResult = ref(null)
const savedViews = ref([])
const selectedSavedViewId = ref('')
const savingSavedView = ref(false)
const editingSavedView = ref(null)
const savedViewForm = reactive({ name: '', visibility: 'PRIVATE' })
const knowledgeKeyword = ref('')
const knowledgeCategory = ref('')
const knowledgeStatus = ref('')
const tickets = ref([])
const articles = ref([])
const knowledgeAnalytics = ref(null)
const ticketTotal = ref(0)
const articleTotal = ref(0)
const slaSummary = ref({ active: 0, overdue: 0, dueSoon: 0, onTrack: 0 })
const dashboardOverview = ref(null)
const dashboardTrends = ref([])
const dashboardReport = ref(null)
const reportSubscriptions = ref([])
const showReportSubscriptionModal = ref(false)
const editingReportSubscription = ref(null)
const savingReportSubscription = ref(false)
const runningReportSubscriptionId = ref(null)
const deletingReportSubscriptionId = ref(null)
const reportSubscriptionForm = reactive({ name: '', frequency: 'DAILY', rangeDays: 14, enabled: true })
const reportRangeDays = ref(14)
const serviceCatalogItems = ref([])
const serviceRequests = ref([])
const serviceRequestFilter = ref('')
const showCatalogItemModal = ref(false)
const editingCatalogItem = ref(null)
const savingCatalogItem = ref(false)
const catalogItemForm = reactive({ name: '', description: '', category: '', defaultPriority: 'MEDIUM', approvalRequired: true, enabled: true, sortOrder: 100 })
const showServiceRequestModal = ref(false)
const selectedCatalogItem = ref(null)
const submittingServiceRequest = ref(false)
const serviceRequestKey = ref('')
const serviceRequestForm = reactive({ summary: '', detail: '' })
const showServiceDecisionModal = ref(false)
const selectedServiceRequest = ref(null)
const serviceDecision = ref('APPROVE')
const decidingServiceRequest = ref(false)
const serviceDecisionForm = reactive({ comment: '' })
const cmdbAssets = ref([])
const changes = ref([])
const problems = ref([])
const webhooks = ref([])
const webhookDeliveries = ref([])
const webhookOutboxEvents = ref([])
const webhookOutboxSummary = ref({ pending: 0, processing: 0, failed: 0, dead: 0, published: 0, oldestPendingAt: null })
const webhookDeliveryStatusFilter = ref('')
const webhookOutboxStatusFilter = ref('')
const selectedWebhookId = ref('')
const showWebhookModal = ref(false)
const editingWebhook = ref(null)
const savingWebhook = ref(false)
const retryingWebhookDeliveryId = ref(null)
const retryingWebhookOutboxId = ref(null)
const webhookForm = reactive({ name: '', url: '', secret: '', eventTypes: '*' })
const problemStatusFilter = ref('')
const problemPriorityFilter = ref('')
const problemKeyword = ref('')
const selectedProblem = ref(null)
const selectedProblemDetail = ref(null)
const problemDetailLoading = ref(false)
const showProblemModal = ref(false)
const savingProblem = ref(false)
const problemForm = reactive({ title: '', description: '', businessImpact: '', priority: 'HIGH', workaround: '', knownError: false, ownerId: '', taskTitles: '' })
const showProblemAnalysisModal = ref(false)
const savingProblemAnalysis = ref(false)
const problemAnalysisForm = reactive({ priority: 'HIGH', rootCause: '', workaround: '', resolution: '', knownError: false, ownerId: '' })
const addingProblemTask = ref(false)
const problemTaskForm = reactive({ title: '', assigneeId: '', sortOrder: 100 })
const problemTicketSelection = ref('')
const problemChangeSelection = ref('')
const linkingProblemTicket = ref(false)
const linkingProblemChange = ref(false)
const changeStatusFilter = ref('')
const changeRiskFilter = ref('')
const changeKeyword = ref('')
const selectedChange = ref(null)
const selectedChangeDetail = ref(null)
const changeDetailLoading = ref(false)
const showChangeModal = ref(false)
const savingChange = ref(false)
const changeForm = reactive({ title: '', description: '', changeType: 'NORMAL', riskLevel: 'MEDIUM', impactScope: '', implementationPlan: '', validationPlan: '', rollbackPlan: '', scheduledStart: '', scheduledEnd: '', linkedTicketId: '', taskTitles: '' })
const showChangeDecisionModal = ref(false)
const changeDecision = ref('APPROVE')
const decidingChange = ref(false)
const changeDecisionForm = reactive({ comment: '' })
const addingChangeTask = ref(false)
const changeTaskForm = reactive({ title: '', assigneeId: '', sortOrder: 100 })
const changeAssetSelection = ref('')
const linkingChangeAsset = ref(false)
const cmdbKeyword = ref('')
const cmdbTypeFilter = ref('')
const cmdbStatusFilter = ref('')
const cmdbCriticalityFilter = ref('')
const selectedCmdbAsset = ref(null)
const cmdbRelations = ref([])
const cmdbImpact = ref(null)
const cmdbContextLoading = ref(false)
const showCmdbAssetModal = ref(false)
const editingCmdbAsset = ref(null)
const savingCmdbAsset = ref(false)
const cmdbAssetForm = reactive({ assetNo: '', name: '', assetType: 'APPLICATION', environment: 'PRODUCTION', status: 'ACTIVE', criticality: 'MEDIUM', ownerId: null, businessSystem: '', location: '', description: '', metadataJson: '' })
const showCmdbRelationModal = ref(false)
const savingCmdbRelation = ref(false)
const cmdbRelationForm = reactive({ targetAssetId: '', relationType: 'DEPENDS_ON', description: '' })
const ticketAssetLinks = ref([])
const ticketAssetSelection = ref('')
const linkingTicketAsset = ref(false)
const staffMembers = ref([])
const assignTarget = ref(null)
const assignForm = reactive({ assigneeId: '' })
const notifications = ref([])
const notificationUnread = ref(0)
const teamUsers = ref([])
const adminAuditLogs = ref([])
const teamLoading = ref(false)
const creatingAdminUser = ref(false)
const showCreateUserModal = ref(false)
const adminAuditFilter = reactive({ action: '', actorId: '', from: '', to: '' })
const auditExportActions = ref([])
const exportingAuditLogs = ref(false)
const adminUserForm = reactive({ username: '', displayName: '', password: '', role: 'AGENT' })
const slaPolicy = ref(null)
const savingSlaPolicy = ref(false)
const slaPolicyForm = reactive({ urgentHours: 4, highHours: 8, mediumHours: 24, lowHours: 72 })
const automationRules = ref([])
const automationSupportGroups = ref([])
const showAutomationModal = ref(false)
const editingAutomationRule = ref(null)
const savingAutomationRule = ref(false)
const automationForm = reactive({
  name: '', enabled: true, sortOrder: 100, matchCategory: '', matchPriority: '', matchKeyword: '',
  targetAssigneeId: '', targetGroupId: '', assignmentStrategy: 'QUEUE_ONLY', targetPriority: '', targetStatus: ''
})
const passwordForm = reactive({ currentPassword: '', newPassword: '' })

const loginForm = reactive({ username: 'opsflow_demo', password: 'opsflow123' })
const ticketForm = reactive({ title: '', description: '', category: '网络', priority: 'MEDIUM' })
const ticketEditForm = reactive({ title: '', description: '', category: '', priority: 'MEDIUM' })
const articleForm = reactive({ title: '', slug: '', category: '网络', content: '' })

const statusLabels = { OPEN: '待处理', IN_PROGRESS: '处理中', WAITING_CONFIRMATION: '待确认', RESOLVED: '已解决', CLOSED: '已关闭', REJECTED: '已驳回' }
const priorityLabels = { LOW: '低', MEDIUM: '中', HIGH: '高', URGENT: '紧急' }
const articleStatusLabels = { DRAFT: '草稿', PUBLISHED: '已发布', ARCHIVED: '已归档' }
const serviceRequestStatusLabels = { PENDING_APPROVAL: '待审批', APPROVED: '已通过', REJECTED: '已驳回' }
const changeTypeLabels = { STANDARD: '标准', NORMAL: '普通', EMERGENCY: '紧急' }
const changeRiskLabels = { LOW: '低', MEDIUM: '中', HIGH: '高', CRITICAL: '严重' }
const changeStatusLabels = { DRAFT: '草稿', PENDING_APPROVAL: '待审批', APPROVED: '已批准', REJECTED: '已驳回', IN_PROGRESS: '实施中', COMPLETED: '已完成', FAILED: '失败', CANCELLED: '已取消' }
const changeTaskStatusLabels = { TODO: '待执行', IN_PROGRESS: '执行中', DONE: '已完成' }
const problemPriorityLabels = { LOW: '低', MEDIUM: '中', HIGH: '高', CRITICAL: '严重' }
const problemStatusLabels = { OPEN: '待分析', INVESTIGATING: '分析中', KNOWN_ERROR: '已知错误', PENDING_CHANGE: '等待变更', RESOLVED: '已解决', CLOSED: '已关闭', CANCELLED: '已取消' }
const problemTaskStatusLabels = { TODO: '待执行', IN_PROGRESS: '执行中', DONE: '已完成' }
const webhookStatusLabels = { PENDING: '待发送', RETRYING: '重试中', DELIVERED: '已送达', DEAD: '死信' }
const webhookOutboxStatusLabels = { PENDING: '待展开', PROCESSING: '处理中', PUBLISHED: '已展开', FAILED: '待重试', DEAD: '死信' }
const problemTicketLinkLabels = { SYMPTOM: '症状工单', RELATED: '相关工单' }
const problemChangeLinkLabels = { REMEDIATION: '修复变更', RELATED: '相关变更' }
const assetTypeLabels = { BUSINESS_SERVICE: '业务服务', APPLICATION: '应用系统', SERVER: '服务器', DATABASE: '数据库', NETWORK: '网络设备', DEVICE: '终端设备', CLOUD_RESOURCE: '云资源', OTHER: '其他' }
const assetEnvironmentLabels = { PRODUCTION: '生产', STAGING: '预发布', DEVELOPMENT: '开发', TEST: '测试' }
const assetStatusLabels = { ACTIVE: '运行中', MAINTENANCE: '维护中', RETIRED: '已退役' }
const assetCriticalityLabels = { CRITICAL: '核心', HIGH: '高', MEDIUM: '中', LOW: '低' }
const assetRelationLabels = { DEPENDS_ON: '依赖', RUNS_ON: '运行于', CONNECTS_TO: '连接', BACKED_BY: '由…支撑', MONITORS: '监控' }
const articleVersionChangeLabels = { CREATED: '创建文章', UPDATED: '编辑内容', PUBLISHED: '发布状态', ARCHIVED: '归档状态', RESTORED: '恢复状态', BACKFILL: '历史初始化', VERSION_RESTORED: '恢复历史版本' }
const ticketSortLabels = { PRIORITY_DESC: '优先级最高', SLA_ASC: 'SLA 最近', UPDATED_DESC: '最近更新', CREATED_DESC: '最新创建', CREATED_ASC: '最早创建' }
const ticketSortOptions = [
  { value: 'PRIORITY_DESC', label: '优先级最高' },
  { value: 'SLA_ASC', label: 'SLA 最近' },
  { value: 'UPDATED_DESC', label: '最近更新' },
  { value: 'CREATED_DESC', label: '最新创建' },
  { value: 'CREATED_ASC', label: '最早创建' }
]
const reportRangeOptions = [
  { value: 7, label: '近 7 天' },
  { value: 14, label: '近 14 天' },
  { value: 30, label: '近 30 天' }
]
const auditLabels = { CREATED: '创建工单', KNOWLEDGE_ARTICLE_CREATED: '创建知识文章', KNOWLEDGE_ARTICLE_UPDATED: '编辑知识文章', KNOWLEDGE_ARTICLE_PUBLISHED: '发布知识文章', KNOWLEDGE_ARTICLE_ARCHIVED: '归档知识文章', KNOWLEDGE_ARTICLE_RESTORED: '恢复知识文章', KNOWLEDGE_ARTICLE_VERSION_RESTORED: '恢复历史版本', TICKET_UPDATED: '编辑工单', STATUS_CHANGED: '状态流转', COMMENT_ADDED: '新增评论', BULK_UPDATED: '批量更新工单', ASSIGNED: '领取 / 指派', UNASSIGNED: '释放工单', ATTACHMENT_ADDED: '添加附件', ATTACHMENT_DELETED: '删除附件', USER_CREATED: '创建用户', USER_STATUS_CHANGED: '更新用户状态', USER_ROLE_CHANGED: '更新用户角色', PASSWORD_CHANGED: '修改密码', SLA_POLICY_UPDATED: '更新 SLA 策略', EXPORT_GENERATED: '导出数据', IMPORT_GENERATED: '批量导入', SAVED_VIEW_CREATED: '创建保存视图', SAVED_VIEW_UPDATED: '更新保存视图', SAVED_VIEW_DELETED: '删除保存视图', AUTOMATION_RULE_CREATED: '创建自动化规则', AUTOMATION_RULE_UPDATED: '更新自动化规则', AUTOMATION_RULE_TOGGLED: '切换自动化规则', AUTOMATION_RULE_DELETED: '删除自动化规则', AUTOMATION_APPLIED: '执行自动化规则', REPORT_SUBSCRIPTION_CREATED: '创建报表订阅', REPORT_SUBSCRIPTION_UPDATED: '更新报表订阅', REPORT_SUBSCRIPTION_DELETED: '删除报表订阅', REPORT_SUBSCRIPTION_RUN: '生成订阅报表', SERVICE_CATALOG_ITEM_CREATED: '创建服务目录项', SERVICE_CATALOG_ITEM_UPDATED: '更新服务目录项', SERVICE_CATALOG_ITEM_TOGGLED: '切换服务目录项', SERVICE_REQUEST_CREATED: '提交服务申请', SERVICE_REQUEST_AUTO_APPROVED: '自动受理服务申请', SERVICE_REQUEST_APPROVED: '通过服务审批', SERVICE_REQUEST_REJECTED: '驳回服务审批', CMDB_ASSET_CREATED: '登记配置项', CMDB_ASSET_UPDATED: '更新配置项', CMDB_RELATION_CREATED: '建立资产关系', CMDB_RELATION_DELETED: '解除资产关系', CMDB_ASSET_LINKED: '关联工单配置项', CMDB_ASSET_UNLINKED: '解除工单配置项', INCIDENT_DECLARED: '宣布重大事件', INCIDENT_UPDATED: '更新事件指挥资料', INCIDENT_STATUS_CHANGED: '重大事件状态流转', INCIDENT_UPDATE_ADDED: '发布事件动态', INCIDENT_TASK_CREATED: '新增事件协同任务', INCIDENT_TASK_UPDATED: '更新事件协同任务', INCIDENT_TICKET_LINKED: '关联事件工单', INCIDENT_TICKET_UNLINKED: '解除事件工单关联', INCIDENT_ASSET_LINKED: '关联受影响配置项', INCIDENT_ASSET_UNLINKED: '解除事件配置项关联', ONCALL_SCHEDULE_CREATED: '创建值班表', ONCALL_SCHEDULE_UPDATED: '更新值班表', ONCALL_SHIFT_CREATED: '创建值班轮班', ONCALL_SHIFT_UPDATED: '更新值班轮班', ONCALL_SHIFT_DELETED: '删除值班轮班', ONCALL_POLICY_CREATED: '创建升级策略', ONCALL_POLICY_UPDATED: '更新升级策略', ONCALL_ESCALATION_STEP_CREATED: '新增升级步骤', ONCALL_ESCALATION_STEP_UPDATED: '更新升级步骤', ONCALL_ESCALATION_STEP_DELETED: '删除升级步骤', ONCALL_ESCALATION_TRIGGERED: '触发值班升级', ONCALL_ESCALATION_DISPATCHED: '派发值班升级通知', ONCALL_ESCALATION_RESOLVED: '停止值班升级', ONCALL_ESCALATION_CANCELLED: '取消值班升级' }
Object.assign(auditLabels, {
  ALERT_SOURCE_CREATED: '创建告警源', ALERT_SOURCE_UPDATED: '更新告警源', ALERT_SOURCE_KEY_ROTATED: '轮换告警密钥',
  ALERT_RULE_CREATED: '创建告警规则', ALERT_RULE_UPDATED: '更新告警规则',
  ALERT_SILENCE_CREATED: '创建静默窗口', ALERT_SILENCE_UPDATED: '更新静默窗口', ALERT_SILENCE_DELETED: '删除静默窗口',
  ALERT_TRIGGERED: '触发监控告警', ALERT_ACKNOWLEDGED: '确认监控告警', ALERT_RESOLVED: '解决监控告警',
  ALERT_SUPPRESSED: '抑制监控告警', ALERT_REOPENED: '重新打开告警', ALERT_SEVERITY_ESCALATED: '告警严重度升级',
  ALERT_RULE_APPLIED: '执行告警自动化规则',
  STATUS_COMPONENT_CREATED: '创建公开服务组件', STATUS_COMPONENT_UPDATED: '更新公开服务组件',
  STATUS_INCIDENT_CREATED: '创建服务公告草稿', STATUS_INCIDENT_UPDATED: '更新服务公告',
  STATUS_INCIDENT_UPDATE_POSTED: '记录服务公告进展', STATUS_INCIDENT_PUBLISHED: '发布服务公告',
  STATUS_INCIDENT_UNPUBLISHED: '撤回服务公告', STATUS_INCIDENT_RESOLVED: '服务恢复公告',
  SLO_DEFINITION_CREATED: '创建服务等级目标', SLO_DEFINITION_UPDATED: '更新服务等级目标',
  SLO_MEASUREMENT_RECORDED: '记录 SLI 样本', SLO_BREACH_CREATED: '创建错误预算违规',
  SLO_BREACH_ESCALATED: '升级错误预算违规', SLO_BREACH_ACKNOWLEDGED: '确认错误预算违规',
  SLO_BREACH_RESOLVED: '关闭错误预算违规', SLO_BREACH_TICKET_LINKED: '关联预算违规工单',
  SLO_BREACH_INCIDENT_LINKED: '关联预算违规重大事件',
  RELEASE_CREATED: '创建发布单', RELEASE_UPDATED: '更新发布计划',
  RELEASE_CHANGE_LINKED: '关联发布变更', RELEASE_CHANGE_UNLINKED: '移除发布变更',
  RELEASE_CHECKLIST_CREATED: '新增发布门禁', RELEASE_CHECKLIST_UPDATED: '记录发布门禁结果',
  RELEASE_GO_APPROVED: '通过发布 Go 决策', RELEASE_NO_GO: '阻断发布 No-Go',
  RELEASE_STARTED: '开始发布实施', RELEASE_VERIFIED: '完成发布验证',
  RELEASE_ROLLED_BACK: '发布回滚', RELEASE_CANCELLED: '取消发布',
  RELEASE_FREEZE_CREATED: '创建发布冻结窗口', RELEASE_FREEZE_UPDATED: '更新发布冻结窗口',
  RELEASE_FREEZE_DELETED: '删除发布冻结窗口', RELEASE_FREEZE_OVERRIDE: '紧急变更绕过发布冻结',
  RUNBOOK_CREATED: '创建运行手册', RUNBOOK_UPDATED: '更新运行手册',
  RUNBOOK_STEP_CREATED: '新增运行手册步骤', RUNBOOK_STEP_UPDATED: '更新运行手册步骤',
  RUNBOOK_EXECUTION_STARTED: '发起运行手册处置', RUNBOOK_EXECUTION_STEP_UPDATED: '记录处置步骤证据',
  RUNBOOK_EXECUTION_COMPLETED: '完成运行手册处置', RUNBOOK_EXECUTION_FAILED: '标记运行手册处置失败',
  RUNBOOK_EXECUTION_CANCELLED: '取消运行手册处置',
  RUNBOOK_ALERT_MATCHER_CREATED: '创建告警运行手册推荐规则', RUNBOOK_ALERT_MATCHER_UPDATED: '更新告警运行手册推荐规则'
})
const auditActionOptions = [
  { value: 'CREATED', label: '创建工单' },
  { value: 'KNOWLEDGE_ARTICLE_CREATED', label: '创建知识文章' },
  { value: 'KNOWLEDGE_ARTICLE_UPDATED', label: '编辑知识文章' },
  { value: 'KNOWLEDGE_ARTICLE_PUBLISHED', label: '发布知识文章' },
  { value: 'KNOWLEDGE_ARTICLE_ARCHIVED', label: '归档知识文章' },
  { value: 'KNOWLEDGE_ARTICLE_RESTORED', label: '恢复知识文章' },  { value: 'KNOWLEDGE_ARTICLE_VERSION_RESTORED', label: '恢复历史版本' },
  { value: 'TICKET_UPDATED', label: '编辑工单' },
  { value: 'STATUS_CHANGED', label: '状态流转' },
  { value: 'COMMENT_ADDED', label: '新增评论' },
  { value: 'ASSIGNED', label: '领取 / 指派' },
  { value: 'UNASSIGNED', label: '释放工单' },
  { value: 'BULK_UPDATED', label: '批量更新' },
  { value: 'ATTACHMENT_ADDED', label: '添加附件' },
  { value: 'ATTACHMENT_DELETED', label: '删除附件' },
  { value: 'USER_CREATED', label: '创建用户' },
  { value: 'USER_STATUS_CHANGED', label: '用户状态' },
  { value: 'USER_ROLE_CHANGED', label: '用户角色' },
  { value: 'PASSWORD_CHANGED', label: '修改密码' },
  { value: 'SLA_POLICY_UPDATED', label: '更新 SLA 策略' },
  { value: 'EXPORT_GENERATED', label: '导出数据' },
  { value: 'IMPORT_GENERATED', label: '批量导入' },
  { value: 'SAVED_VIEW_CREATED', label: '创建保存视图' },
  { value: 'SAVED_VIEW_UPDATED', label: '更新保存视图' },
  { value: 'SAVED_VIEW_DELETED', label: '删除保存视图' },
  { value: 'AUTOMATION_RULE_CREATED', label: '创建自动化规则' },
  { value: 'AUTOMATION_RULE_UPDATED', label: '更新自动化规则' },
  { value: 'AUTOMATION_RULE_TOGGLED', label: '切换自动化规则' },
  { value: 'AUTOMATION_RULE_DELETED', label: '删除自动化规则' },
  { value: 'AUTOMATION_APPLIED', label: '执行自动化规则' },
  { value: 'REPORT_SUBSCRIPTION_CREATED', label: '创建报表订阅' },
  { value: 'REPORT_SUBSCRIPTION_UPDATED', label: '更新报表订阅' },
  { value: 'REPORT_SUBSCRIPTION_DELETED', label: '删除报表订阅' },
  { value: 'REPORT_SUBSCRIPTION_RUN', label: '生成订阅报表' },
  { value: 'SERVICE_CATALOG_ITEM_CREATED', label: '创建服务目录项' },
  { value: 'SERVICE_CATALOG_ITEM_UPDATED', label: '更新服务目录项' },
  { value: 'SERVICE_CATALOG_ITEM_TOGGLED', label: '切换服务目录项' },
  { value: 'SERVICE_REQUEST_CREATED', label: '提交服务申请' },
  { value: 'SERVICE_REQUEST_AUTO_APPROVED', label: '自动受理服务申请' },
  { value: 'SERVICE_REQUEST_APPROVED', label: '通过服务审批' },
  { value: 'SERVICE_REQUEST_REJECTED', label: '驳回服务审批' },
  { value: 'INCIDENT_DECLARED', label: '宣布重大事件' },
  { value: 'INCIDENT_UPDATED', label: '更新事件指挥资料' },
  { value: 'INCIDENT_STATUS_CHANGED', label: '重大事件状态流转' },
  { value: 'INCIDENT_UPDATE_ADDED', label: '发布事件动态' },
  { value: 'INCIDENT_TASK_CREATED', label: '新增事件协同任务' },
  { value: 'INCIDENT_TASK_UPDATED', label: '更新事件协同任务' },
  { value: 'INCIDENT_TICKET_LINKED', label: '关联事件工单' },
  { value: 'INCIDENT_TICKET_UNLINKED', label: '解除事件工单关联' },
  { value: 'INCIDENT_ASSET_LINKED', label: '关联受影响配置项' },
  { value: 'INCIDENT_ASSET_UNLINKED', label: '解除事件配置项关联' },
  { value: 'POSTMORTEM_CREATED', label: '创建事件复盘' },
  { value: 'POSTMORTEM_UPDATED', label: '更新事件复盘' },
  { value: 'POSTMORTEM_ACTION_CREATED', label: '新增复盘整改项' },
  { value: 'POSTMORTEM_ACTION_UPDATED', label: '更新复盘整改项' },
  { value: 'POSTMORTEM_ACTION_DELETED', label: '删除复盘整改项' },
  { value: 'POSTMORTEM_SUBMITTED', label: '提交复盘评审' },
  { value: 'POSTMORTEM_APPROVED', label: '批准事件复盘' },
  { value: 'POSTMORTEM_REJECTED', label: '驳回事件复盘' },
  { value: 'POSTMORTEM_PUBLISHED', label: '发布事件复盘' },
  { value: 'ONCALL_SCHEDULE_CREATED', label: '创建值班表' },
  { value: 'ONCALL_SCHEDULE_UPDATED', label: '更新值班表' },
  { value: 'ONCALL_SHIFT_CREATED', label: '创建值班轮班' },
  { value: 'ONCALL_SHIFT_UPDATED', label: '更新值班轮班' },
  { value: 'ONCALL_SHIFT_DELETED', label: '删除值班轮班' },
  { value: 'ONCALL_POLICY_CREATED', label: '创建升级策略' },
  { value: 'ONCALL_POLICY_UPDATED', label: '更新升级策略' },
  { value: 'ONCALL_ESCALATION_STEP_CREATED', label: '新增升级步骤' },
  { value: 'ONCALL_ESCALATION_STEP_UPDATED', label: '更新升级步骤' },
  { value: 'ONCALL_ESCALATION_STEP_DELETED', label: '删除升级步骤' },
  { value: 'ONCALL_ESCALATION_TRIGGERED', label: '触发值班升级' },
  { value: 'ONCALL_ESCALATION_DISPATCHED', label: '派发值班升级通知' },
  { value: 'ONCALL_ESCALATION_RESOLVED', label: '停止值班升级' },
  { value: 'ONCALL_ESCALATION_CANCELLED', label: '取消值班升级' },
  { value: 'ALERT_SOURCE_CREATED', label: '创建告警源' },
  { value: 'ALERT_SOURCE_UPDATED', label: '更新告警源' },
  { value: 'ALERT_SOURCE_KEY_ROTATED', label: '轮换告警密钥' },
  { value: 'ALERT_RULE_CREATED', label: '创建告警规则' },
  { value: 'ALERT_RULE_UPDATED', label: '更新告警规则' },
  { value: 'ALERT_SILENCE_CREATED', label: '创建静默窗口' },
  { value: 'ALERT_SILENCE_UPDATED', label: '更新静默窗口' },
  { value: 'ALERT_SILENCE_DELETED', label: '删除静默窗口' },
  { value: 'ALERT_TRIGGERED', label: '触发监控告警' },
  { value: 'ALERT_ACKNOWLEDGED', label: '确认监控告警' },
  { value: 'ALERT_RESOLVED', label: '解决监控告警' },
  { value: 'ALERT_SUPPRESSED', label: '抑制监控告警' },
  { value: 'ALERT_REOPENED', label: '重新打开告警' },
  { value: 'ALERT_SEVERITY_ESCALATED', label: '告警严重度升级' },
  { value: 'ALERT_RULE_APPLIED', label: '执行告警自动化规则' }
]
const nextStatus = { OPEN: 'IN_PROGRESS', IN_PROGRESS: 'WAITING_CONFIRMATION', WAITING_CONFIRMATION: 'RESOLVED', RESOLVED: 'CLOSED' }
function canAdvanceTicket(ticket) {
  return canBulkUpdate.value || ['WAITING_CONFIRMATION', 'RESOLVED'].includes(ticket?.status)
}
const displayName = computed(() => user.value?.displayName || user.value?.username || '协作成员')
const isAdmin = computed(() => (user.value?.roles || []).includes('ADMIN'))
const canBulkUpdate = computed(() => (user.value?.roles || []).some(role => ['AGENT', 'ADMIN'].includes(role)))
const canWriteInternal = canBulkUpdate
const pendingServiceRequestCount = computed(() => serviceRequests.value.filter(item => item.status === 'PENDING_APPROVAL').length)
const pendingChangeCount = computed(() => changes.value.filter(item => item.status === 'PENDING_APPROVAL').length)
const pendingProblemCount = computed(() => problems.value.filter(item => ['OPEN', 'INVESTIGATING', 'KNOWN_ERROR', 'PENDING_CHANGE'].includes(item.status)).length)
const webhookEnabledCount = computed(() => webhooks.value.filter(item => item.enabled).length)
const webhookPendingCount = computed(() => webhookDeliveries.value.filter(item => ['PENDING', 'RETRYING'].includes(item.delivery?.status)).length)
const webhookDeadCount = computed(() => webhookDeliveries.value.filter(item => item.delivery?.status === 'DEAD').length)
const webhookOutboxPendingCount = computed(() => Number(webhookOutboxSummary.value?.pending || 0)
  + Number(webhookOutboxSummary.value?.processing || 0) + Number(webhookOutboxSummary.value?.failed || 0))
const webhookOutboxDeadCount = computed(() => Number(webhookOutboxSummary.value?.dead || 0))
const filteredWebhookOutboxEvents = computed(() => webhookOutboxStatusFilter.value
  ? webhookOutboxEvents.value.filter(item => item.status === webhookOutboxStatusFilter.value)
  : webhookOutboxEvents.value)
const filteredWebhookDeliveries = computed(() => webhookDeliveries.value.filter(item => {
  const delivery = item.delivery || {}
  return (!selectedWebhookId.value || String(delivery.endpointId) === String(selectedWebhookId.value))
    && (!webhookDeliveryStatusFilter.value || delivery.status === webhookDeliveryStatusFilter.value)
}))
const filteredProblems = computed(() => {
  const keyword = problemKeyword.value.trim().toLowerCase()
  return problems.value.filter(item => {
    const searchable = [item.problemNo, item.title, item.description, item.rootCause].filter(Boolean).join(' ').toLowerCase()
    return (!keyword || searchable.includes(keyword))
      && (!problemStatusFilter.value || item.status === problemStatusFilter.value)
      && (!problemPriorityFilter.value || item.priority === problemPriorityFilter.value)
  })
})
const knownErrorProblemCount = computed(() => problems.value.filter(item => item.status === 'KNOWN_ERROR' || item.knownError).length)
const problemHighPriorityCount = computed(() => problems.value.filter(item => ['HIGH', 'CRITICAL'].includes(item.priority) && !['CLOSED', 'CANCELLED'].includes(item.status)).length)
const availableProblemTickets = computed(() => {
  const linked = new Set((selectedProblemDetail.value?.tickets || []).map(item => String(item.ticketId)))
  return tickets.value.filter(ticket => !linked.has(String(ticket.id)))
})
const availableProblemChanges = computed(() => {
  const linked = new Set((selectedProblemDetail.value?.changes || []).map(item => String(item.changeId)))
  return changes.value.filter(change => !linked.has(String(change.id)) && !['REJECTED', 'CANCELLED'].includes(change.status))
})
const filteredChanges = computed(() => {
  const keyword = changeKeyword.value.trim().toLowerCase()
  return changes.value.filter(item => {
    const searchable = [item.changeNo, item.title, item.description].filter(Boolean).join(' ').toLowerCase()
    return (!keyword || searchable.includes(keyword))
      && (!changeStatusFilter.value || item.status === changeStatusFilter.value)
      && (!changeRiskFilter.value || item.riskLevel === changeRiskFilter.value)
  })
})
const changeHighRiskCount = computed(() => changes.value.filter(item => ['HIGH', 'CRITICAL'].includes(item.riskLevel) && !['COMPLETED', 'CANCELLED', 'REJECTED'].includes(item.status)).length)
const changeInProgressCount = computed(() => changes.value.filter(item => item.status === 'IN_PROGRESS').length)
const availableChangeAssets = computed(() => {
  const linked = new Set((selectedChangeDetail.value?.assets || []).map(item => String(item.assetId)))
  return cmdbAssets.value.filter(asset => asset.status !== 'RETIRED' && !linked.has(String(asset.id)))
})
const filteredServiceRequests = computed(() => serviceRequestFilter.value
  ? serviceRequests.value.filter(item => item.status === serviceRequestFilter.value)
  : serviceRequests.value)
const filteredCmdbAssets = computed(() => {
  const keyword = cmdbKeyword.value.trim().toLowerCase()
  return cmdbAssets.value.filter(asset => {
    const matchesKeyword = !keyword || [asset.assetNo, asset.name, asset.businessSystem, asset.location].filter(Boolean).join(' ').toLowerCase().includes(keyword)
    return matchesKeyword
      && (!cmdbTypeFilter.value || asset.assetType === cmdbTypeFilter.value)
      && (!cmdbStatusFilter.value || asset.status === cmdbStatusFilter.value)
      && (!cmdbCriticalityFilter.value || asset.criticality === cmdbCriticalityFilter.value)
  })
})
const cmdbActiveCount = computed(() => cmdbAssets.value.filter(asset => asset.status === 'ACTIVE').length)
const cmdbCriticalCount = computed(() => cmdbAssets.value.filter(asset => ['CRITICAL', 'HIGH'].includes(asset.criticality) && asset.status !== 'RETIRED').length)
const availableTicketAssets = computed(() => cmdbAssets.value.filter(asset => asset.status !== 'RETIRED' && !ticketAssetLinks.value.some(link => String(link.assetId) === String(asset.id))))
const pageEyebrow = computed(() => ({ overview: 'OVERVIEW', workbench: 'UNIFIED OPERATIONS', supportGroups: 'TEAM ROUTING', tickets: 'TICKETS', satisfaction: 'CUSTOMER SATISFACTION', catalog: 'SERVICE CATALOG', changes: 'CHANGE MANAGEMENT', releases: 'RELEASE GOVERNANCE', runbooks: 'RUNBOOK ORCHESTRATION', problems: 'PROBLEM MANAGEMENT', alerts: 'OBSERVABILITY ALERTS', reliability: 'RELIABILITY ENGINEERING', statusPage: 'SERVICE STATUS', incidents: 'INCIDENT COMMAND', postmortems: 'POST-INCIDENT REVIEW', oncall: 'ON-CALL & ESCALATION', cmdb: 'CONFIGURATION MANAGEMENT', integrations: 'INTEGRATION CENTER', team: 'TEAM ADMIN', knowledge: 'KNOWLEDGE BASE' })[activeView.value] || 'OPSFLOW')
const pageTitle = computed(() => activeView.value === 'overview' ? '早上好，' + displayName.value : ({ workbench: '运营待办', supportGroups: '支持组队列', tickets: '工单中心', satisfaction: '服务评价与质量', catalog: '服务目录', changes: '变更管理', releases: '发布治理', runbooks: '运行手册', problems: '问题管理', alerts: '监控告警', reliability: '可靠性 SLO', statusPage: '服务状态', incidents: '事件指挥台', postmortems: '事件复盘', oncall: '值班与升级', cmdb: '资产与配置项', integrations: '集成中心', team: '团队管理', knowledge: '知识库' })[activeView.value] || 'OpsFlow')
const activeCount = computed(() => dashboardOverview.value?.active ?? tickets.value.filter(ticket => ['OPEN', 'IN_PROGRESS', 'WAITING_CONFIRMATION'].includes(ticket.status)).length)
const urgentCount = computed(() => dashboardOverview.value?.urgent ?? tickets.value.filter(ticket => ticket.priority === 'URGENT').length)
const resolvedCount = computed(() => dashboardOverview.value?.resolved ?? tickets.value.filter(ticket => ['RESOLVED', 'CLOSED'].includes(ticket.status)).length)
const unassignedCount = computed(() => dashboardOverview.value?.unassigned ?? tickets.value.filter(ticket => !ticket.assigneeId).length)
const assignedCount = computed(() => dashboardOverview.value?.assigned ?? tickets.value.filter(ticket => ticket.assigneeId).length)
const createdToday = computed(() => dashboardOverview.value?.createdToday ?? 0)
const resolvedToday = computed(() => dashboardOverview.value?.resolvedToday ?? 0)
const statusMetrics = computed(() => dashboardOverview.value?.statuses || [])
const priorityMetrics = computed(() => dashboardOverview.value?.priorities || [])
const trendMax = computed(() => Math.max(1, ...dashboardTrends.value.flatMap(item => [item.created || 0, item.resolved || 0])))
const filteredTickets = computed(() => ticketFilter.value ? tickets.value.filter(ticket => ticket.status === ticketFilter.value) : tickets.value)
const visibleSelectedCount = computed(() => filteredTickets.value.filter(ticket => selectedTicketIds.value.includes(ticket.id)).length)
const allVisibleSelected = computed(() => filteredTickets.value.length > 0
  && visibleSelectedCount.value === filteredTickets.value.length)
const someVisibleSelected = computed(() => visibleSelectedCount.value > 0 && !allVisibleSelected.value)
const ticketPageCount = computed(() => Math.max(1, Math.ceil(ticketTotal.value / ticketPageSize.value)))
const ticketRangeStart = computed(() => ticketTotal.value ? (ticketPageNumber.value - 1) * ticketPageSize.value + 1 : 0)
const ticketRangeEnd = computed(() => Math.min(ticketTotal.value, ticketPageNumber.value * ticketPageSize.value))
const selectedSavedView = computed(() => savedViews.value.find(view => String(view.id) === String(selectedSavedViewId.value)))
const canEditSelectedSavedView = computed(() => Boolean(selectedSavedView.value)
  && String(selectedSavedView.value.ownerId) === String(user.value?.id))
const canDeleteSelectedSavedView = computed(() => Boolean(selectedSavedView.value)
  && (String(selectedSavedView.value.ownerId) === String(user.value?.id) || isAdmin.value))
const knowledgeCategories = computed(() => Array.from(new Set(
  articles.value.map(article => article.category).filter(Boolean)
)).sort((left, right) => left.localeCompare(right, 'zh-CN')))
const hasKnowledgeFilters = computed(() => Boolean(
  knowledgeKeyword.value.trim() || knowledgeCategory.value || (canBulkUpdate.value && knowledgeStatus.value)
))
const displayedArticle = computed(() => selectedArticleVersion.value || selectedArticle.value)
const filteredArticles = computed(() => {
  const keyword = knowledgeKeyword.value.trim().toLowerCase()
  return articles.value.filter(article => {
    const searchableText = article.title + ' ' + article.category + ' ' + article.content
    const matchesKeyword = !keyword || searchableText.toLowerCase().includes(keyword)
    const matchesCategory = !knowledgeCategory.value || article.category === knowledgeCategory.value
    const matchesStatus = !canBulkUpdate.value || !knowledgeStatus.value || article.status === knowledgeStatus.value
    return matchesKeyword && matchesCategory && matchesStatus
  })
})
const slaRiskTickets = computed(() => {
  const threshold = Date.now() + 24 * 60 * 60 * 1000
  return tickets.value
    .filter(ticket => ticket.dueAt
      && ['OPEN', 'IN_PROGRESS', 'WAITING_CONFIRMATION'].includes(ticket.status)
      && new Date(ticket.dueAt).getTime() <= threshold)
    .sort((a, b) => new Date(a.dueAt).getTime() - new Date(b.dueAt).getTime())
    .slice(0, 5)
})

function isOverdue(ticket) {

  return ticket?.dueAt && !["RESOLVED", "CLOSED", "REJECTED"].includes(ticket.status)
    && new Date(ticket.dueAt).getTime() < Date.now()
}
function statusClass(status) { return `status-${String(status || '').toLowerCase()}` }
function formatDate(value) {
  if (!value) return '-'
  return new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}
async function handleGlobalSearchSelect(result) {
  if (!result?.targetView) return
  activeView.value = result.targetView
  try {
    if (result.type === 'TICKET') {
      const ticket = tickets.value.find(item => String(item.id) === String(result.id))
        || await api('/api/v1/tickets/' + result.id)
      await openTicket(ticket)
    } else if (result.type === 'KNOWLEDGE') {
      const article = articles.value.find(item => String(item.id) === String(result.id))
        || (result.status === 'PUBLISHED' ? { id: result.id, title: result.title, status: result.status } : null)
      if (article) await openArticle(article)
      else notify('已定位到知识库草稿 ' + result.referenceNo)
    } else if (result.type === 'CMDB_ASSET') {
      const asset = cmdbAssets.value.find(item => String(item.id) === String(result.id))
        || await api('/api/v1/cmdb/assets/' + result.id)
      await selectCmdbAsset(asset)
    } else if (result.type === 'CHANGE') {
      await openChangeDetail({ id: result.id })
    } else if (result.type === 'PROBLEM') {
      await openProblemDetail({ id: result.id })
    } else {
      notify('已定位到' + result.typeLabel + ' ' + result.referenceNo)
    }
  } catch (err) {
    error.value = err.message
  }
}
function trendLabel(value) { return String(value || '').slice(5) }
function trendBarHeight(value) { return Math.max(4, Math.round((value || 0) / trendMax.value * 100)) }
function localDateKey(date) {
  const pad = value => String(value).padStart(2, '0')
  return date.getFullYear() + '-' + pad(date.getMonth() + 1) + '-' + pad(date.getDate())
}
function reportRangeDates() {
  const to = new Date()
  to.setHours(0, 0, 0, 0)
  const from = new Date(to)
  from.setDate(from.getDate() - reportRangeDays.value + 1)
  return { from: localDateKey(from), to: localDateKey(to) }
}
function staffDisplayName(id) {
  if (!id) return '未分配'
  const staff = staffMembers.value.find(item => String(item.id) === String(id))
  return staff?.displayName || '用户 #' + id
}
function assigneeName(ticket) {
  if (!ticket?.assigneeId) return '未分配'
  const staff = staffMembers.value.find(item => item.id === ticket.assigneeId)
  return staff ? staff.displayName : '用户 #' + ticket.assigneeId
}
function notify(message) {
  toast.value = message
  window.setTimeout(() => { toast.value = '' }, 2600)
}
function logout() {
  token.value = ''
  user.value = null
  closePreview()
  closeArticleReader()
  localStorage.removeItem(TOKEN_KEY)
  clearTicketSelection()
  localStorage.removeItem(USER_KEY)
  staffMembers.value = []
  dashboardOverview.value = null
  dashboardReport.value = null
  reportSubscriptions.value = []
  serviceCatalogItems.value = []
  serviceRequests.value = []
  changes.value = []
  selectedChange.value = null
  problems.value = []
  webhooks.value = []
  webhookDeliveries.value = []
  webhookOutboxEvents.value = []
  webhookOutboxSummary.value = { pending: 0, processing: 0, failed: 0, dead: 0, published: 0, oldestPendingAt: null }
  selectedWebhookId.value = ''
  webhookDeliveryStatusFilter.value = ''
  webhookOutboxStatusFilter.value = ''
  selectedProblem.value = null
  selectedProblemDetail.value = null
  selectedChangeDetail.value = null
  cmdbAssets.value = []
  selectedCmdbAsset.value = null
  cmdbRelations.value = []
  cmdbImpact.value = null
  ticketAssetLinks.value = []
  showCatalogItemModal.value = false
  showServiceRequestModal.value = false
  showServiceDecisionModal.value = false
  selectedCatalogItem.value = null
  selectedServiceRequest.value = null
  showReportSubscriptionModal.value = false
  editingReportSubscription.value = null
  knowledgeAnalytics.value = null
  notifications.value = []
  notificationUnread.value = 0
  teamUsers.value = []
  adminAuditLogs.value = []
  slaPolicy.value = null
  automationRules.value = []
  automationSupportGroups.value = []
  savedViews.value = []
  selectedSavedViewId.value = ''
  editingSavedView.value = null
  showNotifications.value = false
  showAuditExportModal.value = false
  auditExportActions.value = []

  showExportModal.value = false
}

function toggleTicketSelection(ticketId) {
  const index = selectedTicketIds.value.indexOf(ticketId)
  if (index === -1) {
    if (selectedTicketIds.value.length >= MAX_TICKET_SELECTION) {
      error.value = `单次最多选择 ${MAX_TICKET_SELECTION} 张工单`
      return
    }
    selectedTicketIds.value.push(ticketId)
  } else {
    selectedTicketIds.value.splice(index, 1)
  }
}

function toggleAllTickets() {
  const visibleIds = filteredTickets.value.map(ticket => ticket.id)
  if (allVisibleSelected.value) {
    selectedTicketIds.value = selectedTicketIds.value.filter(id => !visibleIds.includes(id))
    return
  }
  const additions = visibleIds.filter(id => !selectedTicketIds.value.includes(id))
  const remainingSlots = MAX_TICKET_SELECTION - selectedTicketIds.value.length
  if (additions.length > remainingSlots) {
    error.value = `单次最多选择 ${MAX_TICKET_SELECTION} 张工单，已按上限保留选择`
  }
  selectedTicketIds.value = Array.from(new Set([
    ...selectedTicketIds.value,
    ...additions.slice(0, Math.max(0, remainingSlots))
  ]))
}

function clearTicketSelection() {
  selectedTicketIds.value = []
  bulkForm.status = ''
  bulkForm.priority = ''
}
async function api(path, options = {}) {
  const headers = { ...(options.headers || {}) }
  if (!(options.body instanceof FormData)) headers['Content-Type'] = 'application/json'
  if (token.value) headers.Authorization = `Bearer ${token.value}`
  const response = await fetch(path, { ...options, headers })
  if (response.status === 401 || response.status === 403) {
    logout()
    throw new Error('登录已失效，请重新登录')
  }
  const payload = await response.json().catch(() => ({}))
  if (!response.ok || payload.success === false) throw new Error(payload.message || '请求失败')
  return payload.data
}

async function downloadCsv(path, filename) {
  const response = await fetch(path, { headers: { Authorization: 'Bearer ' + token.value } })
  if (response.status === 401 || response.status === 403) {
    logout()
    throw new Error('登录已失效，请重新登录')
  }
  if (!response.ok) throw new Error('导出失败，请稍后重试')
  const blob = await response.blob()
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  link.click()
  window.setTimeout(() => URL.revokeObjectURL(url), 1000)
}

async function signIn() {
  loading.value = true
  error.value = ''
  try {
    const data = await api('/api/v1/auth/login', { method: 'POST', body: JSON.stringify(loginForm) })
    token.value = data.token
    user.value = data
    localStorage.setItem(TOKEN_KEY, data.token)
    localStorage.setItem(USER_KEY, JSON.stringify(data))
    await loadDashboard()
  } catch (err) {
    error.value = err.message
  } finally {
    loading.value = false
  }
}

async function changePassword() {
  changingPassword.value = true
  try {
    await api('/api/v1/auth/change-password', {
      method: 'POST',
      body: JSON.stringify(passwordForm)
    })
    Object.assign(passwordForm, { currentPassword: '', newPassword: '' })
    showPasswordModal.value = false
    logout()
    notify('密码已更新，请重新登录')
  } catch (err) {
    error.value = err.message
  } finally {
    changingPassword.value = false
  }
}

function buildTicketQuery(page = ticketPageNumber.value) {
  const params = new URLSearchParams({ page: String(page), size: String(ticketPageSize.value) })
  if (ticketFilter.value) params.set('status', ticketFilter.value)
  if (ticketPriority.value) params.set('priority', ticketPriority.value)
  if (ticketSort.value) params.set('sort', ticketSort.value)
  if (ticketKeyword.value.trim()) params.set('keyword', ticketKeyword.value.trim())
  if (ticketOverdue.value) params.set('overdue', 'true')
  if (ticketMine.value) params.set('mine', 'true')
  return params
}

async function loadTicketPage({ clearSelection = false } = {}) {
  loading.value = true
  error.value = ''
  try {
    const ticketPage = await api('/api/v1/tickets?' + buildTicketQuery().toString())
    tickets.value = ticketPage.records || []
    ticketTotal.value = ticketPage.total || 0
    if (clearSelection) clearTicketSelection()
    if (ticketPageNumber.value > ticketPageCount.value) ticketPageNumber.value = ticketPageCount.value
  } catch (err) {
    error.value = err.message
  } finally {
    loading.value = false
  }
}

async function searchTickets() {
  ticketPageNumber.value = 1
  return loadTicketPage({ clearSelection: true })
}

async function goToTicketPage(page) {
  const targetPage = Math.min(Math.max(1, Number(page) || 1), ticketPageCount.value)
  if (targetPage === ticketPageNumber.value) return
  ticketPageNumber.value = targetPage
  await loadTicketPage()
}

async function changeTicketPageSize(size) {
  const nextSize = Number(size)
  if (![20, 50, 100].includes(nextSize)) return
  ticketPageSize.value = nextSize
  ticketPageNumber.value = 1
  await loadTicketPage()
}
function openImportModal() {
  importFile.value = null
  importResult.value = null
  showImportModal.value = true
}

function handleImportFile(event) {
  importFile.value = event.target.files?.[0] || null
  importResult.value = null
}

async function downloadImportTemplate() {
  try {
    await downloadCsv('/api/v1/tickets/import/template.csv', 'opsflow-ticket-import-template.csv')
    notify('导入模板已下载')
  } catch (err) { error.value = err.message }
}

async function importTickets() {
  if (!importFile.value) {
    error.value = '请选择要导入的 CSV 文件'
    return
  }
  importingTickets.value = true
  try {
    const formData = new FormData()
    formData.append('file', importFile.value)
    importResult.value = await api('/api/v1/tickets/import', {
      method: 'POST',
      body: formData
    })
    await loadDashboard()
    notify('批量导入完成：成功 ' + importResult.value.successRows + ' 条，失败 ' + importResult.value.failedRows + ' 条')
  } catch (err) { error.value = err.message }
  finally { importingTickets.value = false }
}
function openSavedViewModal(view = null) {
  editingSavedView.value = view
  savedViewForm.name = view?.name || ''
  savedViewForm.visibility = view?.visibility || 'PRIVATE'
  showSavedViewModal.value = true
}

async function saveSavedView() {
  if (!savedViewForm.name.trim()) {
    error.value = '请输入保存视图名称'
    return
  }
  savingSavedView.value = true
  const editing = editingSavedView.value
  const payload = {
    name: savedViewForm.name.trim(),
    visibility: savedViewForm.visibility,
    status: ticketFilter.value || null,
    priority: ticketPriority.value || null,
    sort: ticketSort.value,
    keyword: ticketKeyword.value.trim() || null,
    overdue: ticketOverdue.value,
    mine: ticketMine.value
  }
  try {
    const saved = await api(editing ? '/api/v1/ticket-views/' + editing.id : '/api/v1/ticket-views', {
      method: editing ? 'PUT' : 'POST',
      body: JSON.stringify(payload)
    })
    savedViews.value = editing
      ? savedViews.value.map(view => String(view.id) === String(saved.id) ? saved : view)
      : [saved, ...savedViews.value.filter(view => String(view.id) !== String(saved.id))]
    selectedSavedViewId.value = String(saved.id)
    showSavedViewModal.value = false
    editingSavedView.value = null
    notify(editing ? '工单视图已更新' : '工单视图已保存')
  } catch (err) { error.value = err.message }
  finally { savingSavedView.value = false }
}

async function applySavedView(viewId) {
  selectedSavedViewId.value = String(viewId || '')
  const view = savedViews.value.find(item => String(item.id) === String(viewId))
  if (!view) return
  ticketFilter.value = view.status || ''
  ticketPriority.value = view.priority || ''
  ticketSort.value = view.sort || 'CREATED_DESC'
  ticketKeyword.value = view.keyword || ''
  ticketOverdue.value = Boolean(view.overdue)
  ticketMine.value = Boolean(view.mine)
  await searchTickets()
}

async function deleteSelectedSavedView() {
  const view = savedViews.value.find(item => String(item.id) === String(selectedSavedViewId.value))
  if (!view) return
  if (!window.confirm('确认删除保存视图“' + view.name + '”吗？')) return
  try {
    await api('/api/v1/ticket-views/' + view.id, { method: 'DELETE' })
    savedViews.value = savedViews.value.filter(item => item.id !== view.id)
    selectedSavedViewId.value = ''
    editingSavedView.value = null
    notify('保存视图已删除')
  } catch (err) { error.value = err.message }
}function openTicketExportModal() {
  if (!selectedTicketIds.value.length) {
    error.value = '请先在工单队列中勾选要导出的工单'
    return
  }
  showExportModal.value = true
}
async function exportTickets() {
  if (!selectedTicketIds.value.length) {
    error.value = '请先在工单队列中勾选要导出的工单'
    return
  }
  if (!exportFields.value.length) {
    error.value = '至少选择一项工单导出字段'
    return
  }
  exportingTickets.value = true
  try {
    const params = new URLSearchParams()
    params.set('ticketIds', selectedTicketIds.value.join(','))
    params.set('fields', exportFields.value.join(','))
    await downloadCsv('/api/v1/exports/tickets.csv?' + params.toString(), 'opsflow-tickets.csv')
    showExportModal.value = false
    notify('已导出 ' + selectedTicketIds.value.length + ' 张工单 CSV')
  } catch (err) { error.value = err.message }
  finally { exportingTickets.value = false }
}

async function loadDashboard() {
  loading.value = true
  error.value = ''
  const reportDates = reportRangeDates()
  try {
    const [ticketPage, articlePage, sla, overview, trends, staff, notificationList, notificationCount, savedViewList, knowledgeAnalyticsResult, dashboardReportResult, reportSubscriptionList, catalogItemList, serviceRequestList, cmdbAssetList, changeList, problemList, webhookList, webhookDeliveryList, webhookOutboxList, webhookOutboxHealth] = await Promise.all([
      api('/api/v1/tickets?page=1&size=' + ticketPageSize.value + '&sort=' + ticketSort.value),
      api('/api/v1/knowledge/articles?page=1&size=50'),
      api('/api/v1/tickets/sla/summary'),
      api('/api/v1/dashboard/overview'),
      api('/api/v1/dashboard/trends?days=14'),
      canBulkUpdate.value ? api('/api/v1/auth/staff') : Promise.resolve([]),
      api('/api/v1/notifications?limit=20'),
      api('/api/v1/notifications/unread-count'),
      api('/api/v1/ticket-views'),
      canBulkUpdate.value ? api('/api/v1/knowledge/articles/analytics') : Promise.resolve(null),
      canBulkUpdate.value ? api('/api/v1/dashboard/report?from=' + reportDates.from + '&to=' + reportDates.to) : Promise.resolve(null),
      canBulkUpdate.value ? api('/api/v1/dashboard/report-subscriptions') : Promise.resolve([]),
      api('/api/v1/service-catalog/items'),
      api('/api/v1/service-catalog/requests'),
      api('/api/v1/cmdb/assets?limit=200'),
      api('/api/v1/changes?limit=100'),
      api('/api/v1/problems?limit=100'),
      isAdmin.value ? api('/api/v1/admin/webhooks') : Promise.resolve([]),
      isAdmin.value ? api('/api/v1/admin/webhooks/deliveries?limit=100') : Promise.resolve([]),
      isAdmin.value ? api('/api/v1/admin/webhooks/outbox?limit=100') : Promise.resolve([]),
      isAdmin.value ? api('/api/v1/admin/webhooks/outbox/summary') : Promise.resolve(null)
    ])
    ticketPageNumber.value = 1
    tickets.value = ticketPage.records || []
    clearTicketSelection()
    articles.value = articlePage.records || []
    ticketTotal.value = ticketPage.total || 0
    articleTotal.value = articlePage.total || 0
    slaSummary.value = sla || { active: 0, overdue: 0, dueSoon: 0, onTrack: 0 }
    dashboardOverview.value = overview || null
    dashboardTrends.value = trends || []
    dashboardReport.value = dashboardReportResult || null
    reportSubscriptions.value = reportSubscriptionList || []
    serviceCatalogItems.value = catalogItemList || []
    serviceRequests.value = serviceRequestList || []
    changes.value = changeList || []
    problems.value = problemList || []
    webhooks.value = webhookList || []
    webhookDeliveries.value = webhookDeliveryList || []
    webhookOutboxEvents.value = webhookOutboxList || []
    webhookOutboxSummary.value = webhookOutboxHealth || { pending: 0, processing: 0, failed: 0, dead: 0, published: 0, oldestPendingAt: null }
    cmdbAssets.value = cmdbAssetList || []
    staffMembers.value = staff || []
    notifications.value = notificationList || []
    notificationUnread.value = notificationCount || 0
    savedViews.value = savedViewList || []
    knowledgeAnalytics.value = knowledgeAnalyticsResult
    if (selectedSavedViewId.value && !savedViews.value.some(view => String(view.id) === String(selectedSavedViewId.value))) {
      selectedSavedViewId.value = ''
    }
  } catch (err) {
    error.value = err.message
  } finally {
    loading.value = false
  }
}

function changeReportRange(days) {
  if (reportRangeDays.value === days || loading.value) return
  reportRangeDays.value = days
  loadDashboard()
}

async function exportDashboardReport() {
  if (!dashboardReport.value || exportingDashboardReport.value) return
  exportingDashboardReport.value = true
  try {
    const report = dashboardReport.value
    await downloadCsv(
      '/api/v1/exports/dashboard-report.csv?from=' + report.from + '&to=' + report.to,
      'opsflow-dashboard-report-' + report.from + '-' + report.to + '.csv')
    notify('运营报表已导出')
  } catch (err) {
    error.value = err.message
  } finally {
    exportingDashboardReport.value = false
  }
}

function resetReportSubscriptionForm() {
  Object.assign(reportSubscriptionForm, { name: '', frequency: 'DAILY', rangeDays: 14, enabled: true })
  editingReportSubscription.value = null
}

function openReportSubscriptionModal(subscription = null) {
  if (subscription) {
    editingReportSubscription.value = subscription
    Object.assign(reportSubscriptionForm, {
      name: subscription.name || '',
      frequency: subscription.frequency || 'DAILY',
      rangeDays: subscription.rangeDays || 14,
      enabled: subscription.enabled !== false
    })
  } else {
    resetReportSubscriptionForm()
  }
  showReportSubscriptionModal.value = true
}

function closeReportSubscriptionModal() {
  showReportSubscriptionModal.value = false
  editingReportSubscription.value = null
}

function reportFrequencyLabel(value) {
  return value === 'WEEKLY' ? '每周' : '每日'
}

async function saveReportSubscription() {
  if (!canBulkUpdate.value || savingReportSubscription.value) return
  savingReportSubscription.value = true
  error.value = ''
  try {
    const editing = Boolean(editingReportSubscription.value)
    const payload = {
      name: reportSubscriptionForm.name.trim(),
      frequency: reportSubscriptionForm.frequency,
      rangeDays: Number(reportSubscriptionForm.rangeDays)
    }
    if (editing) payload.enabled = Boolean(reportSubscriptionForm.enabled)
    await api(editing
      ? '/api/v1/dashboard/report-subscriptions/' + editingReportSubscription.value.id
      : '/api/v1/dashboard/report-subscriptions', {
      method: editing ? 'PUT' : 'POST',
      body: JSON.stringify(payload)
    })
    closeReportSubscriptionModal()
    notify(editing ? '报表订阅已更新' : '报表订阅已创建')
    await loadDashboard()
  } catch (err) {
    error.value = err.message
  } finally {
    savingReportSubscription.value = false
  }
}

async function toggleReportSubscription(subscription) {
  if (!canBulkUpdate.value) return
  try {
    await api('/api/v1/dashboard/report-subscriptions/' + subscription.id, {
      method: 'PUT',
      body: JSON.stringify({
        name: subscription.name,
        frequency: subscription.frequency,
        rangeDays: subscription.rangeDays,
        enabled: !subscription.enabled
      })
    })
    notify(subscription.enabled ? '报表订阅已停用' : '报表订阅已启用')
    await loadDashboard()
  } catch (err) {
    error.value = err.message
  }
}

async function runReportSubscription(subscription) {
  if (runningReportSubscriptionId.value) return
  runningReportSubscriptionId.value = subscription.id
  error.value = ''
  try {
    await api('/api/v1/dashboard/report-subscriptions/' + subscription.id + '/run', { method: 'POST' })
    notify('已生成报表摘要，并发送到通知中心')
    await loadDashboard()
  } catch (err) {
    error.value = err.message
  } finally {
    runningReportSubscriptionId.value = null
  }
}

async function deleteReportSubscription(subscription) {
  if (deletingReportSubscriptionId.value || !window.confirm('确认删除报表订阅“' + subscription.name + '”吗？')) return
  deletingReportSubscriptionId.value = subscription.id
  error.value = ''
  try {
    await api('/api/v1/dashboard/report-subscriptions/' + subscription.id, { method: 'DELETE' })
    notify('报表订阅已删除')
    await loadDashboard()
  } catch (err) {
    error.value = err.message
  } finally {
    deletingReportSubscriptionId.value = null
  }
}
function assetTypeLabel(type) { return assetTypeLabels[type] || type || '其他' }
function assetEnvironmentLabel(environment) { return assetEnvironmentLabels[environment] || environment || '-' }
function assetStatusLabel(status) { return assetStatusLabels[status] || status || '-' }
function assetCriticalityLabel(criticality) { return assetCriticalityLabels[criticality] || criticality || '-' }
function assetOwnerName(asset) { return asset?.ownerId ? staffDisplayName(asset.ownerId) : '未指定负责人' }
function assetRelationLabel(relation) { return assetRelationLabels[relation] || relation || '关联' }
function linkTypeLabel(type) { return { AFFECTED: '受影响', HOSTED_ON: '运行于', RELATED: '相关' }[type] || type || '相关' }
function assetCriticalityClass(criticality) { return 'criticality-' + String(criticality || 'MEDIUM').toLowerCase() }
function relationDirection(relation) {
  if (!selectedCmdbAsset.value) return ''
  return relation.sourceAssetId === selectedCmdbAsset.value.id ? '依赖 →' : '被依赖 ←'
}
function openCmdbAssetModal(asset = null) {
  editingCmdbAsset.value = asset
  Object.assign(cmdbAssetForm, {
    assetNo: asset?.assetNo || '',
    name: asset?.name || '',
    assetType: asset?.assetType || 'APPLICATION',
    environment: asset?.environment || 'PRODUCTION',
    status: asset?.status || 'ACTIVE',
    criticality: asset?.criticality || 'MEDIUM',
    ownerId: asset?.ownerId || null,
    businessSystem: asset?.businessSystem || '',
    location: asset?.location || '',
    description: asset?.description || '',
    metadataJson: asset?.metadataJson || ''
  })
  showCmdbAssetModal.value = true
}
function closeCmdbAssetModal() {
  showCmdbAssetModal.value = false
  editingCmdbAsset.value = null
  Object.assign(cmdbAssetForm, { assetNo: '', name: '', assetType: 'APPLICATION', environment: 'PRODUCTION', status: 'ACTIVE', criticality: 'MEDIUM', ownerId: null, businessSystem: '', location: '', description: '', metadataJson: '' })
}
function cmdbAssetPayload() {
  return { ...cmdbAssetForm, ownerId: cmdbAssetForm.ownerId ? Number(cmdbAssetForm.ownerId) : null }
}
async function saveCmdbAsset() {
  savingCmdbAsset.value = true
  try {
    const saved = await api(editingCmdbAsset.value ? '/api/v1/cmdb/assets/' + editingCmdbAsset.value.id : '/api/v1/cmdb/assets', {
      method: editingCmdbAsset.value ? 'PUT' : 'POST',
      body: JSON.stringify(cmdbAssetPayload())
    })
    if (editingCmdbAsset.value) {
      cmdbAssets.value = cmdbAssets.value.map(item => item.id === saved.id ? saved : item)
      if (selectedCmdbAsset.value?.id === saved.id) await selectCmdbAsset(saved)
    } else {
      cmdbAssets.value = [saved, ...cmdbAssets.value]
      await selectCmdbAsset(saved)
    }
    closeCmdbAssetModal()
    notify(editingCmdbAsset.value ? '资产信息已更新' : '资产已登记')
  } catch (err) { error.value = err.message }
  finally { savingCmdbAsset.value = false }
}
async function selectCmdbAsset(asset) {
  if (!asset) return
  selectedCmdbAsset.value = asset
  cmdbContextLoading.value = true
  try {
    const [relations, impact] = await Promise.all([
      api('/api/v1/cmdb/assets/' + asset.id + '/relations'),
      api('/api/v1/cmdb/assets/' + asset.id + '/impact')
    ])
    cmdbRelations.value = relations || []
    cmdbImpact.value = impact || null
  } catch (err) { error.value = err.message }
  finally { cmdbContextLoading.value = false }
}
function openCmdbRelationModal() {
  if (!selectedCmdbAsset.value || !canBulkUpdate.value) return
  const candidate = cmdbAssets.value.find(asset => asset.id !== selectedCmdbAsset.value.id && asset.status !== 'RETIRED')
  Object.assign(cmdbRelationForm, { targetAssetId: candidate?.id || '', relationType: 'DEPENDS_ON', description: '' })
  showCmdbRelationModal.value = true
}
function closeCmdbRelationModal() {
  showCmdbRelationModal.value = false
  Object.assign(cmdbRelationForm, { targetAssetId: '', relationType: 'DEPENDS_ON', description: '' })
}
async function saveCmdbRelation() {
  if (!selectedCmdbAsset.value || !cmdbRelationForm.targetAssetId) return
  savingCmdbRelation.value = true
  try {
    await api('/api/v1/cmdb/assets/' + selectedCmdbAsset.value.id + '/relations', {
      method: 'POST',
      body: JSON.stringify({ targetAssetId: Number(cmdbRelationForm.targetAssetId), relationType: cmdbRelationForm.relationType, description: cmdbRelationForm.description })
    })
    closeCmdbRelationModal()
    await selectCmdbAsset(selectedCmdbAsset.value)
    notify('资产关系已建立')
  } catch (err) { error.value = err.message }
  finally { savingCmdbRelation.value = false }
}
async function deleteCmdbRelation(relation) {
  if (!canBulkUpdate.value || !window.confirm('确认解除这条资产关系吗？')) return
  try {
    await api('/api/v1/cmdb/relations/' + relation.id, { method: 'DELETE' })
    await selectCmdbAsset(selectedCmdbAsset.value)
    notify('资产关系已解除')
  } catch (err) { error.value = err.message }
}
async function loadTicketAssets(ticketId) {
  ticketAssetLinks.value = await api('/api/v1/cmdb/tickets/' + ticketId + '/assets') || []
  ticketAssetSelection.value = ''
}
async function linkSelectedTicketAsset() {
  if (!selectedTicket.value || !ticketAssetSelection.value || !canBulkUpdate.value) return
  linkingTicketAsset.value = true
  try {
    const link = await api('/api/v1/cmdb/tickets/' + selectedTicket.value.id + '/assets', {
      method: 'POST',
      body: JSON.stringify({ assetId: Number(ticketAssetSelection.value), linkType: 'AFFECTED', note: '' })
    })
    ticketAssetLinks.value = [link, ...ticketAssetLinks.value]
    ticketAssetSelection.value = ''
    notify('配置项已关联到工单')
  } catch (err) { error.value = err.message }
  finally { linkingTicketAsset.value = false }
}
async function unlinkTicketAsset(link) {
  if (!selectedTicket.value || !canBulkUpdate.value) return
  try {
    await api('/api/v1/cmdb/tickets/' + selectedTicket.value.id + '/assets/' + link.assetId, { method: 'DELETE' })
    ticketAssetLinks.value = ticketAssetLinks.value.filter(item => item.assetId !== link.assetId)
    notify('工单资产关联已解除')
  } catch (err) { error.value = err.message }
}
function changeStatusLabel(status) { return changeStatusLabels[status] || status || '-' }
function changeRiskLabel(risk) { return changeRiskLabels[risk] || risk || '-' }
function changeTypeLabel(type) { return changeTypeLabels[type] || type || '-' }
function changeTaskStatusLabel(status) { return changeTaskStatusLabels[status] || status || '-' }
function changeStatusClass(status) { return 'status-' + String(status || '').toLowerCase() }
function changeRequesterName(id) { return staffDisplayName(id) }
function resetChangeForm() {
  Object.assign(changeForm, { title: '', description: '', changeType: 'NORMAL', riskLevel: 'MEDIUM', impactScope: '', implementationPlan: '', validationPlan: '', rollbackPlan: '', scheduledStart: '', scheduledEnd: '', linkedTicketId: '', taskTitles: '' })
}
function openChangeModal() {
  resetChangeForm()
  showChangeModal.value = true
}
function closeChangeModal() {
  showChangeModal.value = false
  resetChangeForm()
}
async function saveChange() {
  savingChange.value = true
  try {
    const taskTitles = String(changeForm.taskTitles || '').split(/\r?\n|;/).map(item => item.trim()).filter(Boolean)
    const payload = {
      ...changeForm,
      linkedTicketId: changeForm.linkedTicketId ? Number(changeForm.linkedTicketId) : null,
      scheduledStart: changeForm.scheduledStart || null,
      scheduledEnd: changeForm.scheduledEnd || null,
      taskTitles
    }
    const saved = await api('/api/v1/changes', {
      method: 'POST',
      headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify(payload)
    })
    changes.value = [saved, ...changes.value.filter(item => item.id !== saved.id)]
    closeChangeModal()
    notify(saved.status === 'APPROVED' ? '标准低风险变更已预批准' : '变更申请已提交审批')
    await loadNotifications()
  } catch (err) { error.value = err.message }
  finally { savingChange.value = false }
}
async function openChangeDetail(change) {
  if (!change) return
  selectedChange.value = change
  changeDetailLoading.value = true
  try {
    selectedChangeDetail.value = await api('/api/v1/changes/' + change.id)
    selectedChange.value = selectedChangeDetail.value.change
    changeAssetSelection.value = ''
  } catch (err) { error.value = err.message }
  finally { changeDetailLoading.value = false }
}
function closeChangeDetail() {
  selectedChange.value = null
  selectedChangeDetail.value = null
  changeAssetSelection.value = ''
}
function openChangeDecisionModal(change, decision) {
  selectedChange.value = change
  changeDecision.value = decision
  changeDecisionForm.comment = ''
  showChangeDecisionModal.value = true
}
function closeChangeDecisionModal() {
  showChangeDecisionModal.value = false
  changeDecisionForm.comment = ''
}
async function submitChangeDecision() {
  if (!selectedChange.value) return
  decidingChange.value = true
  try {
    const action = changeDecision.value === 'REJECT' ? 'reject' : 'approve'
    const updated = await api('/api/v1/changes/' + selectedChange.value.id + '/' + action, {
      method: 'POST',
      body: JSON.stringify({ comment: changeDecisionForm.comment })
    })
    changes.value = changes.value.map(item => item.id === updated.id ? updated : item)
    closeChangeDecisionModal()
    await openChangeDetail(updated)
    await loadNotifications()
    notify(changeDecision.value === 'REJECT' ? '变更申请已驳回' : '变更申请已通过')
  } catch (err) { error.value = err.message }
  finally { decidingChange.value = false }
}
async function transitionChange(change, target) {
  if (!change || !canBulkUpdate.value) return
  try {
    const updated = await api('/api/v1/changes/' + change.id + '/transition?target=' + target, { method: 'POST' })
    changes.value = changes.value.map(item => item.id === updated.id ? updated : item)
    await openChangeDetail(updated)
    notify('变更状态已更新为 ' + changeStatusLabel(updated.status))
  } catch (err) { error.value = err.message }
}
function changeTaskNextStatus(status) {
  return status === 'TODO' ? 'IN_PROGRESS' : status === 'IN_PROGRESS' ? 'DONE' : 'TODO'
}
async function addChangeTask() {
  if (!selectedChangeDetail.value?.change || !changeTaskForm.title.trim()) return
  addingChangeTask.value = true
  try {
    await api('/api/v1/changes/' + selectedChangeDetail.value.change.id + '/tasks', {
      method: 'POST',
      body: JSON.stringify({ title: changeTaskForm.title, assigneeId: changeTaskForm.assigneeId ? Number(changeTaskForm.assigneeId) : null, sortOrder: Number(changeTaskForm.sortOrder) || 100 })
    })
    Object.assign(changeTaskForm, { title: '', assigneeId: '', sortOrder: 100 })
    await openChangeDetail(selectedChangeDetail.value.change)
    notify('实施任务已添加')
  } catch (err) { error.value = err.message }
  finally { addingChangeTask.value = false }
}
async function toggleChangeTask(task) {
  if (!canBulkUpdate.value || !selectedChangeDetail.value?.change) return
  try {
    await api('/api/v1/changes/tasks/' + task.id, {
      method: 'PUT',
      body: JSON.stringify({ status: changeTaskNextStatus(task.status) })
    })
    await openChangeDetail(selectedChangeDetail.value.change)
  } catch (err) { error.value = err.message }
}
async function linkChangeAsset() {
  if (!canBulkUpdate.value || !selectedChangeDetail.value?.change || !changeAssetSelection.value) return
  linkingChangeAsset.value = true
  try {
    await api('/api/v1/changes/' + selectedChangeDetail.value.change.id + '/assets', {
      method: 'POST',
      body: JSON.stringify({ assetId: Number(changeAssetSelection.value), linkType: 'AFFECTED', note: '' })
    })
    changeAssetSelection.value = ''
    await openChangeDetail(selectedChangeDetail.value.change)
    notify('配置项已关联到变更')
  } catch (err) { error.value = err.message }
  finally { linkingChangeAsset.value = false }
}
async function unlinkChangeAsset(assetView) {
  if (!canBulkUpdate.value || !selectedChangeDetail.value?.change) return
  try {
    await api('/api/v1/changes/' + selectedChangeDetail.value.change.id + '/assets/' + assetView.assetId, { method: 'DELETE' })
    await openChangeDetail(selectedChangeDetail.value.change)
    notify('变更资产关联已解除')
  } catch (err) { error.value = err.message }
}
function problemPriorityLabel(priority) { return problemPriorityLabels[priority] || priority || '-' }
function problemStatusLabel(status) { return problemStatusLabels[status] || status || '-' }
function problemTaskStatusLabel(status) { return problemTaskStatusLabels[status] || status || '-' }
function problemStatusClass(status) { return 'status-' + String(status || '').toLowerCase() }
function problemTicketLinkLabel(type) { return problemTicketLinkLabels[type] || type || '相关工单' }
function problemChangeLinkLabel(type) { return problemChangeLinkLabels[type] || type || '相关变更' }
function resetProblemForm() {
  Object.assign(problemForm, { title: '', description: '', businessImpact: '', priority: 'HIGH', workaround: '', knownError: false, ownerId: '', taskTitles: '' })
}
function openProblemModal() {
  resetProblemForm()
  showProblemModal.value = true
}
function closeProblemModal() {
  showProblemModal.value = false
  resetProblemForm()
}
async function saveProblem() {
  savingProblem.value = true
  try {
    const taskTitles = String(problemForm.taskTitles || '').split(/\r?\n|;/).map(item => item.trim()).filter(Boolean)
    const payload = { ...problemForm, knownError: Boolean(problemForm.knownError), ownerId: problemForm.ownerId ? Number(problemForm.ownerId) : null, taskTitles }
    const saved = await api('/api/v1/problems', {
      method: 'POST',
      headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify(payload)
    })
    problems.value = [saved, ...problems.value.filter(item => item.id !== saved.id)]
    closeProblemModal()
    notify(saved.status === 'KNOWN_ERROR' ? '问题单已登记为已知错误' : '问题单已提交')
    await loadNotifications()
  } catch (err) { error.value = err.message }
  finally { savingProblem.value = false }
}
async function openProblemDetail(problem) {
  if (!problem) return
  selectedProblem.value = problem
  problemDetailLoading.value = true
  try {
    selectedProblemDetail.value = await api('/api/v1/problems/' + problem.id)
    selectedProblem.value = selectedProblemDetail.value.problem
    problemTicketSelection.value = ''
    problemChangeSelection.value = ''
  } catch (err) { error.value = err.message }
  finally { problemDetailLoading.value = false }
}
function closeProblemDetail() {
  selectedProblem.value = null
  selectedProblemDetail.value = null
  problemTicketSelection.value = ''
  problemChangeSelection.value = ''
}
function openProblemAnalysisModal(problem = selectedProblemDetail.value?.problem) {
  if (!problem || !canBulkUpdate.value) return
  Object.assign(problemAnalysisForm, { priority: problem.priority || 'HIGH', rootCause: problem.rootCause || '', workaround: problem.workaround || '', resolution: problem.resolution || '', knownError: Boolean(problem.knownError), ownerId: problem.ownerId || '' })
  showProblemAnalysisModal.value = true
}
function closeProblemAnalysisModal() {
  showProblemAnalysisModal.value = false
}
async function saveProblemAnalysis() {
  if (!selectedProblemDetail.value?.problem) return
  savingProblemAnalysis.value = true
  try {
    const updated = await api('/api/v1/problems/' + selectedProblemDetail.value.problem.id, {
      method: 'PUT',
      body: JSON.stringify({ ...problemAnalysisForm, ownerId: problemAnalysisForm.ownerId ? Number(problemAnalysisForm.ownerId) : null, knownError: Boolean(problemAnalysisForm.knownError) })
    })
    problems.value = problems.value.map(item => item.id === updated.id ? updated : item)
    closeProblemAnalysisModal()
    await openProblemDetail(updated)
    notify('问题分析资料已保存')
  } catch (err) { error.value = err.message }
  finally { savingProblemAnalysis.value = false }
}
async function transitionProblem(problem, target) {
  if (!problem || !canBulkUpdate.value) return
  try {
    const updated = await api('/api/v1/problems/' + problem.id + '/transition?target=' + target, { method: 'POST' })
    problems.value = problems.value.map(item => item.id === updated.id ? updated : item)
    await openProblemDetail(updated)
    notify('问题单状态已更新为 ' + problemStatusLabel(updated.status))
  } catch (err) { error.value = err.message }
}
function problemTaskNextStatus(status) {
  return status === 'TODO' ? 'IN_PROGRESS' : status === 'IN_PROGRESS' ? 'DONE' : 'TODO'
}
async function addProblemTask() {
  if (!selectedProblemDetail.value?.problem || !problemTaskForm.title.trim()) return
  addingProblemTask.value = true
  try {
    await api('/api/v1/problems/' + selectedProblemDetail.value.problem.id + '/tasks', {
      method: 'POST',
      body: JSON.stringify({ title: problemTaskForm.title, assigneeId: problemTaskForm.assigneeId ? Number(problemTaskForm.assigneeId) : null, sortOrder: Number(problemTaskForm.sortOrder) || 100 })
    })
    Object.assign(problemTaskForm, { title: '', assigneeId: '', sortOrder: 100 })
    await openProblemDetail(selectedProblemDetail.value.problem)
    notify('问题分析任务已添加')
  } catch (err) { error.value = err.message }
  finally { addingProblemTask.value = false }
}
async function toggleProblemTask(task) {
  if (!canBulkUpdate.value || !selectedProblemDetail.value?.problem) return
  try {
    await api('/api/v1/problems/tasks/' + task.id, { method: 'PUT', body: JSON.stringify({ status: problemTaskNextStatus(task.status) }) })
    await openProblemDetail(selectedProblemDetail.value.problem)
  } catch (err) { error.value = err.message }
}
async function linkProblemTicket() {
  if (!canBulkUpdate.value || !selectedProblemDetail.value?.problem || !problemTicketSelection.value) return
  linkingProblemTicket.value = true
  try {
    await api('/api/v1/problems/' + selectedProblemDetail.value.problem.id + '/tickets', {
      method: 'POST',
      body: JSON.stringify({ targetId: Number(problemTicketSelection.value), linkType: 'SYMPTOM', note: '' })
    })
    problemTicketSelection.value = ''
    await openProblemDetail(selectedProblemDetail.value.problem)
    notify('症状工单已关联')
  } catch (err) { error.value = err.message }
  finally { linkingProblemTicket.value = false }
}
async function unlinkProblemTicket(ticketView) {
  if (!canBulkUpdate.value || !selectedProblemDetail.value?.problem) return
  try {
    await api('/api/v1/problems/' + selectedProblemDetail.value.problem.id + '/tickets/' + ticketView.ticketId, { method: 'DELETE' })
    await openProblemDetail(selectedProblemDetail.value.problem)
    notify('问题工单关联已解除')
  } catch (err) { error.value = err.message }
}
async function linkProblemChange() {
  if (!canBulkUpdate.value || !selectedProblemDetail.value?.problem || !problemChangeSelection.value) return
  linkingProblemChange.value = true
  try {
    await api('/api/v1/problems/' + selectedProblemDetail.value.problem.id + '/changes', {
      method: 'POST',
      body: JSON.stringify({ targetId: Number(problemChangeSelection.value), linkType: 'REMEDIATION', note: '' })
    })
    problemChangeSelection.value = ''
    await openProblemDetail(selectedProblemDetail.value.problem)
    notify('修复变更已关联')
  } catch (err) { error.value = err.message }
  finally { linkingProblemChange.value = false }
}
async function unlinkProblemChange(changeView) {
  if (!canBulkUpdate.value || !selectedProblemDetail.value?.problem) return
  try {
    await api('/api/v1/problems/' + selectedProblemDetail.value.problem.id + '/changes/' + changeView.changeId, { method: 'DELETE' })
    await openProblemDetail(selectedProblemDetail.value.problem)
    notify('问题变更关联已解除')
  } catch (err) { error.value = err.message }
}
function webhookStatusClass(status) {
  return 'webhook-status-' + String(status || 'PENDING').toLowerCase()
}
function webhookEventLabel(eventType) {
  return auditLabels[eventType] || eventType || '未知事件'
}
function openWebhookModal(endpoint = null) {
  editingWebhook.value = endpoint
  webhookForm.name = endpoint?.name || ''
  webhookForm.url = endpoint?.url || ''
  webhookForm.secret = ''
  webhookForm.eventTypes = endpoint?.eventTypes || '*'
  showWebhookModal.value = true
}
function closeWebhookModal() {
  showWebhookModal.value = false
  editingWebhook.value = null
  webhookForm.name = ''
  webhookForm.url = ''
  webhookForm.secret = ''
  webhookForm.eventTypes = '*'
}
async function saveWebhook() {
  if (savingWebhook.value) return
  savingWebhook.value = true
  const editing = Boolean(editingWebhook.value)
  try {
    const path = editing ? '/api/v1/admin/webhooks/' + editingWebhook.value.id : '/api/v1/admin/webhooks'
    await api(path, {
      method: editing ? 'PUT' : 'POST',
      body: JSON.stringify({
        name: webhookForm.name,
        url: webhookForm.url,
        secret: webhookForm.secret,
        eventTypes: webhookForm.eventTypes
      })
    })
    closeWebhookModal()
    await loadDashboard()
    notify(editing ? 'Webhook 集成已更新' : 'Webhook 集成已创建')
  } catch (err) { error.value = err.message }
  finally { savingWebhook.value = false }
}
async function toggleWebhook(endpoint) {
  try {
    await api('/api/v1/admin/webhooks/' + endpoint.id + '/toggle', {
      method: 'POST',
      body: JSON.stringify({ enabled: !endpoint.enabled })
    })
    await loadDashboard()
    notify(endpoint.enabled ? 'Webhook 已停用' : 'Webhook 已启用')
  } catch (err) { error.value = err.message }
}
async function deleteWebhook(endpoint) {
  if (!window.confirm('确认删除「' + endpoint.name + '」？历史投递记录会保留，但后续不会继续发送。')) return
  try {
    await api('/api/v1/admin/webhooks/' + endpoint.id, { method: 'DELETE' })
    await loadDashboard()
    notify('Webhook 集成已删除')
  } catch (err) { error.value = err.message }
}
async function retryWebhookDelivery(item) {
  const delivery = item.delivery
  if (!delivery || retryingWebhookDeliveryId.value) return
  retryingWebhookDeliveryId.value = delivery.id
  try {
    await api('/api/v1/admin/webhooks/deliveries/' + delivery.id + '/retry', { method: 'POST' })
    await loadWebhookDeliveries()
    notify('Webhook 已重新排队')
  } catch (err) { error.value = err.message }
  finally { retryingWebhookDeliveryId.value = null }
}
async function loadWebhookDeliveries() {
  if (!isAdmin.value) return
  const query = selectedWebhookId.value ? '&endpointId=' + encodeURIComponent(selectedWebhookId.value) : ''
  webhookDeliveries.value = await api('/api/v1/admin/webhooks/deliveries?limit=100' + query)
}
async function loadWebhookOutbox() {
  if (!isAdmin.value) return
  const [events, summary] = await Promise.all([
    api('/api/v1/admin/webhooks/outbox?limit=100'),
    api('/api/v1/admin/webhooks/outbox/summary')
  ])
  webhookOutboxEvents.value = events || []
  webhookOutboxSummary.value = summary || { pending: 0, processing: 0, failed: 0, dead: 0, published: 0, oldestPendingAt: null }
}
async function retryWebhookOutbox(event) {
  if (!event || retryingWebhookOutboxId.value) return
  retryingWebhookOutboxId.value = event.id
  try {
    await api('/api/v1/admin/webhooks/outbox/' + event.id + '/retry', { method: 'POST' })
    await loadWebhookOutbox()
    notify('Outbox 事件已重新排队')
  } catch (err) { error.value = err.message }
  finally { retryingWebhookOutboxId.value = null }
}
async function openTicket(ticket) {
  selectedTicket.value = ticket
  showDetail.value = true
  loadingDetail.value = true
  try {
    const recommendationQuery = ticket.category
      ? 'category=' + encodeURIComponent(ticket.category)
      : 'keyword=' + encodeURIComponent(ticket.title || '')
    const [comments, logs, attachments, recommended, assetLinks] = await Promise.all([
      api('/api/v1/tickets/' + ticket.id + '/comments'),
      api('/api/v1/tickets/' + ticket.id + '/audit-logs'),
      api('/api/v1/tickets/' + ticket.id + '/attachments'),
      api('/api/v1/knowledge/articles?page=1&size=3&' + recommendationQuery),
      api('/api/v1/cmdb/tickets/' + ticket.id + '/assets')
    ])
    ticketComments.value = comments || []
    ticketAuditLogs.value = logs || []
    ticketAttachments.value = attachments || []
    recommendedArticles.value = recommended?.records || []
    ticketAssetLinks.value = assetLinks || []
    ticketAssetSelection.value = ''
  } catch (err) { error.value = err.message }
  finally { loadingDetail.value = false }
}

function closeTicket() {
  showDetail.value = false
  selectedTicket.value = null
  ticketComments.value = []
  ticketAuditLogs.value = []
  ticketAttachments.value = []
  recommendedArticles.value = []
  ticketAssetLinks.value = []
  ticketAssetSelection.value = ''
  closePreview()
  commentForm.content = ''; commentForm.visibility = 'PUBLIC'
}

function openEditTicketModal() {
  if (!selectedTicket.value || !canBulkUpdate.value) return
  editingTicket.value = selectedTicket.value
  Object.assign(ticketEditForm, {
    title: selectedTicket.value.title || '',
    description: selectedTicket.value.description || '',
    category: selectedTicket.value.category || '',
    priority: selectedTicket.value.priority || 'MEDIUM'
  })
  showEditTicketModal.value = true
}

function closeEditTicketModal() {
  showEditTicketModal.value = false
  editingTicket.value = null
  Object.assign(ticketEditForm, { title: '', description: '', category: '', priority: 'MEDIUM' })
}

async function saveTicketEdit() {
  if (!editingTicket.value) return
  savingTicketEdit.value = true
  try {
    const updated = await api('/api/v1/tickets/' + editingTicket.value.id, {
      method: 'PUT',
      body: JSON.stringify(ticketEditForm)
    })
    selectedTicket.value = updated
    closeEditTicketModal()
    await loadTicketPage()
    await openTicket(updated)
    notify('工单信息已更新')
  } catch (err) { error.value = err.message }
  finally { savingTicketEdit.value = false }
}
function formatFileSize(bytes) {
  if (!bytes) return '0 B'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1024 / 1024).toFixed(1) + ' MB'
}

function isImageAttachment(attachment) {
  return attachment?.contentType?.startsWith('image/')
    || /\.(png|jpe?g|gif)$/i.test(attachment?.originalName || '')
}

function closePreview() {
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
  previewUrl.value = ''
  previewAttachmentItem.value = null
  previewLoading.value = false
  showPreview.value = false
}

async function fetchAttachmentBlob(attachment) {
  const response = await fetch('/api/v1/tickets/' + attachment.ticketId + '/attachments/' + attachment.id + '/download', {
    headers: { Authorization: 'Bearer ' + token.value }
  })
  if (!response.ok) throw new Error('附件读取失败')
  return response.blob()
}

async function previewImage(attachment) {
  if (!isImageAttachment(attachment)) return
  closePreview()
  previewLoading.value = true
  previewAttachmentItem.value = attachment
  showPreview.value = true
  try {
    const blob = await fetchAttachmentBlob(attachment)
    previewUrl.value = URL.createObjectURL(blob)
  } catch (err) {
    closePreview()
    error.value = err.message
  } finally {
    previewLoading.value = false
  }
}

function canDeleteAttachment(attachment) {
  return canBulkUpdate.value || attachment.uploaderId === user.value?.id
}

async function refreshTicketAttachments() {
  if (!selectedTicket.value) return
  ticketAttachments.value = await api('/api/v1/tickets/' + selectedTicket.value.id + '/attachments')
}

async function uploadAttachment(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file || !selectedTicket.value) return
  uploadingAttachment.value = true
  try {
    const formData = new FormData()
    formData.append('file', file)
    await api('/api/v1/tickets/' + selectedTicket.value.id + '/attachments', {
      method: 'POST',
      body: formData
    })
    await refreshTicketAttachments()
    notify('附件上传成功')
  } catch (err) { error.value = err.message }
  finally { uploadingAttachment.value = false }
}

async function downloadAttachment(attachment) {
  try {
    const blob = await fetchAttachmentBlob(attachment)
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = attachment.originalName
    link.click()
    URL.revokeObjectURL(url)
  } catch (err) { error.value = err.message }
}

async function deleteAttachment(attachment) {
  if (!canDeleteAttachment(attachment)) return
  try {
    await api('/api/v1/tickets/' + attachment.ticketId + '/attachments/' + attachment.id, { method: 'DELETE' })
    await refreshTicketAttachments()
    notify('附件已删除')
  } catch (err) { error.value = err.message }
}

async function addComment() {
  if (!selectedTicket.value || !commentForm.content.trim()) return
  try {
    await api(`/api/v1/tickets/${selectedTicket.value.id}/comments`, {
      method: 'POST',
      body: JSON.stringify({ content: commentForm.content, visibility: commentForm.visibility })
    })
    commentForm.content = ''; commentForm.visibility = 'PUBLIC'
    const [comments, logs] = await Promise.all([
      api(`/api/v1/tickets/${selectedTicket.value.id}/comments`),
      api(`/api/v1/tickets/${selectedTicket.value.id}/audit-logs`)
    ])
    ticketComments.value = comments || []
    ticketAuditLogs.value = logs || []
    notify('评论已添加')
  } catch (err) { error.value = err.message }
}
async function claimTicket(ticket) {
  try {
    await api('/api/v1/tickets/' + ticket.id + '/claim', { method: 'POST' })
    notify('工单已领取')
    await searchTickets()
  } catch (err) { error.value = err.message }
}

async function releaseTicket(ticket) {
  try {
    await api('/api/v1/tickets/' + ticket.id + '/release', { method: 'POST' })
    notify('工单已释放')
    await searchTickets()
  } catch (err) { error.value = err.message }
}

function openAssignModal(ticket) {
  assignTarget.value = ticket
  assignForm.assigneeId = ticket.assigneeId || (staffMembers.value[0]?.id || '')
  showAssignModal.value = true
}

function closeAssignModal() {
  showAssignModal.value = false
  assignTarget.value = null
  assignForm.assigneeId = ''
}

async function assignTicket() {
  if (!assignTarget.value || !assignForm.assigneeId) return
  try {
    await api('/api/v1/tickets/' + assignTarget.value.id + '/assign', {
      method: 'POST',
      body: JSON.stringify({ assigneeId: Number(assignForm.assigneeId) })
    })
    notify('工单已指派给 ' + assigneeName({ assigneeId: Number(assignForm.assigneeId) }))
    closeAssignModal()
    await loadDashboard()
  } catch (err) { error.value = err.message }
}

function roleLabel(role) {
  return { ADMIN: '管理员', AGENT: '客服 / 处理人', REQUESTER: '提单人' }[role] || role
}

function serviceRequestStatusLabel(status) {
  return serviceRequestStatusLabels[status] || status
}

function serviceRequesterName(requesterId) {
  if (String(requesterId) === String(user.value?.id)) return displayName.value
  const member = teamUsers.value.find(item => String(item.id) === String(requesterId))
  return member?.displayName || '用户 #' + requesterId
}

function resetCatalogItemForm() {
  Object.assign(catalogItemForm, {
    name: '', description: '', category: '', defaultPriority: 'MEDIUM',
    approvalRequired: true, enabled: true, sortOrder: 100
  })
  editingCatalogItem.value = null
}

function openCatalogItemModal(item = null) {
  if (item) {
    editingCatalogItem.value = item
    Object.assign(catalogItemForm, {
      name: item.name || '',
      description: item.description || '',
      category: item.category || '',
      defaultPriority: item.defaultPriority || 'MEDIUM',
      approvalRequired: item.approvalRequired !== false,
      enabled: item.enabled !== false,
      sortOrder: item.sortOrder ?? 100
    })
  } else {
    resetCatalogItemForm()
  }
  showCatalogItemModal.value = true
}

function closeCatalogItemModal() {
  showCatalogItemModal.value = false
  editingCatalogItem.value = null
}

async function saveCatalogItem() {
  if (!isAdmin.value || savingCatalogItem.value) return
  savingCatalogItem.value = true
  error.value = ''
  try {
    const editing = Boolean(editingCatalogItem.value)
    const payload = {
      name: catalogItemForm.name.trim(),
      description: catalogItemForm.description.trim(),
      category: catalogItemForm.category.trim(),
      defaultPriority: catalogItemForm.defaultPriority,
      approvalRequired: Boolean(catalogItemForm.approvalRequired),
      enabled: Boolean(catalogItemForm.enabled),
      sortOrder: Number(catalogItemForm.sortOrder)
    }
    await api(editing
      ? '/api/v1/service-catalog/items/' + editingCatalogItem.value.id
      : '/api/v1/service-catalog/items', {
      method: editing ? 'PUT' : 'POST',
      body: JSON.stringify(payload)
    })
    closeCatalogItemModal()
    notify(editing ? '服务目录项已更新' : '服务目录项已创建')
    await loadDashboard()
  } catch (err) {
    error.value = err.message
  } finally {
    savingCatalogItem.value = false
  }
}

async function toggleCatalogItem(item) {
  if (!isAdmin.value) return
  error.value = ''
  try {
    await api('/api/v1/service-catalog/items/' + item.id + '/toggle', { method: 'POST' })
    notify(item.enabled ? '服务目录项已停用' : '服务目录项已启用')
    await loadDashboard()
  } catch (err) {
    error.value = err.message
  }
}

function newServiceRequestKey() {
  return window.crypto?.randomUUID
    ? window.crypto.randomUUID()
    : 'service-request-' + Date.now() + '-' + Math.random().toString(16).slice(2)
}

function openServiceRequestModal(item) {
  if (!item?.enabled) return
  selectedCatalogItem.value = item
  Object.assign(serviceRequestForm, { summary: '', detail: '' })
  serviceRequestKey.value = newServiceRequestKey()
  showServiceRequestModal.value = true
}

function closeServiceRequestModal() {
  showServiceRequestModal.value = false
  selectedCatalogItem.value = null
}

async function submitServiceRequest() {
  if (!selectedCatalogItem.value || submittingServiceRequest.value) return
  submittingServiceRequest.value = true
  error.value = ''
  try {
    const result = await api('/api/v1/service-catalog/requests', {
      method: 'POST',
      headers: { 'Idempotency-Key': serviceRequestKey.value },
      body: JSON.stringify({
        catalogItemId: selectedCatalogItem.value.id,
        summary: serviceRequestForm.summary.trim(),
        detail: serviceRequestForm.detail.trim()
      })
    })
    closeServiceRequestModal()
    notify(result.status === 'PENDING_APPROVAL' ? '服务申请已提交，等待管理员审批' : '服务申请已受理并生成工单')
    await loadDashboard()
  } catch (err) {
    error.value = err.message
  } finally {
    submittingServiceRequest.value = false
  }
}

function openServiceDecisionModal(request, decision = 'APPROVE') {
  selectedServiceRequest.value = request
  serviceDecision.value = decision
  serviceDecisionForm.comment = ''
  showServiceDecisionModal.value = true
}

function closeServiceDecisionModal() {
  showServiceDecisionModal.value = false
  selectedServiceRequest.value = null
}

async function submitServiceDecision() {
  if (!selectedServiceRequest.value || decidingServiceRequest.value) return
  if (serviceDecision.value === 'REJECT' && !serviceDecisionForm.comment.trim()) {
    error.value = '驳回时必须填写原因'
    return
  }
  decidingServiceRequest.value = true
  error.value = ''
  try {
    const action = serviceDecision.value === 'REJECT' ? 'reject' : 'approve'
    await api('/api/v1/service-catalog/requests/' + selectedServiceRequest.value.id + '/' + action, {
      method: 'POST',
      body: JSON.stringify({ comment: serviceDecisionForm.comment.trim() })
    })
    closeServiceDecisionModal()
    notify(action === 'approve' ? '服务申请已通过并生成工单' : '服务申请已驳回')
    await loadDashboard()
  } catch (err) {
    error.value = err.message
  } finally {
    decidingServiceRequest.value = false
  }
}

async function openServiceRequestTicket(request) {
  if (!request?.ticketId) return
  try {
    const ticket = await api('/api/v1/tickets/' + request.ticketId)
    await openTicket(ticket)
  } catch (err) {
    error.value = err.message
  }
}
function userStatusLabel(status) {
  return status === 'ACTIVE' ? '启用中' : '已停用'
}

function primaryRole(teamUser) {
  return teamUser?.roles?.[0] || 'REQUESTER'
}

function auditActorName(actorId) {
  const actor = teamUsers.value.find(item => item.id === actorId)
  return actor ? actor.displayName : `用户 #${actorId || '-'}`
}

async function loadTeam() {
  if (!isAdmin.value) return
  teamLoading.value = true
  error.value = ''
  try {
    const params = new URLSearchParams({ limit: '80' })
    if (adminAuditFilter.action) params.set('action', adminAuditFilter.action)
    if (adminAuditFilter.actorId) params.set('actorId', adminAuditFilter.actorId)
    if (adminAuditFilter.from) params.set('from', adminAuditFilter.from)
    if (adminAuditFilter.to) params.set('to', adminAuditFilter.to)
    const [users, logs, policy, rules, supportGroups] = await Promise.all([
      api('/api/v1/admin/users'),
      api('/api/v1/audit-logs?' + params.toString()),
      api('/api/v1/admin/sla-policy'),
      api('/api/v1/admin/automation-rules'),
      api('/api/v1/support-groups')
    ])
    teamUsers.value = users || []
    adminAuditLogs.value = logs || []
    slaPolicy.value = policy || null
    automationRules.value = rules || []
    automationSupportGroups.value = supportGroups || []
    if (policy) Object.assign(slaPolicyForm, {
      urgentHours: policy.urgentHours,
      highHours: policy.highHours,
      mediumHours: policy.mediumHours,
      lowHours: policy.lowHours
    })
  } catch (err) {
    error.value = err.message
  } finally {
    teamLoading.value = false
  }
}

function openTeam() {
  if (!isAdmin.value) return
  activeView.value = 'team'
  loadTeam()
}

async function searchAdminAuditLogs() {
  if (!isAdmin.value) return
  teamLoading.value = true
  try {
    const params = new URLSearchParams({ limit: '80' })
    if (adminAuditFilter.action) params.set('action', adminAuditFilter.action)
    if (adminAuditFilter.actorId) params.set('actorId', adminAuditFilter.actorId)
    if (adminAuditFilter.from) params.set('from', adminAuditFilter.from)
    if (adminAuditFilter.to) params.set('to', adminAuditFilter.to)
    adminAuditLogs.value = await api('/api/v1/audit-logs?' + params.toString()) || []
  } catch (err) {
    error.value = err.message
  } finally {
    teamLoading.value = false
  }
}

async function saveSlaPolicy() {
  if (!isAdmin.value) return
  savingSlaPolicy.value = true
  try {
    slaPolicy.value = await api('/api/v1/admin/sla-policy', {
      method: 'PUT',
      body: JSON.stringify({
        urgentHours: Number(slaPolicyForm.urgentHours),
        highHours: Number(slaPolicyForm.highHours),
        mediumHours: Number(slaPolicyForm.mediumHours),
        lowHours: Number(slaPolicyForm.lowHours)
      })
    })
    notify('SLA 策略已更新，新建工单将使用新规则')
    await searchAdminAuditLogs()
  } catch (err) {
    error.value = err.message
  } finally {
    savingSlaPolicy.value = false
  }
}

function resetAutomationForm() {
  Object.assign(automationForm, {
    name: '', enabled: true, sortOrder: 100, matchCategory: '', matchPriority: '', matchKeyword: '',
    targetAssigneeId: '', targetGroupId: '', assignmentStrategy: 'QUEUE_ONLY', targetPriority: '', targetStatus: ''
  })
  editingAutomationRule.value = null
}

function openAutomationModal(rule = null) {
  editingAutomationRule.value = rule
  Object.assign(automationForm, rule ? {
    name: rule.name,
    enabled: rule.enabled,
    sortOrder: rule.sortOrder,
    matchCategory: rule.matchCategory || '',
    matchPriority: rule.matchPriority || '',
    matchKeyword: rule.matchKeyword || '',
    targetAssigneeId: rule.targetAssigneeId || '',
    targetGroupId: rule.targetGroupId || '',
    assignmentStrategy: rule.assignmentStrategy || 'QUEUE_ONLY',
    targetPriority: rule.targetPriority || '',
    targetStatus: rule.targetStatus || ''
  } : {
    name: '', enabled: true, sortOrder: 100, matchCategory: '', matchPriority: '', matchKeyword: '',
    targetAssigneeId: '', targetGroupId: '', assignmentStrategy: 'QUEUE_ONLY', targetPriority: '', targetStatus: ''
  })
  showAutomationModal.value = true
}

function closeAutomationModal() {
  showAutomationModal.value = false
  resetAutomationForm()
}

function automationCondition(rule) {
  const parts = []
  if (rule.matchCategory) parts.push('分类=' + rule.matchCategory)
  if (rule.matchPriority) parts.push('优先级=' + (priorityLabels[rule.matchPriority] || rule.matchPriority))
  if (rule.matchKeyword) parts.push('文本含“' + rule.matchKeyword + '”')
  return parts.join(' · ')
}

function automationAction(rule) {
  const parts = []
  if (rule.targetGroupId) {
    parts.push('路由至 ' + automationGroupName(rule.targetGroupId))
    parts.push(rule.assignmentStrategy === 'LEAST_LOADED' ? '最少负载自动接单' : '进入组队列待领取')
  } else if (rule.targetAssigneeId) parts.push('分派给 ' + assigneeName({ assigneeId: rule.targetAssigneeId }))
  if (rule.targetPriority) parts.push('优先级→' + (priorityLabels[rule.targetPriority] || rule.targetPriority))
  if (rule.targetStatus) parts.push('状态→' + (statusLabels[rule.targetStatus] || rule.targetStatus))
  return parts.join(' · ')
}

function automationGroupName(groupId) {
  return automationSupportGroups.value.find(item => String(item.group.id) === String(groupId))?.group.name || ('支持组 #' + groupId)
}

async function saveAutomationRule() {
  if (!isAdmin.value) return
  savingAutomationRule.value = true
  try {
    const payload = {
      name: automationForm.name,
      enabled: automationForm.enabled,
      sortOrder: Number(automationForm.sortOrder),
      matchCategory: automationForm.matchCategory.trim() || null,
      matchPriority: automationForm.matchPriority || null,
      matchKeyword: automationForm.matchKeyword.trim() || null,
      targetAssigneeId: !automationForm.targetGroupId && automationForm.targetAssigneeId ? Number(automationForm.targetAssigneeId) : null,
      targetGroupId: automationForm.targetGroupId ? Number(automationForm.targetGroupId) : null,
      assignmentStrategy: automationForm.targetGroupId ? automationForm.assignmentStrategy : null,
      targetPriority: automationForm.targetPriority || null,
      targetStatus: automationForm.targetStatus || null
    }
    const editing = Boolean(editingAutomationRule.value)
    const path = editingAutomationRule.value
      ? '/api/v1/admin/automation-rules/' + editingAutomationRule.value.id
      : '/api/v1/admin/automation-rules'
    await api(path, { method: editing ? 'PUT' : 'POST', body: JSON.stringify(payload) })
    closeAutomationModal()
    notify(editing ? '自动化规则已更新' : '自动化规则已创建')
    await loadTeam()
  } catch (err) {
    error.value = err.message
  } finally {
    savingAutomationRule.value = false
  }
}

async function toggleAutomationRule(rule) {
  try {
    await api('/api/v1/admin/automation-rules/' + rule.id + '/enabled?enabled=' + (!rule.enabled), { method: 'PATCH' })
    notify(rule.enabled ? '自动化规则已停用' : '自动化规则已启用')
    await loadTeam()
  } catch (err) { error.value = err.message }
}

async function deleteAutomationRule(rule) {
  if (!window.confirm('确认删除规则“' + rule.name + '”吗？')) return
  try {
    await api('/api/v1/admin/automation-rules/' + rule.id, { method: 'DELETE' })
    notify('自动化规则已删除')
    await loadTeam()
  } catch (err) { error.value = err.message }
}

function openAuditExportModal() {
  if (!isAdmin.value) return
  auditExportActions.value = adminAuditFilter.action
    ? [adminAuditFilter.action]
    : auditActionOptions.map(option => option.value)
  showAuditExportModal.value = true
}

function selectAllAuditExportActions() {
  auditExportActions.value = auditActionOptions.map(option => option.value)
}

function clearAuditExportActions() {
  auditExportActions.value = []
}

async function exportAdminAuditLogs() {
  if (!isAdmin.value || !auditExportActions.value.length) return
  exportingAuditLogs.value = true
  try {
    const params = new URLSearchParams()
    params.set('actions', auditExportActions.value.join(','))
    if (adminAuditFilter.actorId) params.set('actorId', adminAuditFilter.actorId)
    if (adminAuditFilter.from) params.set('from', adminAuditFilter.from)
    if (adminAuditFilter.to) params.set('to', adminAuditFilter.to)
    params.set('limit', '200')
    await downloadCsv('/api/v1/exports/audit-logs.csv?' + params.toString(), 'opsflow-audit-logs.csv')
    showAuditExportModal.value = false
    notify('审计日志 CSV 已下载（' + auditExportActions.value.length + ' 类事件）')
  } catch (err) { error.value = err.message }
  finally { exportingAuditLogs.value = false }
}

async function createAdminUser() {
  if (!isAdmin.value) return
  creatingAdminUser.value = true
  try {
    await api('/api/v1/admin/users', {
      method: 'POST',
      body: JSON.stringify(adminUserForm)
    })
    Object.assign(adminUserForm, { username: '', displayName: '', password: '', role: 'AGENT' })
    showCreateUserModal.value = false
    notify('用户已创建')
    await loadTeam()
  } catch (err) {
    error.value = err.message
  } finally {
    creatingAdminUser.value = false
  }
}

async function toggleUserStatus(teamUser) {
  const nextStatus = teamUser.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
  try {
    await api('/api/v1/admin/users/' + teamUser.id + '/status', {
      method: 'PATCH',
      body: JSON.stringify({ status: nextStatus })
    })
    notify(nextStatus === 'ACTIVE' ? '用户已启用' : '用户已停用')
    await loadTeam()
  } catch (err) { error.value = err.message }
}

async function changeUserRole(teamUser, role) {
  if (!role || role === primaryRole(teamUser)) return
  try {
    await api('/api/v1/admin/users/' + teamUser.id + '/role', {
      method: 'PUT',
      body: JSON.stringify({ role })
    })
    notify('用户角色已更新')
    await loadTeam()
  } catch (err) { error.value = err.message }
}

async function loadNotifications() {
  if (!token.value) return
  loadingNotifications.value = true
  try {
    const [items, count] = await Promise.all([
      api('/api/v1/notifications?limit=20'),
      api('/api/v1/notifications/unread-count')
    ])
    notifications.value = items || []
    notificationUnread.value = count || 0
  } catch (err) {
    error.value = err.message
  } finally {
    loadingNotifications.value = false
  }
}

async function toggleNotifications() {
  showNotifications.value = !showNotifications.value
  if (showNotifications.value) await loadNotifications()
}

async function openNotification(notification) {
  try {
    if (!notification.readAt) {
      await api('/api/v1/notifications/' + notification.id + '/read', { method: 'POST' })
    }
    notification.readAt = new Date().toISOString()
    notificationUnread.value = Math.max(0, notificationUnread.value - 1)
    if (notification.type === 'SATISFACTION_REQUESTED') {
      showNotifications.value = false
      activeView.value = 'satisfaction'
      return
    }
    if (notification.ticketId) {
      const ticket = tickets.value.find(item => item.id === notification.ticketId)
        || await api('/api/v1/tickets/' + notification.ticketId)
      showNotifications.value = false
      await openTicket(ticket)
    }
  } catch (err) { error.value = err.message }
}

async function markAllNotifications() {
  try {
    await api('/api/v1/notifications/read-all', { method: 'POST' })
    notifications.value = notifications.value.map(item => ({ ...item, readAt: item.readAt || new Date().toISOString() }))
    notificationUnread.value = 0
    notify('通知已全部读完')
  } catch (err) { error.value = err.message }
}

async function runSlaScan() {
  if (!canBulkUpdate.value) return
  try {
    const created = await api('/api/v1/notifications/scan', { method: 'POST' })
    await loadNotifications()
    notify('SLA 扫描完成，新增 ' + created + ' 条通知')
  } catch (err) { error.value = err.message }
}

async function bulkUpdateTickets() {
  if (!canBulkUpdate.value || !selectedTicketIds.value.length) return
  if (!bulkForm.status && !bulkForm.priority) {
    error.value = '请选择要批量更新的状态或优先级'
    return
  }
  try {
    const payload = { ticketIds: selectedTicketIds.value }
    if (bulkForm.status) payload.targetStatus = bulkForm.status
    if (bulkForm.priority) payload.priority = bulkForm.priority
    const result = await api('/api/v1/tickets/batch-update', {
      method: 'POST',
      body: JSON.stringify(payload)
    })
    notify('已批量更新 ' + result.updated + ' 张工单')
    clearTicketSelection()
    await loadDashboard()
  } catch (err) { error.value = err.message }
}

async function createTicket() {
  try {
    await api('/api/v1/tickets', {
      method: 'POST',
      headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify(ticketForm)
    })
    Object.assign(ticketForm, { title: '', description: '', category: '网络', priority: 'MEDIUM' })
    showTicketModal.value = false
    notify('工单已创建')
    await loadDashboard()
  } catch (err) { error.value = err.message }
}

async function advanceTicket(ticket) {
  const target = nextStatus[ticket.status]
  if (!target) return
  try {
    await api(`/api/v1/tickets/${ticket.id}/transition?target=${target}`, { method: 'POST' })
    notify(`工单已更新为${statusLabels[target]}`)
    await loadDashboard()
  } catch (err) { error.value = err.message }
}

function openArticleModal(article = null) {
  editingArticle.value = article
  Object.assign(articleForm, {
    title: article?.title || '',
    slug: article?.slug || '',
    category: article?.category || '网络',
    content: article?.content || ''
  })
  showArticleModal.value = true
}

function closeArticleModal() {
  showArticleModal.value = false
  editingArticle.value = null
  Object.assign(articleForm, { title: '', slug: '', category: '网络', content: '' })
}

async function openArticle(article) {
  if (!article) return
  selectedArticle.value = article
  selectedArticleVersion.value = null
  articleVersions.value = []
  articleFeedback.value = null
  showArticleVersions.value = false
  showArticleReader.value = true
  articleReaderLoading.value = article.status === 'PUBLISHED'
  feedbackLoading.value = article.status === 'PUBLISHED'
  if (article.status !== 'PUBLISHED') return
  try {
    const detail = await api('/api/v1/knowledge/articles/' + article.id)
    selectedArticle.value = detail
    const localArticle = articles.value.find(item => item.id === detail.id)
    if (localArticle) Object.assign(localArticle, detail)
    try {
      articleFeedback.value = await api('/api/v1/knowledge/articles/' + article.id + '/feedback')
    } catch (feedbackError) {
      articleFeedback.value = null
    }
  } catch (err) {
    showArticleReader.value = false
    error.value = err.message
  } finally {
    articleReaderLoading.value = false
    feedbackLoading.value = false
  }
}

function closeArticleReader() {
  showArticleReader.value = false
  articleReaderLoading.value = false
  selectedArticle.value = null
  articleFeedback.value = null
  feedbackLoading.value = false
  submittingArticleFeedback.value = false
  showArticleVersions.value = false
  articleVersionsLoading.value = false
  articleVersions.value = []
  selectedArticleVersion.value = null
}

async function openArticleVersions() {
  if (!canBulkUpdate.value || !selectedArticle.value) return
  showArticleVersions.value = true
  articleVersionsLoading.value = true
  selectedArticleVersion.value = null
  try {
    articleVersions.value = await api('/api/v1/knowledge/articles/' + selectedArticle.value.id + '/versions') || []
  } catch (err) {
    error.value = err.message
    showArticleVersions.value = false
  } finally {
    articleVersionsLoading.value = false
  }
}

function selectArticleVersion(version) {
  selectedArticleVersion.value = version
}

function showCurrentArticleVersion() {
  selectedArticleVersion.value = null
}
async function clearArticleFeedback() {
  if (!selectedArticle.value || selectedArticle.value.status !== 'PUBLISHED') return
  submittingArticleFeedback.value = true
  try {
    articleFeedback.value = await api('/api/v1/knowledge/articles/' + selectedArticle.value.id + '/feedback', { method: 'DELETE' })
    notify('已撤销本次反馈')
  } catch (err) { error.value = err.message }
  finally { submittingArticleFeedback.value = false }
}
async function submitArticleFeedback(helpful) {
  if (!selectedArticle.value || selectedArticle.value.status !== 'PUBLISHED') return
  submittingArticleFeedback.value = true
  try {
    articleFeedback.value = await api('/api/v1/knowledge/articles/' + selectedArticle.value.id + '/feedback', {
      method: 'POST',
      body: JSON.stringify({ helpful })
    })
    notify(helpful ? '已记录“有帮助”反馈' : '已记录改进建议')
  } catch (err) { error.value = err.message }
  finally { submittingArticleFeedback.value = false }
}
async function restoreArticleVersion(version) {
  if (!canBulkUpdate.value || !selectedArticle.value || !version) return
  if (!window.confirm('确认将文章恢复为 v' + version.versionNo + ' 吗？当前内容会保留在新的版本记录中。')) return
  try {
    const updated = await api('/api/v1/knowledge/articles/' + selectedArticle.value.id + '/versions/' + version.id + '/restore', { method: 'POST' })
    selectedArticle.value = updated
    const localArticle = articles.value.find(item => item.id === updated.id)
    if (localArticle) Object.assign(localArticle, updated)
    selectedArticleVersion.value = null
    await openArticleVersions()
    notify('历史版本已恢复为当前内容')
  } catch (err) { error.value = err.message }
}
function editArticleFromReader() {
  const article = selectedArticle.value
  closeArticleReader()
  if (article) openArticleModal(article)
}

function resetKnowledgeFilters() {
  knowledgeKeyword.value = ''
  knowledgeCategory.value = ''
  knowledgeStatus.value = ''
}

async function saveArticle() {
  savingArticle.value = true
  try {
    if (editingArticle.value) {
      await api('/api/v1/knowledge/articles/' + editingArticle.value.id, {
        method: 'PUT',
        body: JSON.stringify(articleForm)
      })
      notify('知识文章已更新')
    } else {
      const article = await api('/api/v1/knowledge/articles', {
        method: 'POST',
        body: JSON.stringify(articleForm)
      })
      await api('/api/v1/knowledge/articles/' + article.id + '/publish', { method: 'POST' })
      notify('知识文章已发布')
    }
    closeArticleModal()
    await loadDashboard()
  } catch (err) { error.value = err.message }
  finally { savingArticle.value = false }
}

async function publishArticle(article) {
  if (!canBulkUpdate.value || article.status !== 'DRAFT') return
  try {
    await api('/api/v1/knowledge/articles/' + article.id + '/publish', { method: 'POST' })
    await loadDashboard()
    notify('知识文章已发布')
  } catch (err) { error.value = err.message }
}

async function archiveArticle(article) {
  if (!canBulkUpdate.value || article.status === 'ARCHIVED') return
  if (!window.confirm('确认归档知识文章“' + article.title + '”吗？')) return
  try {
    await api('/api/v1/knowledge/articles/' + article.id + '/archive', { method: 'POST' })
    await loadDashboard()
    notify('知识文章已归档')
  } catch (err) { error.value = err.message }
}

async function restoreArticle(article) {
  if (!canBulkUpdate.value || article.status !== 'ARCHIVED') return
  try {
    await api('/api/v1/knowledge/articles/' + article.id + '/restore', { method: 'POST' })
    await loadDashboard()
    notify('知识文章已恢复为草稿')
  } catch (err) { error.value = err.message }
}
onMounted(() => { if (token.value && !isPublicStatusPage) loadDashboard() })
</script>

<template>
  <StatusPagePublic v-if="isPublicStatusPage" />
  <main v-else class="app-shell">
    <section v-if="!token" class="login-page">
      <div class="login-card">
        <div class="brand-mark">OF</div>
        <p class="eyebrow">ENTERPRISE SERVICE OPERATIONS</p>
        <h1>让每个请求，都有清晰的流转路径。</h1>
        <p class="login-subtitle">OpsFlow 将工单、知识和团队协作放在同一个工作台。</p>
        <form class="login-form" @submit.prevent="signIn">
          <label>账号<input v-model="loginForm.username" autocomplete="username" /></label>
          <label>密码<input v-model="loginForm.password" type="password" autocomplete="current-password" /></label>
          <p v-if="error" class="form-error">{{ error }}</p>
          <button class="primary-button" :disabled="loading">{{ loading ? '登录中…' : '进入工作台' }}</button>
        </form>
        <p class="demo-hint">演示账号：opsflow_demo / opsflow123</p>
      </div>
      <div class="login-side"><p class="eyebrow">OPS / FLOW / 01</p><h2>把“有人跟进”<br /><span>变成可观测的流程。</span></h2><div class="signal-card"><i></i>系统运行正常<strong>99.9%</strong></div></div>
    </section>

    <section v-else class="workspace">
      <GlobalSearch ref="globalSearchRef" :api="api" @select="handleGlobalSearchSelect" />
      <aside class="sidebar">
        <div class="brand-lockup"><div class="brand-mark small">OF</div><div><strong>OpsFlow</strong><small>Service workspace</small></div></div>
        <p class="workspace-label">工作台</p>
        <nav>
          <button :class="{ active: activeView === 'overview' }" @click="activeView = 'overview'"><b>⌂</b>总览</button>
          <button @click="globalSearchRef?.openSearch()"><b>⌕</b>全局搜索<em>Ctrl K</em></button>
          <button :class="{ active: activeView === 'tickets' }" @click="activeView = 'tickets'"><b>◫</b>工单中心<em>{{ ticketTotal }}</em></button>
          <button :class="{ active: activeView === 'satisfaction' }" @click="activeView = 'satisfaction'"><b>★</b>服务评价</button>
          <button v-if="canBulkUpdate" :class="{ active: activeView === 'workbench' }" @click="activeView = 'workbench'"><b>◎</b>运营待办</button>
          <button v-if="canBulkUpdate" :class="{ active: activeView === 'supportGroups' }" @click="activeView = 'supportGroups'"><b>♜</b>支持组队列</button>
          <button :class="{ active: activeView === 'catalog' }" @click="activeView = 'catalog'"><b>◇</b>服务目录<em v-if="pendingServiceRequestCount">{{ pendingServiceRequestCount }}</em></button>          <button :class="{ active: activeView === 'changes' }" @click="activeView = 'changes'"><b>↗</b>变更管理<em v-if="pendingChangeCount">{{ pendingChangeCount }}</em></button>          <button v-if="canBulkUpdate" :class="{ active: activeView === 'releases' }" @click="activeView = 'releases'"><b>⇪</b>发布治理</button>          <button v-if="canBulkUpdate" :class="{ active: activeView === 'runbooks' }" @click="activeView = 'runbooks'"><b>☷</b>运行手册</button>          <button v-if="canBulkUpdate" :class="{ active: activeView === 'alerts' }" @click="activeView = 'alerts'"><b>⚡</b>监控告警</button>          <button v-if="canBulkUpdate" :class="{ active: activeView === 'reliability' }" @click="activeView = 'reliability'"><b>◌</b>可靠性 SLO</button>          <button v-if="canBulkUpdate" :class="{ active: activeView === 'statusPage' }" @click="activeView = 'statusPage'"><b>◉</b>服务状态</button>          <button v-if="canBulkUpdate" :class="{ active: activeView === 'oncall' }" @click="activeView = 'oncall'"><b>◷</b>值班升级</button>          <button v-if="canBulkUpdate" :class="{ active: activeView === 'incidents' }" @click="activeView = 'incidents'"><b>⚑</b>事件指挥</button>          <button v-if="canBulkUpdate" :class="{ active: activeView === 'postmortems' }" @click="activeView = 'postmortems'"><b>◈</b>事件复盘</button>          <button :class="{ active: activeView === 'problems' }" @click="activeView = 'problems'"><b>⌁</b>问题管理<em v-if="pendingProblemCount">{{ pendingProblemCount }}</em></button><button :class="{ active: activeView === 'cmdb' }" @click="activeView = 'cmdb'"><b>▦</b>资产 CMDB<em>{{ cmdbAssets.length }}</em></button>
          <button :class="{ active: activeView === 'knowledge' }" @click="activeView = 'knowledge'"><b>▤</b>知识库<em>{{ articleTotal }}</em></button>
          <button v-if="isAdmin" :class="{ active: activeView === 'integrations' }" @click="activeView = 'integrations'"><b>⌁</b>集成中心<em v-if="webhookDeadCount">{{ webhookDeadCount }}</em></button>
          <button v-if="isAdmin" :class="{ active: activeView === 'team' }" @click="openTeam"><b>♙</b>团队管理<em>{{ teamUsers.length }}</em></button>
        </nav>
        <div class="sidebar-bottom"><div class="mini-status"><i></i><span>API & Redis 正常</span></div><button class="logout-button" @click="logout">退出登录</button></div>
      </aside>

      <div class="content-area">
        <header class="topbar"><div><p class="eyebrow">{{ pageEyebrow }}</p><h1>{{ pageTitle }}</h1></div><div class="topbar-user"><div class="notification-wrap"><button class="notification-button" @click="toggleNotifications" aria-label="打开通知中心">♢<b v-if="notificationUnread">{{ notificationUnread > 99 ? '99+' : notificationUnread }}</b></button><div v-if="showNotifications" class="notification-popover"><div class="notification-header"><strong>通知中心</strong><div><button v-if="canBulkUpdate" class="notification-scan" @click.stop="runSlaScan">扫描 SLA</button><button class="notification-scan" @click.stop="markAllNotifications">全部已读</button></div></div><div v-if="loadingNotifications" class="notification-empty">正在加载通知…</div><div v-else-if="!notifications.length" class="notification-empty">暂无通知</div><button v-for="notification in notifications" v-else :key="notification.id" :class="['notification-item', { unread: !notification.readAt }]" @click="openNotification(notification)"><i></i><div><strong>{{ notification.title }}</strong><p>{{ notification.content }}</p><small>{{ formatDate(notification.createdAt) }}</small></div></button><div class="notification-footer">自动扫描 SLA 风险 · 仅展示最近 20 条</div></div></div><div class="avatar">{{ displayName.slice(0, 1) }}</div><div><strong>{{ displayName }}</strong><small>{{ user?.roles?.join(' · ') || 'REQUESTER' }}</small></div><button class="account-action" @click="showPasswordModal = true">修改密码</button></div></header>
        <p v-if="error" class="page-error">{{ error }} <button @click="error = ''">×</button></p>

        <section v-if="activeView === 'overview'" class="view-panel">
          <div class="hero-row"><div><span class="tag">TODAY'S CONTROL ROOM</span><h2>把团队的注意力，<br /><span>放在真正重要的请求上。</span></h2><p>从接收到解决，每一步都有记录，每个知识都能复用。</p></div><button class="primary-button" @click="showTicketModal = true">＋ 创建新工单</button></div>
          <div class="stat-grid"><article class="stat-card"><div class="stat-icon blue">◫</div><span>全部工单</span><strong>{{ dashboardOverview?.total ?? ticketTotal }}</strong><small>当前可见范围</small></article><article class="stat-card"><div class="stat-icon amber">◷</div><span>处理中</span><strong>{{ activeCount }}</strong><small>需要团队持续跟进</small></article><article class="stat-card"><div class="stat-icon red">!</div><span>SLA 逾期</span><strong>{{ slaSummary.overdue }}</strong><small>需要立即升级处理</small></article><article class="stat-card"><div class="stat-icon amber">⚡</div><span>紧急事项</span><strong>{{ urgentCount }}</strong><small>优先处理高影响请求</small></article><article class="stat-card"><div class="stat-icon green">✓</div><span>已解决</span><strong>{{ resolvedCount }}</strong><small>今日新增 {{ resolvedToday }} 张</small></article></div>
          <div class="two-column"><article class="panel-card"><div class="panel-heading"><div><p class="eyebrow">LATEST REQUESTS</p><h3>最近工单</h3></div><button class="text-button" @click="activeView = 'tickets'">查看全部 →</button></div><div v-if="loading" class="empty-state">正在同步数据…</div><div v-else-if="!tickets.length" class="empty-state">还没有工单，创建第一张工单吧。</div><div v-else class="ticket-list"><div v-for="ticket in tickets.slice(0, 5)" :key="ticket.id" class="ticket-row"><code>#{{ ticket.ticketNo?.replace('OPF-', '') || ticket.id }}</code><div><strong>{{ ticket.title }}</strong><small>{{ ticket.category || '未分类' }} · {{ assigneeName(ticket) }} · {{ formatDate(ticket.createdAt) }}</small></div><span :class="['status-pill', statusClass(ticket.status)]">{{ statusLabels[ticket.status] || ticket.status }}</span></div></div></article><article class="panel-card knowledge-highlight"><div class="panel-heading"><div><p class="eyebrow">KNOWLEDGE SIGNAL</p><h3>知识库</h3></div><button class="text-button" @click="activeView = 'knowledge'">浏览 →</button></div><div class="knowledge-number">{{ articleTotal }}<small>篇</small></div><p>把解决方案沉淀下来，让下一次响应更快。</p><div class="knowledge-line"><span></span></div><small>本租户已发布知识文章</small></article></div>
          <div class="dashboard-insights"><article class="panel-card"><div class="panel-heading"><div><p class="eyebrow">QUEUE HEALTH</p><h3>队列结构</h3></div><span class="insight-caption">今日新增 {{ createdToday }}</span></div><div class="metric-bars"><div v-for="metric in statusMetrics" :key="metric.key" class="metric-row"><span>{{ metric.label }}</span><div class="metric-track"><i :class="statusClass(metric.key)" :style="{ width: (dashboardOverview?.total ? Math.max(4, metric.count / dashboardOverview.total * 100) : 4) + '%' }"></i></div><b>{{ metric.count }}</b></div></div></article><article class="panel-card"><div class="panel-heading"><div><p class="eyebrow">OWNERSHIP</p><h3>处理覆盖</h3></div><span class="insight-caption">{{ assignedCount }} / {{ dashboardOverview?.total ?? 0 }}</span></div><div class="ownership-ring" :style="{ '--coverage': dashboardOverview?.total ? Math.round(assignedCount / dashboardOverview.total * 360) : 0 }"><div><strong>{{ dashboardOverview?.total ? Math.round(assignedCount / dashboardOverview.total * 100) : 0 }}%</strong><small>已分配</small></div></div><div class="ownership-meta"><span><i class="dot green"></i>已分配 {{ assignedCount }}</span><span><i class="dot amber"></i>待认领 {{ unassignedCount }}</span></div></article><article class="panel-card"><div class="panel-heading"><div><p class="eyebrow">PRIORITY MIX</p><h3>优先级分布</h3></div></div><div class="priority-mix"><div v-for="metric in priorityMetrics" :key="metric.key" class="priority-chip"><span>{{ metric.label }}</span><strong>{{ metric.count }}</strong></div></div></article></div>
          <section class="panel-card trend-panel"><div class="panel-heading"><div><p class="eyebrow">OPERATIONS TREND</p><h3>近 14 天流转趋势</h3></div><span class="insight-caption">创建 / 解决</span></div><div v-if="!dashboardTrends.length" class="empty-state">暂无趋势数据。</div><div v-else class="trend-chart"><div v-for="trend in dashboardTrends" :key="trend.date" class="trend-day"><div class="trend-bars"><i class="trend-created" :style="{ height: trendBarHeight(trend.created) + '%' }" :title="'创建 ' + trend.created"></i><i class="trend-resolved" :style="{ height: trendBarHeight(trend.resolved) + '%' }" :title="'解决 ' + trend.resolved"></i></div><small>{{ trendLabel(trend.date) }}</small></div></div><div class="trend-legend"><span><i class="trend-created"></i>创建 {{ dashboardTrends.reduce((sum, item) => sum + item.created, 0) }}</span><span><i class="trend-resolved"></i>解决 {{ dashboardTrends.reduce((sum, item) => sum + item.resolved, 0) }}</span></div></section>        <section v-if="canBulkUpdate && dashboardReport" class="panel-card operations-report-panel"><div class="panel-heading"><div><p class="eyebrow">PERFORMANCE REPORT</p><h3>工单运营报表</h3><small class="report-period">{{ dashboardReport.from }} 至 {{ dashboardReport.to }}</small></div><div class="report-range-switch"><button v-for="option in reportRangeOptions" :key="option.value" type="button" :class="{ active: reportRangeDays === option.value }" :disabled="loading" @click="changeReportRange(option.value)">{{ option.label }}</button><button type="button" class="primary-button report-export-button" :disabled="loading || exportingDashboardReport" @click="exportDashboardReport">{{ exportingDashboardReport ? '导出中…' : '导出 CSV' }}</button></div></div><div class="report-stat-grid"><article class="report-stat-card"><span>周期新增</span><strong>{{ dashboardReport.createdTickets }}</strong><small>时间范围内创建</small></article><article class="report-stat-card"><span>周期已解决</span><strong>{{ dashboardReport.resolvedTickets }}</strong><small>按解决时间统计</small></article><article class="report-stat-card"><span>平均解决时长</span><strong>{{ dashboardReport.averageResolutionHours }}<small>小时</small></strong><small>已解决工单样本</small></article><article class="report-stat-card"><span>SLA 达成率</span><strong>{{ dashboardReport.slaComplianceRate }}%</strong><small>{{ dashboardReport.slaResolvedSample }} 个有截止时间的样本</small></article><article class="report-stat-card"><span>当前逾期</span><strong>{{ dashboardReport.overdueTickets }}</strong><small>活跃工单 · 当前时点</small></article></div><div class="report-breakdown-grid"><section class="report-breakdown-card"><div class="panel-heading"><div><p class="eyebrow">ASSIGNEE PERFORMANCE</p><h3>处理人表现</h3></div><span class="insight-caption">最多 8 人</span></div><div v-if="!dashboardReport.assignees.length" class="empty-state">当前时间范围暂无处理人数据。</div><div v-else class="report-list"><div v-for="metric in dashboardReport.assignees" :key="String(metric.assigneeId || 'unassigned')" class="report-row"><div><strong>{{ staffDisplayName(metric.assigneeId) }}</strong><small>新增 {{ metric.createdTickets }} · 处理中 {{ metric.activeTickets }} · 平均 {{ metric.averageResolutionHours }}h</small></div><b>{{ metric.resolvedTickets }}<small>已解决</small></b><span class="report-rate">{{ metric.slaComplianceRate }}%<small>SLA</small></span></div></div></section><section class="report-breakdown-card"><div class="panel-heading"><div><p class="eyebrow">CATEGORY MIX</p><h3>分类分布</h3></div><span class="insight-caption">最多 6 类</span></div><div v-if="!dashboardReport.categories.length" class="empty-state">当前时间范围暂无分类数据。</div><div v-else class="report-list"><div v-for="metric in dashboardReport.categories" :key="metric.category" class="report-row"><div><strong>{{ metric.category }}</strong><small>新增 {{ metric.createdTickets }} · 已解决 {{ metric.resolvedTickets }}</small></div><b>{{ metric.activeTickets }}<small>处理中</small></b></div></div></section></div><div class="report-subscription-panel"><div class="report-subscription-heading"><div><p class="eyebrow">AUTOMATED DIGESTS</p><h3>报表订阅</h3><small>日报或周报会自动生成运营摘要，并发送到通知中心。</small></div><button type="button" class="secondary-button" @click="openReportSubscriptionModal()">＋ 新建订阅</button></div><div v-if="!reportSubscriptions.length" class="report-subscription-empty">还没有报表订阅。创建一个日报或周报，让团队定期收到运营摘要。</div><div v-else class="report-subscription-list"><article v-for="subscription in reportSubscriptions" :key="subscription.id" class="report-subscription-row"><div class="report-subscription-main"><div class="report-subscription-title"><strong>{{ subscription.name }}</strong><span :class="['report-subscription-status', { enabled: subscription.enabled }]">{{ subscription.enabled ? '运行中' : '已停用' }}</span></div><small>{{ reportFrequencyLabel(subscription.frequency) }} · 近 {{ subscription.rangeDays }} 天 · 下次生成 {{ formatDate(subscription.nextRunAt) }}</small><small v-if="subscription.lastRunAt">上次生成 {{ formatDate(subscription.lastRunAt) }}</small></div><div class="report-subscription-actions"><button type="button" class="row-action" :disabled="runningReportSubscriptionId === subscription.id" @click="runReportSubscription(subscription)">{{ runningReportSubscriptionId === subscription.id ? '生成中…' : '立即生成' }}</button><button type="button" class="row-action" @click="openReportSubscriptionModal(subscription)">编辑</button><button type="button" class="row-action" @click="toggleReportSubscription(subscription)">{{ subscription.enabled ? '停用' : '启用' }}</button><button type="button" class="row-action danger" :disabled="deletingReportSubscriptionId === subscription.id" @click="deleteReportSubscription(subscription)">{{ deletingReportSubscriptionId === subscription.id ? '删除中…' : '删除' }}</button></div></article></div></div></section><section class="risk-panel panel-card"><div class="panel-heading"><div><p class="eyebrow">SLA AT RISK</p><h3>需要优先关注的工单</h3></div><button class="text-button" @click="ticketOverdue = true; activeView = 'tickets'; searchTickets()">查看逾期 →</button></div><div v-if="!slaRiskTickets.length" class="empty-state">当前没有临近 SLA 截止的活跃工单。</div><div v-else class="risk-list"><button v-for="ticket in slaRiskTickets" :key="ticket.id" class="risk-row" @click="openTicket(ticket)"><div class="risk-main"><strong>{{ ticket.title }}</strong><small>{{ ticket.ticketNo }} · {{ priorityLabels[ticket.priority] || ticket.priority }} · 截止 {{ formatDate(ticket.dueAt) }}</small></div><span :class="['risk-badge', { overdue: isOverdue(ticket) }]">{{ isOverdue(ticket) ? '已逾期' : '24h 内到期' }}</span></button></div></section>
        </section>


<CustomerSatisfactionCenter v-else-if="activeView === 'satisfaction'" :api="api" :is-staff="canBulkUpdate" />
<OperationsWorkbench v-else-if="activeView === 'workbench'" :api="api" :is-admin="isAdmin" @navigate="activeView = $event" />
<SupportGroupCenter v-else-if="activeView === 'supportGroups'" :api="api" :staff-members="staffMembers" :is-admin="isAdmin" />
<section v-else-if="activeView === 'tickets'" class="view-panel">
  <div class="section-toolbar">
    <div><p class="eyebrow">WORK QUEUE</p><h2>工作队列</h2></div>
    <div class="toolbar-actions">
      <div v-if="selectedTicketIds.length" class="bulk-actions">
        <span class="selection-count">已选 {{ selectedTicketIds.length }} 张</span>
        <template v-if="canBulkUpdate">
          <select v-model="bulkForm.status" aria-label="批量状态"><option value="">状态不变</option><option value="IN_PROGRESS">处理中</option><option value="WAITING_CONFIRMATION">待确认</option><option value="RESOLVED">已解决</option><option value="CLOSED">已关闭</option><option value="REJECTED">已驳回</option></select>
          <select v-model="bulkForm.priority" aria-label="批量优先级"><option value="">优先级不变</option><option value="URGENT">紧急</option><option value="HIGH">高</option><option value="MEDIUM">中</option><option value="LOW">低</option></select>
          <button class="secondary-button" @click="bulkUpdateTickets">批量应用</button>
        </template>
        <button class="clear-selection" @click="clearTicketSelection">清除选择</button>
      </div>
      <div class="saved-view-actions">
        <select v-model="selectedSavedViewId" @change="applySavedView($event.target.value)" aria-label="选择保存视图"><option value="">保存视图</option><option v-for="view in savedViews" :key="view.id" :value="String(view.id)">{{ view.name }} · {{ view.visibility === 'TEAM' ? '团队' : '个人' }}</option></select>
        <button v-if="canEditSelectedSavedView" class="text-button saved-view-edit" @click="openSavedViewModal(selectedSavedView)">编辑</button><button class="text-button saved-view-save" @click="openSavedViewModal()">保存当前</button>
        <button v-if="canDeleteSelectedSavedView" class="text-button saved-view-delete" @click="deleteSelectedSavedView">删除</button>
      </div>
      <input v-model="ticketKeyword" placeholder="搜索标题 / 编号…" @keyup.enter="searchTickets" />
      <select v-model="ticketFilter"><option value="">全部状态</option><option v-for="(label, value) in statusLabels" :key="value" :value="value">{{ label }}</option></select>
      <select v-model="ticketPriority"><option value="">全部优先级</option><option value="URGENT">紧急</option><option value="HIGH">高</option><option value="MEDIUM">中</option><option value="LOW">低</option></select><select v-model="ticketSort" @change="searchTickets" aria-label="工单排序"><option v-for="option in ticketSortOptions" :key="option.value" :value="option.value">排序：{{ option.label }}</option></select>
      <label class="check-filter"><input v-model="ticketOverdue" type="checkbox" /> 仅逾期</label>
      <label v-if="canBulkUpdate" class="check-filter"><input v-model="ticketMine" type="checkbox" /> 我的工单</label>
      <button class="secondary-button search-button" @click="searchTickets">查询</button>
      <button v-if="canBulkUpdate" class="secondary-button import-button" @click="openImportModal">批量导入</button>
      <button class="secondary-button export-button" :class="{ 'export-ready': selectedTicketIds.length }" @click="openTicketExportModal">导出 CSV<span v-if="selectedTicketIds.length">（{{ selectedTicketIds.length }}）</span></button>
      <button class="primary-button" @click="showTicketModal = true">＋ 新建工单</button>
    </div>
  </div>
  <div class="panel-card table-card">
    <div class="table-head with-selection">
      <span class="selection-column"><input class="table-checkbox" type="checkbox" :checked="allVisibleSelected" :indeterminate="someVisibleSelected" @change="toggleAllTickets" aria-label="选择当前列表工单" /></span>
      <span>工单</span><span>分类</span><span>优先级</span><span>状态</span><span>创建时间 / SLA</span><span>操作</span>
    </div>
    <div v-if="!filteredTickets.length" class="empty-state">没有符合条件的工单。</div>
    <div v-for="ticket in filteredTickets" :key="ticket.id" :class="['table-row', 'with-selection', { selected: selectedTicketIds.includes(ticket.id) }]">
      <span class="selection-column"><input class="table-checkbox" type="checkbox" :checked="selectedTicketIds.includes(ticket.id)" @change="toggleTicketSelection(ticket.id)" @click.stop aria-label="选择工单" /></span>
      <div><button class="ticket-title-button" @click="openTicket(ticket)"><strong>{{ ticket.title }}</strong><small>{{ ticket.ticketNo }}</small></button></div>
      <span>{{ ticket.category || '未分类' }}</span>
      <b :class="['priority-text', `priority-${ticket.priority?.toLowerCase()}`]">{{ priorityLabels[ticket.priority] || ticket.priority }}</b>
      <span :class="['status-pill', statusClass(ticket.status)]">{{ statusLabels[ticket.status] || ticket.status }}</span>
      <span>{{ formatDate(ticket.createdAt) }}<small class="sla-hint">{{ isOverdue(ticket) ? "SLA 已逾期" : "截止 " + formatDate(ticket.dueAt) }}</small></span>
      <div class="row-actions"><button v-if="canBulkUpdate" class="row-action assign-action" @click="openAssignModal(ticket)">指派</button><button v-if="canBulkUpdate && !ticket.assigneeId" class="row-action claim-action" @click="claimTicket(ticket)">领取</button><button v-else-if="canBulkUpdate && ticket.assigneeId === user?.id" class="row-action claim-action" @click="releaseTicket(ticket)">释放</button><span v-else-if="canBulkUpdate && ticket.assigneeId" class="assignee-hint">已领取</span><button v-if="canAdvanceTicket(ticket)" class="row-action" :disabled="!nextStatus[ticket.status]" @click="advanceTicket(ticket)">{{ nextStatus[ticket.status] ? (ticket.status === 'WAITING_CONFIRMATION' && !canBulkUpdate ? '确认解决' : ticket.status === 'RESOLVED' && !canBulkUpdate ? '确认关闭' : '推进状态') : '已结束' }}</button></div>
    </div>
  </div>
  <div v-if="ticketTotal" class="queue-footer">
    <div class="queue-summary"><strong>显示 {{ ticketRangeStart }}-{{ ticketRangeEnd }} / {{ ticketTotal }} 张</strong><small v-if="selectedTicketIds.length">已选 {{ selectedTicketIds.length }} 张，翻页不会清除选择</small><small v-else>勾选工单后可批量处理或精确导出</small></div>
    <div class="queue-pagination"><label>每页 <select :value="ticketPageSize" @change="changeTicketPageSize($event.target.value)" aria-label="每页显示数量"><option :value="20">20</option><option :value="50">50</option><option :value="100">100</option></select> 条</label><button class="page-button" :disabled="loading || ticketPageNumber <= 1" @click="goToTicketPage(ticketPageNumber - 1)">上一页</button><span>第 {{ ticketPageNumber }} / {{ ticketPageCount }} 页</span><button class="page-button" :disabled="loading || ticketPageNumber >= ticketPageCount" @click="goToTicketPage(ticketPageNumber + 1)">下一页</button></div>
  </div><section v-else-if="activeView === 'integrations'" class="view-panel integration-view">
  <div class="section-toolbar"><div><p class="eyebrow">INTEGRATION CENTER</p><h2>集成中心</h2><small class="section-description">把工单、知识库和流程事件可靠地推送到外部系统。</small></div><div class="toolbar-actions"><button class="primary-button" @click="openWebhookModal()">＋ 新建 Webhook</button></div></div>
  <div class="integration-summary-strip"><article><span>集成总数</span><strong>{{ webhooks.length }}</strong><small>当前租户配置</small></article><article><span>运行中</span><strong>{{ webhookEnabledCount }}</strong><small>正在接收事件</small></article><article :class="{ attention: webhookOutboxPendingCount }"><span>事务消息积压</span><strong>{{ webhookOutboxPendingCount }}</strong><small>待展开 / 重试</small></article><article :class="{ attention: webhookPendingCount }"><span>出站待处理</span><strong>{{ webhookPendingCount }}</strong><small>等待 HTTP 投递</small></article><article :class="{ attention: webhookDeadCount + webhookOutboxDeadCount }"><span>全链路死信</span><strong>{{ webhookDeadCount + webhookOutboxDeadCount }}</strong><small>需要人工复核</small></article></div>
  <div class="integration-layout">
    <section class="panel-card webhook-list-panel"><div class="panel-heading"><div><p class="eyebrow">ENDPOINTS</p><h3>Webhook 订阅</h3></div><span class="insight-caption">{{ webhooks.length }} 个</span></div><div v-if="!webhooks.length" class="empty-state">还没有配置 Webhook。新建一个订阅，把 OpsFlow 事件接入告警、IM 或数据平台。</div><div v-else class="webhook-card-list"><article v-for="endpoint in webhooks" :key="endpoint.id" :class="['webhook-card', { disabled: !endpoint.enabled }]" @click="openWebhookModal(endpoint)"><div class="webhook-card-head"><div><span class="webhook-kicker">WEBHOOK #{{ endpoint.id }}</span><strong>{{ endpoint.name }}</strong></div><span :class="['webhook-state', endpoint.enabled ? 'enabled' : 'disabled']">{{ endpoint.enabled ? '已启用' : '已停用' }}</span></div><code class="webhook-url">{{ endpoint.url }}</code><div class="webhook-event-list"><span v-for="eventType in (endpoint.eventTypes || '*').split(',')" :key="eventType">{{ eventType === '*' ? '全部审计事件' : webhookEventLabel(eventType) }}</span></div><footer><small>事件：{{ endpoint.eventTypes }}</small><div class="webhook-card-actions"><button class="row-action" @click.stop="toggleWebhook(endpoint)">{{ endpoint.enabled ? '停用' : '启用' }}</button><button class="row-action" @click.stop="openWebhookModal(endpoint)">编辑</button><button class="row-action danger" @click.stop="deleteWebhook(endpoint)">删除</button></div></footer></article></div></section>
    <section class="panel-card webhook-delivery-panel"><div class="panel-heading"><div><p class="eyebrow">DELIVERY MONITOR</p><h3>投递监控</h3></div><span class="insight-caption">最近 100 条</span></div><div class="webhook-delivery-filters"><select v-model="selectedWebhookId" @change="loadWebhookDeliveries"><option value="">全部集成</option><option v-for="endpoint in webhooks" :key="endpoint.id" :value="endpoint.id">{{ endpoint.name }}</option></select><select v-model="webhookDeliveryStatusFilter"><option value="">全部状态</option><option v-for="(label, key) in webhookStatusLabels" :key="key" :value="key">{{ label }}</option></select></div><div v-if="!filteredWebhookDeliveries.length" class="empty-state">当前没有符合筛选条件的投递记录。产生一条工单或配置事件后，这里会显示投递状态。</div><div v-else class="webhook-delivery-list"><article v-for="item in filteredWebhookDeliveries" :key="item.delivery.id" class="webhook-delivery-row"><div class="webhook-delivery-head"><div><strong>{{ item.endpointName }}</strong><small>{{ webhookEventLabel(item.delivery.eventType) }} · {{ item.delivery.eventId ? item.delivery.eventId.substring(0, 12) : '未知事件' }}</small></div><span :class="['webhook-status', webhookStatusClass(item.delivery.status)]">{{ webhookStatusLabels[item.delivery.status] || item.delivery.status }}</span></div><div class="webhook-delivery-meta"><span>尝试 {{ item.delivery.attemptCount || 0 }} 次</span><span>HTTP {{ item.delivery.responseStatus || '—' }}</span><span>{{ formatDate(item.delivery.updatedAt || item.delivery.createdAt) }}</span></div><p v-if="item.delivery.lastError" class="webhook-delivery-error">{{ item.delivery.lastError }}</p><button v-if="item.delivery.status === 'DEAD'" class="row-action danger" :disabled="retryingWebhookDeliveryId === item.delivery.id" @click="retryWebhookDelivery(item)">{{ retryingWebhookDeliveryId === item.delivery.id ? '重新排队中…' : '手动重试' }}</button></article></div></section>
  </div>
  <section class="panel-card webhook-outbox-panel"><div class="panel-heading"><div><p class="eyebrow">TRANSACTIONAL OUTBOX</p><h3>事务消息流水</h3><small>业务数据、审计日志和事件在同一事务落库，再异步展开为具体 Webhook 投递。</small></div><div class="webhook-outbox-tools"><select v-model="webhookOutboxStatusFilter"><option value="">全部状态</option><option v-for="(label, key) in webhookOutboxStatusLabels" :key="key" :value="key">{{ label }}</option></select><button class="secondary-button" @click="loadWebhookOutbox">刷新流水</button></div></div><div class="webhook-outbox-health"><span><b>{{ webhookOutboxSummary.published || 0 }}</b>已可靠展开</span><span><b>{{ webhookOutboxSummary.failed || 0 }}</b>等待退避重试</span><span><b>{{ webhookOutboxSummary.dead || 0 }}</b>事务消息死信</span><span><b>{{ webhookOutboxSummary.oldestPendingAt ? formatDate(webhookOutboxSummary.oldestPendingAt) : '无积压' }}</b>最早待处理</span></div><div v-if="!filteredWebhookOutboxEvents.length" class="empty-state">暂无符合条件的 Outbox 事件。</div><div v-else class="webhook-outbox-list"><article v-for="event in filteredWebhookOutboxEvents" :key="event.id" class="webhook-outbox-row"><div><span :class="['webhook-status', 'webhook-status-' + String(event.status).toLowerCase()]">{{ webhookOutboxStatusLabels[event.status] || event.status }}</span><code>{{ event.eventId?.substring(0, 12) }}</code></div><strong>{{ webhookEventLabel(event.eventType) }}</strong><small>{{ event.resourceType || 'SYSTEM' }} #{{ event.resourceId || '—' }} · 审计 #{{ event.auditLogId }} · {{ formatDate(event.createdAt) }}</small><p v-if="event.lastError">{{ event.lastError }}</p><button v-if="['FAILED', 'DEAD'].includes(event.status)" class="row-action danger" :disabled="retryingWebhookOutboxId === event.id" @click="retryWebhookOutbox(event)">{{ retryingWebhookOutboxId === event.id ? '重新排队中…' : '重试事件' }}</button></article></div></section>
</section>
</section><AlertCenter v-else-if="activeView === 'alerts'" :api="api" :assets="cmdbAssets" :staff-members="staffMembers" :is-admin="isAdmin" /><ReleaseGovernanceCenter v-else-if="activeView === 'releases'" :api="api" :is-admin="isAdmin" /><RunbookCenter v-else-if="activeView === 'runbooks'" :api="api" :is-admin="isAdmin" /><ReliabilityCenter v-else-if="activeView === 'reliability'" :api="api" :is-admin="isAdmin" /><StatusPageCenter v-else-if="activeView === 'statusPage'" :api="api" :assets="cmdbAssets" :is-admin="isAdmin" /><OnCallCenter v-else-if="activeView === 'oncall'" :api="api" :staff-members="staffMembers" :is-admin="isAdmin" /><IncidentCommandCenter v-else-if="activeView === 'incidents'" :api="api" :staff-members="staffMembers" :tickets="tickets" :assets="cmdbAssets" :can-manage="canBulkUpdate" @changed="loadDashboard" /><PostmortemCenter v-else-if="activeView === 'postmortems'" :api="api" :staff-members="staffMembers" :is-admin="isAdmin" /><section v-else-if="activeView === 'changes'" class="view-panel change-view">
  <div class="section-toolbar"><div><p class="eyebrow">CHANGE MANAGEMENT</p><h2>变更管理</h2></div><div class="toolbar-actions change-toolbar-actions"><input v-model="changeKeyword" placeholder="搜索变更编号、标题或描述…" /><select v-model="changeStatusFilter" aria-label="变更状态"><option value="">全部状态</option><option v-for="(label, key) in changeStatusLabels" :key="key" :value="key">{{ label }}</option></select><select v-model="changeRiskFilter" aria-label="变更风险"><option value="">全部风险</option><option v-for="(label, key) in changeRiskLabels" :key="key" :value="key">{{ label }}</option></select><button v-if="changeKeyword || changeStatusFilter || changeRiskFilter" class="secondary-button" @click="changeKeyword = ''; changeStatusFilter = ''; changeRiskFilter = ''">重置</button><button class="primary-button" @click="openChangeModal">＋ 提交变更</button></div></div>
  <div class="change-summary-strip"><article><span>变更总量</span><strong>{{ changes.length }}</strong><small>当前租户申请</small></article><article :class="{ attention: pendingChangeCount }"><span>待审批</span><strong>{{ pendingChangeCount }}</strong><small>{{ isAdmin ? '需要管理员决策' : '等待风险审批' }}</small></article><article><span>实施中</span><strong>{{ changeInProgressCount }}</strong><small>正在执行的变更</small></article><article class="attention"><span>高风险活动</span><strong>{{ changeHighRiskCount }}</strong><small>高 / 严重风险未结束</small></article></div>
  <div class="change-layout"><section class="panel-card change-list-panel"><div class="panel-heading"><div><p class="eyebrow">CHANGE QUEUE</p><h3>{{ canBulkUpdate ? '变更申请队列' : '我的变更申请' }}</h3></div><span class="insight-caption">{{ filteredChanges.length }} 条</span></div><div v-if="!filteredChanges.length" class="empty-state">当前没有符合筛选条件的变更申请。点击右上角提交第一项变更。</div><div v-else class="change-card-list"><article v-for="change in filteredChanges" :key="change.id" :class="['change-card', { selected: selectedChangeDetail?.change?.id === change.id }]" @click="openChangeDetail(change)"><div class="change-card-head"><div><code>{{ change.changeNo }}</code><strong>{{ change.title }}</strong></div><span :class="['change-status', changeStatusClass(change.status)]">{{ changeStatusLabel(change.status) }}</span></div><p>{{ change.description }}</p><div class="change-card-meta"><span :class="['change-risk', 'risk-' + String(change.riskLevel || '').toLowerCase()]">{{ changeRiskLabel(change.riskLevel) }}风险</span><span>{{ changeTypeLabel(change.changeType) }}变更</span><span>{{ formatDate(change.createdAt) }}</span></div><footer><small>{{ changeRequesterName(change.requesterId) }}<span v-if="change.scheduledStart"> · 计划 {{ formatDate(change.scheduledStart) }}</span></small><div class="change-card-actions"><button v-if="isAdmin && change.status === 'PENDING_APPROVAL'" class="row-action approve" @click.stop="openChangeDecisionModal(change, 'APPROVE')">通过</button><button v-if="isAdmin && change.status === 'PENDING_APPROVAL'" class="row-action danger" @click.stop="openChangeDecisionModal(change, 'REJECT')">驳回</button><button v-if="canBulkUpdate && change.status === 'APPROVED'" class="row-action" @click.stop="transitionChange(change, 'IN_PROGRESS')">开始实施</button><button v-if="canBulkUpdate && change.status === 'IN_PROGRESS'" class="row-action" @click.stop="transitionChange(change, 'COMPLETED')">完成</button></div></footer></article></div></section><section v-if="selectedChangeDetail" class="panel-card change-detail-panel"><div v-if="changeDetailLoading" class="empty-state">正在读取变更详情…</div><template v-else><div class="change-detail-header"><div><p class="eyebrow">{{ selectedChangeDetail.change.changeType }} CHANGE</p><code>{{ selectedChangeDetail.change.changeNo }}</code><h2>{{ selectedChangeDetail.change.title }}</h2><small>申请人：{{ changeRequesterName(selectedChangeDetail.change.requesterId) }} · 创建于 {{ formatDate(selectedChangeDetail.change.createdAt) }}</small></div><div class="change-detail-header-actions"><span :class="['change-status', changeStatusClass(selectedChangeDetail.change.status)]">{{ changeStatusLabel(selectedChangeDetail.change.status) }}</span><button class="close-button" @click="closeChangeDetail">×</button></div></div><div class="change-detail-actions"><button v-if="isAdmin && selectedChangeDetail.change.status === 'PENDING_APPROVAL'" class="primary-button" @click="openChangeDecisionModal(selectedChangeDetail.change, 'APPROVE')">通过申请</button><button v-if="isAdmin && selectedChangeDetail.change.status === 'PENDING_APPROVAL'" class="danger-button" @click="openChangeDecisionModal(selectedChangeDetail.change, 'REJECT')">驳回申请</button><button v-if="canBulkUpdate && selectedChangeDetail.change.status === 'APPROVED'" class="primary-button" @click="transitionChange(selectedChangeDetail.change, 'IN_PROGRESS')">开始实施</button><button v-if="canBulkUpdate && selectedChangeDetail.change.status === 'IN_PROGRESS'" class="primary-button" @click="transitionChange(selectedChangeDetail.change, 'COMPLETED')">标记完成</button><button v-if="canBulkUpdate && selectedChangeDetail.change.status === 'APPROVED'" class="secondary-button" @click="transitionChange(selectedChangeDetail.change, 'CANCELLED')">取消变更</button><button v-if="canBulkUpdate && selectedChangeDetail.change.status === 'IN_PROGRESS'" class="secondary-button" @click="transitionChange(selectedChangeDetail.change, 'FAILED')">标记失败</button></div><div class="change-detail-facts"><span><b>变更类型</b>{{ changeTypeLabel(selectedChangeDetail.change.changeType) }}</span><span><b>风险等级</b>{{ changeRiskLabel(selectedChangeDetail.change.riskLevel) }}</span><span><b>影响范围</b>{{ selectedChangeDetail.change.impactScope || '未填写' }}</span><span><b>关联工单</b>{{ selectedChangeDetail.change.linkedTicketId ? '#' + selectedChangeDetail.change.linkedTicketId : '未关联' }}</span><span><b>计划窗口</b>{{ selectedChangeDetail.change.scheduledStart ? formatDate(selectedChangeDetail.change.scheduledStart) : '未安排' }}<span v-if="selectedChangeDetail.change.scheduledEnd"> → {{ formatDate(selectedChangeDetail.change.scheduledEnd) }}</span></span><span><b>审批意见</b>{{ selectedChangeDetail.change.decisionComment || '暂无' }}</span></div><p class="change-description">{{ selectedChangeDetail.change.description }}</p><div class="change-plan-grid"><section><div class="detail-section-heading"><h3>实施方案</h3></div><p>{{ selectedChangeDetail.change.implementationPlan }}</p></section><section><div class="detail-section-heading"><h3>验证方案</h3></div><p>{{ selectedChangeDetail.change.validationPlan || '未填写验证方案' }}</p></section><section><div class="detail-section-heading"><h3>回滚方案</h3></div><p>{{ selectedChangeDetail.change.rollbackPlan }}</p></section></div><section class="change-context-section"><div class="detail-section-heading"><div><h3>实施 checklist</h3><small>完成所有任务后才能将变更标记为已完成</small></div><span>{{ selectedChangeDetail.tasks.length }} 项</span></div><div v-if="!selectedChangeDetail.tasks.length" class="cmdb-empty-note">暂未拆分实施任务。建议把备份、执行、验证和回滚检查拆成独立步骤。</div><div v-else class="change-task-list"><div v-for="task in selectedChangeDetail.tasks" :key="task.id" class="change-task-row"><button type="button" :class="['change-task-toggle', 'task-' + String(task.status).toLowerCase()]" :disabled="!canBulkUpdate || ['COMPLETED', 'FAILED', 'CANCELLED', 'REJECTED'].includes(selectedChangeDetail.change.status)" @click="toggleChangeTask(task)">{{ task.status === 'DONE' ? '✓' : task.status === 'IN_PROGRESS' ? '→' : '○' }}</button><div><strong>{{ task.title }}</strong><small>{{ changeTaskStatusLabel(task.status) }} · {{ task.assigneeId ? staffDisplayName(task.assigneeId) : '未指定负责人' }}</small></div><span v-if="task.completedAt">{{ formatDate(task.completedAt) }}</span></div></div><form v-if="canBulkUpdate && !['COMPLETED', 'FAILED', 'CANCELLED', 'REJECTED'].includes(selectedChangeDetail.change.status)" class="change-task-form" @submit.prevent="addChangeTask"><input v-model="changeTaskForm.title" required maxlength="160" placeholder="添加实施任务，例如：备份数据库" /><select v-model="changeTaskForm.assigneeId"><option value="">未指定负责人</option><option v-for="staff in staffMembers" :key="staff.id" :value="staff.id">{{ staff.displayName }}</option></select><button class="secondary-button" :disabled="addingChangeTask">{{ addingChangeTask ? '添加中…' : '添加任务' }}</button></form></section><section class="change-context-section"><div class="detail-section-heading"><div><h3>受影响配置项</h3><small>从 CMDB 选择本次变更涉及的资产</small></div><span>{{ selectedChangeDetail.assets.length }} 项</span></div><div v-if="canBulkUpdate && !['COMPLETED', 'FAILED', 'CANCELLED', 'REJECTED'].includes(selectedChangeDetail.change.status)" class="change-asset-linker"><select v-model="changeAssetSelection"><option value="">选择要关联的配置项</option><option v-for="asset in availableChangeAssets" :key="asset.id" :value="asset.id">{{ asset.assetNo }} · {{ asset.name }}</option></select><button class="secondary-button" :disabled="!changeAssetSelection || linkingChangeAsset" @click="linkChangeAsset">{{ linkingChangeAsset ? '关联中…' : '关联资产' }}</button></div><div v-if="!selectedChangeDetail.assets.length" class="cmdb-empty-note">还没有关联配置项。关联后可以把变更风险与资产影响面放在同一个上下文里。</div><div v-else class="change-asset-list"><div v-for="assetView in selectedChangeDetail.assets" :key="assetView.assetId" class="change-asset-row"><div><strong>{{ assetView.asset.assetNo }} · {{ assetView.asset.name }}</strong><small>{{ linkTypeLabel(assetView.linkType) }} · {{ assetView.asset.businessSystem || '未归属业务系统' }}</small><small v-if="assetView.note">{{ assetView.note }}</small></div><button v-if="canBulkUpdate && !['COMPLETED', 'FAILED', 'CANCELLED', 'REJECTED'].includes(selectedChangeDetail.change.status)" class="row-action danger" @click="unlinkChangeAsset(assetView)">解除</button></div></div></section></template></section><section v-else class="panel-card change-context-placeholder"><div class="empty-state">选择左侧变更查看审批意见、实施方案、checklist 和受影响资产。</div></section></div>
</section><section v-else-if="activeView === 'problems'" class="view-panel problem-view">
  <div class="section-toolbar"><div><p class="eyebrow">PROBLEM MANAGEMENT</p><h2>问题管理</h2></div><div class="toolbar-actions problem-toolbar-actions"><input v-model="problemKeyword" placeholder="搜索问题编号、标题或根因…" /><select v-model="problemStatusFilter" aria-label="问题状态"><option value="">全部状态</option><option v-for="(label, key) in problemStatusLabels" :key="key" :value="key">{{ label }}</option></select><select v-model="problemPriorityFilter" aria-label="问题优先级"><option value="">全部优先级</option><option v-for="(label, key) in problemPriorityLabels" :key="key" :value="key">{{ label }}</option></select><button v-if="problemKeyword || problemStatusFilter || problemPriorityFilter" class="secondary-button" @click="problemKeyword = ''; problemStatusFilter = ''; problemPriorityFilter = ''">重置</button><button class="primary-button" @click="openProblemModal">＋ 登记问题</button></div></div>
  <div class="problem-summary-strip"><article><span>问题总量</span><strong>{{ problems.length }}</strong><small>当前租户问题单</small></article><article :class="{ attention: pendingProblemCount }"><span>待分析</span><strong>{{ pendingProblemCount }}</strong><small>待定位根因或修复路径</small></article><article class="attention"><span>已知错误</span><strong>{{ knownErrorProblemCount }}</strong><small>已有临时规避方案</small></article><article><span>高优先级</span><strong>{{ problemHighPriorityCount }}</strong><small>高 / 严重且未关闭</small></article></div>
  <div class="problem-layout"><section class="panel-card problem-list-panel"><div class="panel-heading"><div><p class="eyebrow">PROBLEM QUEUE</p><h3>{{ canBulkUpdate ? '问题分析队列' : '我的问题单' }}</h3></div><span class="insight-caption">{{ filteredProblems.length }} 条</span></div><div v-if="!filteredProblems.length" class="empty-state">当前没有符合筛选条件的问题单。可以先登记一条重复故障或重大事件。</div><div v-else class="problem-card-list"><article v-for="problem in filteredProblems" :key="problem.id" :class="['problem-card', { selected: selectedProblemDetail?.problem?.id === problem.id }]" @click="openProblemDetail(problem)"><div class="problem-card-head"><div><code>{{ problem.problemNo }}</code><strong>{{ problem.title }}</strong></div><span :class="['problem-status', problemStatusClass(problem.status)]">{{ problemStatusLabel(problem.status) }}</span></div><p>{{ problem.description }}</p><div class="problem-card-meta"><span :class="['problem-priority', 'priority-' + String(problem.priority || '').toLowerCase()]">{{ problemPriorityLabel(problem.priority) }}优先级</span><span v-if="problem.knownError">已知错误</span><span>{{ formatDate(problem.createdAt) }}</span></div><footer><small>负责人：{{ problem.ownerId ? staffDisplayName(problem.ownerId) : '未指定' }}</small><div class="problem-card-actions"><button v-if="canBulkUpdate && problem.status === 'OPEN'" class="row-action" @click.stop="transitionProblem(problem, 'INVESTIGATING')">开始分析</button><button v-if="canBulkUpdate && problem.status === 'INVESTIGATING'" class="row-action" @click.stop="transitionProblem(problem, 'PENDING_CHANGE')">进入修复</button><button v-if="canBulkUpdate && problem.status === 'PENDING_CHANGE'" class="row-action" @click.stop="transitionProblem(problem, 'RESOLVED')">标记解决</button><button v-if="canBulkUpdate && problem.status === 'RESOLVED'" class="row-action" @click.stop="transitionProblem(problem, 'CLOSED')">关闭</button></div></footer></article></div></section><section v-if="selectedProblemDetail" class="panel-card problem-detail-panel"><div v-if="problemDetailLoading" class="empty-state">正在读取问题详情…</div><template v-else><div class="problem-detail-header"><div><p class="eyebrow">{{ selectedProblemDetail.problem.status }} PROBLEM</p><code>{{ selectedProblemDetail.problem.problemNo }}</code><h2>{{ selectedProblemDetail.problem.title }}</h2><small>提交人：{{ staffDisplayName(selectedProblemDetail.problem.requesterId) }} · 创建于 {{ formatDate(selectedProblemDetail.problem.createdAt) }}</small></div><div class="problem-detail-header-actions"><span :class="['problem-status', problemStatusClass(selectedProblemDetail.problem.status)]">{{ problemStatusLabel(selectedProblemDetail.problem.status) }}</span><button class="close-button" @click="closeProblemDetail">×</button></div></div><div class="problem-detail-actions"><button v-if="canBulkUpdate" class="secondary-button" @click="openProblemAnalysisModal()">编辑 RCA</button><button v-if="canBulkUpdate && selectedProblemDetail.problem.status === 'OPEN'" class="primary-button" @click="transitionProblem(selectedProblemDetail.problem, 'INVESTIGATING')">开始分析</button><button v-if="canBulkUpdate && selectedProblemDetail.problem.status === 'INVESTIGATING'" class="primary-button" @click="transitionProblem(selectedProblemDetail.problem, 'KNOWN_ERROR')">标记已知错误</button><button v-if="canBulkUpdate && ['INVESTIGATING', 'KNOWN_ERROR'].includes(selectedProblemDetail.problem.status)" class="secondary-button" @click="transitionProblem(selectedProblemDetail.problem, 'PENDING_CHANGE')">等待变更修复</button><button v-if="canBulkUpdate && selectedProblemDetail.problem.status === 'PENDING_CHANGE'" class="primary-button" @click="transitionProblem(selectedProblemDetail.problem, 'RESOLVED')">标记已解决</button><button v-if="canBulkUpdate && selectedProblemDetail.problem.status === 'RESOLVED'" class="primary-button" @click="transitionProblem(selectedProblemDetail.problem, 'CLOSED')">关闭问题</button></div><div class="problem-detail-facts"><span><b>优先级</b>{{ problemPriorityLabel(selectedProblemDetail.problem.priority) }}</span><span><b>负责人</b>{{ selectedProblemDetail.problem.ownerId ? staffDisplayName(selectedProblemDetail.problem.ownerId) : '未指定' }}</span><span><b>已知错误</b>{{ selectedProblemDetail.problem.knownError ? '是' : '否' }}</span><span><b>关联工单</b>{{ selectedProblemDetail.tickets.length }} 条症状证据</span><span><b>修复变更</b>{{ selectedProblemDetail.changes.length }} 条执行记录</span><span><b>解决时间</b>{{ selectedProblemDetail.problem.resolvedAt ? formatDate(selectedProblemDetail.problem.resolvedAt) : '未解决' }}</span></div><p class="problem-description">{{ selectedProblemDetail.problem.description }}</p><div class="problem-analysis-grid"><section><div class="detail-section-heading"><h3>业务影响</h3></div><p>{{ selectedProblemDetail.problem.businessImpact || '未填写业务影响' }}</p></section><section><div class="detail-section-heading"><h3>根因分析</h3></div><p>{{ selectedProblemDetail.problem.rootCause || '尚未完成根因分析' }}</p></section><section><div class="detail-section-heading"><h3>规避方案</h3></div><p>{{ selectedProblemDetail.problem.workaround || '暂无临时规避方案' }}</p></section><section><div class="detail-section-heading"><h3>最终解决方案</h3></div><p>{{ selectedProblemDetail.problem.resolution || '尚未记录最终解决方案' }}</p></section></div><section class="problem-context-section"><div class="detail-section-heading"><div><h3>问题分析任务</h3><small>完成分析任务后才能将问题标记为已解决</small></div><span>{{ selectedProblemDetail.tasks.length }} 项</span></div><div v-if="!selectedProblemDetail.tasks.length" class="cmdb-empty-note">暂未拆分问题分析任务。建议加入复现、日志收集、根因确认和验证步骤。</div><div v-else class="problem-task-list"><div v-for="task in selectedProblemDetail.tasks" :key="task.id" class="problem-task-row"><button type="button" :class="['problem-task-toggle', 'task-' + String(task.status).toLowerCase()]" :disabled="!canBulkUpdate || ['CLOSED', 'CANCELLED'].includes(selectedProblemDetail.problem.status)" @click="toggleProblemTask(task)">{{ task.status === 'DONE' ? '✓' : task.status === 'IN_PROGRESS' ? '→' : '○' }}</button><div><strong>{{ task.title }}</strong><small>{{ problemTaskStatusLabel(task.status) }} · {{ task.assigneeId ? staffDisplayName(task.assigneeId) : '未指定负责人' }}</small></div><span v-if="task.completedAt">{{ formatDate(task.completedAt) }}</span></div></div><form v-if="canBulkUpdate && !['CLOSED', 'CANCELLED'].includes(selectedProblemDetail.problem.status)" class="problem-task-form" @submit.prevent="addProblemTask"><input v-model="problemTaskForm.title" required maxlength="160" placeholder="添加分析任务，例如：复现故障并收集日志" /><select v-model="problemTaskForm.assigneeId"><option value="">未指定负责人</option><option v-for="staff in staffMembers" :key="staff.id" :value="staff.id">{{ staff.displayName }}</option></select><button class="secondary-button" :disabled="addingProblemTask">{{ addingProblemTask ? '添加中…' : '添加任务' }}</button></form></section><section class="problem-context-section"><div class="detail-section-heading"><div><h3>症状工单</h3><small>用重复工单证明问题的影响范围和发生频率</small></div><span>{{ selectedProblemDetail.tickets.length }} 条</span></div><div v-if="canBulkUpdate && !['CLOSED', 'CANCELLED'].includes(selectedProblemDetail.problem.status)" class="problem-linker"><select v-model="problemTicketSelection"><option value="">选择要关联的工单</option><option v-for="ticket in availableProblemTickets" :key="ticket.id" :value="ticket.id">{{ ticket.ticketNo }} · {{ ticket.title }}</option></select><button class="secondary-button" :disabled="!problemTicketSelection || linkingProblemTicket" @click="linkProblemTicket">{{ linkingProblemTicket ? '关联中…' : '关联工单' }}</button></div><div v-if="!selectedProblemDetail.tickets.length" class="cmdb-empty-note">还没有关联症状工单。</div><div v-else class="problem-link-list"><div v-for="ticketView in selectedProblemDetail.tickets" :key="ticketView.ticketId" class="problem-link-row"><div><strong>{{ ticketView.ticket.ticketNo }} · {{ ticketView.ticket.title }}</strong><small>{{ problemTicketLinkLabel(ticketView.linkType) }} · {{ formatDate(ticketView.createdAt) }}</small></div><button v-if="canBulkUpdate && !['CLOSED', 'CANCELLED'].includes(selectedProblemDetail.problem.status)" class="row-action danger" @click="unlinkProblemTicket(ticketView)">解除</button></div></div></section><section class="problem-context-section"><div class="detail-section-heading"><div><h3>修复变更</h3><small>将根因修复落到 Change Management 执行链路</small></div><span>{{ selectedProblemDetail.changes.length }} 条</span></div><div v-if="canBulkUpdate && !['CLOSED', 'CANCELLED'].includes(selectedProblemDetail.problem.status)" class="problem-linker"><select v-model="problemChangeSelection"><option value="">选择要关联的变更</option><option v-for="change in availableProblemChanges" :key="change.id" :value="change.id">{{ change.changeNo }} · {{ change.title }}</option></select><button class="secondary-button" :disabled="!problemChangeSelection || linkingProblemChange" @click="linkProblemChange">{{ linkingProblemChange ? '关联中…' : '关联变更' }}</button></div><div v-if="!selectedProblemDetail.changes.length" class="cmdb-empty-note">还没有关联修复变更。</div><div v-else class="problem-link-list"><div v-for="changeView in selectedProblemDetail.changes" :key="changeView.changeId" class="problem-link-row"><div><strong>{{ changeView.change.changeNo }} · {{ changeView.change.title }}</strong><small>{{ problemChangeLinkLabel(changeView.linkType) }} · {{ changeStatusLabel(changeView.change.status) }}</small></div><button v-if="canBulkUpdate && !['CLOSED', 'CANCELLED'].includes(selectedProblemDetail.problem.status)" class="row-action danger" @click="unlinkProblemChange(changeView)">解除</button></div></div></section></template></section><section v-else class="panel-card problem-context-placeholder"><div class="empty-state">选择左侧问题单查看根因分析、任务、症状工单和修复变更。</div></section></div>
</section><section v-else-if="activeView === 'cmdb'" class="view-panel cmdb-view"><div class="section-toolbar"><div><p class="eyebrow">CONFIGURATION MANAGEMENT</p><h2>资产与配置项</h2></div><div class="toolbar-actions cmdb-toolbar-actions"><input v-model="cmdbKeyword" placeholder="搜索资产编号、名称或业务系统…" /><select v-model="cmdbTypeFilter" aria-label="资产类型"><option value="">全部类型</option><option v-for="(label, key) in assetTypeLabels" :key="key" :value="key">{{ label }}</option></select><select v-model="cmdbStatusFilter" aria-label="资产状态"><option value="">全部状态</option><option v-for="(label, key) in assetStatusLabels" :key="key" :value="key">{{ label }}</option></select><button v-if="canBulkUpdate" class="primary-button" @click="openCmdbAssetModal()">＋ 登记资产</button></div></div><div class="cmdb-summary-strip"><article><span>资产总量</span><strong>{{ cmdbAssets.length }}</strong><small>当前租户配置项</small></article><article><span>运行中</span><strong>{{ cmdbActiveCount }}</strong><small>可被工单关联</small></article><article class="attention"><span>重点资产</span><strong>{{ cmdbCriticalCount }}</strong><small>核心 / 高重要性</small></article><article><span>当前筛选</span><strong>{{ filteredCmdbAssets.length }}</strong><small>匹配资产数量</small></article></div><div class="cmdb-layout"><section class="panel-card cmdb-assets-panel"><div class="panel-heading"><div><p class="eyebrow">ASSET REGISTER</p><h3>配置项台账</h3></div><span class="insight-caption">最多加载 200 项</span></div><div v-if="!filteredCmdbAssets.length" class="empty-state">还没有符合条件的资产。{{ canBulkUpdate ? '登记第一项服务器、应用或业务服务吧。' : '' }}</div><div v-else class="cmdb-asset-grid"><article v-for="asset in filteredCmdbAssets" :key="asset.id" :class="['cmdb-asset-card', { selected: selectedCmdbAsset?.id === asset.id }]" @click="selectCmdbAsset(asset)"><div class="cmdb-asset-card-head"><div class="cmdb-asset-icon">{{ asset.name?.slice(0, 1) }}</div><div><code>{{ asset.assetNo }}</code><h3>{{ asset.name }}</h3></div><span :class="['cmdb-criticality', assetCriticalityClass(asset.criticality)]">{{ assetCriticalityLabel(asset.criticality) }}</span></div><p>{{ asset.description || '暂无资产说明' }}</p><div class="cmdb-asset-meta"><span>{{ assetTypeLabel(asset.assetType) }}</span><span>{{ assetEnvironmentLabel(asset.environment) }}</span><span>{{ assetStatusLabel(asset.status) }}</span></div><footer><small>{{ asset.businessSystem || '未归属业务系统' }} · {{ assetOwnerName(asset) }}</small><button v-if="canBulkUpdate" type="button" class="row-action" @click.stop="openCmdbAssetModal(asset)">编辑</button></footer></article></div></section><section v-if="selectedCmdbAsset" class="panel-card cmdb-context-panel"><div class="panel-heading"><div><p class="eyebrow">CONFIGURATION ITEM</p><h3>{{ selectedCmdbAsset.name }}</h3><small class="cmdb-context-no">{{ selectedCmdbAsset.assetNo }} · {{ assetTypeLabel(selectedCmdbAsset.assetType) }}</small></div><button v-if="canBulkUpdate" class="secondary-button" @click="openCmdbRelationModal">＋ 建立关系</button></div><div v-if="cmdbContextLoading" class="empty-state">正在读取影响分析…</div><template v-else><div class="cmdb-impact-grid"><div><span>关联工单</span><strong>{{ cmdbImpact?.linkedTicketCount || 0 }}</strong><small>历史关联</small></div><div><span>活跃工单</span><strong>{{ cmdbImpact?.activeTicketCount || 0 }}</strong><small>当前影响面</small></div><div><span>依赖项</span><strong>{{ cmdbImpact?.dependencyCount || 0 }}</strong><small>该资产依赖</small></div><div><span>被依赖</span><strong>{{ cmdbImpact?.dependentCount || 0 }}</strong><small>可能受影响</small></div></div><section class="cmdb-context-section"><div class="detail-section-heading"><h3>资产关系</h3><span>{{ cmdbRelations.length }} 条</span></div><div v-if="!cmdbRelations.length" class="cmdb-empty-note">暂无上下游关系。为核心应用建立依赖关系后，故障影响范围会更清晰。</div><div v-else class="cmdb-relation-list"><div v-for="relation in cmdbRelations" :key="relation.id" class="cmdb-relation-row"><div><strong>{{ relationDirection(relation) }} {{ assetRelationLabel(relation.relationType) }}</strong><small>{{ relation.sourceName }} → {{ relation.targetName }}</small><small v-if="relation.description">{{ relation.description }}</small></div><button v-if="canBulkUpdate" class="row-action danger" @click="deleteCmdbRelation(relation)">解除</button></div></div></section><section class="cmdb-context-section"><div class="detail-section-heading"><h3>数据概览</h3><span>{{ assetStatusLabel(selectedCmdbAsset.status) }}</span></div><div class="cmdb-detail-facts"><span><b>业务系统</b>{{ selectedCmdbAsset.businessSystem || '未填写' }}</span><span><b>位置</b>{{ selectedCmdbAsset.location || '未填写' }}</span><span><b>负责人</b>{{ assetOwnerName(selectedCmdbAsset) }}</span><span><b>更新时间</b>{{ formatDate(selectedCmdbAsset.updatedAt) }}</span></div><p class="cmdb-description">{{ selectedCmdbAsset.description || '暂无描述' }}</p></section></template></section><section v-else class="panel-card cmdb-context-placeholder"><div class="empty-state">选择左侧资产查看关系和影响分析。</div></section></div></section><section v-else-if="activeView === 'catalog'" class="view-panel catalog-view"><div class="section-toolbar"><div><p class="eyebrow">REQUEST FROM A CATALOG</p><h2>服务目录与审批</h2></div><div class="toolbar-actions"><select v-model="serviceRequestFilter" aria-label="申请状态筛选"><option value="">全部申请</option><option value="PENDING_APPROVAL">待审批</option><option value="APPROVED">已通过</option><option value="REJECTED">已驳回</option></select><button v-if="isAdmin" class="primary-button" @click="openCatalogItemModal()">＋ 配置服务项</button></div></div><div class="catalog-overview-strip"><article><span>可申请服务</span><strong>{{ serviceCatalogItems.filter(item => item.enabled).length }}</strong><small>标准化服务入口</small></article><article><span>{{ canBulkUpdate ? '租户申请总数' : '我的申请' }}</span><strong>{{ serviceRequests.length }}</strong><small>最近最多 200 条</small></article><article :class="{ attention: pendingServiceRequestCount }"><span>等待审批</span><strong>{{ pendingServiceRequestCount }}</strong><small>{{ isAdmin ? '需要管理员决策' : '审批流处理中' }}</small></article></div><div class="catalog-columns"><section class="panel-card catalog-items-panel"><div class="panel-heading"><div><p class="eyebrow">SERVICE OFFERINGS</p><h3>标准服务</h3></div><span class="insight-caption">{{ serviceCatalogItems.length }} 个目录项</span></div><div v-if="!serviceCatalogItems.length" class="empty-state">{{ isAdmin ? '还没有服务目录项，先配置一个标准服务。' : '管理员还没有发布可申请的服务。' }}</div><div v-else class="service-catalog-grid"><article v-for="item in serviceCatalogItems" :key="item.id" :class="['service-catalog-card', { disabled: !item.enabled }]"><div class="service-catalog-card-head"><span class="service-catalog-icon">{{ item.name.slice(0, 1) }}</span><div><small>{{ item.category }}</small><h3>{{ item.name }}</h3></div><span v-if="!item.enabled" class="catalog-disabled-badge">已停用</span></div><p>{{ item.description }}</p><div class="service-catalog-meta"><span>{{ priorityLabels[item.defaultPriority] || item.defaultPriority }}优先级</span><span>{{ item.approvalRequired ? '需管理员审批' : '自动受理' }}</span></div><div class="service-catalog-actions"><button v-if="item.enabled" class="primary-button" @click="openServiceRequestModal(item)">发起申请</button><button v-if="isAdmin" class="row-action" @click="openCatalogItemModal(item)">编辑</button><button v-if="isAdmin" class="row-action" @click="toggleCatalogItem(item)">{{ item.enabled ? '停用' : '启用' }}</button></div></article></div></section><section class="panel-card service-requests-panel"><div class="panel-heading"><div><p class="eyebrow">APPROVAL WORKFLOW</p><h3>{{ canBulkUpdate ? '申请与审批队列' : '我的服务申请' }}</h3></div><span class="insight-caption">{{ filteredServiceRequests.length }} 条</span></div><div v-if="!filteredServiceRequests.length" class="empty-state">当前没有符合条件的服务申请。</div><div v-else class="service-request-list"><article v-for="request in filteredServiceRequests" :key="request.id" class="service-request-row"><div class="service-request-row-head"><div><code>{{ request.requestNo }}</code><strong>{{ request.itemName }}</strong></div><span :class="['service-request-status', 'status-' + request.status.toLowerCase()]">{{ serviceRequestStatusLabel(request.status) }}</span></div><h4>{{ request.summary }}</h4><p>{{ request.detail }}</p><div class="service-request-meta"><span>{{ serviceRequesterName(request.requesterId) }}</span><span>{{ priorityLabels[request.priority] || request.priority }}</span><span>{{ formatDate(request.createdAt) }}</span></div><div v-if="request.decisionComment" class="service-decision-note"><b>审批意见</b>{{ request.decisionComment }}</div><div class="service-request-actions"><button v-if="isAdmin && request.status === 'PENDING_APPROVAL'" class="row-action approve" @click="openServiceDecisionModal(request, 'APPROVE')">通过</button><button v-if="isAdmin && request.status === 'PENDING_APPROVAL'" class="row-action danger" @click="openServiceDecisionModal(request, 'REJECT')">驳回</button><button v-if="request.ticketId" class="row-action" @click="openServiceRequestTicket(request)">查看工单 →</button><small v-else-if="request.status === 'PENDING_APPROVAL'">审批通过后自动创建工单并开始计算 SLA</small></div></article></div></section></div></section><section v-else-if="activeView === 'team'" class="view-panel admin-view"><div class="section-toolbar"><div><p class="eyebrow">ACCESS &amp; AUDIT</p><h2>团队与审计</h2></div><div class="toolbar-actions"><span class="admin-caption">{{ teamUsers.length }} 个租户成员 · {{ adminAuditLogs.length }} 条最近记录</span><button class="secondary-button" @click="loadTeam">刷新数据</button><button class="primary-button" @click="showCreateUserModal = true">＋ 创建用户</button></div></div><div v-if="teamLoading" class="panel-card empty-state">正在同步团队与审计数据…</div><div v-else class="admin-columns"><section class="panel-card admin-users-card"><div class="panel-heading"><div><p class="eyebrow">TEAM DIRECTORY</p><h3>成员目录</h3></div><span class="insight-caption">仅限当前租户</span></div><div v-if="!teamUsers.length" class="empty-state">还没有团队成员。</div><div v-else class="admin-user-list"><div v-for="teamUser in teamUsers" :key="teamUser.id" class="admin-user-row"><div class="admin-user-avatar">{{ teamUser.displayName?.slice(0, 1) }}</div><div class="admin-user-main"><strong>{{ teamUser.displayName }}</strong><small>{{ teamUser.username }} · 加入于 {{ formatDate(teamUser.createdAt) }}</small></div><span :class="['user-status', teamUser.status === 'ACTIVE' ? 'active' : 'disabled']">{{ userStatusLabel(teamUser.status) }}</span><select :value="primaryRole(teamUser)" :disabled="teamUser.id === user?.id" @change="changeUserRole(teamUser, $event.target.value)" aria-label="调整用户角色"><option value="ADMIN">管理员</option><option value="AGENT">客服 / 处理人</option><option value="REQUESTER">提单人</option></select><button class="row-action" :disabled="teamUser.id === user?.id" @click="toggleUserStatus(teamUser)">{{ teamUser.status === 'ACTIVE' ? '停用' : '启用' }}</button></div></div></section><section class="panel-card admin-audit-card"><div class="panel-heading"><div><p class="eyebrow">AUDIT TRAIL</p><h3>全局审计日志</h3></div><span class="insight-caption">最多 80 条</span></div><div class="audit-filter-grid"><select v-model="adminAuditFilter.action" aria-label="审计动作"><option value="">全部动作</option><option v-for="option in auditActionOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select><select v-model="adminAuditFilter.actorId" aria-label="审计操作者"><option value="">全部操作者</option><option v-for="teamUser in teamUsers" :key="teamUser.id" :value="teamUser.id">{{ teamUser.displayName }}</option></select><input v-model="adminAuditFilter.from" type="datetime-local" aria-label="开始时间" /><input v-model="adminAuditFilter.to" type="datetime-local" aria-label="结束时间" /><button class="secondary-button" @click="searchAdminAuditLogs">筛选</button><button class="secondary-button export-button" @click="openAuditExportModal">导出 CSV</button></div><div v-if="!adminAuditLogs.length" class="empty-state">没有符合条件的审计记录。</div><div v-else class="audit-list"><div v-for="log in adminAuditLogs" :key="log.id" class="audit-row"><div class="audit-dot"></div><div><div class="audit-row-meta"><strong>{{ auditLabels[log.action] || log.action }}</strong><span>{{ formatDate(log.createdAt) }}</span></div><p>{{ auditActorName(log.actorId) }} · {{ log.resourceType }} #{{ log.resourceId || '-' }}</p><small>{{ log.detail || '无补充说明' }}</small></div></div></div></section></div></section>
          <section v-if="activeView === 'team'" class="panel-card admin-policy-card"><div class="panel-heading"><div><p class="eyebrow">SLA POLICY</p><h3>租户 SLA 策略</h3></div><span class="insight-caption">1-720 小时</span></div><p class="policy-description">按优先级统一定义响应时限，新建工单会根据当前租户策略计算截止时间。</p><div class="sla-policy-grid"><label>紧急<input v-model.number="slaPolicyForm.urgentHours" type="number" min="1" max="720" /></label><label>高<input v-model.number="slaPolicyForm.highHours" type="number" min="1" max="720" /></label><label>中<input v-model.number="slaPolicyForm.mediumHours" type="number" min="1" max="720" /></label><label>低<input v-model.number="slaPolicyForm.lowHours" type="number" min="1" max="720" /></label></div><div class="policy-actions"><small>{{ slaPolicy?.updatedAt ? '最近更新 ' + formatDate(slaPolicy.updatedAt) : '当前使用系统默认策略' }}</small><button class="primary-button" :disabled="savingSlaPolicy" @click="saveSlaPolicy">{{ savingSlaPolicy ? '保存中…' : '保存策略' }}</button></div></section>        <section v-if="activeView === 'team'" class="panel-card admin-policy-card automation-rules-card"><div class="panel-heading"><div><p class="eyebrow">AUTOMATION ROUTING</p><h3>工单自动化规则</h3></div><div class="toolbar-actions"><span class="insight-caption">{{ automationRules.length }} 条规则 · 按顺序执行</span><button class="primary-button" @click="openAutomationModal()">＋ 新建规则</button></div></div><p class="policy-description">新工单创建后，系统会按顺序匹配第一条规则，自动分派处理人、调整优先级或推进到处理中。</p><div v-if="!automationRules.length" class="empty-state">还没有自动化规则，先创建一条“网络问题自动分派”规则吧。</div><div v-else class="automation-rule-list"><article v-for="rule in automationRules" :key="rule.id" class="automation-rule-item"><div class="automation-rule-head"><div><strong>{{ rule.name }}</strong><small>执行顺序 {{ rule.sortOrder }} · {{ rule.enabled ? '启用中' : '已停用' }}</small></div><span :class="['rule-status', rule.enabled ? 'enabled' : 'disabled']">{{ rule.enabled ? 'ACTIVE' : 'PAUSED' }}</span></div><div class="automation-rule-flow"><p><b>当</b>{{ automationCondition(rule) }}</p><p><b>执行</b>{{ automationAction(rule) }}</p></div><div class="automation-rule-actions"><button class="row-action" @click="openAutomationModal(rule)">编辑</button><button class="row-action" @click="toggleAutomationRule(rule)">{{ rule.enabled ? '停用' : '启用' }}</button><button class="row-action danger" @click="deleteAutomationRule(rule)">删除</button></div></article></div></section>                <section v-else-if="activeView === 'knowledge'" class="view-panel"><div class="section-toolbar"><div><p class="eyebrow">REUSE WHAT WORKS</p><h2>知识库</h2></div><div class="toolbar-actions knowledge-toolbar-actions"><input v-model="knowledgeKeyword" placeholder="搜索文章…" /><select v-model="knowledgeCategory" aria-label="知识分类"><option value="">全部分类</option><option v-for="category in knowledgeCategories" :key="category" :value="category">{{ category }}</option></select><select v-if="canBulkUpdate" v-model="knowledgeStatus" aria-label="知识状态"><option value="">全部状态</option><option value="DRAFT">草稿</option><option value="PUBLISHED">已发布</option><option value="ARCHIVED">已归档</option></select><button v-if="hasKnowledgeFilters" class="secondary-button" @click="resetKnowledgeFilters">重置</button><button v-if="canBulkUpdate" class="primary-button" @click="openArticleModal()">＋ 写知识</button></div></div><div v-if="canBulkUpdate && knowledgeAnalytics" class="knowledge-analytics"><div class="knowledge-analytics-summary"><article class="knowledge-analytics-card"><span>文章总量</span><strong>{{ knowledgeAnalytics.totalArticles }}</strong><small>{{ knowledgeAnalytics.publishedArticles }} 篇已发布 · {{ knowledgeAnalytics.draftArticles }} 篇草稿 · {{ knowledgeAnalytics.archivedArticles }} 篇归档</small></article><article class="knowledge-analytics-card"><span>总阅读量</span><strong>{{ knowledgeAnalytics.totalViews }}</strong><small>{{ knowledgeAnalytics.totalFeedback }} 条反馈</small></article><article class="knowledge-analytics-card"><span>平均帮助率</span><strong>{{ knowledgeAnalytics.helpfulRate }}%</strong><small>基于全部用户反馈</small></article><article class="knowledge-analytics-card"><span>待优化文章</span><strong>{{ knowledgeAnalytics.needsReview.length }}</strong><small>帮助率低于 70%</small></article></div><div class="knowledge-analytics-grid"><section class="panel-card knowledge-insight-card"><div class="panel-heading"><div><p class="eyebrow">MOST READ</p><h3>最常阅读</h3></div><span class="insight-caption">已发布文章</span></div><div v-if="!knowledgeAnalytics.topViewed.length" class="empty-state">暂无阅读数据。</div><div v-else class="knowledge-metric-list"><div v-for="metric in knowledgeAnalytics.topViewed" :key="metric.articleId" class="knowledge-metric-row"><div><strong>{{ metric.title }}</strong><small>{{ metric.category }} · 帮助率 {{ metric.helpfulRate }}%</small></div><b>{{ metric.viewCount }}<small>次阅读</small></b></div></div></section><section class="panel-card knowledge-insight-card knowledge-review-card"><div class="panel-heading"><div><p class="eyebrow">NEEDS ATTENTION</p><h3>需要优化</h3></div><span class="insight-caption">帮助率低于 70%</span></div><div v-if="!knowledgeAnalytics.needsReview.length" class="empty-state">暂无需要优化的文章。</div><div v-else class="knowledge-metric-list"><div v-for="metric in knowledgeAnalytics.needsReview" :key="metric.articleId" class="knowledge-metric-row"><div><strong>{{ metric.title }}</strong><small>{{ metric.category }} · {{ metric.totalFeedback }} 条反馈</small></div><b>{{ metric.helpfulRate }}%<small>帮助率</small></b></div></div></section></div></div><div v-if="filteredArticles.length" class="knowledge-result-hint">显示 {{ filteredArticles.length }} / {{ articles.length }} 篇文章<span v-if="knowledgeStatus"> · {{ articleStatusLabels[knowledgeStatus] }}</span><span v-if="knowledgeCategory"> · {{ knowledgeCategory }}</span></div><div class="article-grid"><article v-for="article in filteredArticles" :key="article.id" class="article-card"><div class="article-top"><span>{{ article.category }}</span><b :class="['knowledge-status', 'knowledge-status-' + article.status?.toLowerCase()]">{{ articleStatusLabels[article.status] || article.status }}</b></div><h3><button class="article-title-button" @click="openArticle(article)">{{ article.title }}</button></h3><p>{{ article.content }}</p><footer><div class="article-meta"><small>{{ formatDate(article.updatedAt || article.createdAt) }}</small><small v-if="article.status === 'PUBLISHED'">阅读 {{ article.viewCount || 0 }}</small></div><div class="article-card-actions"><button v-if="canBulkUpdate" class="row-action" @click="openArticleModal(article)">编辑</button><button v-if="canBulkUpdate && article.status === 'PUBLISHED'" class="row-action" @click="openArticle(article)">阅读</button><button v-if="canBulkUpdate && article.status === 'DRAFT'" class="row-action" @click="publishArticle(article)">发布</button><button v-if="canBulkUpdate && article.status !== 'ARCHIVED'" class="row-action danger" @click="archiveArticle(article)">归档</button><button v-if="canBulkUpdate && article.status === 'ARCHIVED'" class="row-action" @click="restoreArticle(article)">恢复</button><button v-if="!canBulkUpdate" class="row-action" @click="openArticle(article)">↗ 阅读全文</button></div></footer></article><div v-if="!filteredArticles.length" class="empty-state full-width">没有找到相关知识文章，请尝试清空筛选条件。</div></div></section>
      </div>
    </section>

    <div v-if="showAuditExportModal" class="modal-backdrop" @click.self="showAuditExportModal = false">
      <form class="modal-card audit-export-modal" @submit.prevent="exportAdminAuditLogs">
        <div class="modal-header">
          <div><p class="eyebrow">AUDIT EXPORT</p><h2>导出审计日志</h2><small class="detail-ticket-no">先选择要导出的事件类型，再生成 CSV 文件。</small></div>
          <button type="button" class="close-button" @click="showAuditExportModal = false">×</button>
        </div>
        <div class="audit-export-context">
          <strong>当前导出条件</strong>
          <small>操作者和时间范围沿用审计筛选；事件类型按下方勾选结果导出。</small>
          <span>操作者：{{ adminAuditFilter.actorId ? '指定操作者' : '全部操作者' }}</span>
          <span>时间：{{ adminAuditFilter.from || '不限开始' }} → {{ adminAuditFilter.to || '不限结束' }}</span>
        </div>
        <div class="audit-export-toolbar">
          <div><strong>选择事件类型</strong><small>已选 {{ auditExportActions.length }} / {{ auditActionOptions.length }}</small></div>
          <div class="export-field-actions"><button type="button" class="export-field-toggle" @click="selectAllAuditExportActions">全选</button><button type="button" class="export-field-toggle" @click="clearAuditExportActions">清空</button></div>
        </div>
        <div class="audit-export-grid">
          <label v-for="option in auditActionOptions" :key="option.value" class="audit-export-option">
            <input v-model="auditExportActions" type="checkbox" :value="option.value" />
            <span><strong>{{ option.label }}</strong><small>{{ option.value }}</small></span>
          </label>
        </div>
        <p v-if="!auditExportActions.length" class="export-empty-hint">至少选择一个事件类型后才能导出。</p>
        <div class="modal-actions"><button type="button" class="secondary-button" @click="showAuditExportModal = false">取消</button><button class="primary-button" :disabled="!auditExportActions.length || exportingAuditLogs">{{ exportingAuditLogs ? '导出中…' : '开始导出' }}</button></div>
      </form>
    </div>
    <div v-if="showSavedViewModal" class="modal-backdrop" @click.self="showSavedViewModal = false"><form class="modal-card saved-view-modal" @submit.prevent="saveSavedView"><div class="modal-header"><div><p class="eyebrow">SAVE WORK QUEUE</p><h2>{{ editingSavedView ? '编辑工单视图' : '保存当前工单视图' }}</h2><small class="detail-ticket-no">{{ editingSavedView ? '更新名称、可见范围或当前筛选条件。' : '把当前筛选条件保存下来，下次可以一键恢复。' }}</small></div><button type="button" class="close-button" @click="showSavedViewModal = false">×</button></div><label>视图名称<input v-model="savedViewForm.name" required maxlength="60" placeholder="例如：我的逾期工单" /></label><label v-if="canBulkUpdate">可见范围<select v-model="savedViewForm.visibility"><option value="PRIVATE">仅自己可见</option><option value="TEAM">团队共享</option></select></label><div class="saved-view-preview"><strong>将保存当前条件</strong><span v-if="ticketFilter">状态：{{ statusLabels[ticketFilter] }}</span><span v-if="ticketPriority">优先级：{{ priorityLabels[ticketPriority] }}</span><span>排序：{{ ticketSortLabels[ticketSort] }}</span><span v-if="ticketKeyword">关键词：{{ ticketKeyword }}</span><span v-if="ticketOverdue">仅逾期</span><span v-if="ticketMine">我的工单</span><span v-if="!ticketFilter && !ticketPriority && !ticketKeyword && !ticketOverdue && !ticketMine">全部工单</span></div><div class="modal-actions"><button type="button" class="secondary-button" @click="showSavedViewModal = false">取消</button><button class="primary-button" :disabled="savingSavedView">{{ savingSavedView ? '保存中…' : editingSavedView ? '更新视图' : '保存视图' }}</button></div></form></div><div v-if="showImportModal" class="modal-backdrop" @click.self="showImportModal = false"><form class="modal-card import-modal" @submit.prevent="importTickets"><div class="modal-header"><div><p class="eyebrow">BATCH IMPORT</p><h2>批量导入工单</h2><small class="detail-ticket-no">仅客服和管理员可用，单次最多 500 条、文件不超过 2MB。</small></div><button type="button" class="close-button" @click="showImportModal = false">×</button></div><div class="import-file-picker"><label>选择 CSV 文件<input type="file" accept=".csv,text/csv" @change="handleImportFile" /></label><div class="import-template-row"><span>建议先下载模板，再填写标题、描述、分类和优先级。</span><button type="button" class="text-button" @click="downloadImportTemplate">下载空白模板 →</button></div><div v-if="importFile" class="import-selected-file"><span>已选择</span><strong>{{ importFile.name }}</strong><small>{{ formatFileSize(importFile.size) }}</small></div></div><div v-if="importResult" class="import-result"><div class="import-result-summary"><div><span>总行数</span><strong>{{ importResult.totalRows }}</strong></div><div class="success"><span>成功</span><strong>{{ importResult.successRows }}</strong></div><div class="failure"><span>失败</span><strong>{{ importResult.failedRows }}</strong></div></div><div v-if="importResult.errors?.length" class="import-error-list"><strong>失败行（最多展示 50 条）</strong><div v-for="item in importResult.errors" :key="item.rowNumber" class="import-error-row"><b>第 {{ item.rowNumber }} 行</b><span>{{ item.message }}</span></div></div><p v-else class="import-success-hint">全部数据已成功处理。</p></div><div class="modal-actions"><button type="button" class="secondary-button" @click="showImportModal = false">关闭</button><button class="primary-button" :disabled="!importFile || importingTickets">{{ importingTickets ? '导入中…' : '开始导入' }}</button></div></form></div>
<div v-if="showExportModal" class="modal-backdrop" @click.self="showExportModal = false">
  <form class="modal-card export-modal ticket-export-modal" @submit.prevent="exportTickets">
    <div class="modal-header"><div><p class="eyebrow">EXPORT CONFIGURATION</p><h2>导出已选工单</h2><small class="detail-ticket-no">工单选择已在工作队列完成，导出只包含当前勾选项。</small></div><button type="button" class="close-button" @click="showExportModal = false">×</button></div>
    <div class="export-selection-summary">
      <div><span>当前导出范围</span><strong>{{ selectedTicketIds.length }} 张工单</strong><small>如需调整，请关闭弹窗后在工单队列前的白色复选框中勾选或取消。</small></div>
      <button type="button" class="text-button" @click="showExportModal = false">返回队列调整</button>
    </div>
    <div class="export-field-heading"><div><strong>选择导出字段</strong><small>只会导出勾选的列，字段顺序按下方列表固定。</small></div><div class="export-field-actions"><button type="button" class="export-field-toggle" @click="selectAllExportFields">全选</button><button type="button" class="export-field-toggle" @click="clearExportFields">清空</button><span class="export-selected-count">已选 {{ exportFields.length }} / {{ exportFieldOptions.length }}</span></div></div>
    <div class="export-field-grid"><label v-for="field in exportFieldOptions" :key="field.value" class="export-field-option"><input v-model="exportFields" type="checkbox" :value="field.value" /><span><strong>{{ field.label }}</strong><small>{{ field.description }}</small></span></label></div>
    <p v-if="!exportFields.length" class="export-empty-hint">至少选择一项字段后才能导出。</p>
    <div class="modal-actions"><button type="button" class="secondary-button" @click="showExportModal = false">取消</button><button class="primary-button" :disabled="!selectedTicketIds.length || !exportFields.length || exportingTickets">{{ exportingTickets ? '导出中…' : '导出已选工单' }}</button></div>
  </form>
</div><div v-if="showDetail" class="modal-backdrop" @click.self="closeTicket"><div class="modal-card detail-modal"><div class="modal-header"><div><p class="eyebrow">TICKET COLLABORATION</p><h2>{{ selectedTicket?.title }}</h2><small class="detail-ticket-no">{{ selectedTicket?.ticketNo }} · {{ statusLabels[selectedTicket?.status] || selectedTicket?.status }}</small></div><div class="detail-header-actions"><button v-if="canBulkUpdate" type="button" class="text-button detail-edit-button" @click="openEditTicketModal">编辑工单</button><button type="button" class="close-button" @click="closeTicket">×</button></div></div><div v-if="loadingDetail" class="empty-state">正在加载工单时间线…</div><template v-else><div class="detail-summary"><div><span>分类</span><strong>{{ selectedTicket?.category || '未分类' }}</strong></div><div><span>优先级</span><strong>{{ priorityLabels[selectedTicket?.priority] || selectedTicket?.priority }}</strong></div><div><span>处理人</span><strong>{{ assigneeName(selectedTicket) }}</strong></div><div><span>创建时间</span><strong>{{ formatDate(selectedTicket?.createdAt) }}</strong></div><div><span>SLA 截止</span><strong>{{ formatDate(selectedTicket?.dueAt) }} · {{ isOverdue(selectedTicket) ? "已逾期" : "按时" }}</strong></div></div><section class="ticket-assets-section"><div class="detail-section-heading"><h3>关联配置项</h3><span>{{ ticketAssetLinks.length }} 项</span></div><div v-if="!ticketAssetLinks.length" class="cmdb-empty-note">该工单还没有关联服务器、应用或业务服务。</div><div v-else class="ticket-asset-list"><div v-for="link in ticketAssetLinks" :key="link.assetId" class="ticket-asset-row"><div><strong>{{ link.asset?.name }}</strong><small>{{ link.asset?.assetNo }} · {{ assetTypeLabel(link.asset?.assetType) }} · {{ linkTypeLabel(link.linkType) }}</small><small v-if="link.note">{{ link.note }}</small></div><button v-if="canBulkUpdate" type="button" class="row-action danger" @click="unlinkTicketAsset(link)">解除</button></div></div><div v-if="canBulkUpdate && availableTicketAssets.length" class="ticket-asset-linker"><select v-model="ticketAssetSelection" aria-label="选择配置项"><option value="">选择要关联的配置项</option><option v-for="asset in availableTicketAssets" :key="asset.id" :value="asset.id">{{ asset.assetNo }} · {{ asset.name }}</option></select><button type="button" class="secondary-button" :disabled="!ticketAssetSelection || linkingTicketAsset" @click="linkSelectedTicketAsset">{{ linkingTicketAsset ? '关联中…' : '关联配置项' }}</button></div><small v-else-if="canBulkUpdate" class="cmdb-link-hint">当前没有可关联的运行中配置项，请先到资产 CMDB 登记资产。</small></section><section class="attachments-section"><div class="detail-section-heading"><h3>附件</h3><span>{{ ticketAttachments.length }} 个文件</span></div><div class="attachment-toolbar"><label class="upload-attachment-button">{{ uploadingAttachment ? '上传中…' : '＋ 上传附件' }}<input type="file" hidden :disabled="uploadingAttachment" @change="uploadAttachment" accept=".png,.jpg,.jpeg,.gif,.pdf,.txt,.md,.csv,.doc,.docx,.xls,.xlsx,.zip,.log" /></label><small>支持图片、文档、表格和压缩包，单个不超过 10MB</small></div><div v-if="!ticketAttachments.length" class="attachment-empty">暂无附件</div><div v-else class="attachment-list"><div v-for="attachment in ticketAttachments" :key="attachment.id" class="attachment-item"><div class="attachment-icon">↗</div><div class="attachment-info"><strong>{{ attachment.originalName }}</strong><small>{{ formatFileSize(attachment.sizeBytes) }} · {{ formatDate(attachment.createdAt) }}</small></div><button v-if="isImageAttachment(attachment)" type="button" class="attachment-action" @click="previewImage(attachment)">预览</button><button type="button" class="attachment-action" @click="downloadAttachment(attachment)">下载</button><button v-if="canDeleteAttachment(attachment)" type="button" class="attachment-action danger" @click="deleteAttachment(attachment)">删除</button></div></div></section><section v-if="recommendedArticles.length" class="recommendation-section"><div class="detail-section-heading"><h3>可能有帮助的知识</h3><span>{{ recommendedArticles.length }} 篇</span></div><div class="recommendation-list"><article v-for="article in recommendedArticles" :key="article.id" class="recommendation-card"><span>{{ article.category }}</span><strong>{{ article.title }}</strong><p>{{ article.content.slice(0, 92) }}{{ article.content.length > 92 ? '…' : '' }}</p></article></div></section>
<section class="timeline-section"><div class="detail-section-heading"><h3>协作时间线</h3><span>{{ ticketAuditLogs.length + ticketComments.length }} 条记录</span></div><div v-if="!ticketAuditLogs.length && !ticketComments.length" class="empty-state">还没有协作记录。</div><div v-for="log in ticketAuditLogs" :key="`log-${log.id}`" class="timeline-item"><i></i><div><div class="timeline-meta"><strong>{{ auditLabels[log.action] || log.action }}</strong><span>{{ formatDate(log.createdAt) }}</span></div><p>{{ log.detail }}</p></div></div><div v-for="comment in ticketComments" :key="`comment-${comment.id}`" class="timeline-item comment-item"><i></i><div><div class="timeline-meta"><strong>{{ comment.visibility === 'INTERNAL' ? "内部备注" : "公开回复" }} · 用户 #{{ comment.authorId }}</strong><span>{{ formatDate(comment.createdAt) }}</span></div><p>{{ comment.content }}</p></div></div></section><form class="comment-form" @submit.prevent="addComment"><textarea v-model="commentForm.content" required rows="3" placeholder="补充处理进展、结论或下一步计划…"></textarea><div class="comment-controls"><select v-if="canWriteInternal" v-model="commentForm.visibility"><option value="PUBLIC">公开回复</option><option value="INTERNAL">内部备注</option></select><button class="primary-button">添加评论</button></div></form></template></div></div>    <div v-if="showEditTicketModal" class="modal-backdrop" @click.self="closeEditTicketModal"><form class="modal-card edit-ticket-modal" @submit.prevent="saveTicketEdit"><div class="modal-header"><div><p class="eyebrow">EDIT TICKET</p><h2>编辑工单</h2><small class="detail-ticket-no">{{ editingTicket?.ticketNo }} · 仅修改基础信息</small></div><button type="button" class="close-button" @click="closeEditTicketModal">×</button></div><label>标题<input v-model="ticketEditForm.title" required maxlength="160" placeholder="请输入工单标题" /></label><label>描述<textarea v-model="ticketEditForm.description" required maxlength="10000" rows="7" placeholder="补充问题现象、影响范围和处理背景"></textarea></label><div class="form-grid"><label>分类<input v-model="ticketEditForm.category" maxlength="64" placeholder="例如：网络" /></label><label>优先级<select v-model="ticketEditForm.priority"><option value="LOW">低</option><option value="MEDIUM">中</option><option value="HIGH">高</option><option value="URGENT">紧急</option></select></label></div><p class="edit-ticket-hint">状态、处理人和 SLA 截止时间请通过详情页中的对应操作管理。</p><div class="modal-actions"><button type="button" class="secondary-button" @click="closeEditTicketModal">取消</button><button class="primary-button" :disabled="savingTicketEdit">{{ savingTicketEdit ? '保存中…' : '保存修改' }}</button></div></form></div><div v-if="showAssignModal" class="modal-backdrop" @click.self="closeAssignModal"><form class="modal-card assign-modal" @submit.prevent="assignTicket"><div class="modal-header"><div><p class="eyebrow">ROUTE REQUEST</p><h2>指派处理人</h2><small class="detail-ticket-no">{{ assignTarget?.ticketNo }} · {{ assignTarget?.title }}</small></div><button type="button" class="close-button" @click="closeAssignModal">×</button></div><label>选择客服<select v-model="assignForm.assigneeId" required><option value="" disabled>请选择处理人</option><option v-for="staff in staffMembers" :key="staff.id" :value="staff.id">{{ staff.displayName }} · {{ staff.roles.join(' / ') }}</option></select></label><div class="modal-actions"><button type="button" class="secondary-button" @click="closeAssignModal">取消</button><button class="primary-button">确认指派</button></div></form></div>
    <div v-if="showCmdbAssetModal" class="modal-backdrop" @click.self="closeCmdbAssetModal"><form class="modal-card cmdb-asset-modal" @submit.prevent="saveCmdbAsset"><div class="modal-header"><div><p class="eyebrow">CONFIGURATION ITEM</p><h2>{{ editingCmdbAsset ? '编辑资产' : '登记资产' }}</h2><small class="detail-ticket-no">资产编号在当前租户内必须唯一</small></div><button type="button" class="close-button" @click="closeCmdbAssetModal">×</button></div><div class="form-grid"><label>资产编号<input v-model="cmdbAssetForm.assetNo" required maxlength="40" placeholder="例如：APP-ORDER-001" /></label><label>资产名称<input v-model="cmdbAssetForm.name" required maxlength="120" placeholder="例如：订单服务" /></label></div><div class="form-grid"><label>资产类型<select v-model="cmdbAssetForm.assetType"><option v-for="(label, key) in assetTypeLabels" :key="key" :value="key">{{ label }}</option></select></label><label>运行环境<select v-model="cmdbAssetForm.environment"><option v-for="(label, key) in assetEnvironmentLabels" :key="key" :value="key">{{ label }}</option></select></label></div><div class="form-grid"><label>运行状态<select v-model="cmdbAssetForm.status"><option v-for="(label, key) in assetStatusLabels" :key="key" :value="key">{{ label }}</option></select></label><label>业务重要性<select v-model="cmdbAssetForm.criticality"><option v-for="(label, key) in assetCriticalityLabels" :key="key" :value="key">{{ label }}</option></select></label></div><div class="form-grid"><label>业务系统<input v-model="cmdbAssetForm.businessSystem" maxlength="120" placeholder="例如：交易平台" /></label><label>部署位置<input v-model="cmdbAssetForm.location" maxlength="120" placeholder="例如：生产集群 A" /></label></div><label>负责人<select v-model="cmdbAssetForm.ownerId"><option :value="null">未指定</option><option v-for="staff in staffMembers" :key="staff.id" :value="staff.id">{{ staff.displayName }} · {{ staff.roles.join(' / ') }}</option></select></label><label>资产说明<textarea v-model="cmdbAssetForm.description" maxlength="1000" rows="4" placeholder="记录用途、访问入口、维护窗口等信息"></textarea></label><label>扩展属性<textarea v-model="cmdbAssetForm.metadataJson" maxlength="4000" rows="3" placeholder="例如：owner=platform；runtime=Java 17"></textarea></label><div class="modal-actions"><button type="button" class="secondary-button" @click="closeCmdbAssetModal">取消</button><button class="primary-button" :disabled="savingCmdbAsset">{{ savingCmdbAsset ? '保存中…' : editingCmdbAsset ? '保存修改' : '登记资产' }}</button></div></form></div><div v-if="showCmdbRelationModal" class="modal-backdrop" @click.self="closeCmdbRelationModal"><form class="modal-card cmdb-relation-modal" @submit.prevent="saveCmdbRelation"><div class="modal-header"><div><p class="eyebrow">ASSET RELATION</p><h2>建立资产关系</h2><small class="detail-ticket-no">{{ selectedCmdbAsset?.assetNo }} · {{ selectedCmdbAsset?.name }}</small></div><button type="button" class="close-button" @click="closeCmdbRelationModal">×</button></div><label>目标配置项<select v-model="cmdbRelationForm.targetAssetId" required><option value="" disabled>请选择目标资产</option><option v-for="asset in cmdbAssets.filter(item => item.id !== selectedCmdbAsset?.id && item.status !== 'RETIRED')" :key="asset.id" :value="asset.id">{{ asset.assetNo }} · {{ asset.name }}</option></select></label><label>关系类型<select v-model="cmdbRelationForm.relationType"><option v-for="(label, key) in assetRelationLabels" :key="key" :value="key">{{ label }}</option></select></label><label>关系说明<textarea v-model="cmdbRelationForm.description" maxlength="500" rows="3" placeholder="例如：订单服务通过内部网络访问订单数据库"></textarea></label><p class="report-subscription-form-hint">关系方向为“当前资产 → 目标资产”，系统会在影响分析中同时展示依赖方和被依赖方。</p><div class="modal-actions"><button type="button" class="secondary-button" @click="closeCmdbRelationModal">取消</button><button class="primary-button" :disabled="savingCmdbRelation">{{ savingCmdbRelation ? '建立中…' : '建立关系' }}</button></div></form></div>    <div v-if="showChangeModal" class="modal-backdrop" @click.self="closeChangeModal"><form class="modal-card change-modal" @submit.prevent="saveChange"><div class="modal-header"><div><p class="eyebrow">NEW CHANGE REQUEST</p><h2>提交变更申请</h2><small class="detail-ticket-no">标准低风险变更会自动预批准，其余变更进入管理员审批队列。</small></div><button type="button" class="close-button" @click="closeChangeModal">×</button></div><label>变更标题<input v-model="changeForm.title" required maxlength="160" placeholder="例如：订单数据库版本升级" /></label><label>变更描述<textarea v-model="changeForm.description" required maxlength="10000" rows="4" placeholder="说明为什么变更、影响哪些业务以及当前背景"></textarea></label><div class="form-grid"><label>变更类型<select v-model="changeForm.changeType"><option value="STANDARD">标准</option><option value="NORMAL">普通</option><option value="EMERGENCY">紧急</option></select></label><label>风险等级<select v-model="changeForm.riskLevel"><option value="LOW">低</option><option value="MEDIUM">中</option><option value="HIGH">高</option><option value="CRITICAL">严重</option></select></label></div><label>影响范围<input v-model="changeForm.impactScope" maxlength="500" placeholder="例如：订单服务、数据库集群和客服查询" /></label><div class="form-grid"><label>计划开始<input v-model="changeForm.scheduledStart" type="datetime-local" /></label><label>计划结束<input v-model="changeForm.scheduledEnd" type="datetime-local" /></label></div><label>实施方案<textarea v-model="changeForm.implementationPlan" required maxlength="10000" rows="4" placeholder="按步骤写清执行顺序、负责人和监控点"></textarea></label><label>验证方案<textarea v-model="changeForm.validationPlan" maxlength="10000" rows="3" placeholder="例如：验证订单创建、查询、监控指标和错误率"></textarea></label><label>回滚方案<textarea v-model="changeForm.rollbackPlan" required maxlength="10000" rows="4" placeholder="写清触发条件、回滚步骤和恢复后的验证方式"></textarea></label><label>关联工单 ID（可选）<input v-model="changeForm.linkedTicketId" type="number" min="1" placeholder="例如：1001" /></label><label>实施任务（可选）<textarea v-model="changeForm.taskTitles" rows="3" placeholder="每行一个任务，例如：&#10;备份数据库&#10;执行灰度升级&#10;验证业务指标"></textarea></label><div class="modal-actions"><button type="button" class="secondary-button" @click="closeChangeModal">取消</button><button class="primary-button" :disabled="savingChange">{{ savingChange ? '提交中…' : '提交变更申请' }}</button></div></form></div>
    <div v-if="showChangeDecisionModal" class="modal-backdrop" @click.self="closeChangeDecisionModal"><form class="modal-card change-decision-modal" @submit.prevent="submitChangeDecision"><div class="modal-header"><div><p class="eyebrow">CHANGE APPROVAL</p><h2>{{ changeDecision === 'REJECT' ? '驳回变更申请' : '审批变更申请' }}</h2><small class="detail-ticket-no">{{ selectedChange?.changeNo }} · {{ selectedChange?.title }}</small></div><button type="button" class="close-button" @click="closeChangeDecisionModal">×</button></div><div class="change-decision-summary"><strong>{{ selectedChange?.title }}</strong><p>{{ selectedChange?.description }}</p><div><span>{{ changeTypeLabel(selectedChange?.changeType) }}变更</span><span>{{ changeRiskLabel(selectedChange?.riskLevel) }}风险</span><span>申请人 #{{ selectedChange?.requesterId }}</span></div></div><label>{{ changeDecision === 'REJECT' ? '驳回原因' : '审批意见（可选）' }}<textarea v-model="changeDecisionForm.comment" :required="changeDecision === 'REJECT'" maxlength="500" rows="4" :placeholder="changeDecision === 'REJECT' ? '请说明未通过原因' : '例如：已确认窗口、回滚方案和业务负责人'" /></label><div class="modal-actions"><button type="button" class="secondary-button" @click="closeChangeDecisionModal">取消</button><button :class="changeDecision === 'REJECT' ? 'danger-button' : 'primary-button'" :disabled="decidingChange">{{ decidingChange ? '处理中…' : changeDecision === 'REJECT' ? '确认驳回' : '通过申请' }}</button></div></form></div>    <div v-if="showProblemModal" class="modal-backdrop" @click.self="closeProblemModal"><form class="modal-card problem-modal" @submit.prevent="saveProblem"><div class="modal-header"><div><p class="eyebrow">NEW PROBLEM RECORD</p><h2>登记问题单</h2><small class="detail-ticket-no">适合记录重复故障、重大事件的共同根因或长期风险。</small></div><button type="button" class="close-button" @click="closeProblemModal">×</button></div><label>问题标题<input v-model="problemForm.title" required maxlength="160" placeholder="例如：订单数据库连接反复失败" /></label><label>问题描述<textarea v-model="problemForm.description" required maxlength="10000" rows="4" placeholder="说明故障模式、出现频率和已知背景"></textarea></label><div class="form-grid"><label>优先级<select v-model="problemForm.priority"><option value="LOW">低</option><option value="MEDIUM">中</option><option value="HIGH">高</option><option value="CRITICAL">严重</option></select></label><label>负责人<select v-model="problemForm.ownerId"><option value="">未指定负责人</option><option v-for="staff in staffMembers" :key="staff.id" :value="staff.id">{{ staff.displayName }}</option></select></label></div><label>业务影响<textarea v-model="problemForm.businessImpact" maxlength="1000" rows="3" placeholder="影响哪些业务、用户或 SLA 指标"></textarea></label><label>临时规避方案<textarea v-model="problemForm.workaround" maxlength="10000" rows="3" placeholder="已有的临时绕行方式；没有可以留空"></textarea></label><label class="checkbox-field"><input v-model="problemForm.knownError" type="checkbox" /> 已经确认是已知错误</label><label>分析任务（可选）<textarea v-model="problemForm.taskTitles" rows="3" placeholder="每行一个任务，例如：&#10;复现故障并收集日志&#10;确认根因&#10;制定修复方案"></textarea></label><div class="modal-actions"><button type="button" class="secondary-button" @click="closeProblemModal">取消</button><button class="primary-button" :disabled="savingProblem">{{ savingProblem ? '提交中…' : '登记问题单' }}</button></div></form></div>
    <div v-if="showProblemAnalysisModal" class="modal-backdrop" @click.self="closeProblemAnalysisModal"><form class="modal-card problem-analysis-modal" @submit.prevent="saveProblemAnalysis"><div class="modal-header"><div><p class="eyebrow">ROOT CAUSE ANALYSIS</p><h2>编辑问题分析</h2><small class="detail-ticket-no">{{ selectedProblem?.problemNo }} · {{ selectedProblem?.title }}</small></div><button type="button" class="close-button" @click="closeProblemAnalysisModal">×</button></div><div class="form-grid"><label>优先级<select v-model="problemAnalysisForm.priority"><option value="LOW">低</option><option value="MEDIUM">中</option><option value="HIGH">高</option><option value="CRITICAL">严重</option></select></label><label>负责人<select v-model="problemAnalysisForm.ownerId"><option value="">未指定负责人</option><option v-for="staff in staffMembers" :key="staff.id" :value="staff.id">{{ staff.displayName }}</option></select></label></div><label>根因分析<textarea v-model="problemAnalysisForm.rootCause" maxlength="10000" rows="5" placeholder="记录经过验证的技术根因、触发条件和证据"></textarea></label><label>规避方案<textarea v-model="problemAnalysisForm.workaround" maxlength="10000" rows="4" placeholder="在永久修复上线前如何降低影响"></textarea></label><label>最终解决方案<textarea v-model="problemAnalysisForm.resolution" maxlength="10000" rows="4" placeholder="记录最终修复动作和验证结论"></textarea></label><label class="checkbox-field"><input v-model="problemAnalysisForm.knownError" type="checkbox" /> 标记为已知错误</label><div class="modal-actions"><button type="button" class="secondary-button" @click="closeProblemAnalysisModal">取消</button><button class="primary-button" :disabled="savingProblemAnalysis">{{ savingProblemAnalysis ? '保存中…' : '保存分析资料' }}</button></div></form></div>    <div v-if="showWebhookModal" class="modal-backdrop" @click.self="closeWebhookModal"><form class="modal-card webhook-modal" @submit.prevent="saveWebhook"><div class="modal-header"><div><p class="eyebrow">WEBHOOK INTEGRATION</p><h2>{{ editingWebhook ? '编辑 Webhook' : '新建 Webhook' }}</h2><small class="detail-ticket-no">用 HMAC-SHA256 签名保护事件，并在失败时自动重试。</small></div><button type="button" class="close-button" @click="closeWebhookModal">×</button></div><label>集成名称<input v-model="webhookForm.name" required maxlength="80" placeholder="例如：生产告警中心" /></label><label>回调 URL<input v-model="webhookForm.url" required maxlength="500" type="url" placeholder="https://example.com/opsflow/webhook" /></label><label>订阅事件<input v-model="webhookForm.eventTypes" required maxlength="2000" placeholder="* 或 TICKET_CREATED,PROBLEM_CREATED" /></label><label>签名密钥<input v-model="webhookForm.secret" maxlength="200" type="password" placeholder="新建时留空自动生成；编辑时留空保持不变" /></label><p class="webhook-form-hint">支持 * 订阅全部审计事件，也可以用英文逗号填写多个事件类型。签名会通过 X-OpsFlow-Signature 请求头发送。</p><div class="modal-actions"><button type="button" class="secondary-button" @click="closeWebhookModal">取消</button><button class="primary-button" :disabled="savingWebhook">{{ savingWebhook ? '保存中…' : editingWebhook ? '保存修改' : '创建集成' }}</button></div></form></div><div v-if="showCatalogItemModal" class="modal-backdrop" @click.self="closeCatalogItemModal"><form class="modal-card catalog-item-modal" @submit.prevent="saveCatalogItem"><div class="modal-header"><div><p class="eyebrow">SERVICE OFFERING</p><h2>{{ editingCatalogItem ? '编辑服务目录项' : '配置服务目录项' }}</h2><small class="detail-ticket-no">将常见请求标准化，并选择是否需要管理员审批</small></div><button type="button" class="close-button" @click="closeCatalogItemModal">×</button></div><label>服务名称<input v-model="catalogItemForm.name" required maxlength="100" placeholder="例如：研发环境访问申请" /></label><label>服务说明<textarea v-model="catalogItemForm.description" required maxlength="2000" rows="4" placeholder="说明适用场景、交付内容和申请前提"></textarea></label><div class="form-grid"><label>服务分类<input v-model="catalogItemForm.category" required maxlength="64" placeholder="例如：访问权限" /></label><label>默认优先级<select v-model="catalogItemForm.defaultPriority"><option value="LOW">低</option><option value="MEDIUM">中</option><option value="HIGH">高</option><option value="URGENT">紧急</option></select></label></div><div class="form-grid"><label>审批方式<select v-model="catalogItemForm.approvalRequired"><option :value="true">需要管理员审批</option><option :value="false">自动受理</option></select></label><label>发布状态<select v-model="catalogItemForm.enabled"><option :value="true">启用</option><option :value="false">停用</option></select></label></div><label>展示顺序<input v-model.number="catalogItemForm.sortOrder" type="number" min="0" max="9999" /></label><div class="modal-actions"><button type="button" class="secondary-button" @click="closeCatalogItemModal">取消</button><button class="primary-button" :disabled="savingCatalogItem">{{ savingCatalogItem ? '保存中…' : editingCatalogItem ? '保存修改' : '发布服务' }}</button></div></form></div>
    <div v-if="showServiceRequestModal" class="modal-backdrop" @click.self="closeServiceRequestModal"><form class="modal-card service-request-modal" @submit.prevent="submitServiceRequest"><div class="modal-header"><div><p class="eyebrow">NEW SERVICE REQUEST</p><h2>申请 {{ selectedCatalogItem?.name }}</h2><small class="detail-ticket-no">{{ selectedCatalogItem?.category }} · {{ selectedCatalogItem?.approvalRequired ? '需要管理员审批' : '提交后自动受理' }}</small></div><button type="button" class="close-button" @click="closeServiceRequestModal">×</button></div><div class="service-request-context"><span>{{ priorityLabels[selectedCatalogItem?.defaultPriority] || selectedCatalogItem?.defaultPriority }}优先级</span><p>{{ selectedCatalogItem?.description }}</p></div><label>申请摘要<input v-model="serviceRequestForm.summary" required maxlength="160" placeholder="简要说明你希望获得什么服务" /></label><label>详细说明<textarea v-model="serviceRequestForm.detail" required maxlength="10000" rows="6" placeholder="补充使用人、业务原因、期望时间和必要信息"></textarea></label><p class="report-subscription-form-hint">{{ selectedCatalogItem?.approvalRequired ? '提交后进入管理员审批队列；通过后系统自动创建工单并开始计算 SLA。' : '该服务无需审批，提交后会立即创建正式工单。' }}</p><div class="modal-actions"><button type="button" class="secondary-button" @click="closeServiceRequestModal">取消</button><button class="primary-button" :disabled="submittingServiceRequest">{{ submittingServiceRequest ? '提交中…' : '提交服务申请' }}</button></div></form></div>
    <div v-if="showServiceDecisionModal" class="modal-backdrop" @click.self="closeServiceDecisionModal"><form class="modal-card service-decision-modal" @submit.prevent="submitServiceDecision"><div class="modal-header"><div><p class="eyebrow">APPROVAL DECISION</p><h2>{{ serviceDecision === 'REJECT' ? '驳回服务申请' : '通过服务申请' }}</h2><small class="detail-ticket-no">{{ selectedServiceRequest?.requestNo }} · {{ selectedServiceRequest?.itemName }}</small></div><button type="button" class="close-button" @click="closeServiceDecisionModal">×</button></div><div class="service-decision-summary"><strong>{{ selectedServiceRequest?.summary }}</strong><p>{{ selectedServiceRequest?.detail }}</p><small>申请人 #{{ selectedServiceRequest?.requesterId }} · {{ priorityLabels[selectedServiceRequest?.priority] || selectedServiceRequest?.priority }}</small></div><label>{{ serviceDecision === 'REJECT' ? '驳回原因' : '审批意见（可选）' }}<textarea v-model="serviceDecisionForm.comment" :required="serviceDecision === 'REJECT'" maxlength="500" rows="4" :placeholder="serviceDecision === 'REJECT' ? '请说明未通过原因' : '例如：预算和权限范围已确认'"></textarea></label><p v-if="serviceDecision === 'APPROVE'" class="report-subscription-form-hint">通过后会自动创建正式工单，套用目录优先级、SLA 策略和自动化分派规则。</p><div class="modal-actions"><button type="button" class="secondary-button" @click="closeServiceDecisionModal">取消</button><button :class="serviceDecision === 'REJECT' ? 'danger-button' : 'primary-button'" :disabled="decidingServiceRequest">{{ decidingServiceRequest ? '处理中…' : serviceDecision === 'REJECT' ? '确认驳回' : '通过并创建工单' }}</button></div></form></div>
    <div v-if="showReportSubscriptionModal" class="modal-backdrop" @click.self="closeReportSubscriptionModal"><form class="modal-card report-subscription-modal" @submit.prevent="saveReportSubscription"><div class="modal-header"><div><p class="eyebrow">REPORT SUBSCRIPTION</p><h2>{{ editingReportSubscription ? '编辑报表订阅' : '新建报表订阅' }}</h2><small class="detail-ticket-no">生成摘要后会进入你的通知中心</small></div><button type="button" class="close-button" @click="closeReportSubscriptionModal">×</button></div><label>订阅名称<input v-model="reportSubscriptionForm.name" required maxlength="80" placeholder="例如：客服周报" /></label><div class="form-grid"><label>发送频率<select v-model="reportSubscriptionForm.frequency"><option value="DAILY">每日</option><option value="WEEKLY">每周</option></select></label><label>统计周期<select v-model.number="reportSubscriptionForm.rangeDays"><option :value="7">近 7 天</option><option :value="14">近 14 天</option><option :value="30">近 30 天</option></select></label></div><label v-if="editingReportSubscription">订阅状态<select v-model="reportSubscriptionForm.enabled"><option :value="true">启用</option><option :value="false">停用</option></select></label><p class="report-subscription-form-hint">系统会按计划在每天或每周一上午生成摘要；也可以随时点击“立即生成”测试效果。</p><div class="modal-actions"><button type="button" class="secondary-button" @click="closeReportSubscriptionModal">取消</button><button class="primary-button" :disabled="savingReportSubscription">{{ savingReportSubscription ? '保存中…' : editingReportSubscription ? '保存修改' : '创建订阅' }}</button></div></form></div>
    <div v-if="showTicketModal" class="modal-backdrop" @click.self="showTicketModal = false"><form class="modal-card" @submit.prevent="createTicket"><div class="modal-header"><div><p class="eyebrow">NEW REQUEST</p><h2>创建工单</h2></div><button type="button" class="close-button" @click="showTicketModal = false">×</button></div><label>标题<input v-model="ticketForm.title" required placeholder="例如：VPN 无法连接" /></label><label>描述<textarea v-model="ticketForm.description" required rows="4" placeholder="描述问题现象、影响范围和已尝试的处理方式"></textarea></label><div class="form-grid"><label>分类<input v-model="ticketForm.category" /></label><label>优先级<select v-model="ticketForm.priority"><option value="LOW">低</option><option value="MEDIUM">中</option><option value="HIGH">高</option><option value="URGENT">紧急</option></select></label></div><div class="modal-actions"><button type="button" class="secondary-button" @click="showTicketModal = false">取消</button><button class="primary-button">提交工单</button></div></form></div>
    <div v-if="showArticleReader" class="modal-backdrop" @click.self="closeArticleReader"><article class="modal-card article-reader-modal"><div class="modal-header"><div><p class="eyebrow">KNOWLEDGE READER</p><h2>{{ displayedArticle?.title }}</h2><small class="detail-ticket-no">{{ displayedArticle?.category }} · {{ articleStatusLabels[displayedArticle?.status] || displayedArticle?.status }}</small></div><div class="detail-header-actions"><button v-if="canBulkUpdate" type="button" class="text-button detail-edit-button" @click="openArticleVersions">版本历史</button><button v-if="canBulkUpdate" type="button" class="text-button detail-edit-button" @click="editArticleFromReader">编辑</button><button type="button" class="close-button" @click="closeArticleReader">×</button></div></div><div v-if="articleReaderLoading" class="empty-state">正在加载文章…</div><template v-else-if="displayedArticle"><div class="article-reader-meta"><span v-if="displayedArticle.versionNo">版本 v{{ displayedArticle.versionNo }}</span><span>Slug：{{ displayedArticle.slug }}</span><span>更新于 {{ formatDate(displayedArticle.updatedAt || displayedArticle.createdAt) }}</span><span v-if="displayedArticle.status === 'PUBLISHED'">阅读 {{ displayedArticle.viewCount || 0 }}</span></div><section v-if="canBulkUpdate && showArticleVersions" class="article-version-panel"><div class="article-version-heading"><div><strong>版本历史</strong><small>最多展示最近 100 个版本</small></div><button type="button" class="row-action" @click="showCurrentArticleVersion">回到当前版本</button></div><div v-if="articleVersionsLoading" class="empty-state">正在读取版本历史…</div><div v-else-if="!articleVersions.length" class="empty-state">还没有版本记录。</div><div v-else class="article-version-list"><div v-for="version in articleVersions" :key="version.id" :class="['article-version-item', { active: selectedArticleVersion?.id === version.id }]"><button type="button" class="article-version-select" @click="selectArticleVersion(version)"><span><strong>v{{ version.versionNo }}</strong><small>{{ articleVersionChangeLabels[version.changeType] || version.changeType }} · 用户 #{{ version.changedBy }}</small></span><time>{{ formatDate(version.createdAt) }}</time></button><button type="button" class="article-version-restore" @click.stop="restoreArticleVersion(version)">恢复</button></div></div></section><p v-if="displayedArticle.status !== 'PUBLISHED'" class="article-reader-preview-note">当前为内部预览，草稿和已归档文章不会计入阅读量。</p><div class="article-reader-content">{{ displayedArticle.content }}</div><section v-if="displayedArticle.status === 'PUBLISHED' && !selectedArticleVersion" class="article-feedback"><div class="article-feedback-heading"><div><strong>这篇文章解决了你的问题吗？</strong><small v-if="feedbackLoading">正在同步反馈数据…</small><small v-else-if="articleFeedback">有帮助 {{ articleFeedback.helpfulCount }} · 没帮助 {{ articleFeedback.notHelpfulCount }} · 帮助率 {{ articleFeedback.helpfulRate }}%</small></div></div><div class="article-feedback-actions"><button type="button" :disabled="submittingArticleFeedback" :class="{ active: articleFeedback?.myHelpful === true }" @click="submitArticleFeedback(true)">👍 有帮助</button><button type="button" :disabled="submittingArticleFeedback" :class="{ active: articleFeedback?.myHelpful === false }" @click="submitArticleFeedback(false)">👎 需要改进</button><button v-if="articleFeedback && articleFeedback.myHelpful !== null" type="button" class="feedback-clear" :disabled="submittingArticleFeedback" @click="clearArticleFeedback">撤销反馈</button></div></section></template></article></div>    <div v-if="showArticleModal" class="modal-backdrop" @click.self="closeArticleModal"><form class="modal-card knowledge-editor-modal" @submit.prevent="saveArticle"><div class="modal-header"><div><p class="eyebrow">KNOWLEDGE EDITOR</p><h2>{{ editingArticle ? '编辑知识文章' : '写一篇知识' }}</h2><small class="detail-ticket-no">{{ editingArticle ? '更新内容后保留当前文章状态。' : '提交后将直接发布到团队知识库。' }}</small></div><button type="button" class="close-button" @click="closeArticleModal">×</button></div><label>标题<input v-model="articleForm.title" required maxlength="160" placeholder="例如：VPN 排障指南" /></label><div class="form-grid"><label>Slug<input v-model="articleForm.slug" required maxlength="180" placeholder="vpn-troubleshooting" /></label><label>分类<input v-model="articleForm.category" required maxlength="64" /></label></div><label>内容<textarea v-model="articleForm.content" required maxlength="50000" rows="9" placeholder="记录可复用的解决步骤、注意事项和验证结果"></textarea></label><p v-if="editingArticle" class="knowledge-editor-hint">编辑不会自动改变草稿、已发布或已归档状态；请在文章卡片上使用对应操作。</p><div class="modal-actions"><button type="button" class="secondary-button" @click="closeArticleModal">取消</button><button class="primary-button" :disabled="savingArticle">{{ savingArticle ? '保存中…' : editingArticle ? '保存修改' : '发布文章' }}</button></div></form></div><div v-if="showCreateUserModal" class="modal-backdrop" @click.self="showCreateUserModal = false"><form class="modal-card" @submit.prevent="createAdminUser"><div class="modal-header"><div><p class="eyebrow">ADD TEAM MEMBER</p><h2>创建团队账号</h2><small class="detail-ticket-no">账号创建后立即生效</small></div><button type="button" class="close-button" @click="showCreateUserModal = false">×</button></div><label>登录账号<input v-model="adminUserForm.username" required minlength="4" maxlength="64" placeholder="例如：zhangsan" /></label><label>显示名称<input v-model="adminUserForm.displayName" required maxlength="64" placeholder="例如：张三" /></label><label>初始密码<input v-model="adminUserForm.password" required minlength="8" maxlength="72" type="password" placeholder="至少 8 位" /></label><label>角色<select v-model="adminUserForm.role"><option value="AGENT">客服 / 处理人</option><option value="REQUESTER">提单人</option><option value="ADMIN">管理员</option></select></label><div class="modal-actions"><button type="button" class="secondary-button" @click="showCreateUserModal = false">取消</button><button class="primary-button" :disabled="creatingAdminUser">{{ creatingAdminUser ? '创建中…' : '创建账号' }}</button></div></form></div>
    <div v-if="showPreview" class="modal-backdrop image-preview-backdrop" @click.self="closePreview"><div class="image-preview-modal"><div class="modal-header"><div><p class="eyebrow">ATTACHMENT PREVIEW</p><h2>{{ previewAttachmentItem?.originalName }}</h2></div><button type="button" class="close-button" @click="closePreview">×</button></div><div v-if="previewLoading" class="image-preview-loading">正在读取图片…</div><img v-else-if="previewUrl" :src="previewUrl" :alt="previewAttachmentItem?.originalName" class="image-preview" /><p v-if="previewAttachmentItem" class="image-preview-meta">{{ formatFileSize(previewAttachmentItem.sizeBytes) }} · {{ formatDate(previewAttachmentItem.createdAt) }}</p></div></div>
    <div v-if="showPasswordModal" class="modal-backdrop" @click.self="showPasswordModal = false"><form class="modal-card" @submit.prevent="changePassword"><div class="modal-header"><div><p class="eyebrow">ACCOUNT SECURITY</p><h2>修改登录密码</h2><small class="detail-ticket-no">修改后当前登录会失效</small></div><button type="button" class="close-button" @click="showPasswordModal = false">×</button></div><label>当前密码<input v-model="passwordForm.currentPassword" required type="password" autocomplete="current-password" /></label><label>新密码<input v-model="passwordForm.newPassword" required minlength="8" maxlength="72" type="password" autocomplete="new-password" placeholder="至少 8 位" /></label><div class="modal-actions"><button type="button" class="secondary-button" @click="showPasswordModal = false">取消</button><button class="primary-button" :disabled="changingPassword">{{ changingPassword ? '更新中…' : '更新密码' }}</button></div></form></div>
    <div v-if="showAutomationModal" class="modal-backdrop" @click.self="closeAutomationModal">
      <form class="modal-card automation-modal" @submit.prevent="saveAutomationRule">
        <div class="modal-header">
          <div><p class="eyebrow">AUTOMATION RULE</p><h2>{{ editingAutomationRule ? '编辑自动化规则' : '新建自动化规则' }}</h2><small class="detail-ticket-no">按执行顺序匹配第一条规则</small></div>
          <button type="button" class="close-button" @click="closeAutomationModal">×</button>
        </div>
        <label>规则名称<input v-model="automationForm.name" required maxlength="80" placeholder="例如：网络问题自动分诊" /></label>
        <div class="form-grid">
          <label>执行顺序<input v-model.number="automationForm.sortOrder" type="number" min="0" max="9999" /></label>
          <label>启用状态<select v-model="automationForm.enabled"><option :value="true">启用</option><option :value="false">停用</option></select></label>
        </div>
        <p class="automation-form-label">匹配条件（至少配置一项）</p>
        <div class="form-grid">
          <label>工单分类<input v-model="automationForm.matchCategory" maxlength="64" placeholder="例如：网络" /></label>
          <label>工单优先级<select v-model="automationForm.matchPriority"><option value="">不限制</option><option value="URGENT">紧急</option><option value="HIGH">高</option><option value="MEDIUM">中</option><option value="LOW">低</option></select></label>
        </div>
        <label>标题 / 描述关键词<input v-model="automationForm.matchKeyword" maxlength="80" placeholder="例如：VPN" /></label>
        <p class="automation-form-label">团队分诊（可选）</p>
        <div class="form-grid">
          <label>目标支持组
            <select v-model="automationForm.targetGroupId" @change="automationForm.targetAssigneeId = ''">
              <option value="">不使用团队队列</option>
              <option v-for="item in automationSupportGroups.filter(current => current.group.enabled)" :key="item.group.id" :value="item.group.id">{{ item.group.name }} · {{ item.group.groupCode }}</option>
            </select>
          </label>
          <label>组内分派策略
            <select v-model="automationForm.assignmentStrategy" :disabled="!automationForm.targetGroupId">
              <option value="QUEUE_ONLY">进入组队列待领取</option>
              <option value="LEAST_LOADED">最少负载成员自动接单</option>
            </select>
          </label>
        </div>
        <p v-if="automationForm.targetGroupId" class="automation-routing-hint">最少负载会统计成员当前全部活跃工单；团队没有成员时自动回退为待领取队列。</p>
        <p class="automation-form-label">其他执行动作（至少配置一项）</p>
        <div class="form-grid">
          <label>固定分派给
            <select v-model="automationForm.targetAssigneeId" :disabled="Boolean(automationForm.targetGroupId)"><option value="">不改变</option><option v-for="staff in staffMembers" :key="staff.id" :value="staff.id">{{ staff.displayName }} · {{ staff.roles.join(' / ') }}</option></select>
          </label>
          <label>调整优先级<select v-model="automationForm.targetPriority"><option value="">不改变</option><option value="URGENT">紧急</option><option value="HIGH">高</option><option value="MEDIUM">中</option><option value="LOW">低</option></select></label>
        </div>
        <label>创建后状态<select v-model="automationForm.targetStatus"><option value="">不改变</option><option value="IN_PROGRESS">处理中</option><option value="REJECTED">已驳回</option></select></label>
        <div class="modal-actions"><button type="button" class="secondary-button" @click="closeAutomationModal">取消</button><button class="primary-button" :disabled="savingAutomationRule">{{ savingAutomationRule ? '保存中…' : '保存规则' }}</button></div>
      </form>
    </div>
    <transition name="toast"><div v-if="toast" class="toast-message">✓ {{ toast }}</div></transition>
  </main>
</template>
