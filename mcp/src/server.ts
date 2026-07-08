// MCP sidecar — T9 surface.
//
// Eight tools wired against the existing vehicleRegistration definition:
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

import { AsyncLocalStorage } from 'node:async_hooks';
import express, { type Request, type Response, type NextFunction } from 'express';
import type { JWTPayload } from 'jose';
import { Server } from '@modelcontextprotocol/sdk/server/index.js';
import { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js';
import { CallToolRequestSchema, ListToolsRequestSchema } from '@modelcontextprotocol/sdk/types.js';
import { engineRequest, businessRequest, engineBaseUrl } from './engine/client.js';
import { toCamundaVariables, type JsonSchema } from './engine/variables.js';
import { decodeBearerUsername } from './auth/identity.js';
import { audiencesOf, hasInviterAccess, realmRolesOf } from './auth/inviterRole.js';
import { verifyBearer } from './auth/verify.js';
import { adminRequest } from './keycloak/admin.js';
import {
  findServiceByFormKey,
  getManifest,
  listManifests,
  loadManifests,
  validateTaskVariables,
  validateVariables,
} from './services/manifest.js';

const PORT = Number(process.env.PORT ?? 8090);
const RESOURCE_URL = process.env.MCP_RESOURCE_URL ?? 'http://localhost:3000/mcp';
// Public site origin, derived from the MCP endpoint by dropping the `/mcp`
// suffix (e.g. https://companylab.ai/mcp -> https://companylab.ai). Used to
// build absolute links in the agent-discovery surfaces below.
const SITE_BASE_URL = RESOURCE_URL.replace(/\/mcp\/?$/, '');
const KEYCLOAK_ISSUER = process.env.KEYCLOAK_ISSUER_URL ?? 'http://localhost:8180/realms/cib7-poc';
const APPLICANT_PORTAL_URL = process.env.MCP_APPLICANT_PORTAL_URL ?? 'http://localhost:3000';
const MAILPIT_URL = process.env.MCP_MAILPIT_URL ?? 'http://localhost:8025';

// Pre-built deep-link to Keycloak's hosted registration form for the
// cib7-frontend client. We use the SPA's client because registration must
// land back at the applicant portal (the SPA), not at Claude Desktop's
// loopback callback. The user fills the Keycloak form, verifies via email,
// is redirected back to the SPA already signed in, and from there can come
// back to chat and the next MCP tool call will reuse their fresh session.
const REGISTRATION_URL =
  `${KEYCLOAK_ISSUER}/protocol/openid-connect/registrations` +
  `?client_id=cib7-frontend` +
  `&response_type=code` +
  `&scope=openid` +
  `&redirect_uri=${encodeURIComponent(APPLICANT_PORTAL_URL + '/')}`;

// Password-reset uses the same hosted-page deep-link pattern via the
// kc_action=reset_credentials parameter on the standard auth endpoint.
const PASSWORD_RESET_URL =
  `${KEYCLOAK_ISSUER}/protocol/openid-connect/auth` +
  `?client_id=cib7-frontend` +
  `&response_type=code` +
  `&scope=openid` +
  `&kc_action=reset_credentials` +
  `&redirect_uri=${encodeURIComponent(APPLICANT_PORTAL_URL + '/')}`;

const SERVER_INSTRUCTIONS = [
  'This MCP server drives Estonian e-government processes (business registration,',
  'person registration) backed by a CIB seven 2.1 / Camunda 7 engine. Identity is',
  'handled by a separate Keycloak realm. This server never creates accounts and',
  'never handles passwords — those stay entirely with Keycloak and the user.',
  '',
  'WHEN THE USER ASKS YOU TO REGISTER THEM OR SOMEONE ELSE (e.g. "register me",',
  '"sign me up", "invite a new user", "add Lisa to the system", "I want an account"):',
  '  Prefer the invite-by-email path: call `send_account_invitation` with',
  '  { username, email, firstName, lastName }. Ask the user for those four',
  '  fields one by one if you do not already have them. NEVER ask for a',
  '  password — the tool does not accept one and Keycloak handles password',
  '  setup itself via the magic link the user receives. After the tool',
  '  returns, tell the invitee to open the Mailpit inbox at the URL the tool',
  '  returns, click the link in the invitation email, set their password',
  '  in the Keycloak form, and they are signed in.',
  '',
  'WHEN THE USER PREFERS TO REGISTER ON THEIR OWN WEB PAGE (e.g. "just give',
  'me a link", "I will do it myself", or `send_account_invitation` is not',
  'appropriate because no email is known): call `get_signup_url` instead.',
  '  It returns the public URL of the hosted Keycloak sign-up page plus the',
  '  step-by-step. The user fills the form themselves.',
  '',
  'WHEN THE USER SAYS THEY FORGOT THEIR PASSWORD or cannot sign in:',
  '  Same shape — call `get_password_reset_url`. It returns the URL of the',
  '  Keycloak reset-credentials page plus the steps. The user resets the',
  '  password themselves; you only relay the link.',
  '',
  'NEVER ask the user for their password and never accept one in chat — if they',
  'volunteer it, tell them not to share it and point them at the reset URL.',
  '',
  'WHEN THE USER WANTS TO SEE OR REVIEW THEIR APPLICATION IN THE WEB PORTAL',
  'BEFORE SUBMITTING (e.g. "can I see the form first?", "send me a link to',
  'check my data"): call `save_draft` with the task id and everything you have',
  'collected so far, then share the `portalUrl` it returns. NEVER construct or',
  'share a portal task link yourself — without `save_draft` the form opens',
  'empty because your collected data lives only in this conversation.',
  '',
  'WHEN THE USER ASKS WHAT THEY CAN DO HERE: start with `list_services`.',
  'WHEN STARTING ANY UNFAMILIAR SERVICE: call `describe_service` first.',
  'WHEN THE USER ASKS ABOUT THE CONTENT OF THEIR DOCUMENTS (e.g. "what does my',
  'certificate say?", "find my invoice amount"): call `search_documents`.',
].join('\n');

interface RequestContext {
  bearer: string;
  /** Claims verified by requireBearer — for tools that authorize locally. */
  claims?: JWTPayload;
}
const requestStorage = new AsyncLocalStorage<RequestContext>();

function currentBearer(): string {
  return requestStorage.getStore()?.bearer ?? '';
}

function currentUsername(): string {
  return decodeBearerUsername(currentBearer());
}

function currentClaims(): JWTPayload | undefined {
  return requestStorage.getStore()?.claims;
}

// -------------------------------------------------------------------------
// Engine response shapes
// -------------------------------------------------------------------------

interface ProcessDefinition {
  id: string;
  key: string;
  name?: string;
  version: number;
  description?: string;
}

interface StartProcessResponse {
  id: string;
  definitionId: string;
  businessKey: string | null;
  caseInstanceId: string | null;
  ended: boolean;
  suspended: boolean;
}

interface EngineTask {
  id: string;
  name?: string;
  assignee?: string | null;
  created: string;
  processInstanceId: string;
  processDefinitionId: string;
  formKey?: string;
  taskDefinitionKey?: string;
}

interface HistoricProcessInstance {
  id: string;
  processDefinitionKey: string;
  processDefinitionName?: string;
  startTime: string;
  endTime?: string | null;
  state: string;
  startUserId?: string;
}

interface HistoricVariableInstance {
  id: string;
  name: string;
  value: unknown;
  type: string;
  processInstanceId: string;
  createTime?: string;
}

// -------------------------------------------------------------------------
// Tool result helpers
// -------------------------------------------------------------------------

interface ToolResult {
  // Index signature keeps this assignable to the MCP SDK's ServerResult union.
  [key: string]: unknown;
  content: { type: 'text'; text: string }[];
  isError?: boolean;
}

function textResult(payload: unknown, isError = false): ToolResult {
  return {
    content: [{ type: 'text', text: JSON.stringify(payload, null, 2) }],
    ...(isError ? { isError: true } : {}),
  };
}

function engineErrorResult(result: {
  status: number;
  code?: string;
  message?: string;
  retryable?: boolean;
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
  );
}

function stripFormKeyPrefix(formKey?: string): string | undefined {
  if (!formKey) return undefined;
  return formKey.replace(/^react:/, '');
}

// -------------------------------------------------------------------------
// Tool handlers
// -------------------------------------------------------------------------

async function handleListServices(): Promise<ToolResult> {
  const result = await engineRequest<ProcessDefinition[]>('/engine-rest/process-definition', {
    bearer: currentBearer(),
    query: { latestVersion: 'true' },
  });
  if (!result.ok) return engineErrorResult(result);

  const services = (result.data ?? []).map((d) => {
    const manifest = getManifest(d.key)?.manifest;
    return {
      key: d.key,
      engineName: d.name ?? d.key,
      version: d.version,
      description: manifest?.description ?? d.description,
      audience: manifest?.audience,
      mcpCallable: Boolean(manifest),
    };
  });

  return textResult({ ok: true, services });
}

async function handleDescribeService(args: unknown): Promise<ToolResult> {
  const key = (args as { key?: string })?.key;
  if (typeof key !== 'string' || !key) {
    return textResult(
      { ok: false, code: 'INVALID_ARGUMENT', message: 'Tool argument "key" is required.' },
      true,
    );
  }
  const entry = getManifest(key);
  if (!entry) {
    const available = listManifests().map((e) => e.manifest.key);
    return textResult(
      {
        ok: false,
        code: 'unknown_service',
        message: `No manifest found for service "${key}". MCP-callable services: ${available.join(', ') || '(none)'}.`,
      },
      true,
    );
  }
  return textResult({
    ok: true,
    manifest: entry.manifest,
    training: entry.trainingMd,
  });
}

async function handleStartProcess(args: unknown): Promise<ToolResult> {
  const a = (args ?? {}) as { key?: string; variables?: Record<string, unknown> };
  if (typeof a.key !== 'string' || !a.key) {
    return textResult(
      { ok: false, code: 'INVALID_ARGUMENT', message: 'Tool argument "key" is required.' },
      true,
    );
  }
  if (a.variables === undefined || a.variables === null || typeof a.variables !== 'object') {
    return textResult(
      {
        ok: false,
        code: 'INVALID_ARGUMENT',
        message: 'Tool argument "variables" is required and must be an object.',
      },
      true,
    );
  }

  const validated = validateVariables(a.key, a.variables);
  if (!validated.ok) {
    return textResult(
      {
        ok: false,
        code: 'INVALID_VARIABLES',
        message: `Variables for "${a.key}" failed schema validation.`,
        issues: validated.issues,
      },
      true,
    );
  }

  const entry = getManifest(a.key)!;
  const camundaVars = toCamundaVariables(validated.data, entry.manifest.variables as JsonSchema);

  const result = await engineRequest<StartProcessResponse>(
    `/engine-rest/process-definition/key/${encodeURIComponent(a.key)}/start`,
    {
      bearer: currentBearer(),
      method: 'POST',
      body: { variables: camundaVars },
    },
  );

  if (!result.ok) return engineErrorResult(result);

  return textResult({
    ok: true,
    processInstanceId: result.data?.id,
    definitionId: result.data?.definitionId,
    ended: result.data?.ended ?? false,
    suspended: result.data?.suspended ?? false,
    nextStep:
      entry.manifest.initialTask?.name ??
      'Check status with list_my_processes; the first user task will appear shortly.',
  });
}

async function handleListMyTasks(): Promise<ToolResult> {
  const me = currentUsername();
  if (!me) {
    return textResult(
      {
        ok: false,
        code: 'INVALID_TOKEN',
        message: 'Could not decode preferred_username from the Bearer token.',
      },
      true,
    );
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
  ]);
  if (!assigned.ok) return engineErrorResult(assigned);
  if (!candidate.ok) return engineErrorResult(candidate);

  const seen = new Set<string>();
  const merged = [...(assigned.data ?? []), ...(candidate.data ?? [])].filter((t) => {
    if (seen.has(t.id)) return false;
    seen.add(t.id);
    return true;
  });

  const tasks = merged.map((t) => {
    const formKey = stripFormKeyPrefix(t.formKey);
    const hit = formKey ? findServiceByFormKey(formKey) : undefined;
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
    };
  });

  return textResult({ ok: true, count: tasks.length, tasks });
}

