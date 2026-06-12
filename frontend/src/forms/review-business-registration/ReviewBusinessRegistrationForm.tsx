import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { formatCurrency } from '../../i18n/format';
import type { FormProps } from '../types';

/**
 * Business Register review form for businessRegistration (PartB). Shows the
 * founder's submitted data read-only with two actions:
 *
 *   Approve    → completes the task with decision="approve". The process
 *                continues to the send-approval-email service task and ends.
 *   Send back  → reveals a reason textarea. On confirm, writes
 *                decision="sendback" + sendBackReason so the founder sees
 *                why the case was returned. The process loops back to the
 *                founder's OÜ-details task.
 *
 * Mirrors vehicle-review/VehicleReviewForm.tsx — same overall shape
 * (read-only summary, action row, optional reason input), different fields.
 */

interface BoardMember {
  firstName?: string;
  lastName?: string;
  personalCode?: string;
}

function residencyKey(raw: string | undefined): string | null {
  switch (raw) {
    case 'citizen':
      return 'residency.citizen';
    case 'e-resident':
      return 'residency.eResident';
    case 'foreign':
      return 'residency.foreign';
    default:
      return null;
  }
}

function parseBoardMembers(raw: unknown): BoardMember[] {
  if (Array.isArray(raw)) return raw as BoardMember[];
  if (typeof raw === 'string' && raw.trim()) {
    try {
      const parsed = JSON.parse(raw);
      if (Array.isArray(parsed)) return parsed as BoardMember[];
    } catch {
      // fall through
    }
  }
  return [];
}

export default function ReviewBusinessRegistrationForm({
  data,
  onComplete,
  submitting,
  readOnly,
}: FormProps) {
  const { t } = useTranslation('review-business-registration');
  const [showSendBack, setShowSendBack] = useState(false);
  const [reason, setReason] = useState((data.sendBackReason as string) ?? '');
  const [error, setError] = useState<string | null>(null); // holds an i18n key

  function accept() {
    setError(null);
    return onComplete({ decision: { value: 'approve', type: 'String' } });
  }

  function sendBack() {
    setError(null);
    const trimmed = reason.trim();
    if (!trimmed) {
      setError('errors.reasonRequired');
      return;
    }
    return onComplete({
      decision: { value: 'sendback', type: 'String' },
      sendBackReason: { value: trimmed, type: 'String' },
    });
  }

  const boardMembers = parseBoardMembers(data.boardMembers);
  const shareCapital =
    data.shareCapital != null && data.shareCapital !== ''
      ? formatCurrency(Number(data.shareCapital))
      : '—';
  const decision = (data.decision as string) ?? null;
  const priorReason = (data.sendBackReason as string) ?? '';
  const residency = residencyKey(data.applicantResidency as string | undefined);

  return (
    <div className="form">
      <p className="form-intro">{readOnly ? t('intro.readOnly') : t('intro.review')}</p>

      <dl className="summary">
        <div className="summary-row">
          <dt>{t('summary.companyName')}</dt>
          <dd>{(data.companyName as string) ?? '—'}</dd>
        </div>
        <div className="summary-row">
          <dt>{t('summary.shareCapital')}</dt>
          <dd>{shareCapital}</dd>
        </div>
        <div className="summary-row">
          <dt>{t('summary.founder')}</dt>
          <dd>
            {(data.applicantFirstName as string) ?? '—'} {(data.applicantLastName as string) ?? ''}
            {data.applicantAge != null && ` ${t('summary.founderAge', { age: String(data.applicantAge) })}`}
          </dd>
        </div>
        <div className="summary-row">
          <dt>{t('summary.residency')}</dt>
          <dd>{residency ? t(residency) : '—'}</dd>
        </div>
        <div className="summary-row">
          <dt>{t('summary.boardMembers')}</dt>
          <dd>
            {boardMembers.length === 0 ? (
              '—'
            ) : (
              <ul className="board-list">
                {boardMembers.map((m, idx) => (
                  <li key={idx}>
                    {m.firstName ?? '?'} {m.lastName ?? '?'} ({m.personalCode ?? '—'})
                  </li>
                ))}
              </ul>
            )}
          </dd>
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

      {error && <p className="form-error">{t(error)}</p>}

      {!readOnly && (
        <div className="form-actions">
          <button className="btn btn-primary" disabled={submitting} onClick={accept}>
            {submitting ? t('common:feedback.submitting') : t('common:actions.approve')}
          </button>
          {!showSendBack ? (
            <button
              type="button"
              className="btn btn-danger"
              disabled={submitting}
              onClick={() => setShowSendBack(true)}
            >
              {t('actions.openSendBack')}
            </button>
          ) : (
            <>
              <button className="btn btn-danger" disabled={submitting} onClick={sendBack}>
                {submitting ? t('common:feedback.submitting') : t('actions.confirmSendBack')}
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
