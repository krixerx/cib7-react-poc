// Service manifest registry.
//
// At startup we walk SERVICES_SPEC_DIR (the pack's docs/business/services/,
// copied into the image), find every */build/mcp-service.json +
// */build/mcp-training.md pair, and index them by the manifest's `key` field
// (which must match the BPMN process definition key — that's the integration
// contract).
//
// A manifest (format version 2, docs/platform-api.md) holds no value rules of
// its own. It names, per form, the fields an agent is offered and what each
// one means; the rules are the engine's own form schemas, read from
// ENGINE_PROCESSES_DIR (<service>/schemas/<form-id>.json, the same files the
// engine checks a completed task against) with the core's shared definitions
// (CORE_SCHEMA_FILE) inlined. So start_process and complete_task refuse what
// the engine would refuse, and the two cannot drift apart.

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import Ajv2020, { type ValidateFunction } from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';

const SERVICES_SPEC_DIR = process.env.SERVICES_SPEC_DIR ?? '/app/services-spec';
const ENGINE_PROCESSES_DIR = process.env.ENGINE_PROCESSES_DIR ?? '/app/pack-engine/processes';
const CORE_SCHEMA_FILE = process.env.CORE_SCHEMA_FILE ?? '/app/core-schemas/core-v1.json';

/** The manifest format this sidecar reads. */
export const MANIFEST_VERSION = 2;

export interface RequiredDocumentDescriptor {
  /** Document category — must match an applicant category in the pack's documents.json. */
  category: string;
  /** The task-variable name where the LLM passes the upload_document response. */
  writeTo: string;
  /** Whitelisted MIME types. */
  accept?: string[];
  /** Max decoded byte size. */
  maxBytes?: number;
  /** Human-readable explanation surfaced to the LLM via get_form_schema. */
  description?: string;
}

/** A field an agent is offered, by name, with what it means. */
export type FieldDescriptions = Record<string, string>;

interface ManifestTaskFile {
  formKey: string;
  name?: string;
  audience?: string;
  description?: string;
  requiredDocuments?: RequiredDocumentDescriptor[];
  fields: FieldDescriptions;
  /**
   * Fields the form's variable policy allows that an agent is deliberately not offered (identity
   * fields the engine fills, what only the portal form writes). The sidecar refuses them like any
   * other unoffered field; the list is there so the pack check can hold policy and manifest equal.
   */
  notOffered?: string[];
}

interface ManifestFile {
  version: number;
  key: string;
  name: string;
  description: string;
  audience?: string;
  candidateGroups?: string[];
  initialTask?: { formKey?: string; audience?: string; name?: string };
  start?: { description?: string; fields: FieldDescriptions };
  userTasks?: ManifestTaskFile[];
}

export interface UserTaskDescriptor {
  formKey: string;
  name?: string;
  audience?: string;
  description?: string;
  /** The engine's form schema, cut to the offered fields and described for the LLM. */
  schema: Record<string, unknown>;
  /** Documents that must be uploaded (via upload_document) before complete_task. */
  requiredDocuments?: RequiredDocumentDescriptor[];
}

/** A manifest as the tools show it: the file, with each form's schema filled in. */
export interface ServiceManifest {
  key: string;
  name: string;
  description: string;
  audience?: string;
  candidateGroups?: string[];
  initialTask?: { formKey?: string; audience?: string; name?: string };
  variables: Record<string, unknown>;
  userTasks?: UserTaskDescriptor[];
}

interface CompiledForm {
  validate: ValidateFunction;
  offered: Set<string>;
}

export interface CompiledUserTask extends CompiledForm {
  descriptor: UserTaskDescriptor;
}

export interface CompiledManifest {
  manifest: ServiceManifest;
  start: CompiledForm;
  trainingMd: string;
  userTasks: Map<string, CompiledUserTask>;
}

type Schema = Record<string, unknown>;

const ajv = new Ajv2020({ allErrors: true, strict: false });
addFormats(ajv);

const registry = new Map<string, CompiledManifest>();
const formKeyIndex = new Map<string, { serviceKey: string; task: CompiledUserTask }>();
let initialized = false;

const isObject = (v: unknown): v is Schema =>
  typeof v === 'object' && v !== null && !Array.isArray(v);

