import { useCallback, useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import {
  approve,
  getStatus,
  reject,
  sendToProcess,
  OwnerConfirmationError,
  type OwnerStatus,
} from '../api/ownerConfirmationsApi';

/**
 * Public, unauthenticated page reached from the email link
 * `${frontendBaseUrl}/confirm-owner/:token`. No Keycloak — the token
 * itself is the credential (see PublicApiSecurityConfig).
 *
 * State surface, mirroring the backend's OwnerStatus.state:
 *   pending           - viewer hasn't signed; show Approve/Reject form
 *   confirmed_waiting - viewer signed; others still pending; poll
 *   ready_to_send     - all signed; "Send to process" enabled for everyone
 *   sent              - already forwarded to back office
 *   rejected          - some owner rejected; case is back with applicant
 *
 * Polls /status every 3s while the case is unresolved so the "Send to
 * process" button activates for every owner the moment the last signature
 * lands. The interval drops once we hit a terminal state.
 */

const POLL_INTERVAL_MS = 3000;

export default function ConfirmOwnerPage() {
  const { token } = useParams<{ token: string }>();
  const [status, setStatus] = useState<OwnerStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<{ code: string; message: string } | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [rejectReason, setRejectReason] = useState('');
  const [showRejectForm, setShowRejectForm] = useState(false);

  const refresh = useCallback(async () => {
    if (!token) return;
    try {
      const s = await getStatus(token);
      setStatus(s);
      setError(null);
    } catch (e) {
      if (e instanceof OwnerConfirmationError) {
        setError({ code: e.code, message: e.message });
      } else {
        setError({ code: 'network', message: e instanceof Error ? e.message : String(e) });
      }
    } finally {
      setLoading(false);
    }
  }, [token]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  // Poll while the case is unresolved. Stop once we reach a terminal state
  // so we're not hammering the backend after the case has been sent or
  // rejected.
  useEffect(() => {
    if (!status) return;
    if (status.state === 'sent' || status.state === 'rejected') return;
    const id = setInterval(refresh, POLL_INTERVAL_MS);
    return () => clearInterval(id);
  }, [refresh, status]);

  async function handleApprove() {
    if (!token) return;
    setSubmitting(true);
    setError(null);
    try {
      const next = await approve(token);
      setStatus(next);
    } catch (e) {
      handleActionError(e);
    } finally {
      setSubmitting(false);
    }
  }

  async function handleReject() {
    if (!token) return;
    if (!rejectReason.trim()) {
      setError({ code: 'missing_reason', message: 'Please describe why you are rejecting.' });
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      const next = await reject(token, rejectReason.trim());
      setStatus(next);
      setShowRejectForm(false);
    } catch (e) {
      handleActionError(e);
    } finally {
      setSubmitting(false);
    }
  }

  async function handleSendToProcess() {
    if (!token) return;
    setSubmitting(true);
    setError(null);
    try {
      const next = await sendToProcess(token);
      setStatus(next);
    } catch (e) {
      handleActionError(e);
    } finally {
      setSubmitting(false);
    }
  }

  function handleActionError(e: unknown) {
    if (e instanceof OwnerConfirmationError) {
      setError({ code: e.code, message: e.message });
      // The server is the source of truth — re-fetch so the UI catches up
      // even if our local state is stale (e.g. someone else already sent).
      refresh();
    } else {
      setError({ code: 'network', message: e instanceof Error ? e.message : String(e) });
    }
  }

  if (loading) {
    return (
      <div className="confirm-page">
        <div className="card">
          <p className="muted">Loading…</p>
        </div>
      </div>
    );
  }

  if (!status) {
    return (
      <div className="confirm-page">
        <div className="card">
          <h1 className="card-title">Confirmation link</h1>
          <p className="form-error">
            {error?.message ?? 'This confirmation link is unknown or has expired.'}
          </p>
        </div>
      </div>
    );
  }

  const current = status.currentOwner;
  const allOwners = status.owners;
  const stateLabel = STATE_LABELS[status.state] ?? status.state;

  return (
    <div className="confirm-page">
      <div className="card">
        <div className="card-head">
          <h1 className="card-title">Co-owner confirmation</h1>
          <span className="confirm-state">{stateLabel}</span>
        </div>

        <p className="form-intro">
          <strong>{status.applicantName || 'The applicant'}</strong> has submitted a
          registration that requires every co-owner's signature before it can be
          sent to the back office.
        </p>

        {current && (
          <p className="muted">
            You are signing as <strong>{current.name}</strong>
            {current.isApplicant && ' (the applicant)'}.
          </p>
        )}

        {status.state === 'rejected' && (
          <div className="form-banner form-banner-warn">
            <strong>Rejected by {status.rejectedBy ?? 'an owner'}.</strong>
            {status.rejectionReason && (
              <p className="form-banner-body">{status.rejectionReason}</p>
            )}
            <p className="form-banner-body">
              The case has been sent back to the applicant. New confirmation
              links will be issued once they resubmit.
            </p>
          </div>
        )}

        {status.state === 'sent' && (
          <div className="form-banner">
            <strong>Sent to the back office.</strong>
            <p className="form-banner-body">
              All co-owners signed and the case has been forwarded for review.
            </p>
          </div>
        )}

        <h2 className="card-subtitle">Owners</h2>
        <ul className="owner-list">
          {allOwners.map((o) => (
            <li key={o.token}>
              <span className="owner-meta">
                <span className="owner-name">
                  {o.name}
                  {o.isApplicant && (
                    <>
                      {' '}
                      <span className="muted">· applicant</span>
                    </>
                  )}
                </span>
                <span className="owner-email">{o.email}</span>
                {o.status === 'rejected' && o.reason && (
                  <span className="owner-email">Reason: {o.reason}</span>
                )}
              </span>
              <span className={pillClass(o.status)}>{ownerStatusLabel(o.status)}</span>
            </li>
          ))}
        </ul>

        {error && <p className="form-error">{error.message}</p>}

        {/* Action area depends on state */}
        {status.state === 'pending' && current && current.status === 'pending' && !current.isApplicant && (
          <>
            {!showRejectForm ? (
              <div className="form-actions">
                <button
                  type="button"
                  className="btn btn-primary"
                  onClick={handleApprove}
                  disabled={submitting}
                >
                  {submitting ? 'Signing…' : 'Approve and sign'}
                </button>
                <button
                  type="button"
                  className="btn btn-danger"
                  onClick={() => setShowRejectForm(true)}
                  disabled={submitting}
                >
                  Reject
                </button>
              </div>
            ) : (
              <div className="field-group">
                <label className="field">
                  <span className="field-label">Reason for rejection</span>
                  <textarea
                    className="field-input"
                    rows={3}
                    value={rejectReason}
                    onChange={(e) => setRejectReason(e.target.value)}
                    placeholder="Explain what needs to change before you'd sign."
                  />
                </label>
                <div className="form-actions">
                  <button
                    type="button"
                    className="btn btn-danger"
                    onClick={handleReject}
                    disabled={submitting}
                  >
                    {submitting ? 'Sending…' : 'Send rejection'}
                  </button>
                  <button
                    type="button"
                    className="btn"
                    onClick={() => {
                      setShowRejectForm(false);
                      setRejectReason('');
                      setError(null);
                    }}
                    disabled={submitting}
                  >
                    Cancel
                  </button>
                </div>
              </div>
            )}
          </>
        )}

        {(status.state === 'pending' || status.state === 'ready_to_send') &&
          current &&
          current.status === 'approved' &&
          status.state !== 'ready_to_send' && (
            <p className="muted">
              Your signature is on file. Waiting for the other co-owners.
            </p>
          )}

        {status.state === 'ready_to_send' && (
          <div className="form-actions">
            <button
              type="button"
              className="btn btn-primary"
              onClick={handleSendToProcess}
              disabled={submitting}
            >
              {submitting ? 'Sending…' : 'Send to process'}
            </button>
          </div>
        )}
      </div>
    </div>
  );
}

const STATE_LABELS: Record<string, string> = {
  pending: 'Awaiting signatures',
  confirmed_waiting: 'Awaiting signatures',
  ready_to_send: 'Ready to send',
  sent: 'Sent',
  rejected: 'Rejected',
};

function pillClass(status: string): string {
  switch (status) {
    case 'approved':
      return 'status-pill status-done';
    case 'rejected':
      return 'status-pill status-warn';
    default:
      return 'status-pill status-active';
  }
}

function ownerStatusLabel(status: string): string {
  switch (status) {
    case 'approved':
      return 'Signed';
    case 'rejected':
      return 'Rejected';
    default:
      return 'Pending';
  }
}
