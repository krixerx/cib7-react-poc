import { useCallback, useEffect, useState } from 'react';
import { Trans, useTranslation } from 'react-i18next';
import { useParams } from 'react-router-dom';
import { Download } from 'lucide-react';
import {
  approve,
  ConsentError,
  getDocumentDownloadUrl,
  getStatus,
  reject,
  send,
  type ConsentDetail,
  type ConsentStatus,
} from '../api/consentApi';

/**
 * Public, unauthenticated co-signing page, `/consent/:purpose/:token`, reached from the link in a
 * co-signing email. No Keycloak: the engine-signed capability token in the URL is the credential
 * (docs/security.md rule 3).
 *
 * One page for every purpose the service pack declares (`backend/consent/<purpose>.yaml`). The
 * flow is the same for all: each party approves or rejects, and once everyone has approved, any of
 * them sends the case on. What differs comes from the pack: the wording (`consent:<purpose>.*`)
 * and what the parties see, the descriptor's details and documents, which the status response
 * carries by name and in order. Core texts (`consent:*`) cover the parts every purpose shares.
 *
 * States, as the backend reports them:
 *   pending           - the viewer has not signed; show Approve / Reject
 *   confirmed_waiting - the viewer signed, others have not; poll
 *   ready_to_send     - everyone signed; the send button is enabled for every party
 *   sent              - the case has been sent on
 *   rejected          - a party rejected; the case is back with the applicant
 *
 * Polls every 3 s until the case is sent or rejected, so the send button appears for every party
 * the moment the last signature lands.
 */

const POLL_INTERVAL_MS = 3000;

const STATE_LABEL_KEYS: Record<string, string> = {
  pending: 'states.awaitingSignatures',
  confirmed_waiting: 'states.awaitingSignatures',
  ready_to_send: 'states.readyToSend',
  sent: 'states.sent',
  rejected: 'states.rejected',
};

