// MCP sidecar — T9 surface.
//
// Eight tools wired against the existing personRegistration definition:
//   list_services         (T3)
//   describe_service      (T6)
//   start_process         (T6)
//   list_my_tasks         (T9)
//   get_form_schema       (T9)
//   complete_task         (T9)
//   list_my_processes     (T9)
//   query_user_history    (T9)
//
// The MCP service stays a stateless Bearer-proxy (decision A2). Username is
// decoded from the Bearer payload locally for query construction
// (assignee=<me>, startedBy=<me>); the engine validates the token signature.
// Variable shapes are driven by manifests under
// docs/business/services/<id>/build/, hand-written for T9 and regenerated
// by /service-builder in T14.

import { AsyncLocalStorage } from 'node:async_hooks'
import express, { type Request, type Response, type NextFunction } from 'express'
import { Server } from '@modelcontextprotocol/sdk/server/index.js'
import { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js'
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
} from '@modelcontextprotocol/sdk/types.js'
import { engineRequest, engineBaseUrl } from './engine/client.js'
import { toCamundaVariables } from './engine/variables.js'
import { decodeBearerUsername } from './auth/identity.js'
import {
  findServiceByFormKey,
  getManifest,
  listManifests,
  loadManifests,
  validateTaskVariables,
  validateVariables,
} from './services/manifest.js'

const PORT = Number(process.env.PORT ?? 8090)
const RESOURCE_URL = process.env.MCP_RESOURCE_URL ?? 'http://localhost:3000/mcp'
const KEYCLOAK_ISSUER =
  process.env.KEYCLOAK_ISSUER_URL ?? 'http://localhost:8180/realms/cib7-poc'

interface RequestContext {
  bearer: string
}
const requestStorage = new AsyncLocalStorage<RequestContext>()

function currentBearer(): string {
  return requestStorage.getStore()?.bearer ?? ''
}

function currentUsername(): string {
  return decodeBearerUsername(currentBearer())
}

// -------------------------------------------------------------------------
// Engine response shapes
// -------------------------------------------------------------------------

interface ProcessDefinition {
  id: string
  key: string
  name?: string
  version: number
  description?: string
}

interface StartProcessResponse {
  id: string
  definitionId: string
  businessKey: string | null
  caseInstanceId: string | null
  ended: boolean
  suspended: boolean
}

interface EngineTask {
  id: string
  name?: string
  assignee?: string | null
  created: string
  processInstanceId: string
  processDefinitionId: string
  formKey?: string
  taskDefinitionKey?: string
}

interface HistoricProcessInstance {
  id: string
  processDefinitionKey: string
  processDefinitionName?: string
  startTime: string
  endTime?: string | null
  state: string
  startUserId?: string
}

interface HistoricVariableInstance {
  id: string
  name: string
  value: unknown
  type: string
  processInstanceId: string
  createTime?: string
}

// -------------------------------------------------------------------------
// Tool result helpers
// -------------------------------------------------------------------------

interface ToolResult {
  content: { type: 'text'; text: string }[]
  isError?: boolean
}

function textResult(payload: unknown, isError = false): ToolResult {
  return {
    content: [{ type: 'text', text: JSON.stringify(payload, null, 2) }],
    ...(isError ? { isError: true } : {}),
  }
}

function engineErrorResult(result: {
  status: number
  code?: string
  message?: string
  retryable?: boolean
}): ToolResult {
  return textResult(
    {
      ok: false,
      status: result.status,
      code: result.code,
      message: result.message,
      retryable: result.retryable,
    },
    true,
  )
}

function stripFormKeyPrefix(formKey?: string): string | undefined {
  if (!formKey) return undefined
  return formKey.replace(/^react:/, '')
}

// -------------------------------------------------------------------------
// Tool handlers
// -------------------------------------------------------------------------

async function handleListServices(): Promise<ToolResult> {
  const result = await engineRequest<ProcessDefinition[]>(
    '/engine-rest/process-definition',
    { bearer: currentBearer(), query: { latestVersion: 'true' } },
  )
  if (!result.ok) return engineErrorResult(result)

  const services = (result.data ?? []).map((d) => {
    const manifest = getManifest(d.key)?.manifest
    return {
      key: d.key,
      engineName: d.name ?? d.key,
      version: d.version,
      description: manifest?.description ?? d.description,
      audience: manifest?.audience,
      mcpCallable: Boolean(manifest),
    }
  })

  return textResult({ ok: true, services })
}

