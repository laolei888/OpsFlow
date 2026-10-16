const baseUrl = (process.env.OPSFLOW_BASE_URL || 'http://localhost:8081').replace(/\/$/, '')
const specUrl = `${baseUrl}/api-docs`

function assert(condition, message) {
  if (!condition) throw new Error(message)
}

const requiredOperations = [
  ['post', '/api/v1/auth/register'],
  ['post', '/api/v1/auth/login'],
  ['get', '/api/v1/tickets'],
  ['post', '/api/v1/tickets'],
  ['get', '/api/v1/tickets/{id}'],
  ['post', '/api/v1/tickets/{id}/transition'],
  ['get', '/api/v1/knowledge/articles'],
  ['get', '/api/v1/dashboard/overview'],
  ['get', '/api/v1/operations-workbench'],
  ['get', '/api/v1/support-groups'],
  ['get', '/api/v1/service-catalog/items'],
  ['get', '/api/v1/cmdb/assets'],
  ['get', '/api/v1/changes'],
  ['get', '/api/v1/problems'],
  ['get', '/api/v1/releases'],
  ['get', '/api/v1/runbooks'],
  ['get', '/api/v1/incidents'],
  ['get', '/api/v1/postmortems'],
  ['get', '/api/v1/oncall/schedules'],
  ['get', '/api/v1/alert-center/alerts'],
  ['get', '/api/v1/reliability'],
  ['get', '/api/v1/status-page'],
  ['get', '/api/v1/satisfaction/my-surveys'],
  ['get', '/api/v1/admin/webhooks'],
  ['get', '/api/v1/admin/async-messages'],
  ['get', '/api/v1/admin/async-messages/summary'],
  ['get', '/api/v1/admin/async-messages/{id}'],
  ['post', '/api/v1/admin/async-messages/retry']
]

const publicOperations = [
  ['post', '/api/v1/auth/register'],
  ['post', '/api/v1/auth/login'],
  ['get', '/api/v1/status-page'],
  ['get', '/api/v1/status-page/incidents/{publicNo}'],
  ['post', '/api/v1/alert-ingest/{sourceKey}']
]

const response = await fetch(specUrl, {
  headers: { Accept: 'application/json' },
  signal: AbortSignal.timeout(10_000)
})

assert(response.ok, `OpenAPI endpoint returned HTTP ${response.status}`)
const spec = await response.json()

assert(String(spec.openapi || '').startsWith('3.'), 'OpenAPI document must use version 3.x')
assert(spec.info?.title === 'OpsFlow API', 'Unexpected API title')
assert(spec.info?.version === 'v1', 'Unexpected API version')

const pathCount = Object.keys(spec.paths || {}).length
assert(pathCount >= 185, `OpenAPI path count regressed: expected at least 185, received ${pathCount}`)

const bearer = spec.components?.securitySchemes?.bearerAuth
assert(bearer?.type === 'http', 'bearerAuth must be an HTTP security scheme')
assert(bearer?.scheme === 'bearer', 'bearerAuth must use the bearer scheme')
assert(String(bearer?.bearerFormat || '').toUpperCase() === 'JWT', 'bearerAuth must declare JWT format')
assert(
  Array.isArray(spec.security) && spec.security.some((item) => Object.hasOwn(item, 'bearerAuth')),
  'Global bearerAuth security requirement is missing'
)

for (const [method, path] of requiredOperations) {
  const operation = spec.paths?.[path]?.[method]
  assert(operation, `Required operation missing: ${method.toUpperCase()} ${path}`)
  assert(operation.responses?.['200'], `HTTP 200 response missing: ${method.toUpperCase()} ${path}`)
}

for (const [method, path] of publicOperations) {
  const operation = spec.paths?.[path]?.[method]
  assert(operation, `Public operation missing: ${method.toUpperCase()} ${path}`)
  assert(Array.isArray(operation.security) && operation.security.length === 0,
    `Public operation unexpectedly requires JWT: ${method.toUpperCase()} ${path}`)
}

const ticketList = spec.paths['/api/v1/tickets'].get
const ticketParameters = new Set((ticketList.parameters || []).map((parameter) => parameter.name))
for (const parameter of ['page', 'size', 'status', 'priority', 'keyword', 'overdue', 'mine', 'sort']) {
  assert(ticketParameters.has(parameter), `Ticket list parameter missing: ${parameter}`)
}

const ticketCreate = spec.paths['/api/v1/tickets'].post
assert(ticketCreate.requestBody?.required === true, 'Ticket creation request body must be required')
assert(ticketCreate.requestBody?.content?.['application/json']?.schema,
  'Ticket creation JSON request schema is missing')

const operationIds = new Map()
const httpMethods = new Set(['get', 'post', 'put', 'patch', 'delete', 'head', 'options', 'trace'])
let operationCount = 0

for (const [path, pathItem] of Object.entries(spec.paths || {})) {
  for (const [method, operation] of Object.entries(pathItem || {})) {
    if (!httpMethods.has(method)) continue
    operationCount += 1
    assert(operation.operationId, `operationId missing: ${method.toUpperCase()} ${path}`)
    const previous = operationIds.get(operation.operationId)
    assert(!previous, `Duplicate operationId ${operation.operationId}: ${previous} and ${method.toUpperCase()} ${path}`)
    operationIds.set(operation.operationId, `${method.toUpperCase()} ${path}`)
  }
}

console.log(JSON.stringify({
  result: 'PASS',
  specUrl,
  api: `${spec.info.title} ${spec.info.version}`,
  pathCount,
  operationCount,
  guardedOperations: requiredOperations.length,
  publicOperations: publicOperations.length
}, null, 2))