async function handleGetFormSchema(args: unknown): Promise<ToolResult> {
  const taskId = (args as { taskId?: string })?.taskId;
  if (typeof taskId !== 'string' || !taskId) {
    return textResult(
      { ok: false, code: 'INVALID_ARGUMENT', message: 'Tool argument "taskId" is required.' },
      true,
    );
  }

  const taskResult = await engineRequest<EngineTask>(
    `/engine-rest/task/${encodeURIComponent(taskId)}`,
    { bearer: currentBearer() },
  );
  if (!taskResult.ok) return engineErrorResult(taskResult);

  const formKey = stripFormKeyPrefix(taskResult.data?.formKey);
  if (!formKey) {
    return textResult(
      {
        ok: false,
        code: 'no_form_key',
        message: 'Task has no formKey — it may be a system task or a free-form task.',
      },
      true,
    );
  }

  const hit = findServiceByFormKey(formKey);
  if (!hit) {
    return textResult(
      {
        ok: false,
        code: 'no_manifest_entry',
        message: `Task formKey "${formKey}" has no manifest entry. The service is either not MCP-callable yet or the user task is missing a userTasks entry.`,
      },
      true,
    );
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
    requiredDocuments: hit.task.descriptor.requiredDocuments ?? [],
  });
}

async function handleUploadDocument(args: unknown): Promise<ToolResult> {
  const a = (args ?? {}) as {
    category?: string;
    filename?: string;
    contentType?: string;
    base64?: string;
  };
  const missing = (['category', 'filename', 'contentType', 'base64'] as const).filter(
    (k) => !a[k] || typeof a[k] !== 'string' || !(a[k] as string).trim(),
  );
  if (missing.length > 0) {
    return textResult(
      {
        ok: false,
        code: 'INVALID_ARGUMENT',
        message: `Missing required fields: ${missing.join(', ')}.`,
      },
      true,
    );
  }

  // /api/documents moved from the engine into the backend business service.
  const result = await businessRequest<{
    pendingKey: string;
    filename: string;
    contentType: string;
  }>('/api/documents/stage', {
    bearer: currentBearer(),
    method: 'POST',
    body: {
      category: a.category,
      filename: a.filename,
      contentType: a.contentType,
      base64: a.base64,
    },
  });
  if (!result.ok) return engineErrorResult(result);

  return textResult({
    ok: true,
    pendingKey: result.data?.pendingKey,
    filename: result.data?.filename,
    contentType: result.data?.contentType,
    nextStep:
      "Pass this whole object verbatim as `pendingIdDocument` (or whatever `writeTo` field the task's requiredDocuments entry names) when you call complete_task.",
  });
}