async function handleDescribeService(args: unknown): Promise<ToolResult> {
  const key = (args as { key?: string })?.key
  if (typeof key !== 'string' || !key) {
    return textResult(
      { ok: false, code: 'INVALID_ARGUMENT', message: 'Tool argument "key" is required.' },
      true,
    )
  }
  const entry = getManifest(key)
  if (!entry) {
    const available = listManifests().map((e) => e.manifest.key)
    return textResult(
      {
        ok: false,
        code: 'unknown_service',
        message: `No manifest found for service "${key}". MCP-callable services: ${available.join(', ') || '(none)'}.`,
      },
      true,
    )
  }
  return textResult({
    ok: true,
    manifest: entry.manifest,
    training: entry.trainingMd,
  })
}

async function handleStartProcess(args: unknown): Promise<ToolResult> {
  const a = (args ?? {}) as { key?: string; variables?: Record<string, unknown> }
  if (typeof a.key !== 'string' || !a.key) {
    return textResult(
      { ok: false, code: 'INVALID_ARGUMENT', message: 'Tool argument "key" is required.' },
      true,
    )
  }
  if (a.variables === undefined || a.variables === null || typeof a.variables !== 'object') {
    return textResult(
      {
        ok: false,
        code: 'INVALID_ARGUMENT',
        message: 'Tool argument "variables" is required and must be an object.',
      },
      true,
    )
  }

  const validated = validateVariables(a.key, a.variables)
  if (!validated.ok) {
    return textResult(
      {
        ok: false,
        code: 'INVALID_VARIABLES',
        message: `Variables for "${a.key}" failed schema validation.`,
        issues: validated.issues,
      },
      true,
    )
  }

  const entry = getManifest(a.key)!
  const camundaVars = toCamundaVariables(validated.data, entry.manifest.variables as {
    type?: string
    properties?: Record<string, unknown>
  })

  const result = await engineRequest<StartProcessResponse>(
    `/engine-rest/process-definition/key/${encodeURIComponent(a.key)}/start`,
    {
      bearer: currentBearer(),
      method: 'POST',
      body: { variables: camundaVars },
    },
  )

  if (!result.ok) return engineErrorResult(result)

  return textResult({
    ok: true,
    processInstanceId: result.data?.id,
    definitionId: result.data?.definitionId,
    ended: result.data?.ended ?? false,
    suspended: result.data?.suspended ?? false,
    nextStep:
      entry.manifest.initialTask?.name ??
      'Check status with list_my_processes; the first user task will appear shortly.',
  })
}

async function handleListMyTasks(): Promise<ToolResult> {
  const me = currentUsername()
  if (!me) {
    return textResult(
      { ok: false, code: 'INVALID_TOKEN', message: 'Could not decode preferred_username from the Bearer token.' },
      true,
    )
  }

  // Two queries because /engine-rest/task doesn't combine cleanly with OR:
  //   (a) tasks already assigned to me            → I just complete them
  //   (b) unassigned tasks I'm a candidate for    → I claim then complete
  // The engine resolves candidate group membership through IdentityService,
  // so candidateUser=<me> covers both direct candidate-user grants AND
  // candidate-group membership (civil-servant for Homer, applicant for Bart).
  const [assigned, candidate] = await Promise.all([
    engineRequest<EngineTask[]>('/engine-rest/task', {
      bearer: currentBearer(),
      query: { assignee: me },
    }),
    engineRequest<EngineTask[]>('/engine-rest/task', {
      bearer: currentBearer(),
      query: { candidateUser: me, unassigned: 'true' },
    }),
  ])
  if (!assigned.ok) return engineErrorResult(assigned)
  if (!candidate.ok) return engineErrorResult(candidate)

  const seen = new Set<string>()
  const merged = [...(assigned.data ?? []), ...(candidate.data ?? [])].filter(
    (t) => {
      if (seen.has(t.id)) return false
      seen.add(t.id)
      return true
    },
  )

  const tasks = merged.map((t) => {
    const formKey = stripFormKeyPrefix(t.formKey)
    const hit = formKey ? findServiceByFormKey(formKey) : undefined
    return {
      id: t.id,
      name: t.name,
      created: t.created,
      assignee: t.assignee ?? null,
      processInstanceId: t.processInstanceId,
      processDefinitionId: t.processDefinitionId,
      formKey,
      service: hit?.serviceKey,
      audience: hit?.task.descriptor.audience,
      action: t.assignee === me ? 'complete' : 'claim_then_complete',
    }
  })

  return textResult({ ok: true, count: tasks.length, tasks })
}

