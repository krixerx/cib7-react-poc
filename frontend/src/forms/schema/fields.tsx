import { useEffect, useState } from 'react';
import type { TFunction } from 'i18next';
import FileUpload from '../../components/FileUpload';
import { formatCurrency, formatNumber } from '../../i18n/format';
import { interpolation, present, type Field, type Format } from './definition';
import type { Contact, InputValue, UploadValue } from './values';

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
}

/** One field of a definition, drawn with the forms' class contract. */
export function FieldInput(props: InputProps) {
  const { field, t } = props;
  if (field.type === 'file') return <FileField {...props} />;
  if (field.type === 'contacts') return <ContactsField {...props} />;
  return (
    <label className="field">
      <span className="field-label">{t(field.label)}</span>
      {field.hint && <span className="field-hint muted">{t(field.hint)}</span>}
      <Control {...props} />
      {field.identity && (
        <span className="field-hint muted">{t('common:identity.fromAccount')}</span>
      )}
    </label>
  );
}

function Control({ field, value, data, onChange, readOnly, autoFocus, t }: InputProps) {
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
          t={t}
        />
      );
    default:
      return (
        <input
          className="field-input"
          type={field.type === 'number' ? 'number' : field.type === 'email' ? 'email' : 'text'}
          min={field.min}
          max={field.max}
          value={text}
          onChange={(e) => onChange(e.target.value)}
          placeholder={placeholder}
          disabled={readOnly || field.identity}
        />
      );
  }
}

function RegistrySelect({
  field,
  value,
  onChange,
  readOnly,
  placeholder,
  t,
}: {
  field: Field;
  value: string;
  onChange: (value: InputValue) => void;
  readOnly: boolean;
  placeholder?: string;
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

function FileField({ field, value, onChange, readOnly, t }: InputProps) {
  const file = field.file!;
  return (
    <div className="field">
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
    </div>
  );
}

function ContactsField({ field, value, onChange, readOnly, t }: InputProps) {
  const c = field.contacts!;
  const rows: Contact[] = Array.isArray(value) ? value : [];
  const update = (index: number, key: keyof Contact, text: string) =>
    onChange(rows.map((row, i) => (i === index ? { ...row, [key]: text } : row)));

  return (
    <fieldset className="field-group">
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
    </fieldset>
  );
}
