import { useEffect, useState } from 'react';
import type { TFunction } from 'i18next';
import FileUpload from '../../components/FileUpload';
import { formatCurrency, formatNumber } from '../../i18n/format';
import { interpolation, present, type Field, type Format } from './definition';
import type { Contact, InputValue, TableRow, UploadValue } from './values';

/** Where the backend serves registries (`RegistryController`). */
const REGISTRY_PATH = '/api/public/registry';

/** A variable as displayed: `—` when absent, otherwise per format. */
export function formatted(value: unknown, format: Format, t: TFunction): string {
  if (!present(value)) return '—';
  const n = Number(value);
  switch (format) {
    case 'currency':
      return Number.isFinite(n) ? formatCurrency(n) : String(value);
    case 'currencyWhole':
      return Number.isFinite(n)
        ? formatNumber(n, { style: 'currency', currency: 'EUR', maximumFractionDigits: 0 })
        : String(value);
    case 'number':
      return Number.isFinite(n) ? formatNumber(n) : String(value);
    case 'decision':
      return value === 'approve' ? t('common:status.approved') : t('common:status.sentBack');
    default:
      return String(value);
  }
}

interface InputProps {
  field: Field;
  value: InputValue;
  data: Record<string, unknown>;
  onChange: (value: InputValue) => void;
  readOnly: boolean;
  autoFocus: boolean;
  t: TFunction;
  /** The field's error message after a refused submit; marks the field. */
  message?: string;
}

/** The wrapper class of a field: its base class, plus `field-invalid` while it carries a message. */
function fieldClass(base: string, message?: string): string {
  return message ? `${base} field-invalid` : base;
}

function Message({ message }: { message?: string }) {
  return message ? <span className="field-message">{message}</span> : null;
}

/** One field of a definition, drawn with the forms' class contract. */
export function FieldInput(props: InputProps) {
  const { field, t } = props;
  if (field.type === 'file') return <FileField {...props} />;
  if (field.type === 'contacts') return <ContactsField {...props} />;
  if (field.type === 'radio') return <RadioField {...props} />;
  if (field.type === 'rows') return <RowsField {...props} />;
  return (
    <label className={fieldClass('field', props.message)}>
      <span className="field-label">{t(field.label)}</span>
      {field.hint && <span className="field-hint muted">{t(field.hint)}</span>}
      <Control {...props} />
      {field.identity && (
        <span className="field-hint muted">{t('common:identity.fromAccount')}</span>
      )}
      <Message message={props.message} />
    </label>
  );
}

function Control({ field, value, data, onChange, readOnly, autoFocus, t, message }: InputProps) {
  const invalid = message ? true : undefined;
  const text = typeof value === 'string' ? value : '';
  const placeholder = field.placeholder ? t(field.placeholder) : undefined;
  switch (field.type) {
    case 'display':
      return (
        <input
          className="field-input"
          value={formatted(data[field.name], field.format, t)}
          disabled
          readOnly
        />
      );
    case 'textarea':
      return (
        <textarea
          className="field-input"
          rows={field.rows ?? 3}
          value={text}
          onChange={(e) => onChange(e.target.value)}
          placeholder={placeholder}
          disabled={readOnly}
          autoFocus={autoFocus}
          aria-invalid={invalid}
        />
      );
    case 'select':
      return (
        <RegistrySelect
          field={field}
          value={text}
          onChange={onChange}
          readOnly={readOnly}
          placeholder={placeholder}
          invalid={invalid}
          t={t}
        />
      );
    default:
      if (field.type === 'text' && field.fixedLength) {
        return (
          <FixedLengthInput
            length={field.fixedLength}
            value={text}
            onChange={onChange}
            placeholder={placeholder}
            disabled={readOnly || !!field.identity}
            invalid={invalid}
          />
        );
      }
      return (
        <input
          className="field-input"
          type={field.type === 'number' ? 'number' : field.type === 'email' ? 'email' : 'text'}
          min={field.min}
          max={field.max}
          step={field.type === 'number' ? (field.step ?? (field.decimal ? 'any' : 1)) : undefined}
          value={text}
          onChange={(e) => onChange(e.target.value)}
          placeholder={placeholder}
          disabled={readOnly || field.identity}
          aria-invalid={invalid}
        />
      );
  }
}