interface DocumentSearchHit {
  attachmentId: string;
  processInstanceId: string;
  category: string;
  filename: string;
  snippet: string;
  score: number | null;
}

async function handleSearchDocuments(args: unknown): Promise<ToolResult> {
  const query = (args as { query?: string })?.query;
  if (typeof query !== 'string' || !query.trim()) {
    return textResult(
      { ok: false, code: 'INVALID_ARGUMENT', message: 'Tool argument "query" is required.' },
      true,
    );
  }

  // The backend embeds the query, searches its vector index over the
  // extracted text of stored documents, and post-filters every hit through
  // the same per-case access rule as the rest of the documents API — so
  // the results only ever cover cases this user may see.
  const result = await businessRequest<{
    query: string;
    count: number;
    results: DocumentSearchHit[];
  }>('/api/documents/search', {
    bearer: currentBearer(),
    query: { q: query.trim() },
  });
  if (!result.ok) return engineErrorResult(result);

  return textResult({
    ok: true,
    count: result.data?.count ?? 0,
    results: result.data?.results ?? [],
    note:
      (result.data?.count ?? 0) === 0
        ? 'No matching document text. Scanned images (JPEG/PNG) are not indexed — only documents with extractable text (PDFs). The document may also still be indexing if it was uploaded seconds ago.'
        : 'Snippets are chunk previews of the stored documents. Cite the filename when answering; the SPA Documents sidebar of the case can download the full file.',
  });
}

