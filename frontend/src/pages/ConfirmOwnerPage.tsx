import { useCallback, useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { Trans, useTranslation } from 'react-i18next';
import type { TFunction } from 'i18next';
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
  const { t } = useTranslation('confirm-owner');
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

  // Maps locally- and backend-generated error codes to translated copy at
  // render time; unknown codes fall back to the raw backend message.
  function errorText(err: { code: string; message: string }): string {
    if (err.code === 'network') return t('errors.network', { message: err.message });
    const key = ERROR_KEYS[err.code];
    return key ? t(key) : err.message;
  }

  if (loading) {
    return (
      <div className="confirm-page">
        <div className="card">
          <p className="muted">{t('common:feedback.loading')}</p>
        </div>
      </div>
    );
  }

  if (!status) {
    return (
      <div className="confirm-page">
        <div className="card">
          <h1 className="card-title">{t('linkCard.title')}</h1>
          <p className="form-error">{error ? errorText(error) : t('errors.unknownToken')}</p>
        </div>
      </div>
    );
  }

  const current = status.currentOwner;
  const allOwners = status.owners;
  const stateKey = STATE_KEYS[status.state];
  const stateLabel = stateKey ? t(stateKey) : status.state;

  return (
    <div className="confirm-page">
      <div className="card">
        <div className="card-head">
          <h1 className="card-title">{t('title')}</h1>
          <span className="confirm-state">{stateLabel}</span>
        </div>

        <p className="form-intro">
          <Trans
            t={t}
            i18nKey="intro.submitted"
            values={{ name: status.applicantName || t('intro.applicantFallback') }}
            components={{ strong: <strong /> }}
          />
        </p>

        {current && (
          <p className="muted">
            <Trans
              t={t}
              i18nKey={current.isApplicant ? 'intro.signingAsApplicant' : 'intro.signingAs'}
              values={{ name: current.name }}
              components={{ strong: <strong /> }}
            />
          </p>
        )}

        {status.state === 'rejected' && (
          <div className="form-banner form-banner-warn">
            <strong>
              {status.rejectedBy
                ? t('banners.rejectedBy', { name: status.rejectedBy })
                : t('banners.rejectedByUnknown')}
            </strong>
            {status.rejectionReason && <p className="form-banner-body">{status.rejectionReason}</p>}
            <p className="form-banner-body">{t('banners.rejectedBody')}</p>
          </div>
        )}

        {status.state === 'sent' && (
          <div className="form-banner">
            <strong>{t('banners.sentTitle')}</strong>
            <p className="form-banner-body">{t('banners.sentBody')}</p>
          </div>
        )}

        <h2 className="card-subtitle">{t('summary.ownersHeading')}</h2>
        <ul className="owner-list">
          {allOwners.map((o) => (
            <li key={o.token}>
              <span className="owner-meta">
                <span className="owner-name">
                  {o.name}
                  {o.isApplicant && (
                    <>
                      {' '}
                      <span className="muted">{t('summary.applicantTag')}</span>
                    </>
                  )}
                </span>
                <span className="owner-email">{o.email}</span>
                {o.status === 'rejected' && o.reason && (
                  <span className="owner-email">{t('summary.reason', { reason: o.reason })}</span>
                )}
              </span>
              <span className={pillClass(o.status)}>{ownerStatusLabel(t, o.status)}</span>
            </li>
          ))}
        </ul>

        {error && <p className="form-error">{errorText(error)}</p>}

        {/* Action area depends on state */}
        {status.state === 'pending' &&
          current &&
          current.status === 'pending' &&
          !current.isApplicant && (
            <>
              {!showRejectForm ? (
                <div className="form-actions">
                  <button
                    type="button"
                    className="btn btn-primary"
                    onClick={handleApprove}
                    disabled={submitting}
                  >
                    {submitting ? t('actions.signing') : t('actions.approveAndSign')}
                  </button>
                  <button
                    type="button"
                    className="btn btn-danger"
                    onClick={() => setShowRejectForm(true)}
                    disabled={submitting}
                  >
                    {t('common:actions.reject')}
                  </button>
                </div>
              ) : (
                <div className="field-group">
                  <label className="field">
                    <span className="field-label">{t('actions.rejectReasonLabel')}</span>
                    <textarea
                      className="field-input"
                      rows={3}
                      value={rejectReason}
                      onChange={(e) => setRejectReason(e.target.value)}
                      placeholder={t('actions.rejectReasonPlaceholder')}
                    />
                  </label>
                  <div className="form-actions">
                    <button
                      type="button"
                      className="btn btn-danger"
                      onClick={handleReject}
                      disabled={submitting}
                    >
                      {submitting ? t('actions.sending') : t('actions.sendRejection')}
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
                      {t('common:actions.cancel')}
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
            <p className="muted">{t('waiting.signatureOnFile')}</p>
          )}

        {status.state === 'ready_to_send' && (
          <div className="form-actions">
            <button
              type="button"
              className="btn btn-primary"
              onClick={handleSendToProcess}
              disabled={submitting}
            >
              {submitting ? t('actions.sending') : t('actions.sendToProcess')}
            </button>
          </div>
        )}
      </div>
    </div>
  );
}

/** API state values (left) → translation keys (right). Never compare against labels. */
const STATE_KEYS: Record<string, string> = {
  pending: 'states.awaitingSignatures',
  confirmed_waiting: 'states.awaitingSignatures',
  ready_to_send: 'states.readyToSend',
  sent: 'states.sent',
  rejected: 'states.rejected',
};

/** Known error codes (local + backend OwnerConfirmationController) → translation keys. */
const ERROR_KEYS: Record<string, string> = {
  missing_reason: 'errors.missingReason',
  unknown_token: 'errors.unknownToken',
  already_rejected: 'errors.alreadyRejected',
  already_sent: 'errors.alreadySent',
  already_signed: 'errors.alreadySigned',
  not_waiting: 'errors.notWaiting',
  not_ready: 'errors.notReady',
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

function ownerStatusLabel(t: TFunction<'confirm-owner'>, status: string): string {
  switch (status) {
    case 'approved':
      return t('summary.ownerStatus.signed');
    case 'rejected':
      return t('summary.ownerStatus.rejected');
    default:
      return t('summary.ownerStatus.pending');
  }
}
