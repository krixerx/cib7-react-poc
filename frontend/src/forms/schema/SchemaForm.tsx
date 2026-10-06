import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { TFunction } from 'i18next';
import type { FormProps } from '../types';
import { formatCurrency, formatNumber } from '../../i18n/format';
import {
  completion,
  interpolation,
  InvalidDefinition,
  listEntries,
  parseDefinition,
  present,
  revealedFields,
  shown,
  summaryPresent,
  type Action,
  type Format,
  type FormDefinition,
  type SummaryItem,
} from './definition';

/** Where the service pack's form definitions are served (nginx in the image, Vite in dev). */
export const DEFINITIONS_PATH = '/pack/forms';

type Loaded =
  | { state: 'loading' }
  | { state: 'missing' }
  | { state: 'invalid'; message: string }
  | { state: 'ready'; definition: FormDefinition };

/**
 * Draws any task form from its JSON definition (format v1, `definition.ts`)
 * instead of form-specific React code, so a service pack holds data only.
 * Uses the same class contract as the generated TSX forms (`form`, `field`,
 * `field-input`, `summary`, `btn`, `form-error`), so it looks the same.
 *
 * An action whose fields are revealed by it (for example "Send back…" with a
 * reason) works in two steps: the first press shows the fields and a confirm
 * and a cancel button; the confirm completes the task.
 */
export default function SchemaForm({
  formId,
  data,
  onComplete,
  submitting,
  readOnly = false,
}: FormProps & { formId: string }) {
  const [loaded, setLoaded] = useState<Loaded>({ state: 'loading' });

  useEffect(() => {
    let cancelled = false;
    fetch(`${DEFINITIONS_PATH}/${encodeURIComponent(formId)}.json`, {
      headers: { Accept: 'application/json' },
    })
      .then(async (res) => {
        if (cancelled) return;
        if (!res.ok) {
          setLoaded({ state: 'missing' });
          return;
        }
        const definition = parseDefinition(await res.json(), formId);
        if (!cancelled) setLoaded({ state: 'ready', definition });
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        setLoaded(
          e instanceof InvalidDefinition
            ? { state: 'invalid', message: e.message }
            : { state: 'missing' },
        );
      });
    return () => {
      cancelled = true;
    };
  }, [formId]);

  if (loaded.state !== 'ready') {
    return <DefinitionStatus formId={formId} loaded={loaded} />;
  }
  return (
    <DefinedForm
      definition={loaded.definition}
      data={data}
      onComplete={onComplete}
      submitting={submitting}
      readOnly={readOnly}
    />
  );
}

function DefinitionStatus({ formId, loaded }: { formId: string; loaded: Loaded }) {
  const { t } = useTranslation('common');
  if (loaded.state === 'loading') return <p className="muted">{t('schemaForm.loading')}</p>;
  if (loaded.state === 'invalid') {
    return (
      <p className="form-error">{t('schemaForm.invalid', { formId, message: loaded.message })}</p>
    );
  }
  return <p className="form-error">{t('schemaForm.missing', { formId })}</p>;
}