async function handleCompleteTask(args: unknown): Promise<ToolResult> {
  const a = (args ?? {}) as { taskId?: string; variables?: Record<string, unknown> };
  if (typeof a.taskId !== 'string' || !a.taskId) {
    return textResult(
      { ok: false, code: 'INVALID_ARGUMENT', message: 'Tool argument "taskId" is required.' },
      true,
    );
  }
  if (a.variables === undefined || a.variables === null || typeof a.variables !== 'object') {
    return textResult(
      {
        ok: false,
        code: 'INVALID_ARGUMENT',
        message: 'Tool argument "variables" is required and must be an object.',
      },
      true,
    );
  }

  const taskResult = await engineRequest<EngineTask>(
    `/engine-rest/task/${encodeURIComponent(a.taskId)}`,
    { bearer: currentBearer() },
  );
  if (!taskResult.ok) return engineErrorResult(taskResult);

  const formKey = stripFormKeyPrefix(taskResult.data?.formKey);
  if (!formKey) {
    return textResult(
      {
        ok: false,
        code: 'no_form_key',
        message: 'Task has no formKey — cannot validate variables against a schema.',
      },
      true,
    );
  }

  // Backfill BPMN-required reset variables that the React form always writes
  // but the LLM shouldn't have to think about. Keeps the manifest schema
  // small and the LLM-facing tool ergonomic.
  //
  // - additionalOwners: the gateway right after personal-details evaluates
  //   `additionalOwners == null || additionalOwners.elements().isEmpty()`,
  //   which throws PropertyNotFoundException if the variable is missing
  //   entirely. Default to [] so the empty-owners branch is taken.
  const withDefaults = { ...a.variables } as Record<string, unknown>;
  if (formKey === 'personal-details' && withDefaults['additionalOwners'] === undefined) {
    withDefaults['additionalOwners'] = [];
  }

  const validated = validateTaskVariables(formKey, withDefaults);
  if (!validated.ok) {
    return textResult(
      {
        ok: false,
        code: 'INVALID_VARIABLES',
        message: `Variables for task with formKey "${formKey}" failed schema validation.`,
        issues: validated.issues,
      },
      true,
    );
  }

  // Cross-check required documents against the variables, ahead of any
  // engine call. The schema may already require the `writeTo` field, but
  // returning a document-shaped error here gives the LLM an explicit
  // pointer at upload_document instead of a generic schema failure.
  const docs = validated.task.descriptor.requiredDocuments ?? [];
  const missingDocs = docs.filter((d) => {
    const v = (validated.data as Record<string, unknown>)[d.writeTo];
    if (!v || typeof v !== 'object') return true;
    const pendingKey = (v as { pendingKey?: unknown }).pendingKey;
    return typeof pendingKey !== 'string' || !pendingKey;
  });
  if (missingDocs.length > 0) {
    return textResult(
      {
        ok: false,
        code: 'DOCUMENT_REQUIRED',
        message:
          `Task "${formKey}" requires document${missingDocs.length > 1 ? 's' : ''} that have not been uploaded. ` +
          'For each missing entry below, call upload_document with the listed category, then pass the response object verbatim as the variable named in `writeTo` when you call complete_task again.',
        missingDocuments: missingDocs,
      },
      true,
    );
  }

  const camundaVars = toCamundaVariables(
    validated.data,
    validated.task.descriptor.schema as JsonSchema,
  );

  // Auto-claim unassigned candidate-group tasks. The engine refuses
  // `complete` on a task without an assignee even if the caller is in a
  // candidate group; the canonical flow is claim-then-complete. We hide
  // that two-step from the LLM so it can call complete_task uniformly.
  const me = currentUsername();
  const currentAssignee = taskResult.data?.assignee ?? null;
  let claimed = false;
  if (!currentAssignee && me) {
    const claim = await engineRequest<unknown>(
      `/engine-rest/task/${encodeURIComponent(a.taskId)}/claim`,
      {
        bearer: currentBearer(),
        method: 'POST',
        body: { userId: me },
      },
    );
    if (!claim.ok) return engineErrorResult(claim);
    claimed = true;
  }

  const completeResult = await engineRequest<unknown>(
    `/engine-rest/task/${encodeURIComponent(a.taskId)}/complete`,
    {
      bearer: currentBearer(),
      method: 'POST',
      body: { variables: camundaVars },
    },
  );
  if (!completeResult.ok) return engineErrorResult(completeResult);

  return textResult({
    ok: true,
    taskId: a.taskId,
    service: validated.serviceKey,
    formKey,
    claimed,
  });
}

async function handleSaveDraft(args: unknown): Promise<ToolResult> {
  const a = (args ?? {}) as { taskId?: string; variables?: Record<string, unknown> };
  if (typeof a.taskId !== 'string' || !a.taskId) {
    return textResult(
      { ok: false, code: 'INVALID_ARGUMENT', message: 'Tool argument "taskId" is required.' },
      true,
    );
  }
  if (a.variables === undefined || a.variables === null || typeof a.variables !== 'object') {
    return textResult(
      {
        ok: false,
        code: 'INVALID_ARGUMENT',
        message: 'Tool argument "variables" is required and must be an object.',
      },
      true,
    );
  }

  const taskResult = await engineRequest<EngineTask>(
    `/engine-rest/task/${encodeURIComponent(a.taskId)}`,
    { bearer: currentBearer() },
  );
  if (!taskResult.ok) return engineErrorResult(taskResult);

  const formKey = stripFormKeyPrefix(taskResult.data?.formKey);
  if (!formKey) {
    return textResult(
      {
        ok: false,
        code: 'no_form_key',
        message: 'Task has no formKey — cannot validate draft variables against a schema.',
      },
      true,
    );
  }

  // Drafts are allowed to be incomplete (partial: true skips missing-required
  // errors) but not wrong: type mismatches and unknown fields still fail so
  // the portal form never receives values it cannot render.
  const validated = validateTaskVariables(formKey, a.variables, { partial: true });
  if (!validated.ok) {
    return textResult(
      {
        ok: false,
        code: 'INVALID_VARIABLES',
        message: `Draft variables for task with formKey "${formKey}" failed schema validation.`,
        issues: validated.issues,
      },
      true,
    );
  }

  const camundaVars = toCamundaVariables(
    validated.data,
    validated.task.descriptor.schema as JsonSchema,
  );

  // Task-LOCAL variables, deliberately: /task/{id}/form-variables resolves
  // the local scope first, so the SPA form prefills from the draft — but the
  // values never enter the process scope, so an unreviewed half-draft cannot
  // drive gateways, DMN inputs, or listeners. When the user submits in the
  // portal, the SPA writes the real process variables via its normal complete
  // path and the draft dies with the task.
  const saveResult = await engineRequest<unknown>(
    `/engine-rest/task/${encodeURIComponent(a.taskId)}/localVariables`,
    {
      bearer: currentBearer(),
      method: 'POST',
      body: { modifications: camundaVars },
    },
  );
  if (!saveResult.ok) return engineErrorResult(saveResult);

  return textResult({
    ok: true,
    taskId: a.taskId,
    service: validated.serviceKey,
    formKey,
    savedFields: Object.keys(camundaVars),
    portalUrl: `${APPLICANT_PORTAL_URL}/tasks/${encodeURIComponent(a.taskId)}`,
    notes: [
      'Share portalUrl with the user — the form there is now prefilled with the draft.',
      'This saved a DRAFT only; nothing was submitted and the process has not advanced.',
      'The user can review, edit, and submit directly in the portal. If they submit there, the task disappears from list_my_tasks.',
      'The portal has no draft-save of its own: if the user edits fields there WITHOUT submitting, those edits are not sent back to you — re-confirm values with the user before calling complete_task.',
    ],
  });
}

