import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Lock, Mail, ShieldCheck, ChevronDown } from 'lucide-react';

/**
 * Strip above the header that states what the site is and how to recognise
 * it, the pattern public-sector portals use so visitors can tell the real
 * service from a look-alike. On the public demo it also warns visitors not to
 * enter real personal data (every verification and notification email lands
 * in one shared Mailpit inbox anyone can read) and links to that inbox.
 *
 * The demo part shows by default; set `DEMO_BANNER: 'false'` in /env.js
 * (window.__ENV__) to hide it on a non-demo deployment.
 */
function resolveMailpitUrl(): string {
  // Explicit runtime override wins (same /env.js mechanism as Keycloak config).
  const configured = window.__ENV__?.MAILPIT_URL;
  if (configured && configured.length > 0) return configured;
  // On the deployed host the SPA and Mailpit share an origin, so a relative
  // path just works. Locally, Mailpit is on its own port (the `mail` profile).
  const host = window.location.hostname;
  if (host === 'localhost' || host === '127.0.0.1') return 'http://localhost:8025/';
  return '/mailpit/';
}

export default function OfficialBanner() {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const demo = window.__ENV__?.DEMO_BANNER !== 'false';

  return (
    <div className="official">
      <div className="official-row shell-wrap">
        <ShieldCheck aria-hidden="true" />
        <span>{t('brand:portal')}</span>
        <button
          type="button"
          className="official-toggle"
          aria-expanded={open}
          aria-controls="official-more"
          onClick={() => setOpen((o) => !o)}
        >
          {t('official.howYouKnow')}
          <ChevronDown aria-hidden="true" />
        </button>
        {demo && (
          <span className="official-demo" role="note">
            <span className="official-demo-tag">{t('demo.badge')}</span>
            <span className="official-demo-text">{t('demo.warning')}</span>
            <a href={resolveMailpitUrl()} target="_blank" rel="noopener noreferrer">
              <Mail aria-hidden="true" />
              {t('demo.inbox')}
            </a>
          </span>
        )}
      </div>
      <div id="official-more" className="official-more" hidden={!open}>
        <div className="shell-wrap official-more-grid">
          <p>
            <ShieldCheck aria-hidden="true" />
            <span>
              <strong>{t('official.signInTitle')}</strong>
              {t('official.signInBody')}
            </span>
          </p>
          <p>
            <Lock aria-hidden="true" />
            <span>
              <strong>{t('official.httpsTitle')}</strong>
              {t('official.httpsBody')}
            </span>
          </p>
        </div>
      </div>
    </div>
  );
}
