import http from 'k6/http'
import { check, fail, sleep } from 'k6'

const baseUrl = (__ENV.OPSFLOW_BASE_URL || 'http://localhost:8081').replace(/\/$/, '')

export const options = {
  stages: [
    { duration: '5s', target: 5 },
    { duration: '15s', target: 10 },
    { duration: '5s', target: 0 }
  ],
  thresholds: {
    checks: ['rate>0.99'],
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<800'],
    'http_req_duration{endpoint:public-status}': ['p(95)<500'],
    'http_req_duration{endpoint:ticket-list}': ['p(95)<600'],
    'http_req_duration{endpoint:dashboard}': ['p(95)<700']
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max']
}

const jsonHeaders = { 'Content-Type': 'application/json' }

function parseJson(response, label) {
  try {
    return response.json()
  } catch (error) {
    fail(`${label} returned invalid JSON: ${error.message}`)
  }
}

export function setup() {
  const suffix = `${Date.now()}${Math.floor(Math.random() * 10000)}`
  const username = `k6_${suffix}`
  const password = 'OpsFlow-k6-2026!'

  const registration = http.post(`${baseUrl}/api/v1/auth/register`, JSON.stringify({
    username,
    password,
    displayName: 'k6 Performance User'
  }), {
    headers: jsonHeaders,
    tags: { endpoint: 'setup-register' }
  })

  const registrationBody = parseJson(registration, 'Registration')
  const registered = check(registration, {
    'setup registration returns 200': (response) => response.status === 200,
    'setup registration returns JWT': () => Boolean(registrationBody?.data?.token)
  })
  if (!registered) fail(`Unable to register k6 user: HTTP ${registration.status}`)

  const token = registrationBody.data.token
  const ticket = http.post(`${baseUrl}/api/v1/tickets`, JSON.stringify({
    title: 'k6 baseline ticket',
    description: 'Seed ticket created once during the k6 setup lifecycle.',
    category: 'Performance',
    priority: 'MEDIUM'
  }), {
    headers: {
      ...jsonHeaders,
      Authorization: `Bearer ${token}`,
      'Idempotency-Key': `k6-seed-${suffix}`
    },
    tags: { endpoint: 'setup-ticket' }
  })

  if (!check(ticket, { 'setup ticket creation succeeds': (response) => response.status === 200 })) {
    fail(`Unable to create k6 seed ticket: HTTP ${ticket.status}`)
  }

  return { token }
}

export default function (data) {
  const authHeaders = { Authorization: `Bearer ${data.token}` }

  const statusPage = http.get(`${baseUrl}/api/v1/status-page`, {
    tags: { endpoint: 'public-status' }
  })
  check(statusPage, {
    'public status page returns 200': (response) => response.status === 200,
    'public status page returns success envelope': (response) => parseJson(response, 'Status page')?.success === true
  })

  const tickets = http.get(`${baseUrl}/api/v1/tickets?page=1&size=20&sort=CREATED_DESC`, {
    headers: authHeaders,
    tags: { endpoint: 'ticket-list' }
  })
  check(tickets, {
    'ticket list returns 200': (response) => response.status === 200,
    'ticket list returns paged data': (response) => Array.isArray(parseJson(response, 'Ticket list')?.data?.records)
  })

  const dashboard = http.get(`${baseUrl}/api/v1/dashboard/overview`, {
    headers: authHeaders,
    tags: { endpoint: 'dashboard' }
  })
  check(dashboard, {
    'dashboard returns 200': (response) => response.status === 200,
    'dashboard returns success envelope': (response) => parseJson(response, 'Dashboard')?.success === true
  })

  const sla = http.get(`${baseUrl}/api/v1/tickets/sla/summary`, {
    headers: authHeaders,
    tags: { endpoint: 'sla-summary' }
  })
  check(sla, {
    'SLA summary returns 200': (response) => response.status === 200,
    'SLA summary returns success envelope': (response) => parseJson(response, 'SLA summary')?.success === true
  })

  sleep(0.5)
}