function DefinedForm({
  definition,
  data,
  onComplete,
  submitting,
  readOnly,
}: Omit<FormProps, 'task'> & { definition: FormDefinition; readOnly: boolean }) {
  const { t } = useTranslation([definition.i18n, 'common']);
  const [inputs, setInputs] = useState<Record<string, string>>(() =>
    Object.fromEntries(
      definition.fields
        .filter((f) => f.type !== 'display')
        .map((f) => [f.name, present(data[f.name]) ? String(data[f.name]) : '']),
    ),
  );
  const [pending, setPending] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  function run(action: Action) {
    setError(null);
    if (pending !== action.id && revealedFields(definition, action.id).length > 0) {
      setPending(action.id);
      return;
    }
    const result = completion(definition, action, inputs);
    if ('missing' in result) {
      setError(t(result.missing));
      return;
    }
    return onComplete(result.variables);
  }

  const visibleFields = definition.fields.filter(
    (f) => !f.revealedBy || (!readOnly && pending === f.revealedBy),
  );

  return (
    <div className="form">
      <p className="form-intro">
        {t(readOnly ? definition.intro.readOnly : definition.intro.edit)}
      </p>

      {definition.summary.length > 0 && (
        <dl className="summary">
          {definition.summary
            .filter(
              (s) => shown(s.show, readOnly) && (s.show === 'always' || summaryPresent(s, data)),
            )
            .map((s) => (
              <div className="summary-row" key={s.variable + s.label}>
                <dt>{t(s.label)}</dt>
                <dd className={decisionClass(s.format, data[s.variable])}>
                  <SummaryValue item={s} data={data} t={t} />
                </dd>
              </div>
            ))}
        </dl>
      )}

      {visibleFields.map((f) => (
        <label className="field" key={f.name}>
          <span className="field-label">{t(f.label)}</span>
          {f.type === 'display' ? (
            <input
              className="field-input"
              value={formatted(data[f.name], f.format, t)}
              disabled
              readOnly
            />
          ) : f.type === 'textarea' ? (
            <textarea
              className="field-input"
              rows={f.rows ?? 3}
              value={inputs[f.name] ?? ''}
              onChange={(e) => setInputs((prev) => ({ ...prev, [f.name]: e.target.value }))}
              placeholder={f.placeholder ? t(f.placeholder) : undefined}
              disabled={readOnly}
              autoFocus={pending === f.revealedBy}
            />
          ) : (
            <input
              className="field-input"
              value={inputs[f.name] ?? ''}
              onChange={(e) => setInputs((prev) => ({ ...prev, [f.name]: e.target.value }))}
              placeholder={f.placeholder ? t(f.placeholder) : undefined}
              disabled={readOnly}
            />
          )}
        </label>
      ))}

      {definition.notices
        .filter((n) => shown(n.show, readOnly) && present(data[n.variable]))
        .map((n) => (
          <p className="muted" key={n.variable + n.label}>
            {t(n.label)} <em>{String(data[n.variable])}</em>
          </p>
        ))}

      {error && <p className="form-error">{error}</p>}

      {!readOnly && (
        <div className="form-actions">
          {pending === null
            ? definition.actions.map((a) => (
                <button
                  key={a.id}
                  type="button"
                  className={buttonClass(a)}
                  disabled={submitting}
                  onClick={() => run(a)}
                >
                  {submitting ? t(a.workingLabel) : t(a.label)}
                </button>
              ))
            : definition.actions
                .filter((a) => a.id === pending)
                .map((a) => (
                  <span key={a.id} className="form-actions">
                    <button
                      type="button"
                      className={buttonClass(a)}
                      disabled={submitting}
                      onClick={() => run(a)}
                    >
                      {submitting ? t(a.workingLabel) : t(a.confirmLabel ?? a.label)}
                    </button>
                    <button
                      type="button"
                      className="btn"
                      disabled={submitting}
                      onClick={() => {
                        setPending(null);
                        setError(null);
                      }}
                    >
                      {t('common:actions.cancel')}
                    </button>
                  </span>
                ))}
        </div>
      )}
    </div>
  );
}

/** The value cell of a summary row, per its kind. */
function SummaryValue({
  item,
  data,
  t,
}: {
  item: SummaryItem;
  data: Record<string, unknown>;
  t: TFunction;
}) {
  if (item.template) {
    return <>{t(item.template.key, interpolation(item.template.variables, data))}</>;
  }
  const value = data[item.variable];
  if (item.options) {
    const key = typeof value === 'string' ? item.options[value] : undefined;
    return <>{key ? t(key) : present(value) ? String(value) : '—'}</>;
  }
  if (item.item) {
    const entries = listEntries(value);
    if (entries.length === 0) return <>{'—'}</>;
    const itemKey = item.item;
    return (
      <ul className="board-list">
        {entries.map((entry, i) => (
          <li key={i}>{t(itemKey, interpolation(Object.keys(entry), entry))}</li>
        ))}
      </ul>
    );
  }
  return <>{formatted(value, item.format, t)}</>;
}

function buttonClass(action: Action): string {
  return action.style === 'primary'
    ? 'btn btn-primary'
    : action.style === 'danger'
      ? 'btn btn-danger'
      : 'btn';
}

function decisionClass(format: Format, value: unknown): string | undefined {
  if (format !== 'decision' || !present(value)) return undefined;
  return value === 'approve' ? 'decision-approve' : 'decision-reject';
}

/** A variable as displayed: `—` when absent, otherwise per format. */
export function formatted(value: unknown, format: Format, t: TFunction): string {
  if (!present(value)) return '—';
  const n = Number(value);
  switch (format) {
    case 'currency':
      return Number.isFinite(n) ? formatCurrency(n) : String(value);
    case 'number':
      return Number.isFinite(n) ? formatNumber(n) : String(value);
    case 'decision':
      return value === 'approve' ? t('common:status.approved') : t('common:status.sentBack');
    default:
      return String(value);
  }
}
