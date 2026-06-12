import { useCallback, useEffect, useState } from 'react';
import { Trans, useTranslation } from 'react-i18next';
import { useParams } from 'react-router-dom';
import {
  approve,
  getStatus,
  reject,
  submitToRegister,
  FounderSignatureError,
  type FounderStatus,
} from '../api/founderSignaturesApi';

/**
 * Public, unauthenticated page reached from the email link
 * `${frontendBaseUrl}/sign-founder/:token`. No Keycloak — the token
 * itself is the credential (see PublicApiSecurityConfig).
 *
 * Mirror of {@link ./ConfirmOwnerPage.tsx} for the OÜ-registration flow.
 *
 * State surface, mirroring the backend's FounderStatus.state:
 *   pending           - viewer hasn't signed; show Approve/Reject form
 *   confirmed_waiting - viewer signed; others still pending; poll
 *   ready_to_send     - all signed; "Submit to register" enabled for everyone
 *   sent              - already submitted to Business Register
 *   rejected          - some co-founder rejected; case is back with applicant
 *
 * Polls /status every 3s while the case is unresolved so the "Submit to
 * register" button activates for every founder the moment the last signature
 * lands. The interval drops once we hit a terminal state.
 */

const POLL_INTERVAL_MS = 3000;

export default function SignFounderPage() {
  const { t } = useTranslation('sign-founder');
  const { token } = useParams<{ token: string }>();
  const [status, setStatus] = useState<FounderStatus | null>(null);
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
      if (e instanceof FounderSignatureError) {
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
      // Message resolved from the code at render time so it follows the
      // active language; see errorText().
      setError({ code: 'missing_reason', message: '' });
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

  async function handleSubmitToRegister() {
    if (!token) return;
    setSubmitting(true);
    setError(null);
    try {
      const next = await submitToRegister(token);
      setStatus(next);
    } catch (e) {
      handleActionError(e);
    } finally {
      setSubmitting(false);
    }
  }

  function handleActionError(e: unknown) {
    if (e instanceof FounderSignatureError) {
      setError({ code: e.code, message: e.message });
      // The server is the source of truth — re-fetch so the UI catches up
      // even if our local state is stale (e.g. someone else already submitted).
      refresh();
    } else {
      setError({ code: 'network', message: e instanceof Error ? e.message : String(e) });
    }
  }

  /**
   * Locally raised errors carry a code and are translated here at render
   * time; backend-provided messages are shown verbatim.
   */
  function errorText(err: { code: string; message: string }): string {
    if (err.code === 'missing_reason') return t('errors.missingReason');
    return err.message;
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
          <h1 className="card-title">{t('errors.linkTitle')}</h1>
          <p className="form-error">{error ? errorText(error) : t('errors.unknownLink')}</p>
        </div>
      </div>
    );
  }

  const current = status.currentFounder;
  const allFounders = status.founders;
  const stateLabelKey = STATE_LABEL_KEYS[status.state];
  const stateLabel = stateLabelKey ? t(stateLabelKey) : status.state;
  const pendingCount = allFounders.filter((f) => f.status === 'pending').length;

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
            i18nKey="summary.intro"
            values={{
              applicant: status.applicantName || t('summary.applicantFallback'),
              company: status.companyName || t('summary.companyFallback'),
            }}
            components={{ strong: <strong /> }}
          />
        </p>

        {current && (
          <p className="muted">
            <Trans
              t={t}
              i18nKey={current.isApplicant ? 'summary.signingAsApplicant' : 'summary.signingAs'}
              values={{ name: current.name }}
              components={{ strong: <strong /> }}
            />
          </p>
        )}

        {status.state === 'rejected' && (
          <div className="form-banner form-banner-warn">
            <strong>
              {t('banners.rejectedBy', {
                name: status.rejectedBy ?? t('banners.coFounderFallback'),
              })}
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

        <h2 className="card-subtitle">{t('summary.coFounders')}</h2>
        <ul className="owner-list">
          {allFounders.map((f) => (
            <li key={f.token}>
              <span className="owner-meta">
                <span className="owner-name">
                  {f.name}
                  {f.isApplicant && (
                    <>
                      {' '}
                      <span className="muted">{t('summary.applicantTag')}</span>
                    </>
                  )}
                </span>
                <span className="owner-email">{f.email}</span>
                {f.status === 'rejected' && f.reason && (
                  <span className="owner-email">{t('summary.reason', { reason: f.reason })}</span>
                )}
              </span>
              <span className={pillClass(f.status)}>
                {t(FOUNDER_STATUS_KEYS[f.status] ?? 'common:status.pending')}
              </span>
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
                    <span className="field-label">{t('actions.reasonLabel')}</span>
                    <textarea
                      className="field-input"
                      rows={3}
                      value={rejectReason}
                      onChange={(e) => setRejectReason(e.target.value)}
                      placeholder={t('actions.reasonPlaceholder')}
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
            <p className="muted">{t('states.waitingOthers', { count: pendingCount })}</p>
          )}

        {status.state === 'ready_to_send' && (
          <div className="form-actions">
            <button
              type="button"
              className="btn btn-primary"
              onClick={handleSubmitToRegister}
              disabled={submitting}
            >
              {submitting ? t('common:feedback.submitting') : t('actions.submitToRegister')}
            </button>
          </div>
        )}
      </div>
    </div>
  );
}

/**
 * Backend state → translation key. The raw state strings stay untranslated;
 * they are API values the polling logic compares against.
 */
const STATE_LABEL_KEYS: Record<string, string> = {
  pending: 'states.awaitingSignatures',
  confirmed_waiting: 'states.awaitingSignatures',
  ready_to_send: 'states.readyToSubmit',
  sent: 'states.submitted',
  rejected: 'common:status.rejected',
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

/** Per-founder pill label: backend status → translation key. */
const FOUNDER_STATUS_KEYS: Record<string, string> = {
  approved: 'summary.signed',
  rejected: 'common:status.rejected',
};
