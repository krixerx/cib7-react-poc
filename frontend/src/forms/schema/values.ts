import type { CamundaVariables } from '../../api/camundaClient';
import { listEntries, present, type Action, type Field, type FormDefinition } from './definition';

/** One co-owner or co-founder row as the form edits it. */
export interface Contact {
  name: string;
  email: string;
}

/** An upload as `FileUpload` reports it: fresh (pendingKey) or kept from an earlier round. */
export interface UploadValue {
  pendingKey?: string;
  attachmentId?: string;
  filename: string;
  contentType: string;
  size: number;
}

/** One row of a `rows` field, column name to text. */
export type TableRow = Record<string, string>;

/** What a field holds while the form is edited. */
export type InputValue = string | Contact[] | TableRow[] | UploadValue | null;

export type Inputs = Record<string, InputValue>;

/** A refused submission: the message key and its interpolation values. */
export interface FormError {
  key: string;
  values?: Record<string, string>;
}

/** A refused field: which field, and the first rule it breaks. */
export interface FieldError extends FormError {
  field: string;
}

/** Same rule as the engine's shared `email` definition (schemas/core-v1.json). */
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

/** Column formats, the same rules as the engine's shared definitions (schemas/core-v1.json). */
const COLUMN_RULES: Record<string, RegExp> = {
  personalCodeEE: /^[0-9]{11}$/,
};

function emptyRow(field: Field): TableRow {
  return Object.fromEntries(field.table!.columns.map((c) => [c.name, '']));
}

/**
 * Starting values of every input field, from the process variables. A file
 * field starts with the earlier round's upload when its attachment variable is
 * set (`existingName` names it), so a resubmission need not upload again.
 */
export function initialInputs(
  definition: FormDefinition,
  data: Record<string, unknown>,
  existingName: (key: string) => string,
): Inputs {
  const inputs: Inputs = {};
  for (const f of definition.fields) {
    if (f.type === 'display') continue;
    if (f.type === 'contacts') {
      inputs[f.name] = listEntries(data[f.name]).map((e) => ({
        name: typeof e.name === 'string' ? e.name : '',
        email: typeof e.email === 'string' ? e.email : '',
      }));
    } else if (f.type === 'rows') {
      const rows: TableRow[] = listEntries(data[f.name]).map((e) =>
        Object.fromEntries(
          f.table!.columns.map((c) => [
            c.name,
            typeof e[c.name] === 'string' ? (e[c.name] as string) : '',
          ]),
        ),
      );
      while (rows.length < f.table!.minRows) rows.push(emptyRow(f));
      inputs[f.name] = rows;
    } else if (f.type === 'radio') {
      const raw = data[f.name];
      const match = f.options!.find(
        (o) => typeof raw === 'string' && o.value === raw.toLowerCase(),
      );
      inputs[f.name] = match?.value ?? f.default ?? f.options![0].value;
    } else if (f.type === 'file') {
      const id = data[f.file!.existingVariable];
      inputs[f.name] =
        typeof id === 'string' && id.length > 0
          ? {
              attachmentId: id,
              filename: existingName(f.file!.existingFilename),
              contentType: 'application/octet-stream',
              size: 0,
            }
          : null;
    } else {
      inputs[f.name] = present(data[f.name]) ? String(data[f.name]) : (f.default ?? '');
    }
  }
  return inputs;
}

/** Table rows trimmed, with fully empty rows dropped. */
export function cleanedRows(field: Field, value: InputValue): TableRow[] {
  if (!Array.isArray(value)) return [];
  const columns = field.table!.columns.map((c) => c.name);
  return (value as TableRow[])
    .map((row) => Object.fromEntries(columns.map((c) => [c, (row[c] ?? '').trim()])))
    .filter((row) => columns.some((c) => row[c]));
}

/** A text with its suffix word appended unless it already contains it. */
export function withSuffix(value: string, suffix: string): string {
  if (!value) return value;
  const escaped = suffix.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  return new RegExp(`(^|\\s)${escaped}($|\\s)`, 'iu').test(value) ? value : `${value} ${suffix}`;
}

/** Contact rows trimmed, with fully empty rows dropped. */
export function cleanedContacts(value: InputValue): Contact[] {
  if (!Array.isArray(value)) return [];
  return value
    .map((c) => ({ name: c.name.trim(), email: c.email.trim() }))
    .filter((c) => c.name || c.email);
}

function textOf(value: InputValue): string {
  return typeof value === 'string' ? value.trim() : '';
}

