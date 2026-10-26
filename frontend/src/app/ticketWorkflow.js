export const TICKET_STEPS = Object.freeze([
  Object.freeze({ status: 'OPEN', label: '待处理', caption: '等待团队响应' }),
  Object.freeze({ status: 'IN_PROGRESS', label: '处理中', caption: '正在定位与处理' }),
  Object.freeze({ status: 'WAITING_CONFIRMATION', label: '待确认', caption: '等待结果确认' }),
  Object.freeze({ status: 'RESOLVED', label: '已解决', caption: '方案已经交付' }),
  Object.freeze({ status: 'CLOSED', label: '已关闭', caption: '流程正式结束' })
])

const STAFF_ACTIONS = Object.freeze({
  OPEN: Object.freeze([
    Object.freeze({ target: 'IN_PROGRESS', label: '开始处理', tone: 'primary' }),
    Object.freeze({ target: 'REJECTED', label: '驳回工单', tone: 'danger', confirm: true })
  ]),
  IN_PROGRESS: Object.freeze([
    Object.freeze({ target: 'WAITING_CONFIRMATION', label: '请求用户确认', tone: 'primary' }),
    Object.freeze({ target: 'REJECTED', label: '驳回工单', tone: 'danger', confirm: true })
  ]),
  WAITING_CONFIRMATION: Object.freeze([
    Object.freeze({ target: 'IN_PROGRESS', label: '撤回确认请求', tone: 'secondary' }),
    Object.freeze({ target: 'RESOLVED', label: '确认解决', tone: 'primary' })
  ]),
  RESOLVED: Object.freeze([
    Object.freeze({ target: 'IN_PROGRESS', label: '重新打开', tone: 'secondary', confirm: true }),
    Object.freeze({ target: 'CLOSED', label: '关闭工单', tone: 'primary' })
  ])
})

const REQUESTER_ACTIONS = Object.freeze({
  WAITING_CONFIRMATION: Object.freeze([
    Object.freeze({ target: 'RESOLVED', label: '确认已解决', tone: 'primary' })
  ]),
  RESOLVED: Object.freeze([
    Object.freeze({ target: 'CLOSED', label: '确认关闭', tone: 'primary' })
  ])
})

export function ticketActions(status, isStaff) {
  return (isStaff ? STAFF_ACTIONS : REQUESTER_ACTIONS)[status] || []
}

export function ticketStepState(currentStatus, stepStatus) {
  if (currentStatus === 'REJECTED') return 'blocked'
  const currentIndex = TICKET_STEPS.findIndex(step => step.status === currentStatus)
  const stepIndex = TICKET_STEPS.findIndex(step => step.status === stepStatus)
  if (currentIndex < 0 || stepIndex < 0) return 'upcoming'
  if (stepIndex < currentIndex) return 'complete'
  if (stepIndex === currentIndex) return currentStatus === 'CLOSED' ? 'complete' : 'current'
  return 'upcoming'
}

export function slaState(ticket, now = Date.now()) {
  if (!ticket?.dueAt) return { key: 'unknown', label: '未设置', diffMs: null }
  const diffMs = new Date(ticket.dueAt).getTime() - now
  if (['RESOLVED', 'CLOSED', 'REJECTED'].includes(ticket.status)) {
    return { key: 'completed', label: '已停止计时', diffMs }
  }
  if (diffMs <= 0) return { key: 'overdue', label: '已逾期', diffMs }
  if (diffMs <= 24 * 60 * 60 * 1000) return { key: 'due-soon', label: '即将到期', diffMs }
  return { key: 'on-track', label: '进度正常', diffMs }
}

export function formatSlaRemaining(diffMs) {
  if (diffMs === null || Number.isNaN(diffMs)) return '未配置 SLA 截止时间'
  const overdue = diffMs < 0
  let minutes = Math.max(1, Math.ceil(Math.abs(diffMs) / 60000))
  const days = Math.floor(minutes / 1440)
  minutes %= 1440
  const hours = Math.floor(minutes / 60)
  minutes %= 60
  const parts = []
  if (days) parts.push(`${days} 天`)
  if (hours) parts.push(`${hours} 小时`)
  if (!days && minutes) parts.push(`${minutes} 分钟`)
  return `${overdue ? '已超时' : '剩余'} ${parts.join(' ')}`
}
