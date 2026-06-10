import { useState } from 'react';
import type { FormProps } from '../types';

/**
 * Transport Authority review form (PartB). Shows the owner's submitted data
 * and the looked-up vehicle value read-only, then either:
 *
 *   Accept   → completes the task with decision="approve" (process ends).
 *   Send back → reveals a reason textarea; completing writes decision="sendback"
 *               and sendBackReason so the owner sees why it was returned.
 */
export default function ReviewApplicationForm({
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
      setError('Please give the owner a reason for sending the registration back.');
      return;
    }
    return onComplete({
      decision: { value: 'sendback', type: 'String' },
      sendBackReason: { value: trimmed, type: 'String' },
    });
  }

  const price =
    data.price != null && data.price !== '' ? String(data.price) : '—';
  const decision = (data.decision as string) ?? null;
  const priorReason = (data.sendBackReason as string) ?? '';

  return (
    <div className="form">
      <p className="form-intro">
        {readOnly
          ? 'A read-only view of the owner details, the vehicle value from the registry, and the reviewer’s decision.'
          : 'Transport Authority review. Check the owner details and the vehicle value from the registry. Accept the registration, or send it back to the owner with a reason.'}
      </p>

      <dl className="summary">
        <div className="summary-row">
          <dt>First name</dt>
          <dd>{(data.firstName as string) ?? '—'}</dd>
        </div>
        <div className="summary-row">
          <dt>Last name</dt>
          <dd>{(data.lastName as string) ?? '—'}</dd>
        </div>
        <div className="summary-row">
          <dt>Age</dt>
          <dd>{data.age != null ? String(data.age) : '—'}</dd>
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

      <label className="field">
        <span className="field-label">Vehicle value (€, from registry)</span>
        <input className="field-input" value={price} disabled readOnly />
      </label>

      {!readOnly && priorReason && (
        <p className="muted">
          Previous send-back reason (now resubmitted by the owner):{' '}
          <em>{priorReason}</em>
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
            placeholder="Tell the owner what to fix (e.g. ID document is unreadable)."
            autoFocus
          />
        </label>
      )}

      {error && <p className="form-error">{error}</p>}

      {!readOnly && (
        <div className="form-actions">
          <button
            className="btn btn-primary"
            disabled={submitting}
            onClick={accept}
          >
            {submitting ? 'Working…' : 'Accept'}
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
              <button
                className="btn btn-danger"
                disabled={submitting}
                onClick={sendBack}
              >
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
