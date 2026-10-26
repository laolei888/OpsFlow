import assert from 'node:assert/strict'
import test from 'node:test'
import { NAV_ITEMS, VIEW_META, viewPath } from '../src/app/navigation.js'
import { createHttpClient } from '../src/services/http.js'

test('every navigation item has a registered route and unique key', () => {
  const keys = NAV_ITEMS.map(item => item.key)
  assert.equal(new Set(keys).size, keys.length)
  for (const item of NAV_ITEMS) assert.ok(VIEW_META[item.key], `missing route metadata for ${item.key}`)
})

test('route paths are unique and unknown views fall back to overview', () => {
  const paths = Object.values(VIEW_META).map(meta => meta.path)
  assert.equal(new Set(paths).size, paths.length)
  assert.equal(viewPath('missing-view'), '/overview')
})

test('admin routes declare admin access', () => {
  for (const meta of Object.values(VIEW_META)) {
    if (meta.path.startsWith('/admin/')) assert.equal(meta.access, 'admin')
  }
})

test('HTTP client adds authentication and returns envelope data', async () => {
  const originalFetch = globalThis.fetch
  let captured
  globalThis.fetch = async (path, options) => {
    captured = { path, options }
    return new Response(JSON.stringify({ success: true, data: { id: 7 } }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' }
    })
  }

  try {
    const client = createHttpClient({ getToken: () => 'test-token' })
    const result = await client.request('/api/example', { method: 'POST', body: JSON.stringify({ ok: true }) })
    assert.deepEqual(result, { id: 7 })
    assert.equal(captured.path, '/api/example')
    assert.equal(captured.options.headers.Authorization, 'Bearer test-token')
    assert.equal(captured.options.headers['Content-Type'], 'application/json')
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('HTTP errors preserve trace identifiers for diagnosis', async () => {
  const originalFetch = globalThis.fetch
  globalThis.fetch = async () => new Response(JSON.stringify({ success: false, message: '验证失败' }), {
    status: 422,
    headers: { 'Content-Type': 'application/json', 'X-Trace-Id': 'trace-123' }
  })

  try {
    const client = createHttpClient()
    await assert.rejects(() => client.request('/api/example'), /验证失败.*trace-123/)
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('unauthorized responses clear the active session', async () => {
  const originalFetch = globalThis.fetch
  let cleared = false
  globalThis.fetch = async () => new Response('', { status: 401, headers: { 'X-Request-Id': 'request-401' } })

  try {
    const client = createHttpClient({ onUnauthorized: () => { cleared = true } })
    await assert.rejects(() => client.request('/api/secure'), /登录已失效.*request-401/)
    assert.equal(cleared, true)
  } finally {
    globalThis.fetch = originalFetch
  }
})