async function handleGetFormSchema(args: unknown): Promise<ToolResult> {
  const taskId = (args as { taskId?: string })?.taskId
  if (typeof taskId !== 'string' || !taskId) {
    return textResult(
      { ok: false, code: 'INVALID_ARGUMENT', message: 'Tool argument "taskId" is required.' },
      true,
    )
  }

  const taskResult = await engineRequest<EngineTask>(
    `/engine-rest/task/${encodeURIComponent(taskId)}`,
    { bearer: currentBearer() },
  )
  if (!taskResult.ok) return engineErrorResult(taskResult)

  const formKey = stripFormKeyPrefix(taskResult.data?.formKey)
  if (!formKey) {
    return textResult(
      {
        ok: false,
        code: 'no_form_key',
        message: 'Task has no formKey — it may be a system task or a free-form task.',
      },
      true,
    )
  }

  const hit = findServiceByFormKey(formKey)
  if (!hit) {
    return textResult(
      {
        ok: false,
        code: 'no_manifest_entry',
        message: `Task formKey "${formKey}" has no manifest entry. The service is either not MCP-callable yet or the user task is missing a userTasks entry.`,
      },
      true,
    )
  }

  return textResult({
    ok: true,
    taskId,
    formKey,
    service: hit.serviceKey,
    name: hit.task.descriptor.name,
    audience: hit.task.descriptor.audience,
    description: hit.task.descriptor.description,
    schema: hit.task.descriptor.schema,
  })
}

async function handleCompleteTask(args: unknown): Promise<ToolResult> {
  const a = (args ?? {}) as { taskId?: string; variables?: Record<string, unknown> }
  if (typeof a.taskId !== 'string' || !a.taskId) {
    return textResult(
      { ok: false, code: 'INVALID_ARGUMENT', message: 'Tool argument "taskId" is required.' },
      true,
    )
  }
  if (a.variables === undefined || a.variables === null || typeof a.variables !== 'object') {
    return textResult(
      {
        ok: false,
        code: 'INVALID_ARGUMENT',
        message: 'Tool argument "variables" is required and must be an object.',
      },
      true,
    )
  }

  const taskResult = await engineRequest<EngineTask>(
    `/engine-rest/task/${encodeURIComponent(a.taskId)}`,
    { bearer: currentBearer() },
  )
  if (!taskResult.ok) return engineErrorResult(taskResult)

  const formKey = stripFormKeyPrefix(taskResult.data?.formKey)
  if (!formKey) {
    return textResult(
      {
        ok: false,
        code: 'no_form_key',
        message: 'Task has no formKey — cannot validate variables against a schema.',
      },
      true,
    )
  }

  const validated = validateTaskVariables(formKey, a.variables)
  if (!validated.ok) {
    return textResult(
      {
        ok: false,
        code: 'INVALID_VARIABLES',
        message: `Variables for task with formKey "${formKey}" failed schema validation.`,
        issues: validated.issues,
      },
      true,
    )
  }

  const camundaVars = toCamundaVariables(
    validated.data,
    validated.task.descriptor.schema as {
      type?: string
      properties?: Record<string, unknown>
    },
  )

  // Auto-claim unassigned candidate-group tasks. The engine refuses
  // `complete` on a task without an assignee even if the caller is in a
  // candidate group; the canonical flow is claim-then-complete. We hide
  // that two-step from the LLM so it can call complete_task uniformly.
  const me = currentUsername()
  const currentAssignee = taskResult.data?.assignee ?? null
  let claimed = false
  if (!currentAssignee && me) {
    const claim = await engineRequest<unknown>(
      `/engine-rest/task/${encodeURIComponent(a.taskId)}/claim`,
      {
        bearer: currentBearer(),
        method: 'POST',
        body: { userId: me },
      },
    )
    if (!claim.ok) return engineErrorResult(claim)
    claimed = true
  }

  const completeResult = await engineRequest<unknown>(
    `/engine-rest/task/${encodeURIComponent(a.taskId)}/complete`,
    {
      bearer: currentBearer(),
      method: 'POST',
      body: { variables: camundaVars },
    },
  )
  if (!completeResult.ok) return engineErrorResult(completeResult)

  return textResult({
    ok: true,
    taskId: a.taskId,
    service: validated.serviceKey,
    formKey,
    claimed,
  })
}

