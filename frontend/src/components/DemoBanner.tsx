import { useTranslation } from 'react-i18next';

/**
 * Slim strip above the app header for the public companylab.ai demo: warns
 * visitors not to enter real personal data (every verification + notification
 * email lands in one shared Mailpit inbox anyone can read) and links to that
 * inbox so they can find their own mail.
 *
 * Shown by default; set `DEMO_BANNER: 'false'` in /env.js (window.__ENV__) to
 * hide it on a non-demo deployment.
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

export default function DemoBanner() {
  const { t } = useTranslation();
  if (window.__ENV__?.DEMO_BANNER === 'false') return null;

  return (
    <div className="demo-banner" role="note">
      <span className="demo-banner-text">
        <span className="demo-banner-dot" aria-hidden="true" />
        {t('demo.warning')}
      </span>
      <a
        className="demo-banner-inbox"
        href={resolveMailpitUrl()}
        target="_blank"
        rel="noopener noreferrer"
      >
        <svg
          width="14"
          height="14"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
          aria-hidden="true"
        >
          <rect x="2" y="4" width="20" height="16" rx="2" />
          <path d="m22 7-8.97 5.7a1.94 1.94 0 0 1-2.06 0L2 7" />
        </svg>
        {t('demo.inbox')}
      </a>
    </div>
  );
}
