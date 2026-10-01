import { useCallback, useEffect, useState } from 'react';
import { Trans, useTranslation } from 'react-i18next';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { checkout, getStatus, PaymentError, type PaymentStatus } from '../api/paymentsApi';
import { translateBackendName } from '../i18n/backendNames';
import { formatCurrency } from '../i18n/format';

/**
 * Public, unauthenticated page reached from the approval email's pay link
 * `${frontendBaseUrl}/pay/:token`. No Keycloak: the engine-signed payment
 * capability token in the URL is the credential (docs/security.md rule 3).
 *
 * "Pay" only starts a checkout: the backend creates a payment session with
 * the amount it computed and returns the provider's page, here the demo
 * bank at `/mock-bank/:sessionId`. The fee counts as paid only after the
 * provider's signed callback (rule 4), so on the way back from the bank
 * (`?from=bank`) this page polls the status until it flips to paid.
 *
 * Renders three terminal states:
 *   pending   — the bill + "Continue to payment"
 *   paid      — a success card
 *   unknown   — a not-found card (404 for any invalid or expired link)
 */

const POLL_MS = 2000;
const POLL_LIMIT = 15;

const ISSUERS: Record<string, string> = {
  vehicleRegistration: 'vehicle',
  businessRegistration: 'business',
  transportVehicleRegistration: 'transport',
  transportLearningPermit: 'transport',
};

export default function PayPage() {
  const { t } = useTranslation('pay');
  const { token } = useParams<{ token: string }>();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const fromBank = searchParams.get('from') === 'bank';
  const [status, setStatus] = useState<PaymentStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<{ code: string; message: string } | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [polls, setPolls] = useState(0);

  const refresh = useCallback(async () => {
    if (!token) return;
    try {
      const s = await getStatus(token);
      setStatus(s);
      setError(null);
    } catch (e) {
      if (e instanceof PaymentError) {
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

  // Back from the bank: the callback may land a moment after the redirect.
  useEffect(() => {
    if (!fromBank || status?.status !== 'pending' || polls >= POLL_LIMIT) return;
    const id = window.setTimeout(() => {
      setPolls((n) => n + 1);
      refresh();
    }, POLL_MS);
    return () => window.clearTimeout(id);
  }, [fromBank, status, polls, refresh]);

  async function handlePay() {
    if (!token) return;
    setSubmitting(true);
    setError(null);
    try {
      const session = await checkout(token);
      navigate(session.redirectUrl);
    } catch (e) {
      if (e instanceof PaymentError) {
        setError({ code: e.code, message: e.message });
        refresh();
      } else {
        setError({ code: 'network', message: e instanceof Error ? e.message : String(e) });
      }
      setSubmitting(false);
    }
  }

  if (loading) {
    return (
      <div className="pay-page">
        <div className="card">
          <p className="muted">{t('loading')}</p>
        </div>
      </div>
    );
  }

  if (!status) {
    return (
      <div className="pay-page">
        <div className="card">
          <h1 className="card-title">{t('linkCard.title')}</h1>
          <p className="form-error">
            {error?.code === 'unknown_link' || !error ? t('linkCard.unknown') : error.message}
          </p>
        </div>
      </div>
    );
  }

  const issuerKey = ISSUERS[status.processDefinitionKey] ?? 'transport';
  const issuer = t(`issuer.${issuerKey}.name`);
  const issuerSub = t(`issuer.${issuerKey}.sub`);
  const service = t(`services.${status.processDefinitionKey}`, {
    defaultValue: status.serviceName,
  });

  if (status.status === 'paid') {
    return (
      <div className="pay-page">
        <div className="card pay-success">
          <div className="pay-success-icon" aria-hidden="true">
            <svg
              width="36"
              height="36"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2.5"
              strokeLinecap="round"
              strokeLinejoin="round"
            >
              <path d="M20 6L9 17l-5-5" />
            </svg>
          </div>
          <h1 className="card-title">{t('paid.title')}</h1>
          <p className="muted">
            <Trans
              t={t}
              i18nKey="paid.body"
              values={{ amount: formatCurrency(status.amount, status.currency), issuer }}
              components={{ strong: <strong /> }}
            />
          </p>
          <dl className="pay-summary">
            <div className="summary-row">
              <dt>{t('summary.reference')}</dt>
              <dd className="pay-mono">{status.reference}</dd>
            </div>
            <div className="summary-row">
              <dt>{t('summary.recipient')}</dt>
              <dd>{translateBackendName(t, status.recipient)}</dd>
            </div>
          </dl>
        </div>
      </div>
    );
  }

  return (
    <div className="pay-page">
      <div className="card pay-card">
        <header
          className={`pay-header ${issuerKey === 'business' ? 'pay-header-business' : 'pay-header-vehicle'}`}
        >
          <div>
            <h1 className="pay-title">{t('header.title')}</h1>
            <p className="pay-subtitle">
              {issuer} · {issuerSub}
            </p>
          </div>
          <span className="pay-status">{t('header.awaitingPayment')}</span>
        </header>

        <div className="pay-grid">
          <section className="pay-summary-block">
            <h2 className="pay-section-title">{t('invoice.title')}</h2>
            <dl className="pay-summary">
              <div className="summary-row">
                <dt>{t('summary.for')}</dt>
                <dd>{service}</dd>
              </div>
              <div className="summary-row">
                <dt>{t('summary.recipient')}</dt>
                <dd>{translateBackendName(t, status.recipient)}</dd>
              </div>
              <div className="summary-row">
                <dt>{t('summary.reference')}</dt>
                <dd className="pay-mono">{status.reference}</dd>
              </div>
            </dl>
            <div className="pay-amount">
              <span className="pay-amount-label">{t('invoice.amountDue')}</span>
              <span className="pay-amount-value">
                {formatCurrency(status.amount, status.currency)}
              </span>
            </div>
          </section>

          <section className="pay-bank-block">
            <h2 className="pay-section-title">{t('bank.title')}</h2>
            <p className="field-hint">{t('bank.redirectHint')}</p>
            {fromBank && polls < POLL_LIMIT && (
              <p className="muted" role="status">
                {t('bank.waitingForBank')}
              </p>
            )}

            {error && <p className="form-error">{error.message}</p>}

            <div className="form-actions">
              <button
                type="button"
                className="btn btn-primary pay-confirm"
                onClick={handlePay}
                disabled={submitting}
              >
                {submitting
                  ? t('actions.redirecting')
                  : t('actions.pay', {
                      amount: formatCurrency(status.amount, status.currency),
                    })}
              </button>
            </div>
          </section>
        </div>
      </div>
    </div>
  );
}