async function handleListMyProcesses(args: unknown): Promise<ToolResult> {
  const a = (args ?? {}) as { processInstanceId?: string }
  const me = currentUsername()
  if (!me) {
    return textResult(
      { ok: false, code: 'INVALID_TOKEN', message: 'Could not decode preferred_username from the Bearer token.' },
      true,
    )
  }

  const query: Record<string, string> = {
    startedBy: me,
    sortBy: 'startTime',
    sortOrder: 'desc',
  }
  if (a.processInstanceId) query.processInstanceId = a.processInstanceId

  const result = await engineRequest<HistoricProcessInstance[]>(
    '/engine-rest/history/process-instance',
    { bearer: currentBearer(), query },
  )
  if (!result.ok) return engineErrorResult(result)

  const processes = (result.data ?? []).map((p) => ({
    id: p.id,
    serviceKey: p.processDefinitionKey,
    serviceName: p.processDefinitionName ?? p.processDefinitionKey,
    startTime: p.startTime,
    endTime: p.endTime,
    state: p.state,
  }))

  return textResult({ ok: true, count: processes.length, processes })
}

async function handleQueryUserHistory(args: unknown): Promise<ToolResult> {
  const variableName = (args as { variableName?: string })?.variableName
  if (typeof variableName !== 'string' || !variableName) {
    return textResult(
      {
        ok: false,
        code: 'INVALID_ARGUMENT',
        message: 'Tool argument "variableName" is required.',
      },
      true,
    )
  }
  const me = currentUsername()
  if (!me) {
    return textResult(
      { ok: false, code: 'INVALID_TOKEN', message: 'Could not decode preferred_username from the Bearer token.' },
      true,
    )
  }

  const instances = await engineRequest<HistoricProcessInstance[]>(
    '/engine-rest/history/process-instance',
    {
      bearer: currentBearer(),
      query: { startedBy: me, sortBy: 'startTime', sortOrder: 'desc' },
    },
  )
  if (!instances.ok) return engineErrorResult(instances)

  const ids = (instances.data ?? []).map((i) => i.id)
  if (ids.length === 0) {
    return textResult({ ok: true, variableName, found: false })
  }

  const vars = await engineRequest<HistoricVariableInstance[]>(
    '/engine-rest/history/variable-instance',
    {
      bearer: currentBearer(),
      query: {
        processInstanceIdIn: ids.join(','),
        variableName,
      },
    },
  )
  if (!vars.ok) return engineErrorResult(vars)

  // The engine doesn't sort variable-instance results by createTime by
  // default; pick the most recent by joining against the instance order
  // we already have.
  const instanceOrder = new Map<string, number>()
  ;(instances.data ?? []).forEach((i, idx) => instanceOrder.set(i.id, idx))

  const sorted = (vars.data ?? []).slice().sort((a, b) => {
    const ai = instanceOrder.get(a.processInstanceId) ?? Number.MAX_SAFE_INTEGER
    const bi = instanceOrder.get(b.processInstanceId) ?? Number.MAX_SAFE_INTEGER
    return ai - bi
  })

  const mostRecent = sorted[0]
  if (!mostRecent) {
    return textResult({ ok: true, variableName, found: false })
  }

  return textResult({
    ok: true,
    variableName,
    found: true,
    value: mostRecent.value,
    type: mostRecent.type,
    sourceProcessInstanceId: mostRecent.processInstanceId,
  })
}

// -------------------------------------------------------------------------
// MCP server wiring
// -------------------------------------------------------------------------

loadManifests()

