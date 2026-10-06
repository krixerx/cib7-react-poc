import type { CamundaVariables } from '../../api/camundaClient';

/**
 * Form definition format v1: what the service builder emits from a
 * `forms/<id>.md` spec with `Renderer: schema`, and what {@link SchemaForm}
 * draws. Part of the platform API: a pack holds these JSON files instead of
 * React code, so a core update cannot break a customer's form.
 *
 * Texts are i18n keys in the definition's own namespace (`i18n`), so `en` and
 * `ar` stay in the locale files. The parser below is strict on purpose: a
 * definition outside this shape is refused as a whole instead of half-drawn.
 */
export interface FormDefinition {
  version: 1;
  /** Form id = the BPMN formKey without `react:`. */
  form: string;
  /** BPMN process id the form belongs to. */
  process: string;
  /** i18n namespace holding every key below. */
  i18n: string;
  intro: { edit: string; readOnly: string };
  summary: SummaryItem[];
  fields: Field[];
  notices: Notice[];
  actions: Action[];
}

/** When an element is shown: always, only on a finished case, or only while the task is open. */
export type Show = 'always' | 'readOnly' | 'editing';

/** How a value is displayed. */
export type Format = 'text' | 'number' | 'currency' | 'decision';

/** A read-only `label: value` row above the fields, read from a process variable. */
export interface SummaryItem {
  label: string;
  variable: string;
  format: Format;
  show: Show;
}

/** A field: `display` shows a variable; `text` and `textarea` take input. */
export interface Field {
  name: string;
  label: string;
  type: 'display' | 'text' | 'textarea';
  format: Format;
  placeholder?: string;
  rows?: number;
  /** Shown only after the action with this id was pressed (a two-step action). */
  revealedBy?: string;
  /** Key of the message shown when the field is blank on submit; absent = optional. */
  requiredMessage?: string;
}

/** A hint line showing a variable's value next to a label, for example a previous reason. */
export interface Notice {
  label: string;
  variable: string;
  show: Show;
}

export type VariableType = 'String' | 'Integer' | 'Double' | 'Boolean';

/** One variable an action completes the task with: a fixed value, or a field's input. */
export type Completion =
  { value: string | number | boolean; type: VariableType } | { field: string; type: VariableType };

export interface Action {
  id: string;
  label: string;
  style: 'primary' | 'danger' | 'default';
  workingLabel: string;
  /** Label of the second, confirming button when the action reveals fields. */
  confirmLabel?: string;
  complete: Record<string, Completion>;
}

const ID = /^[a-z][a-z0-9-]{0,62}$/;
const NAME = /^[A-Za-z][A-Za-z0-9_]{0,62}$/;
const KEY = /^([a-z][a-z0-9-]*:)?[A-Za-z][A-Za-z0-9_.]{0,120}$/;
const SHOWS: Show[] = ['always', 'readOnly', 'editing'];
const FORMATS: Format[] = ['text', 'number', 'currency', 'decision'];
const FIELD_TYPES: Field['type'][] = ['display', 'text', 'textarea'];
const STYLES: Action['style'][] = ['primary', 'danger', 'default'];
const VARIABLE_TYPES: VariableType[] = ['String', 'Integer', 'Double', 'Boolean'];

/** Thrown for a definition outside format v1; the form is then not drawn at all. */
export class InvalidDefinition extends Error {}

function fail(message: string): never {
  throw new InvalidDefinition(message);
}

function record(value: unknown, what: string, allowed: string[]): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    fail(`${what} must be an object`);
  }
  const unknownKey = Object.keys(value).find((k) => !allowed.includes(k));
  if (unknownKey) fail(`${what} has an unknown key '${unknownKey}'`);
  return value as Record<string, unknown>;
}

function text(value: unknown, pattern: RegExp, what: string): string {
  if (typeof value !== 'string' || !pattern.test(value)) fail(`${what} is invalid`);
  return value;
}

function oneOf<T extends string>(value: unknown, allowed: T[], fallback: T, what: string): T {
  if (value === undefined) return fallback;
  if (!allowed.includes(value as T)) fail(`${what} must be one of ${allowed.join(', ')}`);
  return value as T;
}

function list(value: unknown, what: string): unknown[] {
  if (value === undefined) return [];
  if (!Array.isArray(value)) fail(`${what} must be a list`);
  return value;
}