async function handleListMyProcesses(args: unknown): Promise<ToolResult> {
  const a = (args ?? {}) as { processInstanceId?: string };
  const me = currentUsername();
  if (!me) {
    return textResult(
      {
        ok: false,
        code: 'INVALID_TOKEN',
        message: 'Could not decode preferred_username from the Bearer token.',
      },
      true,
    );
  }

  const query: Record<string, string> = {
    startedBy: me,
    sortBy: 'startTime',
    sortOrder: 'desc',
  };
  if (a.processInstanceId) query.processInstanceId = a.processInstanceId;

  const result = await engineRequest<HistoricProcessInstance[]>(
    '/engine-rest/history/process-instance',
    { bearer: currentBearer(), query },
  );
  if (!result.ok) return engineErrorResult(result);

  const processes = (result.data ?? []).map((p) => ({
    id: p.id,
    serviceKey: p.processDefinitionKey,
    serviceName: p.processDefinitionName ?? p.processDefinitionKey,
    startTime: p.startTime,
    endTime: p.endTime,
    state: p.state,
  }));

  return textResult({ ok: true, count: processes.length, processes });
}

async function handleQueryUserHistory(args: unknown): Promise<ToolResult> {
  const variableName = (args as { variableName?: string })?.variableName;
  if (typeof variableName !== 'string' || !variableName) {
    return textResult(
      {
        ok: false,
        code: 'INVALID_ARGUMENT',
        message: 'Tool argument "variableName" is required.',
      },
      true,
    );
  }
  const me = currentUsername();
  if (!me) {
    return textResult(
      {
        ok: false,
        code: 'INVALID_TOKEN',
        message: 'Could not decode preferred_username from the Bearer token.',
      },
      true,
    );
  }

  const instances = await engineRequest<HistoricProcessInstance[]>(
    '/engine-rest/history/process-instance',
    {
      bearer: currentBearer(),
      query: { startedBy: me, sortBy: 'startTime', sortOrder: 'desc' },
    },
  );
  if (!instances.ok) return engineErrorResult(instances);

  const ids = (instances.data ?? []).map((i) => i.id);
  if (ids.length === 0) {
    return textResult({ ok: true, variableName, found: false });
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
  );
  if (!vars.ok) return engineErrorResult(vars);

  // The engine doesn't sort variable-instance results by createTime by
  // default; pick the most recent by joining against the instance order
  // we already have.
  const instanceOrder = new Map<string, number>();
  (instances.data ?? []).forEach((i, idx) => instanceOrder.set(i.id, idx));

  const sorted = (vars.data ?? []).slice().sort((a, b) => {
    const ai = instanceOrder.get(a.processInstanceId) ?? Number.MAX_SAFE_INTEGER;
    const bi = instanceOrder.get(b.processInstanceId) ?? Number.MAX_SAFE_INTEGER;
    return ai - bi;
  });

  const mostRecent = sorted[0];
  if (!mostRecent) {
    return textResult({ ok: true, variableName, found: false });
  }

  return textResult({
    ok: true,
    variableName,
    found: true,
    value: mostRecent.value,
    type: mostRecent.type,
    sourceProcessInstanceId: mostRecent.processInstanceId,
  });
}

function handleGetSignupUrl(): ToolResult {
  return textResult({
    ok: true,
    signupUrl: REGISTRATION_URL,
    mailpitUrl: MAILPIT_URL,
    stepsForUser: [
      'Open the signupUrl in a browser tab.',
      'Pick a username, email, first/last name, and password (twice).',
      `Submit the form. A verification email arrives at ${MAILPIT_URL} (this POC uses a local mail catcher — open it in another tab).`,
      'Click the verification link in that email — you are now signed in to the applicant portal.',
      'Return to this chat and let me know; the next tool call will pick up your new session automatically.',
    ],
    note: "This server did not create an account. The URL points the user at Keycloak's hosted sign-up page where the user fills the form themselves.",
  });
}

/**
 * Authorization gate for the one tool that is NOT engine-proxied. The actual
 * audience + realm-role predicate lives in auth/inviterRole.ts (unit-tested);
 * this wrapper turns a denial into the LLM-facing ToolResult.
 */
function requireInviterRole(): ToolResult | null {
  const claims = currentClaims();
  if (hasInviterAccess(claims)) return null;
  console.warn(
    `[send_account_invitation] denied: sub=${claims?.sub ?? '(none)'} aud=[${audiencesOf(claims).join(',')}] roles=[${realmRolesOf(claims).join(',')}]`,
  );
  return textResult(
    {
      ok: false,
      code: 'FORBIDDEN',
      message:
        'Your account is not allowed to send invitations. Only signed-in portal users (applicant, civil-servant or cib7-admin role) can invite others.',
    },
    true,
  );
}

