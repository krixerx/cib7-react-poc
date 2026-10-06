/**
 * Form definition format v1: what the service builder emits from a
 * `forms/<id>.md` spec with `Renderer: schema`, and what {@link SchemaForm}
 * draws. Part of the platform API: a pack holds these JSON files instead of
 * React code, so a core update cannot break a customer's form.
 *
 * Texts are i18n keys in the definition's own namespace (`i18n`), so `en` and
 * `ar` stay in the locale files. The parser below is strict on purpose: a
 * definition outside this shape is refused as a whole instead of half-drawn.
 * New elements are added as optional keys, so every older definition stays
 * valid (the format stays v1).
 */
export interface FormDefinition {
  version: 1;
  /** Form id = the BPMN formKey without `react:`. */
  form: string;
  /** BPMN process id the form belongs to. */
  process: string;
  /** i18n namespace holding every key below. */
  i18n: string;
  intro: { edit: string; readOnly: string; resubmission?: string };
  /**
   * The variable whose value marks a resubmission (a send-back reason): while
   * it is set and the task is open, the resubmission intro, the banner and the
   * actions' `resubmitLabel` are used.
   */
  resubmittedWhen?: string;
  /** Warning banner on a resubmission: a title and the variable's text. */
  banner?: { title: string; variable: string };
  summary: SummaryItem[];
  fields: Field[];
  notices: Notice[];
  actions: Action[];
}

/** When an element is shown: always, only on a finished case, or only while the task is open. */
export type Show = 'always' | 'readOnly' | 'editing';

/** How a value is displayed. `currencyWhole` drops the cents. */
export type Format = 'text' | 'number' | 'currency' | 'currencyWhole' | 'decision';

/**
 * A read-only `label: value` row above the fields. Besides a plain variable
 * (`format`), a row can be:
 * - `template`: one text key interpolating several variables, e.g. a name and an age;
 * - `options`: a coded value shown as the text key mapped to it, e.g. `citizen`;
 * - `item`: a list variable shown as a bulleted list, each entry through a text
 *   key that interpolates the entry's own properties.
 */
export interface SummaryItem {
  label: string;
  /** The variable shown; for a template row, the first of `variables`. */
  variable: string;
  format: Format;
  show: Show;
  template?: { key: string; variables: string[] };
  options?: Record<string, string>;
  item?: string;
}

export type FieldType =
  | 'display'
  | 'text'
  | 'textarea'
  | 'number'
  | 'email'
  | 'select'
  | 'file'
  | 'contacts'
  | 'radio'
  | 'rows';

/** Value checks a `rows` column can apply; the same rules as the engine's shared definitions. */
export const COLUMN_FORMATS = ['personalCodeEE'] as const;
export type ColumnFormat = (typeof COLUMN_FORMATS)[number];