// Stateless mode: build a fresh Server + Transport per HTTP request.
// Sharing a single Server across requests breaks the MCP lifecycle — the
// SDK tracks the current request inside the Server instance, so two
// concurrent (or even back-to-back) requests racing on one Server cause
// the second one's transport.handleRequest to 500.
function createMcpServer(): Server {
  const mcp = new Server(
    { name: 'cib7-mcp', version: '0.1.0' },
    { capabilities: { tools: {} } },
  )

  mcp.setRequestHandler(ListToolsRequestSchema, async () => ({
  tools: [
    {
      name: 'list_services',
      description:
        'List the CIB seven process definitions deployed on this instance, latest version of each, decorated with which entries are MCP-callable. Use this first when the user asks "what can I do here?".',
      inputSchema: { type: 'object', properties: {}, additionalProperties: false },
    },
    {
      name: 'describe_service',
      description:
        'Get the full per-service manifest and LLM training markdown for one service key. Returns the JSON Schema for start-time variables plus prose guidance you should read before asking the user clarifying questions. Call this before start_process for any unfamiliar service.',
      inputSchema: {
        type: 'object',
        properties: {
          key: { type: 'string', description: 'Service key, e.g. "personRegistration".' },
        },
        required: ['key'],
        additionalProperties: false,
      },
    },
    {
      name: 'start_process',
      description:
        'Start a new instance of a process definition with the given start-time variables. Variables are validated against the service manifest schema before forwarding. On success returns the new process instance id. The engine sets the `initiator` variable from the authenticated user automatically; do NOT include it in `variables`.',
      inputSchema: {
        type: 'object',
        properties: {
          key: { type: 'string', description: 'Service key, e.g. "personRegistration".' },
          variables: {
            type: 'object',
            description: 'Start-time variables. Shape is per-service — call describe_service first if you do not already know it.',
            additionalProperties: true,
          },
        },
        required: ['key', 'variables'],
        additionalProperties: false,
      },
    },
    {
      name: 'list_my_tasks',
      description:
        'List user tasks the authenticated user can act on right now — both tasks already assigned to them AND unassigned tasks they are a candidate for (via direct grant or candidate-group membership). Each entry includes an `action` hint: `complete` (just call complete_task) or `claim_then_complete` (complete_task auto-claims first). Use to see what is waiting before calling complete_task.',
      inputSchema: { type: 'object', properties: {}, additionalProperties: false },
    },
    {
      name: 'get_form_schema',
      description:
        'Look up the variable schema and prose description for the form attached to a specific task. Always call this before complete_task on an unfamiliar task so you know what variables the task expects and what the audience is supposed to do.',
      inputSchema: {
        type: 'object',
        properties: {
          taskId: { type: 'string', description: 'Engine task id (from list_my_tasks).' },
        },
        required: ['taskId'],
        additionalProperties: false,
      },
    },
    {
      name: 'complete_task',
      description:
        'Complete a user task with the given variables. The MCP service looks up the task to find its formKey, validates variables against the per-task schema, and forwards to /engine-rest. If the task is unassigned and the caller is a candidate (e.g., civil-servant tasks for Homer), the service auto-claims first so the LLM does not need to call a separate claim tool. On schema mismatch returns INVALID_VARIABLES with issues. After completion the process advances per its BPMN.',
      inputSchema: {
        type: 'object',
        properties: {
          taskId: { type: 'string', description: 'Engine task id (from list_my_tasks).' },
          variables: {
            type: 'object',
            description: 'Form variables. Shape comes from get_form_schema for this task.',
            additionalProperties: true,
          },
        },
        required: ['taskId', 'variables'],
        additionalProperties: false,
      },
    },
    {
      name: 'list_my_processes',
      description:
        'List process instances started by the authenticated user, newest first. Each entry includes its key, name, start/end timestamps, and state (ACTIVE, COMPLETED, INTERNALLY_TERMINATED, etc.). Use to report status when the user asks "where is my registration?". Pass an optional processInstanceId to retrieve a single instance.',
      inputSchema: {
        type: 'object',
        properties: {
          processInstanceId: {
            type: 'string',
            description: 'Optional: filter to one specific instance.',
          },
        },
        additionalProperties: false,
      },
    },
    {
      name: 'query_user_history',
      description:
        'Find the most recent value of a process variable that the authenticated user has ever entered (across all process instances they have started). Use for autofill: if a user is starting a new businessRegistration and the schema asks for firstName, call query_user_history("firstName") first; if found, pre-fill and only ask the user to confirm. Returns { found: true, value, sourceProcessInstanceId } or { found: false }.',
      inputSchema: {
        type: 'object',
        properties: {
          variableName: {
            type: 'string',
            description: 'The variable name to look up, e.g. "firstName".',
          },
        },
        required: ['variableName'],
        additionalProperties: false,
      },
    },
  ],
}))

mcp.setRequestHandler(CallToolRequestSchema, async (req) => {
  switch (req.params.name) {
    case 'list_services':
      return handleListServices()
    case 'describe_service':
      return handleDescribeService(req.params.arguments)
    case 'start_process':
      return handleStartProcess(req.params.arguments)
    case 'list_my_tasks':
      return handleListMyTasks()
    case 'get_form_schema':
      return handleGetFormSchema(req.params.arguments)
    case 'complete_task':
      return handleCompleteTask(req.params.arguments)
    case 'list_my_processes':
      return handleListMyProcesses(req.params.arguments)
    case 'query_user_history':
      return handleQueryUserHistory(req.params.arguments)
    default:
      throw new Error(`Unknown tool: ${req.params.name}`)
  }
})

  return mcp
}

