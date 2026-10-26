export const TICKET_CATEGORIES = Object.freeze([
  Object.freeze({ value: '网络', code: 'NET', label: '网络与连接', description: 'VPN、Wi-Fi、访问超时或网络中断', placeholder: '例如：连接公司 VPN 后无法访问内部系统' }),
  Object.freeze({ value: '账号', code: 'IAM', label: '账号与权限', description: '登录、密码、权限申请或访问受限', placeholder: '例如：登录研发平台时提示没有访问权限' }),
  Object.freeze({ value: '应用', code: 'APP', label: '业务应用', description: '系统报错、功能异常或数据问题', placeholder: '例如：订单提交后一直停留在处理中' }),
  Object.freeze({ value: '设备', code: 'DEV', label: '办公设备', description: '电脑、显示器、打印机或其他终端', placeholder: '例如：办公电脑启动后无法识别外接显示器' }),
  Object.freeze({ value: '服务申请', code: 'SRV', label: '服务申请', description: '软件安装、资源开通或标准服务', placeholder: '例如：需要安装开发工具并开通对应许可证' }),
  Object.freeze({ value: '其他', code: 'ETC', label: '其他问题', description: '暂时无法归入现有服务分类', placeholder: '请用一句话概括你需要获得的帮助' })
])

export function recommendPriority({ serviceUnavailable, affectedUsers, workaround }) {
  if (serviceUnavailable && affectedUsers === 'MANY' && workaround === 'NO') {
    return { value: 'URGENT', label: '紧急', reason: '核心服务不可用、影响多人且没有替代方案' }
  }
  if (serviceUnavailable || affectedUsers === 'MANY' || (affectedUsers === 'TEAM' && workaround === 'NO')) {
    return { value: 'HIGH', label: '高', reason: '业务明显受阻或影响范围较大' }
  }
  if (affectedUsers === 'TEAM' || workaround === 'NO' || workaround === 'UNKNOWN') {
    return { value: 'MEDIUM', label: '中', reason: '工作受到影响，需要团队按正常优先级处理' }
  }
  return { value: 'LOW', label: '低', reason: '影响范围有限且存在可用的替代方案' }
}

export function intakeStepErrors(step, form) {
  const errors = []
  if (step === 1) {
    if (!form.category) errors.push('请选择问题分类')
    if (!String(form.title || '').trim()) errors.push('请填写问题标题')
    else if (String(form.title).trim().length < 6) errors.push('标题至少填写 6 个字符')
  }
  if (step === 2) {
    if (!String(form.description || '').trim()) errors.push('请描述问题现象')
    if (!String(form.impactScope || '').trim()) errors.push('请说明影响范围')
    if (!String(form.expectedOutcome || '').trim()) errors.push('请说明期望结果')
  }
  if (step === 3) {
    if (form.serviceUnavailable === null || form.serviceUnavailable === undefined) errors.push('请选择服务是否完全不可用')
    if (!form.affectedUsers) errors.push('请选择受影响人数')
    if (!form.workaround) errors.push('请选择是否存在替代方案')
    if (!form.priority) errors.push('请选择最终优先级')
  }
  return errors
}
