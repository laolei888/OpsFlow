export const VIEW_META = Object.freeze({
  overview: { path: '/overview', eyebrow: 'OVERVIEW', title: '总览' },
  workbench: { path: '/workbench', eyebrow: 'UNIFIED OPERATIONS', title: '运营待办', access: 'staff' },
  supportGroups: { path: '/support-groups', eyebrow: 'TEAM ROUTING', title: '支持组队列', access: 'staff' },
  tickets: { path: '/tickets', eyebrow: 'TICKETS', title: '工单中心' },
  satisfaction: { path: '/satisfaction', eyebrow: 'CUSTOMER SATISFACTION', title: '服务评价与质量' },
  catalog: { path: '/service-catalog', eyebrow: 'SERVICE CATALOG', title: '服务目录' },
  changes: { path: '/changes', eyebrow: 'CHANGE MANAGEMENT', title: '变更管理' },
  releases: { path: '/releases', eyebrow: 'RELEASE GOVERNANCE', title: '发布治理', access: 'staff' },
  runbooks: { path: '/runbooks', eyebrow: 'RUNBOOK ORCHESTRATION', title: '运行手册', access: 'staff' },
  problems: { path: '/problems', eyebrow: 'PROBLEM MANAGEMENT', title: '问题管理' },
  alerts: { path: '/alerts', eyebrow: 'OBSERVABILITY ALERTS', title: '监控告警', access: 'staff' },
  reliability: { path: '/reliability', eyebrow: 'RELIABILITY ENGINEERING', title: '可靠性 SLO', access: 'staff' },
  statusPage: { path: '/status-center', eyebrow: 'SERVICE STATUS', title: '服务状态', access: 'staff' },
  incidents: { path: '/incidents', eyebrow: 'INCIDENT COMMAND', title: '事件指挥台', access: 'staff' },
  postmortems: { path: '/postmortems', eyebrow: 'POST-INCIDENT REVIEW', title: '事件复盘', access: 'staff' },
  oncall: { path: '/on-call', eyebrow: 'ON-CALL & ESCALATION', title: '值班与升级', access: 'staff' },
  cmdb: { path: '/cmdb', eyebrow: 'CONFIGURATION MANAGEMENT', title: '资产与配置项' },
  messages: { path: '/admin/async-messages', eyebrow: 'ASYNC MESSAGE OPERATIONS', title: '异步消息中心', access: 'admin' },
  attachmentSecurity: { path: '/admin/attachment-security', eyebrow: 'QUARANTINE & MALWARE DEFENSE', title: '附件安全中心', access: 'admin' },
  capacity: { path: '/admin/capacity', eyebrow: 'CAPACITY & RELEASE BASELINE', title: '容量规划中心', access: 'admin' },
  disasterRecovery: { path: '/admin/disaster-recovery', eyebrow: 'BACKUP & DISASTER RECOVERY', title: '备份与灾难恢复', access: 'admin' },
  resilienceChaos: { path: '/admin/resilience-chaos', eyebrow: 'CONTROLLED CHAOS & SIGNED AGENTS', title: '韧性演练中心', access: 'admin' },
  integrations: { path: '/admin/integrations', eyebrow: 'INTEGRATION CENTER', title: '集成中心', access: 'admin' },
  team: { path: '/admin/team', eyebrow: 'TEAM ADMIN', title: '团队管理', access: 'admin' },
  knowledge: { path: '/knowledge', eyebrow: 'KNOWLEDGE BASE', title: '知识库' }
})

export const NAV_ITEMS = Object.freeze([
  { key: 'overview', icon: '⌂', label: '总览' },
  { key: 'tickets', icon: '◫', label: '工单中心', count: 'ticketTotal' },
  { key: 'satisfaction', icon: '★', label: '服务评价' },
  { key: 'workbench', icon: '◎', label: '运营待办', access: 'staff' },
  { key: 'supportGroups', icon: '♜', label: '支持组队列', access: 'staff' },
  { key: 'catalog', icon: '◇', label: '服务目录', count: 'pendingServiceRequestCount', hideZero: true },
  { key: 'changes', icon: '↗', label: '变更管理', count: 'pendingChangeCount', hideZero: true },
  { key: 'releases', icon: '⇪', label: '发布治理', access: 'staff' },
  { key: 'runbooks', icon: '☷', label: '运行手册', access: 'staff' },
  { key: 'alerts', icon: '⚡', label: '监控告警', access: 'staff' },
  { key: 'reliability', icon: '◌', label: '可靠性 SLO', access: 'staff' },
  { key: 'statusPage', icon: '◉', label: '服务状态', access: 'staff' },
  { key: 'oncall', icon: '◷', label: '值班升级', access: 'staff' },
  { key: 'incidents', icon: '⚑', label: '事件指挥', access: 'staff' },
  { key: 'postmortems', icon: '◈', label: '事件复盘', access: 'staff' },
  { key: 'problems', icon: '⌁', label: '问题管理', count: 'pendingProblemCount', hideZero: true },
  { key: 'cmdb', icon: '▦', label: '资产 CMDB', count: 'cmdbAssetCount' },
  { key: 'knowledge', icon: '▤', label: '知识库', count: 'articleTotal' },
  { key: 'attachmentSecurity', icon: '⌾', label: '附件安全', access: 'admin' },
  { key: 'messages', icon: '⇄', label: '异步消息', access: 'admin' },
  { key: 'capacity', icon: '⌁', label: '容量规划', access: 'admin' },
  { key: 'disasterRecovery', icon: '⛨', label: '备份恢复', access: 'admin' },
  { key: 'resilienceChaos', icon: '⌁', label: '韧性演练', access: 'admin' },
  { key: 'integrations', icon: '⌁', label: '集成中心', access: 'admin', count: 'webhookDeadCount', hideZero: true },
  { key: 'team', icon: '♙', label: '团队管理', access: 'admin', count: 'teamUserCount' }
])

export function viewPath(view) {
  return VIEW_META[view]?.path || VIEW_META.overview.path
}

