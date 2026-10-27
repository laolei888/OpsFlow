function timestamp(value) {
  const parsed = value ? new Date(value).getTime() : Number.NaN
  return Number.isFinite(parsed) ? parsed : null
}

export function knowledgeQueueCount(analytics, field, loadedItems) {
  const serverCount = Number(analytics?.[field])
  return Number.isFinite(serverCount) ? serverCount : loadedItems.length
}

export function reviewWaitLabel(value, now = Date.now()) {
  const startedAt = timestamp(value)
  if (startedAt === null) return '等待时长未知'
  const hours = Math.max(0, Math.floor((now - startedAt) / 3_600_000))
  if (hours < 1) return '刚刚提交'
  if (hours < 24) return `已等待 ${hours} 小时`
  return `已等待 ${Math.floor(hours / 24)} 天`
}

export function reviewDueLabel(value, now = Date.now()) {
  const dueAt = timestamp(value)
  if (dueAt === null) return '复核日期未知'
  const difference = now - dueAt
  const days = Math.max(1, Math.ceil(Math.abs(difference) / 86_400_000))
  return difference >= 0 ? `已逾期 ${days} 天` : `剩余 ${days} 天`
}
