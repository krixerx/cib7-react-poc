// Service manifest registry.
//
// At startup we walk SERVICES_SPEC_DIR (mounted from
// docs/business/services/ at image build time), find every
// */build/mcp-service.json + */build/mcp-training.md pair, and index them
// by the manifest's `key` field (which must match the BPMN process
// definition key — that's the integration contract).
//
// Each manifest carries:
//   - variables : JSON Schema (2020-12) for start_process input
//   - userTasks : array of { formKey, name, audience, schema } for each
//                 user task in the BPMN
//
// We compile every schema once with Ajv at startup so start_process and
// complete_task can validate inputs without per-call recompilation.

import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'
import Ajv2020, { type ValidateFunction } from 'ajv/dist/2020.js'
import addFormats from 'ajv-formats'

const SERVICES_SPEC_DIR =
  process.env.SERVICES_SPEC_DIR ?? '/app/services-spec'

export interface RequiredDocumentDescriptor {
  /** Document category — must match an ALLOWED_CATEGORIES entry on the engine side. */
  category: string
  /** The task-variable name where the LLM passes the upload_document response. */
  writeTo: string
  /** Whitelisted MIME types. */
  accept?: string[]
  /** Max decoded byte size. */
  maxBytes?: number
  /** Human-readable explanation surfaced to the LLM via get_form_schema. */
  description?: string
}

export interface UserTaskDescriptor {
  formKey: string
  name?: string
  audience?: string
  description?: string
  schema: Record<string, unknown>
  /** Documents that must be uploaded (via upload_document) before complete_task. */
  requiredDocuments?: RequiredDocumentDescriptor[]
}

export interface ServiceManifest {
  key: string
  name: string
  description: string
  audience?: string
  candidateGroups?: string[]
  initialTask?: {
    formKey?: string
    audience?: string
    name?: string
  }
  variables: Record<string, unknown>
  userTasks?: UserTaskDescriptor[]
}

export interface CompiledUserTask {
  descriptor: UserTaskDescriptor
  validate: ValidateFunction
}

export interface CompiledManifest {
  manifest: ServiceManifest
  validate: ValidateFunction
  trainingMd: string
  userTasks: Map<string, CompiledUserTask>
}

const ajv = new Ajv2020({ allErrors: true, strict: false })
addFormats(ajv)

const registry = new Map<string, CompiledManifest>()
const formKeyIndex = new Map<string, { serviceKey: string; task: CompiledUserTask }>()
let initialized = false

function loadOne(serviceDir: string): CompiledManifest | null {
  const buildDir = join(serviceDir, 'build')
  let manifestRaw: string
  let trainingMd: string
  try {
    manifestRaw = readFileSync(join(buildDir, 'mcp-service.json'), 'utf8')
  } catch {
    return null
  }
  try {
    trainingMd = readFileSync(join(buildDir, 'mcp-training.md'), 'utf8')
  } catch {
    trainingMd = '(no training markdown found for this service)'
  }

  const manifest = JSON.parse(manifestRaw) as ServiceManifest
  if (!manifest.key) {
    throw new Error(
      `${buildDir}/mcp-service.json: missing required "key" field`,
    )
  }
  if (!manifest.variables) {
    throw new Error(
      `${buildDir}/mcp-service.json: missing required "variables" JSON Schema`,
    )
  }

  const validate = ajv.compile(manifest.variables)

  const userTasks = new Map<string, CompiledUserTask>()
  for (const task of manifest.userTasks ?? []) {
    if (!task.formKey || !task.schema) continue
    userTasks.set(task.formKey, {
      descriptor: task,
      validate: ajv.compile(task.schema),
    })
  }

  return { manifest, validate, trainingMd, userTasks }
}

export function loadManifests(): Map<string, CompiledManifest> {
  registry.clear()
  formKeyIndex.clear()

  let entries: string[]
  try {
    entries = readdirSync(SERVICES_SPEC_DIR)
  } catch (e) {
    // eslint-disable-next-line no-console
    console.warn(
      `manifest loader: SERVICES_SPEC_DIR (${SERVICES_SPEC_DIR}) not readable; starting with empty registry (${(e as Error).message})`,
    )
    initialized = true
    return registry
  }

  for (const entry of entries) {
    const path = join(SERVICES_SPEC_DIR, entry)
    if (!statSync(path).isDirectory()) continue
    try {
      const compiled = loadOne(path)
      if (compiled) {
        registry.set(compiled.manifest.key, compiled)
        for (const [formKey, task] of compiled.userTasks) {
          formKeyIndex.set(formKey, { serviceKey: compiled.manifest.key, task })
        }
      }
    } catch (e) {
      // eslint-disable-next-line no-console
      console.error(`manifest loader: failed to load ${path}: ${(e as Error).message}`)
    }
  }

  initialized = true
  return registry
}

export function getManifest(key: string): CompiledManifest | undefined {
  if (!initialized) loadManifests()
  return registry.get(key)
}

export function listManifests(): CompiledManifest[] {
  if (!initialized) loadManifests()
  return Array.from(registry.values())
}

/**
 * Look up which service owns a given formKey. The formKey is the contract
 * between BPMN user tasks and the React form registry (and the MCP manifest);
 * each formKey should be globally unique across services.
 */
export function findServiceByFormKey(
  formKey: string,
): { serviceKey: string; task: CompiledUserTask } | undefined {
  if (!initialized) loadManifests()
  return formKeyIndex.get(formKey)
}

export interface ValidationFailure {
  path: string
  message: string
  keyword?: string
  params?: Record<string, unknown>
}

function toIssues(errors: { instancePath?: string; message?: string; keyword?: string; params?: object }[]): ValidationFailure[] {
  return errors.map((err) => ({
    path: err.instancePath ?? '',
    message: err.message ?? 'validation failed',
    keyword: err.keyword,
    params: err.params as Record<string, unknown>,
  }))
}

export function validateVariables(
  key: string,
  variables: unknown,
): { ok: true; data: Record<string, unknown> } | { ok: false; issues: ValidationFailure[] } {
  const entry = getManifest(key)
  if (!entry) {
    return {
      ok: false,
      issues: [
        {
          path: '',
          message: `Unknown service key "${key}". Use list_services to see what's available.`,
        },
      ],
    }
  }
  const valid = entry.validate(variables)
  if (!valid) return { ok: false, issues: toIssues(entry.validate.errors ?? []) }
  return { ok: true, data: variables as Record<string, unknown> }
}

export function validateTaskVariables(
  formKey: string,
  variables: unknown,
): { ok: true; data: Record<string, unknown>; serviceKey: string; task: CompiledUserTask }
  | { ok: false; issues: ValidationFailure[] } {
  const hit = findServiceByFormKey(formKey)
  if (!hit) {
    return {
      ok: false,
      issues: [
        {
          path: '',
          message: `No manifest entry found for formKey "${formKey}". Either the form is not MCP-callable yet or the user task lacks a manifest entry under userTasks.`,
        },
      ],
    }
  }
  const valid = hit.task.validate(variables)
  if (!valid) return { ok: false, issues: toIssues(hit.task.validate.errors ?? []) }
  return {
    ok: true,
    data: variables as Record<string, unknown>,
    serviceKey: hit.serviceKey,
    task: hit.task,
  }
}
