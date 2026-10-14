const baseUrl = (process.env.OPSFLOW_BASE_URL || 'http://localhost:8081').replace(/\/$/, '')

function assert(condition, message) {
  if (!condition) throw new Error(message)
}

async function request(path, options = {}) {
  return fetch(`${baseUrl}${path}`, {
    ...options,
    signal: AbortSignal.timeout(10_000)
  })
}

const unauthorized = await request('/api/v1/tickets')
assert(unauthorized.status === 401, `Protected API returned HTTP ${unauthorized.status}, expected 401`)
assert((unauthorized.headers.get('content-type') || '').includes('application/json'),
  'Unauthorized response must be JSON')
const unauthorizedBody = await unauthorized.json()
assert(unauthorizedBody.success === false, 'Unauthorized response must use the failure envelope')

const home = await request('/')
assert(home.ok, `Management console returned HTTP ${home.status}`)
const csp = home.headers.get('content-security-policy') || ''
assert(csp.includes("default-src 'self'"), 'Content-Security-Policy is missing or too permissive')
assert(home.headers.get('referrer-policy') === 'no-referrer', 'Referrer-Policy is missing')
assert((home.headers.get('permissions-policy') || '').includes('camera=()'), 'Permissions-Policy is missing')
assert(home.headers.get('x-content-type-options') === 'nosniff', 'X-Content-Type-Options is missing')

const corsProbe = await request('/api/v1/status-page', {
  method: 'OPTIONS',
  headers: {
    Origin: 'https://attacker.invalid',
    'Access-Control-Request-Method': 'GET'
  }
})
assert(!corsProbe.headers.has('access-control-allow-origin'),
  'Unconfigured cross-origin request was unexpectedly authorized')

const runId = `${Date.now()}${Math.floor(Math.random() * 10_000)}`
async function invalidLogin(sequence) {
  return request('/api/v1/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      username: `security_probe_${runId}_${sequence}`,
      password: 'Invalid-Probe-Pass!2026'
    })
  })
}

const firstLogin = await invalidLogin(0)
const loginLimit = Number(firstLogin.headers.get('x-ratelimit-limit'))
assert(Number.isInteger(loginLimit) && loginLimit > 0 && loginLimit <= 100,
  `Invalid login rate-limit header: ${firstLogin.headers.get('x-ratelimit-limit')}`)
assert(firstLogin.status !== 429, 'The first login probe was unexpectedly rate-limited')

for (let sequence = 1; sequence < loginLimit; sequence += 1) {
  const response = await invalidLogin(sequence)
  assert(response.status !== 429, `Login was rate-limited before configured request ${loginLimit + 1}`)
}

const blockedLogin = await invalidLogin(loginLimit)
assert(blockedLogin.status === 429, `Login rate limit returned HTTP ${blockedLogin.status}, expected 429`)
assert(Number(blockedLogin.headers.get('retry-after')) > 0, 'Rate-limited response is missing Retry-After')

console.log(JSON.stringify({
  result: 'PASS',
  baseUrl,
  protectedApiStatus: unauthorized.status,
  securityHeaders: ['Content-Security-Policy', 'Referrer-Policy', 'Permissions-Policy', 'X-Content-Type-Options'],
  hostileCorsOriginAllowed: false,
  loginLimit,
  rateLimitedStatus: blockedLogin.status
}, null, 2))