/** The first rule a field breaks, checked the way the former TSX forms checked them. */
function check(field: Field, inputs: Inputs): FormError | null {
  const value = inputs[field.name];
  switch (field.type) {
    case 'contacts': {
      const c = field.contacts!;
      const rows = cleanedContacts(value);
      for (const row of rows) {
        if (!row.name) return { key: c.nameMessage };
        if (!EMAIL.test(row.email)) return { key: c.emailMessage, values: { name: row.name } };
      }
      const emails = rows.map((r) => r.email.toLowerCase());
      const duplicate = emails.find((e, i) => emails.indexOf(e) !== i);
      if (duplicate) return { key: c.duplicateMessage, values: { email: duplicate } };
      if (c.notField) {
        const own = textOf(inputs[c.notField.field]).toLowerCase();
        if (own && emails.includes(own)) return { key: c.notField.message };
      }
      return null;
    }
    case 'file': {
      const upload = value as UploadValue | null;
      if (field.requiredMessage && !(upload && (upload.pendingKey || upload.attachmentId))) {
        return { key: field.requiredMessage };
      }
      return null;
    }
    case 'number': {
      const raw = textOf(value);
      if (!raw) return field.requiredMessage ? { key: field.requiredMessage } : null;
      const n = Number(raw);
      const whole = field.decimal || Number.isInteger(n);
      const inRange =
        Number.isFinite(n) && n >= (field.min ?? -Infinity) && n <= (field.max ?? Infinity);
      if (field.rangeMessage && !(whole && inRange)) return { key: field.rangeMessage };
      return null;
    }
    case 'rows': {
      const table = field.table!;
      const rows = cleanedRows(field, value);
      if (rows.length === 0) return { key: table.requiredMessage };
      for (const row of rows) {
        if (table.columns.some((c) => !row[c.name])) return { key: table.incompleteMessage };
        const broken = table.columns.find(
          (c) => c.format && !COLUMN_RULES[c.format].test(row[c.name]),
        );
        if (broken) return { key: table.formatMessage, values: row };
      }
      return null;
    }
    case 'radio':
      return null;
    case 'email': {
      const raw = textOf(value);
      if (field.requiredWhenListed) {
        const listed = cleanedContacts(inputs[field.requiredWhenListed.field]).length > 0;
        if (listed && !EMAIL.test(raw)) return { key: field.requiredWhenListed.message };
      }
      if (!raw) return field.requiredMessage ? { key: field.requiredMessage } : null;
      if (field.emailMessage && !EMAIL.test(raw)) return { key: field.emailMessage };
      return null;
    }
    default: {
      const raw = textOf(value);
      const final = field.suffix ? withSuffix(raw, field.suffix) : raw;
      if (!final && field.requiredMessage) return { key: field.requiredMessage };
      return null;
    }
  }
}

/**
 * The typed variables an action completes the task with, or every field that
 * needs attention, each with the first rule it breaks, so the form can mark
 * them all at once. Only the fields the action completes with are checked, in
 * the order the definition lists them; the engine checks the values again
 * against the form's value schema.
 */
export function completion(
  definition: FormDefinition,
  action: Action,
  inputs: Inputs,
): { variables: CamundaVariables } | { errors: FieldError[] } {
  const used = new Set(
    Object.values(action.complete).flatMap((c) => ('field' in c ? [c.field] : [])),
  );
  const errors: FieldError[] = [];
  for (const field of definition.fields) {
    if (!used.has(field.name)) continue;
    const error = check(field, inputs);
    if (error) errors.push({ field: field.name, ...error });
  }
  if (errors.length > 0) return { errors };
  const variables: CamundaVariables = {};
  for (const [name, entry] of Object.entries(action.complete)) {
    if (!('field' in entry)) {
      variables[name] = { value: entry.value, type: entry.type };
      continue;
    }
    const field = definition.fields.find((f) => f.name === entry.field)!;
    const value = inputs[entry.field];
    if (field.type === 'contacts') {
      variables[name] = { value: JSON.stringify(cleanedContacts(value)), type: 'Json' };
    } else if (field.type === 'rows') {
      variables[name] = { value: JSON.stringify(cleanedRows(field, value)), type: 'Json' };
    } else if (field.suffix) {
      variables[name] = { value: withSuffix(textOf(value), field.suffix), type: entry.type };
    } else if (field.type === 'file') {
      const upload = value as UploadValue | null;
      variables[name] = {
        value: upload?.pendingKey
          ? JSON.stringify({
              pendingKey: upload.pendingKey,
              filename: upload.filename,
              contentType: upload.contentType,
            })
          : null,
        type: 'Json',
      };
    } else {
      variables[name] = { value: convert(textOf(value), entry.type), type: entry.type };
    }
  }
  return { variables };
}

function convert(raw: string, type: string): string | number | boolean | null {
  if (type === 'String') return raw;
  if (raw === '') return null;
  if (type === 'Boolean') return raw === 'true';
  const n = Number(raw);
  return Number.isFinite(n) ? n : null;
}