async function handleSendAccountInvitation(args: unknown): Promise<ToolResult> {
  const denied = requireInviterRole();
  if (denied) return denied;

  const a = (args ?? {}) as {
    username?: string;
    email?: string;
    firstName?: string;
    lastName?: string;
  };
  const missing = (['username', 'email', 'firstName', 'lastName'] as const).filter(
    (k) => !a[k] || typeof a[k] !== 'string' || !(a[k] as string).trim(),
  );
  if (missing.length > 0) {
    return textResult(
      {
        ok: false,
        code: 'INVALID_ARGUMENT',
        message: `Missing required fields: ${missing.join(', ')}. Ask the user for these; never ask for a password.`,
      },
      true,
    );
  }
  const username = (a.username as string).trim();
  const email = (a.email as string).trim();
  const firstName = (a.firstName as string).trim();
  const lastName = (a.lastName as string).trim();

  if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) {
    return textResult(
      {
        ok: false,
        code: 'INVALID_ARGUMENT',
        message: `email "${email}" does not look like a valid email address. Ask the user to confirm it.`,
      },
      true,
    );
  }
  if (/[\s/\\@]/.test(username)) {
    return textResult(
      {
        ok: false,
        code: 'INVALID_ARGUMENT',
        message: `username "${username}" contains spaces or invalid characters. Ask the user for a simple login id like "lisa" or "jdoe".`,
      },
      true,
    );
  }

  // Step 1: create the user. requiredActions force the magic-link flow on
  // first sign-in (password setup + email verification). No password is ever
  // posted from this service.
  const create = await adminRequest('/users', {
    method: 'POST',
    body: {
      username,
      email,
      firstName,
      lastName,
      enabled: true,
      emailVerified: false,
      requiredActions: ['UPDATE_PASSWORD', 'VERIFY_EMAIL'],
    },
  });
  if (!create.ok) {
    if (create.status === 409) {
      return textResult(
        {
          ok: false,
          code: 'USER_EXISTS',
          message: `A user with the same username or email already exists. Tell the invitee to use the existing account or pick a different username/email.`,
        },
        true,
      );
    }
    return textResult(
      {
        ok: false,
        code: create.code ?? 'KEYCLOAK_ERROR',
        message: `Keycloak rejected the user creation: ${create.message ?? create.status}.`,
      },
      true,
    );
  }
  const userId = create.locationId;
  if (!userId) {
    return textResult(
      {
        ok: false,
        code: 'KEYCLOAK_ERROR',
        message: 'Keycloak created the user but did not return a user id in the Location header.',
      },
      true,
    );
  }

  // Step 2: send the magic-link email that walks the user through password
  // setup + email verification, then redirects them to the SPA. The body is
  // the list of required actions to execute via the link.
  const send = await adminRequest(`/users/${userId}/execute-actions-email`, {
    method: 'PUT',
    query: {
      client_id: 'cib7-frontend',
      redirect_uri: APPLICANT_PORTAL_URL + '/',
    },
    body: ['UPDATE_PASSWORD', 'VERIFY_EMAIL'],
  });
  if (!send.ok) {
    return textResult(
      {
        ok: false,
        code: 'EMAIL_SEND_FAILED',
        message: `User was created (id=${userId}) but the invitation email failed: ${send.message ?? send.status}. The user can still sign in via the password-reset flow on the login page.`,
        userId,
      },
      true,
    );
  }

  console.log(
    `[send_account_invitation] ${currentUsername() || '(unknown)'} invited ${username} <${email}>`,
  );

  return textResult({
    ok: true,
    userId,
    username,
    email,
    mailpitUrl: MAILPIT_URL,
    nextStepsForUser: [
      `Open the local mail catcher at ${MAILPIT_URL}.`,
      `Find the invitation email for ${email}.`,
      'Click the link in that email — it opens a Keycloak page.',
      'Choose a password (twice) and submit.',
      'Email is automatically marked verified once you complete this flow.',
      'You land on the applicant portal, already signed in.',
    ],
    note: "This server never accepts or stores passwords. The invitee sets their own password in Keycloak's hosted form.",
  });
}

function handleGetPasswordResetUrl(): ToolResult {
  return textResult({
    ok: true,
    resetUrl: PASSWORD_RESET_URL,
    mailpitUrl: MAILPIT_URL,
    stepsForUser: [
      'Open the resetUrl in a browser tab.',
      'Enter the username or email you registered with; submit.',
      `Keycloak sends a reset email to ${MAILPIT_URL} — open it and click the link.`,
      'Set a new password (twice), submit — you are signed in with the new password.',
      'Return to this chat; the next tool call will pick up the fresh session.',
    ],
    note: "This server did not change any password. The URL points the user at Keycloak's hosted reset page where the user resets the password themselves.",
  });
}

// -------------------------------------------------------------------------
// MCP server wiring
// -------------------------------------------------------------------------

loadManifests();

