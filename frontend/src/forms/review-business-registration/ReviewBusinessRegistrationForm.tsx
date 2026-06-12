import { useState } from 'react';
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

function residencyLabel(raw: string | undefined): string {
  switch (raw) {
    case 'citizen':
      return 'Estonian citizen';
    case 'e-resident':
      return 'E-resident';
    case 'foreign':
      return 'Foreign founder';
    default:
      return '—';
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
      setError('Please give the founder a reason for sending the registration back.');
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
      ? `${Number(data.shareCapital).toFixed(2)} EUR`
      : '—';
  const decision = (data.decision as string) ?? null;
  const priorReason = (data.sendBackReason as string) ?? '';

  return (
    <div className="form">
      <p className="form-intro">
        {readOnly
          ? 'A read-only view of the submitted OÜ founding details and the reviewer’s decision.'
          : 'Business Register review. Approve to enter the OÜ in the äriregister; send back with a reason to ask the founder for corrections.'}
      </p>

      <dl className="summary">
        <div className="summary-row">
          <dt>Company name</dt>
          <dd>{(data.companyName as string) ?? '—'}</dd>
        </div>
        <div className="summary-row">
          <dt>Share capital</dt>
          <dd>{shareCapital}</dd>
        </div>
        <div className="summary-row">
          <dt>Founder</dt>
          <dd>
            {(data.applicantFirstName as string) ?? '—'} {(data.applicantLastName as string) ?? ''}
            {data.applicantAge != null && ` (age ${String(data.applicantAge)})`}
          </dd>
        </div>
        <div className="summary-row">
          <dt>Residency</dt>
          <dd>{residencyLabel(data.applicantResidency as string | undefined)}</dd>
        </div>
        <div className="summary-row">
          <dt>Board members</dt>
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
            <dt>Decision</dt>
            <dd className={decision === 'approve' ? 'decision-approve' : 'decision-reject'}>
              {decision === 'approve' ? 'Approved' : 'Sent back'}
            </dd>
          </div>
        )}
        {readOnly && priorReason && (
          <div className="summary-row">
            <dt>Send-back reason</dt>
            <dd>{priorReason}</dd>
          </div>
        )}
      </dl>

      {!readOnly && priorReason && (
        <p className="muted">
          Previous send-back reason (now resubmitted by the founder): <em>{priorReason}</em>
        </p>
      )}

      {!readOnly && showSendBack && (
        <label className="field">
          <span className="field-label">Reason to send back</span>
          <textarea
            className="field-input"
            rows={3}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            placeholder="Tell the founder what to fix (e.g. share capital below €2500)."
            autoFocus
          />
        </label>
      )}

      {error && <p className="form-error">{error}</p>}

      {!readOnly && (
        <div className="form-actions">
          <button className="btn btn-primary" disabled={submitting} onClick={accept}>
            {submitting ? 'Working…' : 'Approve'}
          </button>
          {!showSendBack ? (
            <button
              type="button"
              className="btn btn-danger"
              disabled={submitting}
              onClick={() => setShowSendBack(true)}
            >
              Send back…
            </button>
          ) : (
            <>
              <button className="btn btn-danger" disabled={submitting} onClick={sendBack}>
                {submitting ? 'Working…' : 'Confirm send back'}
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
                Cancel
              </button>
            </>
          )}
        </div>
      )}
    </div>
  );
}