/** A field. `display` shows a variable; every other type takes input. */
export interface Field {
  name: string;
  label: string;
  type: FieldType;
  format: Format;
  /** A hint line under the label. */
  hint?: string;
  placeholder?: string;
  rows?: number;
  /** Filled from the signed-in account: shown disabled with a "from your account" hint. */
  identity?: boolean;
  min?: number;
  max?: number;
  /** `number`: accept fractions; without it the value must be a whole number. */
  decimal?: boolean;
  /** `number`: the input's step. */
  step?: number;
  /** Starting value when the variable is not set yet (`text`, `number`, `radio`). */
  default?: string;
  /** `text`: a word appended on submit when the value does not contain it, e.g. `OÜ`. */
  suffix?: string;
  /**
   * `text`: the value has exactly this many characters; the input shows an
   * underscore for each one still missing.
   */
  fixedLength?: number;
  /** Shown only after the action with this id was pressed (a two-step action). */
  revealedBy?: string;
  /** Message when the field is empty on submit; absent = optional. */
  requiredMessage?: string;
  /** `number`: message when the value is below min, above max, or (unless decimal) not whole. */
  rangeMessage?: string;
  /** `email`: message when a non-empty value is no email address. */
  emailMessage?: string;
  /** `email`: required (and valid) as soon as this contacts field has a row. */
  requiredWhenListed?: { field: string; message: string };
  /** `select`: options read from a backend registry. */
  source?: {
    registry: string;
    value: string;
    /** Text key interpolating the entry's properties. */
    label: string;
    /** Formats applied to entry properties before interpolation. */
    formats: Record<string, Format>;
    /** Message when the registry cannot be read (`{{message}}`). */
    error: string;
  };
  /** `file`: one upload. */
  file?: {
    /**
     * An applicant upload category of the pack (`backend/documents.json`); the backend refuses any
     * other, and the pack tests check it.
     */
    category: string;
    accept: string;
    maxBytes: number;
    dropLabel: string;
    /** Variable holding the attachment id of an earlier round's upload. */
    existingVariable: string;
    /** Text key naming that earlier upload. */
    existingFilename: string;
  };
  /** `contacts`: repeating `{name, email}` rows (co-owners, co-founders). */
  contacts?: {
    /** Legend text key; `{{count}}` is the number of rows. */
    legend: string;
    namePlaceholder: string;
    emailPlaceholder: string;
    add: string;
    /** Aria label of a row's remove button; `{{index}}` is the row number. */
    removeAria: string;
    nameMessage: string;
    /** `{{name}}` is the row's name. */
    emailMessage: string;
    /** `{{email}}` is the repeated address. */
    duplicateMessage: string;
    /** The rows must not repeat this email field's value. */
    notField?: { field: string; message: string };
  };
  /** `radio`: one choice out of these, each with a label and an optional hint. */
  options?: { value: string; label: string; hint?: string }[];
  /** `rows`: repeating rows of text columns (board members). */
  table?: {
    legend: string;
    add: string;
    /** Aria label of a row's remove button; `{{index}}` is the row number. */
    removeAria: string;
    /** Rows always shown, even when empty; removing never goes below it. */
    minRows: number;
    columns: {
      name: string;
      placeholder: string;
      format?: ColumnFormat;
      maxLength?: number;
      /** Exactly this many characters; underscores show the missing ones. */
      fixedLength?: number;
    }[];
    /** Message when every row is empty. */
    requiredMessage: string;
    /** Message when a row has an empty column. */
    incompleteMessage: string;
    /** Message when a column breaks its format; the row's values interpolate by column name. */
    formatMessage: string;
  };
}

/** A hint line showing a variable's value next to a label, for example a previous reason. */
export interface Notice {
  label: string;
  variable: string;
  show: Show;
}

export type VariableType = 'String' | 'Integer' | 'Double' | 'Boolean' | 'Json';

/** One variable an action completes the task with: a fixed value, or a field's input. */
export type Completion =
  { value: string | number | boolean; type: VariableType } | { field: string; type: VariableType };

export interface Action {
  id: string;
  label: string;
  /** Label on a resubmission (see `resubmittedWhen`). */
  resubmitLabel?: string;
  style: 'primary' | 'danger' | 'default';
  workingLabel: string;
  /** Label of the second, confirming button when the action reveals fields. */
  confirmLabel?: string;
  complete: Record<string, Completion>;
}

const ID = /^[a-z][a-z0-9-]{0,62}$/;
const NAME = /^[A-Za-z][A-Za-z0-9_]{0,62}$/;
const KEY = /^([a-z][a-z0-9-]*:)?[A-Za-z][A-Za-z0-9_.]{0,120}$/;
const SUFFIX = /^[\p{L}\p{N}.]{1,10}$/u;
const OPTION = /^[a-z0-9][a-z0-9-]{0,62}$/;
const ACCEPT =
  /^(application\/pdf|image\/jpeg|image\/png)(,(application\/pdf|image\/jpeg|image\/png))*$/;
const SHOWS: Show[] = ['always', 'readOnly', 'editing'];
const FORMATS: Format[] = ['text', 'number', 'currency', 'currencyWhole', 'decision'];
const FIELD_TYPES: FieldType[] = [
  'display',
  'text',
  'textarea',
  'number',
  'email',
  'select',
  'file',
  'contacts',
  'radio',
  'rows',
];
const STYLES: Action['style'][] = ['primary', 'danger', 'default'];
const VARIABLE_TYPES: VariableType[] = ['String', 'Integer', 'Double', 'Boolean', 'Json'];
const MAX_UPLOAD = 25 * 1024 * 1024;

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

/** A mapping whose keys are free (checked by the caller), not a fixed set. */
function mapping(value: unknown, what: string): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    fail(`${what} must be an object`);
  }
  return value as Record<string, unknown>;
}

function text(value: unknown, pattern: RegExp, what: string): string {
  if (typeof value !== 'string' || !pattern.test(value)) fail(`${what} is invalid`);
  return value;
}