/**
 * Replaces every reference to the core's shared definitions with the
 * definition itself, so the schema is complete on its own: an LLM cannot
 * resolve a `$ref` URL, and the type of each field must be readable for the
 * engine variable types (toCamundaVariables).
 */
export function inlineCoreRefs(node: unknown, core: Schema): unknown {
  const coreId = String(core['$id'] ?? '');
  const defs = (core['$defs'] ?? {}) as Schema;
  const walk = (n: unknown): unknown => {
    if (Array.isArray(n)) return n.map(walk);
    if (!isObject(n)) return n;
    const out: Schema = {};
    let base: Schema = {};
    for (const [k, v] of Object.entries(n)) {
      if (k === '$ref' && typeof v === 'string') {
        const name = v.startsWith(`${coreId}#/$defs/`)
          ? v.slice(`${coreId}#/$defs/`.length)
          : v.startsWith('#/$defs/')
            ? v.slice('#/$defs/'.length)
            : undefined;
        if (name === undefined || !isObject(defs[name])) {
          throw new Error(`unresolvable $ref "${v}" (only ${coreId}#/$defs/<name> is allowed)`);
        }
        base = walk(defs[name]) as Schema;
      } else {
        out[k] = walk(v);
      }
    }
    return { ...base, ...out };
  };
  return walk(node);
}

/** Every field a form schema can accept: its properties and those its conditions add. */
function acceptedFields(schema: Schema): Set<string> {
  const names = new Set(Object.keys((schema.properties ?? {}) as Schema));
  for (const rule of (schema.allOf ?? []) as Schema[]) {
    for (const branch of [rule.then, rule.else]) {
      if (isObject(branch))
        Object.keys((branch.properties ?? {}) as Schema).forEach((n) => names.add(n));
    }
  }
  return names;
}

/** The engine's form schemas by `<x-process>/<x-form>`, core definitions inlined. */
function loadFormSchemas(): Map<string, Schema> {
  const core = JSON.parse(readFileSync(CORE_SCHEMA_FILE, 'utf8')) as Schema;
  const out = new Map<string, Schema>();
  for (const service of readdirSync(ENGINE_PROCESSES_DIR)) {
    const dir = join(ENGINE_PROCESSES_DIR, service, 'schemas');
    let files: string[];
    try {
      files = readdirSync(dir).filter((f) => f.endsWith('.json'));
    } catch {
      continue;
    }
    for (const file of files) {
      const raw = JSON.parse(readFileSync(join(dir, file), 'utf8')) as Schema;
      out.set(
        `${String(raw['x-process'])}/${String(raw['x-form'])}`,
        inlineCoreRefs(raw, core) as Schema,
      );
    }
  }
  return out;
}

function fieldDescriptions(value: unknown, where: string): FieldDescriptions {
  if (!isObject(value))
    throw new Error(`${where}: "fields" must be an object of field descriptions`);
  for (const [name, text] of Object.entries(value)) {
    if (typeof text !== 'string' || !text.trim()) {
      throw new Error(`${where}: field "${name}" needs a description`);
    }
  }
  return value as FieldDescriptions;
}

/**
 * One form, compiled: the engine's schema validates, and the LLM sees that
 * schema with only the offered fields, each carrying its description.
 */
function compileForm(
  engine: Schema | undefined,
  fields: FieldDescriptions,
  where: string,
): { compiled: CompiledForm; schema: Schema } {
  const rules: Schema = engine ?? { type: 'object', properties: {} };
  const accepted = acceptedFields(rules);
  for (const name of Object.keys(fields)) {
    if (!accepted.has(name))
      throw new Error(`${where}: "${name}" is not a field of the engine's form schema`);
  }
  const required = ((rules.required ?? []) as string[]).filter((n) => !(n in fields));
  if (required.length > 0) {
    throw new Error(`${where}: required field(s) ${required.join(', ')} are not offered`);
  }
  const properties: Schema = {};
  const engineProps = (rules.properties ?? {}) as Schema;
  for (const [name, description] of Object.entries(fields)) {
    properties[name] = { ...((engineProps[name] as Schema | undefined) ?? {}), description };
  }
  const schema: Schema = { type: 'object', properties };
  if (rules.required) schema.required = rules.required;
  if (rules.allOf) schema.allOf = rules.allOf;
  return {
    compiled: { validate: ajv.compile(rules), offered: new Set(Object.keys(fields)) },
    schema,
  };
}