/** Checks a fetched definition against format v1 and fills in defaults. */
export function parseDefinition(raw: unknown, expectedForm: string): FormDefinition {
  const root = record(raw, 'definition', [
    '$comment',
    'version',
    'form',
    'process',
    'i18n',
    'intro',
    'summary',
    'fields',
    'notices',
    'actions',
  ]);
  if (root.version !== 1) fail('version must be 1');
  const form = text(root.form, ID, 'form');
  if (form !== expectedForm) fail(`definition is for '${form}', not '${expectedForm}'`);
  const intro = record(root.intro, 'intro', ['edit', 'readOnly']);

  const summary = list(root.summary, 'summary').map((item, i) => {
    const s = record(item, `summary[${i}]`, ['label', 'variable', 'format', 'show']);
    return {
      label: text(s.label, KEY, `summary[${i}].label`),
      variable: text(s.variable, NAME, `summary[${i}].variable`),
      format: oneOf(s.format, FORMATS, 'text', `summary[${i}].format`),
      show: oneOf(s.show, SHOWS, 'always', `summary[${i}].show`),
    };
  });

  const actions = list(root.actions, 'actions').map((item, i) => {
    const a = record(item, `actions[${i}]`, [
      'id',
      'label',
      'style',
      'workingLabel',
      'confirmLabel',
      'complete',
    ]);
    // Keys here are variable names, checked one by one below, not a fixed set.
    const complete = record(a.complete, `actions[${i}].complete`, Object.keys(a.complete ?? {}));
    return {
      raw: complete,
      action: {
        id: text(a.id, ID, `actions[${i}].id`),
        label: text(a.label, KEY, `actions[${i}].label`),
        style: oneOf(a.style, STYLES, 'default', `actions[${i}].style`),
        workingLabel: text(a.workingLabel, KEY, `actions[${i}].workingLabel`),
        confirmLabel:
          a.confirmLabel === undefined
            ? undefined
            : text(a.confirmLabel, KEY, `actions[${i}].confirmLabel`),
        complete: {} as Record<string, Completion>,
      },
    };
  });
  if (actions.length === 0) fail('actions must not be empty');
  const actionIds = new Set(actions.map((a) => a.action.id));
  if (actionIds.size !== actions.length) fail('action ids must be unique');

  const fields = list(root.fields, 'fields').map((item, i) => {
    const f = record(item, `fields[${i}]`, [
      'name',
      'label',
      'type',
      'format',
      'placeholder',
      'rows',
      'revealedBy',
      'requiredMessage',
    ]);
    const revealedBy =
      f.revealedBy === undefined ? undefined : text(f.revealedBy, ID, `fields[${i}].revealedBy`);
    if (revealedBy !== undefined && !actionIds.has(revealedBy)) {
      fail(`fields[${i}].revealedBy names no action`);
    }
    if (f.rows !== undefined && !(Number.isInteger(f.rows) && (f.rows as number) > 0)) {
      fail(`fields[${i}].rows must be a positive integer`);
    }
    return {
      name: text(f.name, NAME, `fields[${i}].name`),
      label: text(f.label, KEY, `fields[${i}].label`),
      type: oneOf(f.type, FIELD_TYPES, 'text', `fields[${i}].type`),
      format: oneOf(f.format, FORMATS, 'text', `fields[${i}].format`),
      placeholder:
        f.placeholder === undefined
          ? undefined
          : text(f.placeholder, KEY, `fields[${i}].placeholder`),
      rows: f.rows as number | undefined,
      revealedBy,
      requiredMessage:
        f.requiredMessage === undefined
          ? undefined
          : text(f.requiredMessage, KEY, `fields[${i}].requiredMessage`),
    };
  });
  const inputs = new Set(fields.filter((f) => f.type !== 'display').map((f) => f.name));

  for (const { raw: complete, action } of actions) {
    for (const [variable, entry] of Object.entries(complete)) {
      text(variable, NAME, `actions.${action.id}.complete key`);
      const c = record(entry, `actions.${action.id}.complete.${variable}`, [
        'value',
        'field',
        'type',
      ]);
      const type = oneOf(c.type, VARIABLE_TYPES, 'String', `${action.id}.${variable}.type`);
      if (c.field !== undefined) {
        const field = text(c.field, NAME, `${action.id}.${variable}.field`);
        if (!inputs.has(field)) fail(`${action.id}.${variable} takes a field that is no input`);
        action.complete[variable] = { field, type };
      } else if (['string', 'number', 'boolean'].includes(typeof c.value)) {
        action.complete[variable] = { value: c.value as string | number | boolean, type };
      } else {
        fail(`${action.id}.${variable} needs a value or a field`);
      }
    }
  }

  const notices = list(root.notices, 'notices').map((item, i) => {
    const n = record(item, `notices[${i}]`, ['label', 'variable', 'show']);
    return {
      label: text(n.label, KEY, `notices[${i}].label`),
      variable: text(n.variable, NAME, `notices[${i}].variable`),
      show: oneOf(n.show, SHOWS, 'always', `notices[${i}].show`),
    };
  });

  return {
    version: 1,
    form,
    process: text(root.process, NAME, 'process'),
    i18n: text(root.i18n, ID, 'i18n'),
    intro: {
      edit: text(intro.edit, KEY, 'intro.edit'),
      readOnly: text(intro.readOnly, KEY, 'intro.readOnly'),
    },
    summary,
    fields,
    notices,
    actions: actions.map((a) => a.action),
  };
}

/** Whether an element with this `show` rule is drawn. */
export function shown(show: Show, readOnly: boolean): boolean {
  return show === 'always' || (show === 'readOnly' ? readOnly : !readOnly);
}

/** True for a value worth drawing: not null, undefined or an empty string. */
export function present(value: unknown): boolean {
  return value !== null && value !== undefined && value !== '';
}

/** The fields an action reveals before it can be confirmed. */
export function revealedFields(definition: FormDefinition, actionId: string): Field[] {
  return definition.fields.filter((f) => f.revealedBy === actionId);
}

/**
 * The typed variables an action completes the task with, or the i18n key of
 * the first missing required input among the fields it uses.
 */
export function completion(
  definition: FormDefinition,
  action: Action,
  inputs: Record<string, string>,
): { variables: CamundaVariables } | { missing: string } {
  const variables: CamundaVariables = {};
  for (const [name, entry] of Object.entries(action.complete)) {
    if ('field' in entry) {
      const field = definition.fields.find((f) => f.name === entry.field);
      const raw = (inputs[entry.field] ?? '').trim();
      if (!raw && field?.requiredMessage) return { missing: field.requiredMessage };
      variables[name] = { value: convert(raw, entry.type), type: entry.type };
    } else {
      variables[name] = { value: entry.value, type: entry.type };
    }
  }
  return { variables };
}

function convert(raw: string, type: VariableType): string | number | boolean | null {
  if (type === 'String') return raw;
  if (raw === '') return null;
  if (type === 'Boolean') return raw === 'true';
  const n = Number(raw);
  return Number.isFinite(n) ? n : null;
}