function optionalText(value: unknown, pattern: RegExp, what: string): string | undefined {
  return value === undefined ? undefined : text(value, pattern, what);
}

function oneOf<T extends string>(
  value: unknown,
  allowed: readonly T[],
  fallback: T,
  what: string,
): T {
  if (value === undefined) return fallback;
  if (!allowed.includes(value as T)) fail(`${what} must be one of ${allowed.join(', ')}`);
  return value as T;
}

function integer(value: unknown, what: string, min: number, max: number): number {
  if (!Number.isInteger(value) || (value as number) < min || (value as number) > max) {
    fail(`${what} must be an integer from ${min} to ${max}`);
  }
  return value as number;
}

function list(value: unknown, what: string): unknown[] {
  if (value === undefined) return [];
  if (!Array.isArray(value)) fail(`${what} must be a list`);
  return value;
}

function only(field: Record<string, unknown>, type: FieldType, keys: string[], what: string) {
  for (const key of keys) {
    if (field[key] !== undefined && field.type !== type) fail(`${what}.${key} needs type ${type}`);
  }
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
    'resubmittedWhen',
    'banner',
    'summary',
    'fields',
    'notices',
    'actions',
  ]);
  if (root.version !== 1) fail('version must be 1');
  const form = text(root.form, ID, 'form');
  if (form !== expectedForm) fail(`definition is for '${form}', not '${expectedForm}'`);
  const intro = record(root.intro, 'intro', ['edit', 'readOnly', 'resubmission']);
  const resubmittedWhen = optionalText(root.resubmittedWhen, NAME, 'resubmittedWhen');
  let banner: FormDefinition['banner'];
  if (root.banner !== undefined) {
    const b = record(root.banner, 'banner', ['title', 'variable']);
    banner = {
      title: text(b.title, KEY, 'banner.title'),
      variable: text(b.variable, NAME, 'banner.variable'),
    };
  }
  if ((intro.resubmission !== undefined || banner) && !resubmittedWhen) {
    fail('a resubmission intro or banner needs resubmittedWhen');
  }

  const summary = list(root.summary, 'summary').map((item, i): SummaryItem => {
    const what = `summary[${i}]`;
    const s = record(item, what, [
      'label',
      'variable',
      'format',
      'show',
      'template',
      'variables',
      'options',
      'item',
    ]);
    const kinds = ['template', 'options', 'item'].filter((k) => s[k] !== undefined);
    if (kinds.length > 1) fail(`${what} may use only one of template, options, item`);
    const base = {
      label: text(s.label, KEY, `${what}.label`),
      format: oneOf(s.format, FORMATS, 'text', `${what}.format`),
      show: oneOf(s.show, SHOWS, 'always', `${what}.show`),
    };
    if (s.template !== undefined) {
      if (s.variable !== undefined) fail(`${what} with a template names its variables instead`);
      const variables = list(s.variables, `${what}.variables`).map((v, j) =>
        text(v, NAME, `${what}.variables[${j}]`),
      );
      if (variables.length === 0) fail(`${what}.variables must not be empty`);
      return {
        ...base,
        variable: variables[0],
        template: { key: text(s.template, KEY, `${what}.template`), variables },
      };
    }
    if (s.variables !== undefined) fail(`${what}.variables needs a template`);
    const variable = text(s.variable, NAME, `${what}.variable`);
    if (s.options !== undefined) {
      const options = mapping(s.options, `${what}.options`);
      const mapped: Record<string, string> = {};
      for (const [value, key] of Object.entries(options)) {
        mapped[text(value, ID, `${what}.options key`)] = text(key, KEY, `${what}.options.${value}`);
      }
      if (Object.keys(mapped).length === 0) fail(`${what}.options must not be empty`);
      return { ...base, variable, options: mapped };
    }
    if (s.item !== undefined) {
      return { ...base, variable, item: text(s.item, KEY, `${what}.item`) };
    }
    return { ...base, variable };
  });

  const actions = list(root.actions, 'actions').map((item, i) => {
    const a = record(item, `actions[${i}]`, [
      'id',
      'label',
      'resubmitLabel',
      'style',
      'workingLabel',
      'confirmLabel',
      'complete',
    ]);
    return {
      raw: mapping(a.complete, `actions[${i}].complete`),
      action: {
        id: text(a.id, ID, `actions[${i}].id`),
        label: text(a.label, KEY, `actions[${i}].label`),
        resubmitLabel: optionalText(a.resubmitLabel, KEY, `actions[${i}].resubmitLabel`),
        style: oneOf(a.style, STYLES, 'default', `actions[${i}].style`),
        workingLabel: text(a.workingLabel, KEY, `actions[${i}].workingLabel`),
        confirmLabel: optionalText(a.confirmLabel, KEY, `actions[${i}].confirmLabel`),
        complete: {} as Record<string, Completion>,
      },
    };
  });
  if (actions.length === 0) fail('actions must not be empty');
  const actionIds = new Set(actions.map((a) => a.action.id));
  if (actionIds.size !== actions.length) fail('action ids must be unique');

  const fields = list(root.fields, 'fields').map((item, i): Field => {
    const what = `fields[${i}]`;
    const f = record(item, what, [
      'name',
      'label',
      'type',
      'format',
      'hint',
      'placeholder',
      'rows',
      'identity',
      'min',
      'max',
      'decimal',
      'step',
      'default',
      'suffix',
      'fixedLength',
      'options',
      'table',
      'revealedBy',
      'requiredMessage',
      'rangeMessage',
      'emailMessage',
      'requiredWhenListed',
      'source',
      'file',
      'contacts',
    ]);
    const type = oneOf(f.type, FIELD_TYPES, 'text', `${what}.type`);
    only(f, 'number', ['min', 'max', 'rangeMessage', 'decimal', 'step'], what);
    only(f, 'text', ['suffix', 'fixedLength'], what);
    only(f, 'radio', ['options'], what);
    only(f, 'rows', ['table'], what);
    if (f.default !== undefined && !['text', 'number', 'radio'].includes(type)) {
      fail(`${what}.default needs type text, number or radio`);
    }
    if (f.decimal !== undefined && f.decimal !== true) fail(`${what}.decimal must be true`);
    only(f, 'email', ['emailMessage', 'requiredWhenListed'], what);
    only(f, 'select', ['source'], what);
    only(f, 'file', ['file'], what);
    only(f, 'contacts', ['contacts'], what);
    only(f, 'textarea', ['rows'], what);
    if (f.identity !== undefined && (f.identity !== true || !['text', 'email'].includes(type))) {
      fail(`${what}.identity must be true on a text or email field`);
    }
    const revealedBy = optionalText(f.revealedBy, ID, `${what}.revealedBy`);
    if (revealedBy !== undefined && !actionIds.has(revealedBy))
      fail(`${what}.revealedBy names no action`);
    const field: Field = {
      name: text(f.name, NAME, `${what}.name`),
      label: text(f.label, KEY, `${what}.label`),
      type,
      format: oneOf(f.format, FORMATS, 'text', `${what}.format`),
      hint: optionalText(f.hint, KEY, `${what}.hint`),
      placeholder: optionalText(f.placeholder, KEY, `${what}.placeholder`),
      rows: f.rows === undefined ? undefined : integer(f.rows, `${what}.rows`, 1, 50),
      identity: f.identity === true ? true : undefined,
      min: f.min === undefined ? undefined : integer(f.min, `${what}.min`, -1e9, 1e9),
      max: f.max === undefined ? undefined : integer(f.max, `${what}.max`, -1e9, 1e9),
      decimal: f.decimal === true ? true : undefined,
      step: f.step === undefined ? undefined : integer(f.step, `${what}.step`, 1, 1e9),
      default:
        f.default === undefined
          ? undefined
          : typeof f.default === 'string' && f.default.length <= 200
            ? f.default
            : fail(`${what}.default must be a text of at most 200 characters`),
      suffix: optionalText(f.suffix, SUFFIX, `${what}.suffix`),
      fixedLength:
        f.fixedLength === undefined
          ? undefined
          : integer(f.fixedLength, `${what}.fixedLength`, 1, 64),
      revealedBy,
      requiredMessage: optionalText(f.requiredMessage, KEY, `${what}.requiredMessage`),
      rangeMessage: optionalText(f.rangeMessage, KEY, `${what}.rangeMessage`),
      emailMessage: optionalText(f.emailMessage, KEY, `${what}.emailMessage`),
    };
    if (type === 'number' && field.rangeMessage && field.min === undefined) {
      fail(`${what}.rangeMessage needs min`);
    }
    if (type === 'radio') {
      const options = list(f.options, `${what}.options`).map((o, j) => {
        const opt = record(o, `${what}.options[${j}]`, ['value', 'label', 'hint']);
        return {
          value: text(opt.value, OPTION, `${what}.options[${j}].value`),
          label: text(opt.label, KEY, `${what}.options[${j}].label`),
          hint: optionalText(opt.hint, KEY, `${what}.options[${j}].hint`),
        };
      });
      if (options.length < 2) fail(`${what}.options needs at least two choices`);
      if (new Set(options.map((o) => o.value)).size !== options.length) {
        fail(`${what}.options values must be unique`);
      }
      if (field.default !== undefined && !options.some((o) => o.value === field.default)) {
        fail(`${what}.default must be one of the options`);
      }
      field.options = options;
    }
    if (type === 'rows') {
      const r = record(f.table, `${what}.table`, [
        'legend',
        'add',
        'removeAria',
        'minRows',
        'columns',
        'requiredMessage',
        'incompleteMessage',
        'formatMessage',
      ]);
      const columns = list(r.columns, `${what}.table.columns`).map((c, j) => {
        const col = record(c, `${what}.table.columns[${j}]`, [
          'name',
          'placeholder',
          'format',
          'maxLength',
          'fixedLength',
        ]);
        return {
          name: text(col.name, NAME, `${what}.table.columns[${j}].name`),
          maxLength:
            col.maxLength === undefined
              ? undefined
              : integer(col.maxLength, `${what}.table.columns[${j}].maxLength`, 1, 500),
          fixedLength:
            col.fixedLength === undefined
              ? undefined
              : integer(col.fixedLength, `${what}.table.columns[${j}].fixedLength`, 1, 64),
          placeholder: text(col.placeholder, KEY, `${what}.table.columns[${j}].placeholder`),
          format:
            col.format === undefined
              ? undefined
              : oneOf(
                  col.format,
                  COLUMN_FORMATS,
                  COLUMN_FORMATS[0],
                  `${what}.table.columns[${j}].format`,
                ),
        };
      });
      if (columns.length === 0) fail(`${what}.table.columns must not be empty`);
      if (new Set(columns.map((c) => c.name)).size !== columns.length) {
        fail(`${what}.table.columns names must be unique`);
      }
      field.table = {
        legend: text(r.legend, KEY, `${what}.table.legend`),
        add: text(r.add, KEY, `${what}.table.add`),
        removeAria: text(r.removeAria, KEY, `${what}.table.removeAria`),
        minRows: integer(r.minRows, `${what}.table.minRows`, 0, 20),
        columns,
        requiredMessage: text(r.requiredMessage, KEY, `${what}.table.requiredMessage`),
        incompleteMessage: text(r.incompleteMessage, KEY, `${what}.table.incompleteMessage`),
        formatMessage: text(r.formatMessage, KEY, `${what}.table.formatMessage`),
      };
    }
    if (f.requiredWhenListed !== undefined) {
      const r = record(f.requiredWhenListed, `${what}.requiredWhenListed`, ['field', 'message']);
      field.requiredWhenListed = {
        field: text(r.field, NAME, `${what}.requiredWhenListed.field`),
        message: text(r.message, KEY, `${what}.requiredWhenListed.message`),
      };
    }
    if (type === 'select') {
      const s = record(f.source, `${what}.source`, [
        'registry',
        'value',
        'label',
        'formats',
        'error',
      ]);
      const formats: Record<string, Format> = {};
      if (s.formats !== undefined) {
        for (const [prop, format] of Object.entries(mapping(s.formats, `${what}.source.formats`))) {
          formats[text(prop, NAME, `${what}.source.formats key`)] = oneOf(
            format,
            FORMATS,
            'text',
            `${what}.source.formats.${prop}`,
          );
        }
      }
      field.source = {
        registry: text(s.registry, ID, `${what}.source.registry`),
        value: text(s.value, NAME, `${what}.source.value`),
        label: text(s.label, KEY, `${what}.source.label`),
        formats,
        error: text(s.error, KEY, `${what}.source.error`),
      };
    }
    if (type === 'file') {
      const u = record(f.file, `${what}.file`, [
        'category',
        'accept',
        'maxBytes',
        'dropLabel',
        'existingVariable',
        'existingFilename',
      ]);
      field.file = {
        category: text(u.category, ID, `${what}.file.category`),
        accept: text(u.accept, ACCEPT, `${what}.file.accept`),
        maxBytes: integer(u.maxBytes, `${what}.file.maxBytes`, 1, MAX_UPLOAD),
        dropLabel: text(u.dropLabel, KEY, `${what}.file.dropLabel`),
        existingVariable: text(u.existingVariable, NAME, `${what}.file.existingVariable`),
        existingFilename: text(u.existingFilename, KEY, `${what}.file.existingFilename`),
      };
    }
    if (type === 'contacts') {
      const c = record(f.contacts, `${what}.contacts`, [
        'legend',
        'namePlaceholder',
        'emailPlaceholder',
        'add',
        'removeAria',
        'nameMessage',
        'emailMessage',
        'duplicateMessage',
        'notField',
      ]);
      field.contacts = {
        legend: text(c.legend, KEY, `${what}.contacts.legend`),
        namePlaceholder: text(c.namePlaceholder, KEY, `${what}.contacts.namePlaceholder`),
        emailPlaceholder: text(c.emailPlaceholder, KEY, `${what}.contacts.emailPlaceholder`),
        add: text(c.add, KEY, `${what}.contacts.add`),
        removeAria: text(c.removeAria, KEY, `${what}.contacts.removeAria`),
        nameMessage: text(c.nameMessage, KEY, `${what}.contacts.nameMessage`),
        emailMessage: text(c.emailMessage, KEY, `${what}.contacts.emailMessage`),
        duplicateMessage: text(c.duplicateMessage, KEY, `${what}.contacts.duplicateMessage`),
      };
      if (c.notField !== undefined) {
        const n = record(c.notField, `${what}.contacts.notField`, ['field', 'message']);
        field.contacts.notField = {
          field: text(n.field, NAME, `${what}.contacts.notField.field`),
          message: text(n.message, KEY, `${what}.contacts.notField.message`),
        };
      }
    }
    return field;
  });

  const byName = new Map(fields.map((f) => [f.name, f]));
  if (byName.size !== fields.length) fail('field names must be unique');
  for (const f of fields) {
    if (f.requiredWhenListed && byName.get(f.requiredWhenListed.field)?.type !== 'contacts') {
      fail(`${f.name}.requiredWhenListed must name a contacts field`);
    }
    if (f.contacts?.notField && byName.get(f.contacts.notField.field)?.type !== 'email') {
      fail(`${f.name}.contacts.notField must name an email field`);
    }
  }

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
        const name = text(c.field, NAME, `${action.id}.${variable}.field`);
        const field = byName.get(name);
        if (!field || field.type === 'display') {
          fail(`${action.id}.${variable} takes a field that is no input`);
        }
        const json = ['contacts', 'file', 'rows'].includes(field.type);
        if (json !== (type === 'Json')) {
          fail(
            `${action.id}.${variable}: contacts, file and rows fields complete as Json, others not`,
          );
        }
        action.complete[variable] = { field: name, type };
      } else if (['string', 'number', 'boolean'].includes(typeof c.value) && type !== 'Json') {
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
      resubmission: optionalText(intro.resubmission, KEY, 'intro.resubmission'),
    },
    resubmittedWhen,
    banner,
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