function loadOne(serviceDir: string, forms: Map<string, Schema>): CompiledManifest | null {
  const buildDir = join(serviceDir, 'build');
  let manifestRaw: string;
  let trainingMd: string;
  try {
    manifestRaw = readFileSync(join(buildDir, 'mcp-service.json'), 'utf8');
  } catch {
    return null;
  }
  try {
    trainingMd = readFileSync(join(buildDir, 'mcp-training.md'), 'utf8');
  } catch {
    trainingMd = '(no training markdown found for this service)';
  }

  const where = `${buildDir}/mcp-service.json`;
  const file = JSON.parse(manifestRaw) as ManifestFile;
  if (!file.key) throw new Error(`${where}: missing required "key" field`);
  if (file.version !== MANIFEST_VERSION) {
    throw new Error(
      `${where}: manifest version ${String(file.version)}, this sidecar reads ${MANIFEST_VERSION}`,
    );
  }
  if (!file.name || !file.description)
    throw new Error(`${where}: "name" and "description" are required`);

  const start = compileForm(
    forms.get(`${file.key}/start`),
    fieldDescriptions(file.start?.fields ?? {}, `${where} start`),
    `${where} start`,
  );
  if (file.start?.description) start.schema.description = file.start.description;

  const userTasks = new Map<string, CompiledUserTask>();
  const descriptors: UserTaskDescriptor[] = [];
  for (const task of file.userTasks ?? []) {
    const at = `${where} ${task.formKey}`;
    if (!task.formKey) throw new Error(`${where}: a user task has no formKey`);
    const engine = forms.get(`${file.key}/${task.formKey}`);
    if (!engine) throw new Error(`${at}: the engine has no form schema ${task.formKey}.json`);
    const form = compileForm(engine, fieldDescriptions(task.fields, at), at);
    if (task.notOffered !== undefined) {
      if (!Array.isArray(task.notOffered) || task.notOffered.some((n) => typeof n !== 'string')) {
        throw new Error(`${at}: "notOffered" must be a list of field names`);
      }
      const both = task.notOffered.filter((n) => form.compiled.offered.has(n));
      if (both.length > 0) {
        throw new Error(`${at}: ${both.join(', ')} both offered and in "notOffered"`);
      }
    }
    for (const doc of task.requiredDocuments ?? []) {
      if (!form.compiled.offered.has(doc.writeTo)) {
        throw new Error(`${at}: document writeTo "${doc.writeTo}" is not an offered field`);
      }
    }
    const descriptor: UserTaskDescriptor = {
      formKey: task.formKey,
      name: task.name,
      audience: task.audience,
      description: task.description,
      requiredDocuments: task.requiredDocuments,
      schema: form.schema,
    };
    descriptors.push(descriptor);
    userTasks.set(task.formKey, { ...form.compiled, descriptor });
  }

  const manifest: ServiceManifest = {
    key: file.key,
    name: file.name,
    description: file.description,
    audience: file.audience,
    candidateGroups: file.candidateGroups,
    initialTask: file.initialTask,
    variables: start.schema,
    userTasks: descriptors,
  };
  return { manifest, start: start.compiled, trainingMd, userTasks };
}

export function loadManifests(): Map<string, CompiledManifest> {
  registry.clear();
  formKeyIndex.clear();

  let entries: string[];
  let forms: Map<string, Schema>;
  try {
    entries = readdirSync(SERVICES_SPEC_DIR);
    forms = loadFormSchemas();
  } catch (e) {
    console.warn(
      `manifest loader: SERVICES_SPEC_DIR (${SERVICES_SPEC_DIR}), ENGINE_PROCESSES_DIR (${ENGINE_PROCESSES_DIR}) or CORE_SCHEMA_FILE (${CORE_SCHEMA_FILE}) not readable; starting with empty registry (${(e as Error).message})`,
    );
    initialized = true;
    return registry;
  }

  for (const entry of entries) {
    const path = join(SERVICES_SPEC_DIR, entry);
    if (!statSync(path).isDirectory()) continue;
    try {
      const compiled = loadOne(path, forms);
      if (compiled) {
        registry.set(compiled.manifest.key, compiled);
        for (const [formKey, task] of compiled.userTasks) {
          formKeyIndex.set(formKey, { serviceKey: compiled.manifest.key, task });
        }
      }
    } catch (e) {
      console.error(`manifest loader: failed to load ${path}: ${(e as Error).message}`);
    }
  }

  initialized = true;
  return registry;
}