const PARTY_STATUS_KEYS: Record<string, string> = {
  approved: 'partyStatus.signed',
  rejected: 'partyStatus.rejected',
  pending: 'partyStatus.pending',
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

export default function ConsentPage() {
  const { t, i18n } = useTranslation('consent');
  const { purpose = '', token = '' } = useParams<{ purpose: string; token: string }>();
  const [status, setStatus] = useState<ConsentStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<{ code: string; message: string } | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [rejectReason, setRejectReason] = useState('');
  const [showRejectForm, setShowRejectForm] = useState(false);

  /** The pack's wording for this purpose. */
  const p = (key: string, options?: Record<string, unknown>) =>
    t(`${purpose}.${key}`, options ?? {});

  const refresh = useCallback(async () => {
    if (!purpose || !token) return;
    try {
      setStatus(await getStatus(purpose, token));
      setError(null);
    } catch (e) {
      setError(
        e instanceof ConsentError
          ? { code: e.code, message: e.message }
          : { code: 'network', message: e instanceof Error ? e.message : String(e) },
      );
    } finally {
      setLoading(false);
    }
  }, [purpose, token]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  useEffect(() => {
    if (!status || status.state === 'sent' || status.state === 'rejected') return;
    const id = setInterval(refresh, POLL_INTERVAL_MS);
    return () => clearInterval(id);
  }, [refresh, status]);

  async function act(action: () => Promise<ConsentStatus>) {
    setSubmitting(true);
    setError(null);
    try {
      setStatus(await action());
      setShowRejectForm(false);
    } catch (e) {
      if (e instanceof ConsentError) {
        setError({ code: e.code, message: e.message });
        // The server is the source of truth: catch up even if someone else acted meanwhile.
        refresh();
      } else {
        setError({ code: 'network', message: e instanceof Error ? e.message : String(e) });
      }
    } finally {
      setSubmitting(false);
    }
  }

  function handleReject() {
    if (!rejectReason.trim()) {
      setError({ code: 'missing_reason', message: '' });
      return;
    }
    void act(() => reject(purpose, token, rejectReason.trim()));
  }

  async function handleDownload(name: string) {
    try {
      const d = await getDocumentDownloadUrl(purpose, token, name);
      window.open(d.url, '_blank', 'noopener,noreferrer');
    } catch (e) {
      setError({
        code: e instanceof ConsentError ? e.code : 'network',
        message: e instanceof Error ? e.message : String(e),
      });
    }
  }

  /** Local errors are translated at render time; the backend's messages are shown as they are. */
  function errorText(err: { code: string; message: string }): string {
    if (err.code === 'missing_reason') return t('errors.missingReason');
    if (err.code === 'network') return t('errors.network', { message: err.message });
    return err.message;
  }

  /** A detail as text: names joined, numbers through the pack's format if it gives one. */
  function detailText(name: string, value: ConsentDetail): string {
    if (value == null || value === '') return '—';
    if (Array.isArray(value)) return value.length ? value.map((m) => m.name).join(', ') : '—';
    const format = `${purpose}.values.${name}`;
    if (i18n.exists(format, { ns: 'consent' })) return t(format, { value });
    return typeof value === 'number' ? new Intl.NumberFormat(i18n.language).format(value) : value;
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
          <h1 className="card-title">{t('linkTitle')}</h1>
          <p className="form-error">{error ? errorText(error) : t('errors.unknownLink')}</p>
        </div>
      </div>
    );
  }

  const current = status.current;
  const stateKey = STATE_LABEL_KEYS[status.state];
  const pendingCount = status.parties.filter((x) => x.status === 'pending').length;
  const detailNames = Object.keys(status.details);
  const documentNames = Object.keys(status.documents);
  // Detail values as plain text, for the pack's sentences ({{companyName}} etc.).
  const values: Record<string, string> = Object.fromEntries(
    detailNames.map((name) => [name, detailText(name, status.details[name])]),
  );
  const applicant = status.applicantName || t('applicantFallback');

  return (
    <div className="confirm-page">
      <div className="card">
        <div className="card-head">
          <h1 className="card-title">{p('title')}</h1>
          <span className="confirm-state">{stateKey ? t(stateKey) : status.state}</span>
        </div>

        <p className="form-intro">
          <Trans
            t={t}
            i18nKey={`${purpose}.intro`}
            values={{ ...values, applicant }}
            components={{ strong: <strong /> }}
          />
        </p>

        {current && (
          <p className="muted">
            <Trans
              t={t}
              i18nKey={current.isApplicant ? 'signingAsApplicant' : 'signingAs'}
              values={{ name: current.name }}
              components={{ strong: <strong /> }}
            />
          </p>
        )}

        {(detailNames.length > 0 || documentNames.length > 0) && (
          <>
            <h2 className="card-subtitle">{t('detailsTitle')}</h2>
            <dl className="summary">
              {detailNames.map((name) => (
                <div className="summary-row" key={name}>
                  <dt>{p(`details.${name}`)}</dt>
                  <dd>{values[name]}</dd>
                </div>
              ))}
              {documentNames.map((name) => (
                <div className="summary-row" key={`doc-${name}`}>
                  <dt>{p(`documents.${name}`)}</dt>
                  <dd>
                    {status.documents[name] ? (
                      <button
                        type="button"
                        className="btn btn-small"
                        onClick={() => handleDownload(name)}
                        title={status.documents[name] ?? undefined}
                      >
                        <Download size={14} aria-hidden="true" /> {t('downloadDocument')}
                      </button>
                    ) : (
                      t('noDocument')
                    )}
                  </dd>
                </div>
              ))}
            </dl>
          </>
        )}

        {status.state === 'rejected' && (
          <div className="form-banner form-banner-warn">
            <strong>
              {status.rejectedBy
                ? t('banners.rejectedBy', { name: status.rejectedBy })
                : t('banners.rejectedByUnknown')}
            </strong>
            {status.rejectionReason && <p className="form-banner-body">{status.rejectionReason}</p>}
            <p className="form-banner-body">{p('rejectedBody')}</p>
          </div>
        )}

        {status.state === 'sent' && (
          <div className="form-banner">
            <strong>{p('sentTitle')}</strong>
            <p className="form-banner-body">{p('sentBody')}</p>
          </div>
        )}

        <h2 className="card-subtitle">{p('partiesHeading')}</h2>
        <ul className="owner-list">
          {status.parties.map((party) => (
            <li key={party.partyId}>
              <span className="owner-meta">
                <span className="owner-name">
                  {party.name}
                  {party.isApplicant && (
                    <>
                      {' '}
                      <span className="muted">{t('applicantTag')}</span>
                    </>
                  )}
                </span>
                {party.status === 'rejected' && party.reason && (
                  <span className="owner-email">{t('reason', { reason: party.reason })}</span>
                )}
              </span>
              <span className={pillClass(party.status)}>
                {t(PARTY_STATUS_KEYS[party.status] ?? 'partyStatus.pending')}
              </span>
            </li>
          ))}
        </ul>

        {error && <p className="form-error">{errorText(error)}</p>}

        {status.state === 'pending' &&
          current &&
          current.status === 'pending' &&
          !current.isApplicant && (
            <>
              <p className="muted">{p('consent', { ...values, applicant })}</p>
              {!showRejectForm ? (
                <div className="form-actions">
                  <button
                    type="button"
                    className="btn btn-primary"
                    onClick={() => act(() => approve(purpose, token))}
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

        {status.state !== 'ready_to_send' &&
          status.state !== 'sent' &&
          status.state !== 'rejected' &&
          current?.status === 'approved' && (
            <p className="muted">{t('waitingOthers', { count: pendingCount })}</p>
          )}

        {status.state === 'ready_to_send' && (
          <div className="form-actions">
            <button
              type="button"
              className="btn btn-primary"
              onClick={() => act(() => send(purpose, token))}
              disabled={submitting}
            >
              {submitting ? t('common:feedback.submitting') : p('send')}
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