/** True when a summary row has something to show: any of its template variables, or its variable. */
export function summaryPresent(item: SummaryItem, data: Record<string, unknown>): boolean {
  return item.template
    ? item.template.variables.some((v) => present(data[v]))
    : present(data[item.variable]);
}

/**
 * The entries of a list variable: a JSON array as it comes from history or a
 * Json variable, or a JSON string of one. Anything else is an empty list.
 */
export function listEntries(value: unknown): Record<string, unknown>[] {
  let parsed = value;
  if (typeof value === 'string' && value.trim()) {
    try {
      parsed = JSON.parse(value);
    } catch {
      return [];
    }
  }
  return Array.isArray(parsed)
    ? parsed.filter((e): e is Record<string, unknown> => typeof e === 'object' && e !== null)
    : [];
}

/** The interpolation values for a text key: each named variable, `—` when absent. */
export function interpolation(
  names: string[],
  source: Record<string, unknown>,
): Record<string, string> {
  return Object.fromEntries(names.map((n) => [n, present(source[n]) ? String(source[n]) : '—']));
}

/** The fields an action reveals before it can be confirmed. */
export function revealedFields(definition: FormDefinition, actionId: string): Field[] {
  return definition.fields.filter((f) => f.revealedBy === actionId);
}

/** True while the task is open and the definition's resubmission marker is set. */
export function isResubmission(
  definition: FormDefinition,
  data: Record<string, unknown>,
  readOnly: boolean,
): boolean {
  return !readOnly && !!definition.resubmittedWhen && present(data[definition.resubmittedWhen]);
}
