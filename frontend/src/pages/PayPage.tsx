import { useCallback, useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import {
  confirm,
  getStatus,
  PaymentError,
  type PaymentStatus,
} from '../api/paymentsApi';

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
  { value: 'seb',      label: 'SEB' },
  { value: 'lhv',      label: 'LHV Pank' },
  { value: 'coop',     label: 'Coop Pank' },
  { value: 'luminor',  label: 'Luminor' },
];

function formatAmount(amount: number, currency: string): string {
  return amount.toLocaleString('et-EE', {
    style: 'currency',
    currency,
    minimumFractionDigits: 2,
  });
}

export default function PayPage() {
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
          <p className="muted">Loading payment details…</p>
        </div>
      </div>
    );
  }

  if (!status) {
    return (
      <div className="pay-page">
        <div className="card">
          <h1 className="card-title">Payment link</h1>
          <p className="form-error">
            {error?.message ?? 'This payment link is unknown or has expired.'}
          </p>
        </div>
      </div>
    );
  }

  const isVehicle = status.processDefinitionKey === 'vehicleRegistration';
  const issuer = isVehicle ? 'Transpordiamet POC' : 'Äriregister POC';
  const issuerSub = isVehicle
    ? 'Estonian Transport Authority'
    : 'Estonian Business Register';

  if (status.status === 'paid') {
    return (
      <div className="pay-page">
        <div className="card pay-success">
          <div className="pay-success-icon" aria-hidden="true">
            <svg width="36" height="36" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
              <path d="M20 6L9 17l-5-5" />
            </svg>
          </div>
          <h1 className="card-title">Payment received</h1>
          <p className="muted">
            Thank you. Your state fee of <strong>{formatAmount(status.amount, status.currency)}</strong> has
            been credited to {issuer}. The case will continue to the next step
            automatically.
          </p>
          <dl className="pay-summary">
            <div className="summary-row">
              <dt>Reference</dt>
              <dd className="pay-mono">{status.reference}</dd>
            </div>
            <div className="summary-row">
              <dt>Recipient</dt>
              <dd>{status.recipient}</dd>
            </div>
          </dl>
        </div>
      </div>
    );
  }

  return (
    <div className="pay-page">
      <div className="card pay-card">
        <header className={`pay-header ${isVehicle ? 'pay-header-vehicle' : 'pay-header-business'}`}>
          <div>
            <h1 className="pay-title">Pay state fee</h1>
            <p className="pay-subtitle">{issuer} · {issuerSub}</p>
          </div>
          <span className="pay-status">Awaiting payment</span>
        </header>

        <div className="pay-grid">
          <section className="pay-summary-block">
            <h2 className="pay-section-title">Invoice</h2>
            <dl className="pay-summary">
              <div className="summary-row">
                <dt>Payer</dt>
                <dd>{status.payerName || '—'}</dd>
              </div>
              <div className="summary-row">
                <dt>For</dt>
                <dd>{status.item}</dd>
              </div>
              <div className="summary-row">
                <dt>Recipient</dt>
                <dd>{status.recipient}</dd>
              </div>
              <div className="summary-row">
                <dt>IBAN</dt>
                <dd className="pay-mono">{status.iban}</dd>
              </div>
              <div className="summary-row">
                <dt>Reference</dt>
                <dd className="pay-mono">{status.reference}</dd>
              </div>
            </dl>
            <div className="pay-amount">
              <span className="pay-amount-label">Amount due</span>
              <span className="pay-amount-value">
                {formatAmount(status.amount, status.currency)}
              </span>
            </div>
          </section>

          <section className="pay-bank-block">
            <h2 className="pay-section-title">Pay from</h2>
            <label className="field">
              <span className="field-label">Sender bank</span>
              <select
                className="field-input"
                value={bank}
                onChange={(e) => setBank(e.target.value)}
                disabled={submitting}
              >
                {ESTONIAN_BANKS.map((b) => (
                  <option key={b.value} value={b.value}>{b.label}</option>
                ))}
              </select>
            </label>
            <p className="field-hint">
              POC demo — the Confirm button below simulates a successful SEPA
              transfer without involving a real bank. Production would
              redirect to the chosen bank's online banking screen.
            </p>

            {error && <p className="form-error">{error.message}</p>}

            <div className="form-actions">
              <button
                type="button"
                className="btn btn-primary pay-confirm"
                onClick={handleConfirm}
                disabled={submitting}
              >
                {submitting
                  ? 'Confirming…'
                  : `Confirm payment of ${formatAmount(status.amount, status.currency)}`}
              </button>
            </div>
          </section>
        </div>
      </div>
    </div>
  );
}
