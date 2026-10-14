const urls = {
  app: process.env.OPSFLOW_BASE_URL || 'http://localhost:8081',
  prometheus: process.env.OPSFLOW_PROMETHEUS_URL || 'http://localhost:9090',
  tempo: process.env.OPSFLOW_TEMPO_URL || 'http://localhost:3200',
  loki: process.env.OPSFLOW_LOKI_URL || 'http://localhost:3100',
  grafana: process.env.OPSFLOW_GRAFANA_URL || 'http://localhost:3000'
}

const grafanaAuthorization = `Basic ${Buffer.from(
  `${process.env.OPSFLOW_GRAFANA_USER || process.env.GRAFANA_ADMIN_USER || 'admin'}:` +
    `${process.env.OPSFLOW_GRAFANA_PASSWORD || process.env.GRAFANA_ADMIN_PASSWORD || 'admin'}`
).toString('base64')}`

function assert(condition, message) {
  if (!condition) throw new Error(message)
}

async function json(url, options) {
  const response = await fetch(url, options)
  const body = await response.text()
  assert(response.ok, `${url} returned HTTP ${response.status}: ${body.slice(0, 300)}`)
  return body ? JSON.parse(body) : {}
}

async function retry(label, operation, attempts = 30, delayMs = 2000) {
  let lastError
  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    try {
      return await operation()
    } catch (error) {
      lastError = error
      if (attempt < attempts) await new Promise(resolve => setTimeout(resolve, delayMs))
    }
  }
  throw new Error(`${label} did not become ready: ${lastError?.message || 'unknown error'}`)
}

const requestId = `observability-smoke-${Date.now()}`
const appResponse = await fetch(`${urls.app}/api/v1/status-page`, {
  headers: { 'X-Request-Id': requestId }
})
assert(appResponse.ok, `public status endpoint returned HTTP ${appResponse.status}`)
await appResponse.arrayBuffer()

const returnedRequestId = appResponse.headers.get('x-request-id')
const traceId = appResponse.headers.get('x-trace-id')
assert(returnedRequestId === requestId, 'X-Request-Id was not propagated')
assert(/^[0-9a-f]{32}$/.test(traceId || ''), `invalid X-Trace-Id: ${traceId}`)

const metricsResponse = await fetch(`${urls.app}/actuator/prometheus`)
const metrics = await metricsResponse.text()
assert(metricsResponse.ok, `Prometheus endpoint returned HTTP ${metricsResponse.status}`)
assert(
  metrics.includes('http_server_requests_seconds_count') ||
    metrics.includes('http_server_request_duration_seconds_count'),
  'HTTP server request metrics are missing'
)
assert(metrics.includes('application="opsflow"'), 'common application metric tag is missing')

await retry('Prometheus scrape', async () => {
  const result = await json(`${urls.prometheus}/api/v1/query?query=${encodeURIComponent('up{job="opsflow"}')}`)
  assert(result.status === 'success', 'Prometheus query failed')
  assert(result.data?.result?.some(item => Number(item.value?.[1]) === 1), 'OpsFlow target is not UP')
})

await retry('Tempo trace ingestion', async () => {
  const query = encodeURIComponent('{ resource.service.name = "opsflow" }')
  const result = await json(`${urls.tempo}/api/search?q=${query}&limit=20`)
  assert(Array.isArray(result.traces) && result.traces.length > 0, 'no OpsFlow traces found')
})

await retry('Loki log ingestion', async () => {
  const params = new URLSearchParams({
    query: '{service_name="opsflow"}',
    limit: '20',
    start: String((Date.now() - 10 * 60 * 1000) * 1_000_000)
  })
  const result = await json(`${urls.loki}/loki/api/v1/query_range?${params}`)
  assert(result.status === 'success', 'Loki query failed')
  assert(result.data?.result?.length > 0, 'no OpsFlow logs found')
})

const grafana = await json(`${urls.grafana}/api/health`)
assert(grafana.database === 'ok', 'Grafana database is not healthy')

const grafanaRequest = { headers: { Authorization: grafanaAuthorization } }
const datasources = await json(`${urls.grafana}/api/datasources`, grafanaRequest)
for (const uid of ['prometheus', 'tempo', 'loki']) {
  assert(datasources.some(item => item.uid === uid), `Grafana datasource ${uid} is not provisioned`)
}
const dashboards = await json(
  `${urls.grafana}/api/search?query=${encodeURIComponent('OpsFlow Service Overview')}`,
  grafanaRequest
)
assert(
  dashboards.some(item => item.uid === 'opsflow-overview'),
  'OpsFlow Grafana dashboard is not provisioned'
)

console.log(JSON.stringify({
  result: 'PASS',
  requestId,
  traceId,
  checks: [
    'trace headers',
    'Prometheus metrics',
    'Tempo traces',
    'Loki logs',
    'Grafana health',
    'Grafana provisioning'
  ]
}, null, 2))