/**
 * An input for a value of exactly `length` characters (a personal code). A
 * monospaced underlay repeats what was typed, invisibly, and then shows an
 * underscore for every character still missing, so the user sees at a glance
 * how many are left. While the field is empty and not focused the placeholder
 * shows instead. Codes read left to right, also in the Arabic layout.
 */
function FixedLengthInput({
  length,
  value,
  onChange,
  placeholder,
  disabled,
  invalid,
  numeric,
}: {
  length: number;
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  disabled: boolean;
  invalid?: boolean;
  numeric?: boolean;
}) {
  const [focused, setFocused] = useState(false);
  const showMask = focused || value.length > 0;
  return (
    <span className="fixed-length" dir="ltr">
      <span className="fixed-length-ghost" aria-hidden="true">
        {showMask && (
          <>
            <span className="fixed-length-typed">{value}</span>
            {'_'.repeat(Math.max(0, length - value.length))}
          </>
        )}
      </span>
      <input
        className="field-input"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        onFocus={() => setFocused(true)}
        onBlur={() => setFocused(false)}
        maxLength={length}
        inputMode={numeric ? 'numeric' : undefined}
        placeholder={showMask ? undefined : placeholder}
        disabled={disabled}
        aria-invalid={invalid}
      />
    </span>
  );
}