// Stateless mode: build a fresh Server + Transport per HTTP request.
// Sharing a single Server across requests breaks the MCP lifecycle — the
// SDK tracks the current request inside the Server instance, so two
// concurrent (or even back-to-back) requests racing on one Server cause
// the second one's transport.handleRequest to 500.
function createMcpServer(): Server {
  const mcp = new Server(
    { name: 'cib7-mcp', version: '0.1.0' },
    { capabilities: { tools: {} }, instructions: SERVER_INSTRUCTIONS },
  );

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
            key: { type: 'string', description: 'Service key, e.g. "vehicleRegistration".' },
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
            key: { type: 'string', description: 'Service key, e.g. "vehicleRegistration".' },
            variables: {
              type: 'object',
              description:
                'Start-time variables. Shape is per-service — call describe_service first if you do not already know it.',
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
        name: 'save_draft',
        description:
          'Save the form data collected so far as a DRAFT on a user task and return a portal link where the user can review it in the real web form before submitting. Writes the variables as task-local variables in the engine, so the portal form at the returned `portalUrl` opens prefilled; nothing is submitted and the process does not advance. Variables may be incomplete (missing required fields are fine for a draft) but provided fields are still type-checked against the task schema. ALWAYS use this tool when the user asks to see, review, or check their application in the portal before submitting — NEVER hand out a portal task link without calling this first, otherwise the user sees an empty form. The user can finish and submit in the portal, or come back to chat and you call complete_task as usual.',
        inputSchema: {
          type: 'object',
          properties: {
            taskId: { type: 'string', description: 'Engine task id (from list_my_tasks).' },
            variables: {
              type: 'object',
              description:
                'The form variables collected so far. Shape comes from get_form_schema for this task; a partial subset is allowed.',
              additionalProperties: true,
            },
          },
          required: ['taskId', 'variables'],
          additionalProperties: false,
        },
      },
      {
        name: 'upload_document',
        description:
          "Stage a document (PDF / JPEG / PNG, ≤10 MB) that a later complete_task call will reference. The base64 payload is decoded server-side and stored in the engine's pending area. Returns { pendingKey, filename, contentType } — pass that object VERBATIM as the variable named in the task's requiredDocuments[i].writeTo (e.g. `pendingIdDocument` for vehicleRegistration's personal-details task). Call this BEFORE complete_task whenever get_form_schema or describe_service lists a requiredDocuments entry.",
        inputSchema: {
          type: 'object',
          properties: {
            category: {
              type: 'string',
              description:
                'Document category. Must match the requiredDocuments entry, e.g. "applicant-id-document".',
            },
            filename: {
              type: 'string',
              description: 'Original filename including extension, e.g. "id-card.pdf".',
            },
            contentType: {
              type: 'string',
              description: 'MIME type. One of application/pdf, image/jpeg, image/png.',
            },
            base64: {
              type: 'string',
              description:
                'Base64-encoded file contents (no data: prefix, no line breaks). Claude can extract this from a PDF / image the user has attached in chat.',
            },
          },
          required: ['category', 'filename', 'contentType', 'base64'],
          additionalProperties: false,
        },
      },
      {
        name: 'search_documents',
        description:
          'Semantic search over the text of documents stored in the user\'s cases — uploaded PDFs and engine-generated certificates, approvals, and invoices. Results are scoped to cases the authenticated user may access (applicants: own cases; reviewers: all). Returns snippets with attachmentId, processInstanceId, category, and filename. Use when the user asks what a document says, e.g. "what does my certificate say?" or "find the invoice amount". Scanned images without a text layer are not indexed.',
        inputSchema: {
          type: 'object',
          properties: {
            query: {
              type: 'string',
              description:
                'Natural-language search query, e.g. "vehicle registration certificate plate number".',
            },
          },
          required: ['query'],
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
      {
        name: 'get_signup_url',
        description:
          'Look up and return the public URL of the hosted Keycloak sign-up page, plus the steps the user follows there. This tool performs NO account creation, NO form submission, NO credential handling — it only retrieves a URL string and instructions. It is equivalent to telling the user "the sign-up page is at <URL>" except the URL is constructed against the live Keycloak deployment so you do not have to guess it. Use when the user asks where to register, says they are new, says they do not have an account, or asks how to sign up. The user does the actual sign-up themselves at the returned URL.',
        inputSchema: { type: 'object', properties: {}, additionalProperties: false },
      },
      {
        name: 'get_password_reset_url',
        description:
          'Look up and return the public URL of the Keycloak password-reset page, plus the steps. Like `get_signup_url`, this tool performs NO action — it only retrieves a URL and instructions. The user resets the password themselves at the returned URL. Use when the user says they forgot their password, cannot sign in, or want to change their password.',
        inputSchema: { type: 'object', properties: {}, additionalProperties: false },
      },
      {
        name: 'send_account_invitation',
        description:
          'Send an account invitation to a new user. Creates an invite-pending user record in Keycloak with NO password (the tool does not accept or return one) and emails them a magic link. The invitee clicks the link, chooses their own password in Keycloak\'s hosted form, and verifies their email — at which point they are signed in. Use when the user asks to register a new applicant or to add a new user. NEVER ask the user for a password — only username, email, first name, and last name. The username is the login identifier (e.g. "lisa"); the email is where the invitation lands.',
        inputSchema: {
          type: 'object',
          properties: {
            username: { type: 'string', description: 'Login id (no spaces), e.g. "lisa".' },
            email: {
              type: 'string',
              description: 'Email address that receives the invitation link.',
            },
            firstName: { type: 'string', description: 'Given name.' },
            lastName: { type: 'string', description: 'Family name.' },
          },
          required: ['username', 'email', 'firstName', 'lastName'],
          additionalProperties: false,
        },
      },
    ],
  }));

  mcp.setRequestHandler(CallToolRequestSchema, async (req) => {
    switch (req.params.name) {
      case 'list_services':
        return handleListServices();
      case 'describe_service':
        return handleDescribeService(req.params.arguments);
      case 'start_process':
        return handleStartProcess(req.params.arguments);
      case 'list_my_tasks':
        return handleListMyTasks();
      case 'get_form_schema':
        return handleGetFormSchema(req.params.arguments);
      case 'complete_task':
        return handleCompleteTask(req.params.arguments);
      case 'save_draft':
        return handleSaveDraft(req.params.arguments);
      case 'upload_document':
        return handleUploadDocument(req.params.arguments);
      case 'search_documents':
        return handleSearchDocuments(req.params.arguments);
      case 'list_my_processes':
        return handleListMyProcesses(req.params.arguments);
      case 'query_user_history':
        return handleQueryUserHistory(req.params.arguments);
      case 'get_signup_url':
        return handleGetSignupUrl();
      case 'get_password_reset_url':
        return handleGetPasswordResetUrl();
      case 'send_account_invitation':
        return handleSendAccountInvitation(req.params.arguments);
      default:
        throw new Error(`Unknown tool: ${req.params.name}`);
    }
  });

  return mcp;
}

// -------------------------------------------------------------------------
// Express wiring
// -------------------------------------------------------------------------

const app = express();
app.use(express.json());

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
  });
});

