import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate, useParams } from 'react-router';
import { cancel, getSession, pay, safeReturnPath, type BankSession } from '../api/mockBankApi';
import { PaymentError } from '../api/paymentsApi';
import { formatCurrency } from '../i18n/format';

/**
 * The demo payment provider's page, reached from the pay page's checkout
 * redirect at `/mock-bank/:sessionId`. Deliberately styled as a separate
 * site ("Demo bank"): in production the payer is on the real provider's own
 * domain here, and this page does not exist.
 *
 * Pay and Cancel go to the mock provider, which reports the outcome to the
 * merchant through a signed server-to-server callback. Only then does the
 * payer return to the merchant's pay page, which reads the result from the
 * backend instead of trusting this page.
 */
export default function MockBankPage() {
  const { t } = useTranslation('mock-bank');
  const { sessionId } = useParams<{ sessionId: string }>();
  const navigate = useNavigate();
  const [session, setSession] = useState<BankSession | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    if (!sessionId) return;
    try {
      setSession(await getSession(sessionId));
      setError(null);
    } catch (e) {
      setError(e instanceof PaymentError && e.httpStatus === 404 ? t('unknown') : String(e));
    } finally {
      setLoading(false);
    }
  }, [sessionId, t]);

  useEffect(() => {
    load();
  }, [load]);

  function backToMerchant(s: BankSession) {
    const path = safeReturnPath(s.returnPath);
    if (path) navigate(`${path}?from=bank`);
  }

  async function act(action: typeof pay) {
    if (!sessionId) return;
    setBusy(true);
    setError(null);
    try {
      const next = await action(sessionId);
      setSession(next);
      backToMerchant(next);
    } catch (e) {
      setError(e instanceof PaymentError ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="mock-bank">
      <header className="mock-bank-header">
        <span className="mock-bank-logo" aria-hidden="true">
          DB
        </span>
        <div>
          <p className="mock-bank-name">{t('brand')}</p>
          <p className="mock-bank-tagline">{t('tagline')}</p>
        </div>
      </header>

      <main className="mock-bank-card">
        {loading && <p className="muted">{t('loading')}</p>}

        {!loading && !session && <p className="form-error">{error ?? t('unknown')}</p>}

        {session && (
          <>
            <h1 className="mock-bank-title">{t('title')}</h1>
            <dl className="mock-bank-summary">
              <div className="summary-row">
                <dt>{t('merchant')}</dt>
                <dd>{session.merchantName}</dd>
              </div>
              <div className="summary-row">
                <dt>{t('reference')}</dt>
                <dd className="pay-mono">{session.reference}</dd>
              </div>
              <div className="summary-row">
                <dt>{t('amount')}</dt>
                <dd className="mock-bank-amount">
                  {formatCurrency(session.amount, session.currency)}
                </dd>
              </div>
            </dl>

            {error && <p className="form-error">{error}</p>}

            {session.status === 'OPEN' ? (
              <div className="form-actions">
                <button type="button" className="btn" onClick={() => act(cancel)} disabled={busy}>
                  {t('cancel')}
                </button>
                <button
                  type="button"
                  className="btn btn-primary mock-bank-pay"
                  onClick={() => act(pay)}
                  disabled={busy}
                >
                  {busy ? t('processing') : t('pay')}
                </button>
              </div>
            ) : (
              <>
                <p className="muted">
                  {session.status === 'PAID' ? t('done.paid') : t('done.cancelled')}
                </p>
                <div className="form-actions">
                  <button
                    type="button"
                    className="btn btn-primary"
                    onClick={() => backToMerchant(session)}
                  >
                    {t('back')}
                  </button>
                </div>
              </>
            )}

            <p className="mock-bank-note">{t('demoNote')}</p>
          </>
        )}
      </main>
    </div>
  );
}