function RegistrySelect({
  field,
  value,
  onChange,
  readOnly,
  placeholder,
  invalid,
  t,
}: {
  field: Field;
  value: string;
  onChange: (value: InputValue) => void;
  readOnly: boolean;
  placeholder?: string;
  invalid?: boolean;
  t: TFunction;
}) {
  const source = field.source!;
  const [options, setOptions] = useState<{ value: string; label: string }[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    fetch(`${REGISTRY_PATH}/${encodeURIComponent(source.registry)}`, {
      headers: { Accept: 'application/json' },
    })
      .then(async (res) => {
        if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
        const entries = (await res.json()) as Record<string, unknown>[];
        if (cancelled) return;
        setOptions(
          entries.map((entry) => {
            const shown: Record<string, unknown> = { ...entry };
            for (const [prop, format] of Object.entries(source.formats)) {
              shown[prop] = formatted(entry[prop], format, t);
            }
            return {
              value: String(entry[source.value] ?? ''),
              label: t(source.label, interpolation(Object.keys(shown), shown)),
            };
          }),
        );
      })
      .catch((e: unknown) => {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e));
      });
    return () => {
      cancelled = true;
    };
  }, [source, t]);

  return (
    <>
      <select
        className="field-input"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        disabled={readOnly}
        aria-invalid={invalid}
      >
        <option value="">{placeholder ?? ''}</option>
        {options.map((o) => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
      </select>
      {error && !readOnly && (
        <span className="form-error">{t(source.error, { message: error })}</span>
      )}
    </>
  );
}

function FileField({ field, value, onChange, readOnly, t, message }: InputProps) {
  const file = field.file!;
  return (
    <div className={fieldClass('field', message)}>
      <span className="field-label">{t(field.label)}</span>
      {field.hint && <p className="field-hint">{t(field.hint)}</p>}
      <FileUpload
        accept={file.accept}
        maxBytes={file.maxBytes}
        scope="pending"
        category={file.category}
        value={value as UploadValue | null}
        onChange={(v) => onChange(v)}
        disabled={readOnly}
        label={t(file.dropLabel)}
      />
      <Message message={message} />
    </div>
  );
}

function ContactsField({ field, value, onChange, readOnly, t, message }: InputProps) {
  const c = field.contacts!;
  const rows: Contact[] = Array.isArray(value) ? (value as Contact[]) : [];
  const update = (index: number, key: keyof Contact, text: string) =>
    onChange(rows.map((row, i) => (i === index ? { ...row, [key]: text } : row)));

  return (
    <fieldset className={fieldClass('field-group', message)}>
      <legend className="field-label">{t(c.legend, { count: rows.length })}</legend>
      {field.hint && <p className="field-hint">{t(field.hint)}</p>}
      {rows.map((row, i) => (
        <div key={i} className="owner-row">
          <input
            className="field-input owner-row-name"
            placeholder={t(c.namePlaceholder)}
            value={row.name}
            onChange={(e) => update(i, 'name', e.target.value)}
            disabled={readOnly}
          />
          <input
            className="field-input owner-row-email"
            type="email"
            placeholder={t(c.emailPlaceholder)}
            value={row.email}
            onChange={(e) => update(i, 'email', e.target.value)}
            disabled={readOnly}
          />
          {!readOnly && (
            <button
              type="button"
              className="btn btn-link"
              onClick={() => onChange(rows.filter((_, j) => j !== i))}
              aria-label={t(c.removeAria, { index: i + 1 })}
            >
              {t('common:actions.remove')}
            </button>
          )}
        </div>
      ))}
      {!readOnly && (
        <div className="form-actions">
          <button
            type="button"
            className="btn"
            onClick={() => onChange([...rows, { name: '', email: '' }])}
          >
            {t(c.add)}
          </button>
        </div>
      )}
      <Message message={message} />
    </fieldset>
  );
}

function RadioField({ field, value, onChange, readOnly, t, message }: InputProps) {
  return (
    <fieldset className={fieldClass('field-group', message)}>
      <legend className="field-label">{t(field.label)}</legend>
      {field.hint && <p className="field-hint">{t(field.hint)}</p>}
      {field.options!.map((option) => (
        <label key={option.value} className="radio-row">
          <input
            type="radio"
            name={field.name}
            value={option.value}
            checked={value === option.value}
            onChange={() => onChange(option.value)}
            disabled={readOnly}
          />
          <span className="radio-body">
            <span className="radio-label">{t(option.label)}</span>
            {option.hint && <span className="radio-hint">{t(option.hint)}</span>}
          </span>
        </label>
      ))}
      <Message message={message} />
    </fieldset>
  );
}

function RowsField({ field, value, onChange, readOnly, t, message }: InputProps) {
  const table = field.table!;
  const rows: TableRow[] = Array.isArray(value) ? (value as TableRow[]) : [];
  const empty = (): TableRow => Object.fromEntries(table.columns.map((c) => [c.name, '']));
  const update = (index: number, column: string, text: string) =>
    onChange(rows.map((row, i) => (i === index ? { ...row, [column]: text } : row)));
  const remove = (index: number) => {
    const next = rows.filter((_, i) => i !== index);
    while (next.length < table.minRows) next.push(empty());
    onChange(next);
  };

  return (
    <fieldset className={fieldClass('field-group', message)}>
      <legend className="field-label">{t(table.legend)}</legend>
      {field.hint && <p className="field-hint">{t(field.hint)}</p>}
      {rows.map((row, i) => (
        <div key={i} className="board-member-row">
          {table.columns.map((column) =>
            column.fixedLength ? (
              <FixedLengthInput
                key={column.name}
                length={column.fixedLength}
                value={row[column.name] ?? ''}
                onChange={(text) => update(i, column.name, text)}
                placeholder={t(column.placeholder)}
                disabled={readOnly}
                numeric={column.format === 'personalCodeEE'}
              />
            ) : (
              <input
                key={column.name}
                className="field-input"
                placeholder={t(column.placeholder)}
                value={row[column.name] ?? ''}
                onChange={(e) => update(i, column.name, e.target.value)}
                maxLength={column.maxLength}
                disabled={readOnly}
              />
            ),
          )}
          {!readOnly && rows.length > table.minRows && (
            <button
              type="button"
              className="btn btn-small"
              onClick={() => remove(i)}
              aria-label={t(table.removeAria, { index: i + 1 })}
            >
              ×
            </button>
          )}
        </div>
      ))}
      {!readOnly && (
        <div className="form-actions">
          <button
            type="button"
            className="btn btn-small"
            onClick={() => onChange([...rows, empty()])}
          >
            {t(table.add)}
          </button>
        </div>
      )}
      <Message message={message} />
    </fieldset>
  );
}