// Agent-discovery surfaces. These let an AI agent that simply lands on the
// site (or probes the well-known prefix) realise MCP is supported without
// prior knowledge of the endpoint. They are served from the MCP service so
// they always reflect the live manifest registry. The frontend nginx /
// Traefik route these specific paths here instead of the SPA fallback.
function discoveryServices(): Array<{
  key: string;
  name: string;
  description: string;
  audience?: string;
}> {
  return listManifests().map(({ manifest }) => ({
    key: manifest.key,
    name: manifest.name,
    description: manifest.description,
    audience: manifest.audience,
  }));
}

// Top-level MCP discovery document. Not (yet) an IETF-registered well-known
// URI, but the de-facto path agents probe; kept self-describing — endpoint,
// transport, OAuth pointer, a paste-ready client config, and the catalog.
app.get('/.well-known/mcp.json', (_req, res) => {
  res.json({
    name: 'eRegistrations (CIB seven POC)',
    description:
      'Estonian e-government registration services (business, vehicle, transport) ' +
      'exposed over the Model Context Protocol so AI agents can complete them end-to-end.',
    mcp: {
      url: RESOURCE_URL,
      transport: 'streamable-http',
      authorization: {
        type: 'oauth2',
        protected_resource_metadata: `${SITE_BASE_URL}/.well-known/oauth-protected-resource`,
        authorization_servers: [KEYCLOAK_ISSUER],
      },
    },
    // Ready-to-paste mcp-remote / Claude Desktop client config.
    mcpServers: {
      'cib7-poc': {
        command: 'npx',
        args: ['-y', 'mcp-remote', RESOURCE_URL],
      },
    },
    instructions: SERVER_INSTRUCTIONS,
    services: discoveryServices(),
  });
});

app.get('/.well-known/mcp/services.json', (_req, res) => {
  res.json({ version: 1, services: discoveryServices() });
});

// llms.txt — the plain-text/markdown convention (llmstxt.org) for telling an
// LLM what a site is and how to use it. Highest-signal because an agent can
// read it without an MCP handshake.
app.get('/llms.txt', (_req, res) => {
  const lines = [
    '# eRegistrations (CIB seven POC)',
    '',
    '> Estonian e-government registration services exposed over the Model Context',
    '> Protocol (MCP). AI agents can complete business, vehicle and transport',
    '> registrations end-to-end on behalf of a signed-in user.',
    '',
    'This site supports MCP. Point any MCP client at the endpoint below; it uses',
    'OAuth 2.0 (Keycloak) for authentication and the Streamable HTTP transport.',
    '',
    '## MCP',
    `- Endpoint: ${RESOURCE_URL} (Streamable HTTP)`,
    `- Discovery: ${SITE_BASE_URL}/.well-known/mcp.json`,
    `- Auth (OAuth 2.0 protected resource): ${SITE_BASE_URL}/.well-known/oauth-protected-resource`,
    `- Claude Desktop / mcp-remote: npx -y mcp-remote ${RESOURCE_URL}`,
    '',
    '## Services',
    ...discoveryServices().map((s) => `- ${s.name}: ${s.description}`),
    '',
    '## Getting started',
    'Call `list_services`, then `describe_service` for the one you want, then',
    '`start_process`. To create an account use `send_account_invitation` (invite',
    'by email) or `get_signup_url` (self-service). Never ask the user for a password.',
    '',
  ];
  res.type('text/plain').send(lines.join('\n'));
});

app.get('/health', (_req, res) => {
  res.json({
    ok: true,
    service: 'cib7-mcp',
    version: '0.1.0',
    manifests: listManifests().map((e) => e.manifest.key),
  });
});

const metadataUrl = RESOURCE_URL.replace('/mcp', '/.well-known/oauth-protected-resource');

async function requireBearer(req: Request, res: Response, next: NextFunction): Promise<void> {
  const auth = req.header('authorization');
  // Two stages here: (1) check the Authorization header is present and looks
  // like a Bearer; (2) verify the JWT against Keycloak's JWKS so a stale or
  // signature-mismatched token (typical after a realm rebuild on a dev box)
  // surfaces as a clean HTTP 401 with WWW-Authenticate — mcp-remote treats
  // that as "session expired, re-run OAuth" and the user is silently bounced
  // back to the Keycloak login page. Without this, the engine's 401 leaks
  // into a tool result and Claude has no way to ask for a fresh token.
  const result = await verifyBearer(auth);
  if (!result.ok) {
    res
      .status(401)
      .set(
        'WWW-Authenticate',
        `Bearer resource_metadata="${metadataUrl}", error="invalid_token", error_description="${result.reason}"`,
      )
      .json({
        error: 'unauthorized',
        reason: result.reason,
        resource_metadata: metadataUrl,
      });
    return;
  }
  res.locals.claims = result.payload;
  next();
}

app.all('/mcp', requireBearer, async (req, res) => {
  const bearer = req.header('authorization') ?? '';
  const mcp = createMcpServer();
  const transport = new StreamableHTTPServerTransport({
    sessionIdGenerator: undefined,
  });
  res.on('close', () => {
    void transport.close();
    void mcp.close();
  });
  try {
    await mcp.connect(transport);
    const claims = res.locals.claims as JWTPayload | undefined;
    await requestStorage.run({ bearer, claims }, async () => {
      await transport.handleRequest(req, res, req.body);
    });
  } catch (err) {
    console.error('[/mcp] handler error:', err);
    if (!res.headersSent) {
      res.status(500).json({
        error: 'internal_error',
        message: err instanceof Error ? err.message : String(err),
      });
    }
  }
});

app.listen(PORT, () => {
  const loaded = listManifests().map((e) => e.manifest.key);

  console.log(
    `cib7-mcp listening on :${PORT}\n  resource:        ${RESOURCE_URL}\n  auth server:     ${KEYCLOAK_ISSUER}\n  engine:          ${engineBaseUrl()}\n  metadata:        /.well-known/oauth-protected-resource\n  mcp endpoint:    /mcp\n  manifests:       ${loaded.length > 0 ? loaded.join(', ') : '(none — start_process / complete_task will fail)'}`,
  );
});
