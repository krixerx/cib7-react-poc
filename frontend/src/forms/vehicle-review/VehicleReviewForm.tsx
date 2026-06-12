import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { FormProps } from '../types';
import { formatCurrency } from '../../i18n/format';

/**
 * Transport Authority review form (PartB). Shows the owner's submitted data
 * and the looked-up vehicle value read-only, then either:
 *
 *   Accept   → completes the task with decision="approve" (process ends).
 *   Send back → reveals a reason textarea; completing writes decision="sendback"
 *               and sendBackReason so the owner sees why it was returned.
 */
export default function VehicleReviewForm({ data, onComplete, submitting, readOnly }: FormProps) {
  const { t } = useTranslation('vehicle-review');
  const [showSendBack, setShowSendBack] = useState(false);
  const [reason, setReason] = useState((data.sendBackReason as string) ?? '');
  const [error, setError] = useState<string | null>(null);

  function accept() {
    setError(null);
    return onComplete({ decision: { value: 'approve', type: 'String' } });
  }

  function sendBack() {
    setError(null);
    const trimmed = reason.trim();
    if (!trimmed) {
      setError(t('errors.reasonRequired'));
      return;
    }
    return onComplete({
      decision: { value: 'sendback', type: 'String' },
      sendBackReason: { value: trimmed, type: 'String' },
    });
  }

  const priceNum =
    data.price != null && data.price !== '' && Number.isFinite(Number(data.price))
      ? Number(data.price)
      : null;
  const price =
    priceNum != null ? formatCurrency(priceNum) : data.price != null && data.price !== '' ? String(data.price) : '—';
  const decision = (data.decision as string) ?? null;
  const priorReason = (data.sendBackReason as string) ?? '';

  return (
    <div className="form">
      <p className="form-intro">{readOnly ? t('intro.readOnly') : t('intro.edit')}</p>

      <dl className="summary">
        <div className="summary-row">
          <dt>{t('summary.firstName')}</dt>
          <dd>{(data.firstName as string) ?? '—'}</dd>
        </div>
        <div className="summary-row">
          <dt>{t('summary.lastName')}</dt>
          <dd>{(data.lastName as string) ?? '—'}</dd>
        </div>
        <div className="summary-row">
          <dt>{t('summary.age')}</dt>
          <dd>{data.age != null ? String(data.age) : '—'}</dd>
        </div>
        {readOnly && decision && (
          <div className="summary-row">
            <dt>{t('summary.decision')}</dt>
            <dd className={decision === 'approve' ? 'decision-approve' : 'decision-reject'}>
              {decision === 'approve' ? t('common:status.approved') : t('common:status.sentBack')}
            </dd>
          </div>
        )}
        {readOnly && priorReason && (
          <div className="summary-row">
            <dt>{t('summary.sendBackReason')}</dt>
            <dd>{priorReason}</dd>
          </div>
        )}
      </dl>

      <label className="field">
        <span className="field-label">{t('fields.vehicleValue.label')}</span>
        <input className="field-input" value={price} disabled readOnly />
      </label>

      {!readOnly && priorReason && (
        <p className="muted">
          {t('previousReason')} <em>{priorReason}</em>
        </p>
      )}

      {!readOnly && showSendBack && (
        <label className="field">
          <span className="field-label">{t('fields.sendBackReason.label')}</span>
          <textarea
            className="field-input"
            rows={3}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            placeholder={t('fields.sendBackReason.placeholder')}
            autoFocus
          />
        </label>
      )}

      {error && <p className="form-error">{error}</p>}

      {!readOnly && (
        <div className="form-actions">
          <button className="btn btn-primary" disabled={submitting} onClick={accept}>
            {submitting ? t('actions.working') : t('actions.accept')}
          </button>
          {!showSendBack ? (
            <button
              type="button"
              className="btn btn-danger"
              disabled={submitting}
              onClick={() => setShowSendBack(true)}
            >
              {t('actions.sendBackEllipsis')}
            </button>
          ) : (
            <>
              <button className="btn btn-danger" disabled={submitting} onClick={sendBack}>
                {submitting ? t('actions.working') : t('actions.confirmSendBack')}
              </button>
              <button
                type="button"
                className="btn"
                disabled={submitting}
                onClick={() => {
                  setShowSendBack(false);
                  setError(null);
                }}
              >
                {t('common:actions.cancel')}
              </button>
            </>
          )}
        </div>
      )}
    </div>
  );
}