// -------------------------------------------------------------------------
// Express wiring
// -------------------------------------------------------------------------

const app = express()
app.use(express.json())

app.get('/.well-known/oauth-protected-resource', (_req, res) => {
  res.json({
    resource: RESOURCE_URL,
    authorization_servers: [KEYCLOAK_ISSUER],
    bearer_methods_supported: ['header'],
    // Only advertise `openid` — the OIDC marker scope that every IDP must
    // accept. The claims this deployment actually needs (preferred_username,
    // realm_access.roles, aud=cib7-rest-api) are wired via default client
    // scopes (cib7-claims + cib7-rest-api-audience) on the cib7-mcp client,
    // so the access token carries them whether or not the MCP client asks.
    // Advertising `profile`/`email` here would make mcp-remote request them
    // and Keycloak would reject the auth with invalid_scope — those built-in
    // scopes do not exist in this realm (we replaced them with cib7-claims).
    scopes_supported: ['openid'],
  })
})

app.get('/health', (_req, res) => {
  res.json({
    ok: true,
    service: 'cib7-mcp',
    version: '0.1.0',
    manifests: listManifests().map((e) => e.manifest.key),
  })
})

const metadataUrl = RESOURCE_URL.replace(
  '/mcp',
  '/.well-known/oauth-protected-resource',
)

function requireBearer(req: Request, res: Response, next: NextFunction): void {
  const auth = req.header('authorization')
  if (!auth || !/^Bearer\s+\S+/i.test(auth)) {
    res
      .status(401)
      .set('WWW-Authenticate', `Bearer resource_metadata="${metadataUrl}"`)
      .json({ error: 'unauthorized', resource_metadata: metadataUrl })
    return
  }
  next()
}

app.all('/mcp', requireBearer, async (req, res) => {
  const bearer = req.header('authorization') ?? ''
  const mcp = createMcpServer()
  const transport = new StreamableHTTPServerTransport({
    sessionIdGenerator: undefined,
  })
  res.on('close', () => {
    void transport.close()
    void mcp.close()
  })
  try {
    await mcp.connect(transport)
    await requestStorage.run({ bearer }, async () => {
      await transport.handleRequest(req, res, req.body)
    })
  } catch (err) {
    // eslint-disable-next-line no-console
    console.error('[/mcp] handler error:', err)
    if (!res.headersSent) {
      res.status(500).json({
        error: 'internal_error',
        message: err instanceof Error ? err.message : String(err),
      })
    }
  }
})

app.listen(PORT, () => {
  const loaded = listManifests().map((e) => e.manifest.key)
  // eslint-disable-next-line no-console
  console.log(
    `cib7-mcp listening on :${PORT}\n  resource:        ${RESOURCE_URL}\n  auth server:     ${KEYCLOAK_ISSUER}\n  engine:          ${engineBaseUrl()}\n  metadata:        /.well-known/oauth-protected-resource\n  mcp endpoint:    /mcp\n  manifests:       ${loaded.length > 0 ? loaded.join(', ') : '(none — start_process / complete_task will fail)'}`,
  )
})
