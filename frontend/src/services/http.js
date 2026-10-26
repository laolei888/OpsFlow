function diagnosticHint(response) {
  const diagnosticId = response.headers.get('X-Trace-Id') || response.headers.get('X-Request-Id')
  return diagnosticId ? `（追踪编号：${diagnosticId}）` : ''
}

export function createHttpClient({ getToken, onUnauthorized } = {}) {
  function authHeaders(headers = {}) {
    const token = getToken?.()
    return token ? { ...headers, Authorization: `Bearer ${token}` } : { ...headers }
  }

  async function handleUnauthorized(response) {
    if (![401, 403].includes(response.status)) return
    onUnauthorized?.()
    throw new Error(`登录已失效，请重新登录${diagnosticHint(response)}`)
  }

  async function request(path, options = {}) {
    const headers = authHeaders(options.headers)
    if (!(options.body instanceof FormData)) headers['Content-Type'] = 'application/json'
    const response = await fetch(path, { ...options, headers })
    await handleUnauthorized(response)
    const payload = await response.json().catch(() => ({}))
    if (!response.ok || payload.success === false) {
      throw new Error(`${payload.message || '请求失败'}${diagnosticHint(response)}`)
    }
    return payload.data
  }

  async function download(path, filename) {
    const response = await fetch(path, { headers: authHeaders() })
    await handleUnauthorized(response)
    if (!response.ok) throw new Error(`导出失败，请稍后重试${diagnosticHint(response)}`)
    const blob = await response.blob()
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = filename
    link.click()
    window.setTimeout(() => URL.revokeObjectURL(url), 1000)
  }

  return { request, download }
}

