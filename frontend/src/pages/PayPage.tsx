import { useCallback, useEffect, useState } from 'react';
import { Trans, useTranslation } from 'react-i18next';
import { useParams } from 'react-router-dom';
import { confirm, getStatus, PaymentError, type PaymentStatus } from '../api/paymentsApi';
import { translateBackendName } from '../i18n/backendNames';
import { formatCurrency } from '../i18n/format';

/**
 * Public, unauthenticated page reached from the approval email's pay link
 * `${frontendBaseUrl}/pay/:processInstanceId`. No Keycloak — the process
 * instance id in the URL is the credential (POC; production would gate
 * with a payment token).
 *
 * Visual shape per redomain plan D2: fake Estonian SEPA bank-payment
 * screen — invoice summary on the left, "sender bank" picker + Confirm
 * button on the right. The bank picker is purely cosmetic; the
 * Confirm button correlates a `PaymentReceived` message into the
 * process instance, which unblocks the receive task that's waiting
 * between the approval email and the certificate / B-card generation.
 *
 * Renders three terminal states:
 *   pending   — show the invoice + bank picker + Confirm
 *   paid      — show a success card
 *   unknown   — show a not-found card (404 from /status)
 */

const ESTONIAN_BANKS = [
  { value: 'swedbank', label: 'Swedbank' },
  { value: 'seb', label: 'SEB' },
  { value: 'lhv', label: 'LHV Pank' },
  { value: 'coop', label: 'Coop Pank' },
  { value: 'luminor', label: 'Luminor' },
];

export default function PayPage() {
  const { t } = useTranslation('pay');
  const { processInstanceId } = useParams<{ processInstanceId: string }>();
  const [status, setStatus] = useState<PaymentStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<{ code: string; message: string } | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [bank, setBank] = useState<string>('swedbank');

  const refresh = useCallback(async () => {
    if (!processInstanceId) return;
    try {
      const s = await getStatus(processInstanceId);
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
  }, [processInstanceId]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  async function handleConfirm() {
    if (!processInstanceId) return;
    setSubmitting(true);
    setError(null);
    try {
      const next = await confirm(processInstanceId);
      setStatus(next);
    } catch (e) {
      if (e instanceof PaymentError) {
        setError({ code: e.code, message: e.message });
        refresh();
      } else {
        setError({ code: 'network', message: e instanceof Error ? e.message : String(e) });
      }
    } finally {
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
          <p className="form-error">{error?.message ?? t('linkCard.unknown')}</p>
        </div>
      </div>
    );
  }

  const isVehicle = status.processDefinitionKey === 'vehicleRegistration';
  const issuer = isVehicle ? t('issuer.vehicle.name') : t('issuer.business.name');
  const issuerSub = isVehicle ? t('issuer.vehicle.sub') : t('issuer.business.sub');

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
          className={`pay-header ${isVehicle ? 'pay-header-vehicle' : 'pay-header-business'}`}
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
                <dt>{t('summary.payer')}</dt>
                <dd>{status.payerName || '—'}</dd>
              </div>
              <div className="summary-row">
                <dt>{t('summary.for')}</dt>
                <dd>{status.item}</dd>
              </div>
              <div className="summary-row">
                <dt>{t('summary.recipient')}</dt>
                <dd>{translateBackendName(t, status.recipient)}</dd>
              </div>
              <div className="summary-row">
                <dt>{t('summary.iban')}</dt>
                <dd className="pay-mono">{status.iban}</dd>
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
            <label className="field">
              <span className="field-label">{t('bank.senderBank')}</span>
              <select
                className="field-input"
                value={bank}
                onChange={(e) => setBank(e.target.value)}
                disabled={submitting}
              >
                {ESTONIAN_BANKS.map((b) => (
                  <option key={b.value} value={b.value}>
                    {b.label}
                  </option>
                ))}
              </select>
            </label>
            <p className="field-hint">{t('bank.demoHint')}</p>

            {error && <p className="form-error">{error.message}</p>}

            <div className="form-actions">
              <button
                type="button"
                className="btn btn-primary pay-confirm"
                onClick={handleConfirm}
                disabled={submitting}
              >
                {submitting
                  ? t('actions.confirming')
                  : t('actions.confirmPayment', {
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