export function getManifest(key: string): CompiledManifest | undefined {
  if (!initialized) loadManifests();
  return registry.get(key);
}

export function listManifests(): CompiledManifest[] {
  if (!initialized) loadManifests();
  return Array.from(registry.values());
}

/**
 * Look up which service owns a given formKey. The formKey is the contract
 * between BPMN user tasks and the React form registry (and the MCP manifest);
 * each formKey should be globally unique across services.
 */
export function findServiceByFormKey(
  formKey: string,
): { serviceKey: string; task: CompiledUserTask } | undefined {
  if (!initialized) loadManifests();
  return formKeyIndex.get(formKey);
}

export interface ValidationFailure {
  path: string;
  message: string;
  keyword?: string;
  params?: Record<string, unknown>;
}

function toIssues(
  errors: { instancePath?: string; message?: string; keyword?: string; params?: object }[],
): ValidationFailure[] {
  return errors.map((err) => ({
    path: err.instancePath ?? '',
    message: err.message ?? 'validation failed',
    keyword: err.keyword,
    params: err.params as Record<string, unknown>,
  }));
}

/**
 * The engine schema's verdict plus one of our own: a field the manifest does
 * not offer is refused, even where the engine would take it (the SPA may
 * write fields an agent is not meant to).
 */
function check(form: CompiledForm, variables: unknown, partial: boolean): ValidationFailure[] {
  const issues: ValidationFailure[] = [];
  if (!form.validate(variables)) {
    issues.push(...toIssues(form.validate.errors ?? []));
  }
  if (isObject(variables)) {
    for (const name of Object.keys(variables)) {
      if (!form.offered.has(name)) {
        issues.push({
          path: `/${name}`,
          message: `"${name}" is not a field of this form`,
          keyword: 'additionalProperties',
          params: { additionalProperty: name },
        });
      }
    }
  }
  // Partial mode (drafts): the payload may be incomplete, so drop
  // missing-required complaints (and the conditional rule that reports them)
  // but keep everything else — wrong types and unknown fields must fail even
  // in a draft, or the SPA form would choke on them later.
  return partial ? issues.filter((i) => i.keyword !== 'required' && i.keyword !== 'if') : issues;
}

export function validateVariables(
  key: string,
  variables: unknown,
): { ok: true; data: Record<string, unknown> } | { ok: false; issues: ValidationFailure[] } {
  const entry = getManifest(key);
  if (!entry) {
    return {
      ok: false,
      issues: [
        {
          path: '',
          message: `Unknown service key "${key}". Use list_services to see what's available.`,
        },
      ],
    };
  }
  const issues = check(entry.start, variables, false);
  if (issues.length > 0) return { ok: false, issues };
  return { ok: true, data: variables as Record<string, unknown> };
}

export function validateTaskVariables(
  formKey: string,
  variables: unknown,
  opts?: { partial?: boolean },
):
  | { ok: true; data: Record<string, unknown>; serviceKey: string; task: CompiledUserTask }
  | { ok: false; issues: ValidationFailure[] } {
  const hit = findServiceByFormKey(formKey);
  if (!hit) {
    return {
      ok: false,
      issues: [
        {
          path: '',
          message: `No manifest entry found for formKey "${formKey}". Either the form is not MCP-callable yet or the user task lacks a manifest entry under userTasks.`,
        },
      ],
    };
  }
  const issues = check(hit.task, variables, Boolean(opts?.partial));
  if (issues.length > 0) return { ok: false, issues };
  return {
    ok: true,
    data: variables as Record<string, unknown>,
    serviceKey: hit.serviceKey,
    task: hit.task,
  };
}
