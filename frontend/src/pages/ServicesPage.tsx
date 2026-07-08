import { useEffect, useMemo, useRef, useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { QRCodeSVG } from 'qrcode.react';
import {
  listProcessDefinitions,
  startProcess,
  listTasksByInstance,
  type ProcessDefinition,
} from '../api/camundaClient';
import { useAuth } from '../auth/AuthProvider';
import { CATEGORIES, categoryOf, type CategoryId } from '../services/categories';
import { CategoryIcon } from '../services/CategoryIcon';
import { translateBackendName } from '../i18n/backendNames';
import { formatNumber } from '../i18n/format';

/**
 * PartA landing — life-event catalog. Six category tiles let citizens pick a
 * topic before drilling into the specific service; tiles with no services
 * deployed yet stay visible so the catalog shape is stable as new BPMN models
 * land. Anonymous browsing is allowed; starting still routes through Keycloak.
 */
export default function ServicesPage() {
  const { t } = useTranslation('services');
  const navigate = useNavigate();
  const { authenticated, login, register } = useAuth();

  const [services, setServices] = useState<ProcessDefinition[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [picked, setPicked] = useState<CategoryId | null>(null);
  const [startingKey, setStartingKey] = useState<string | null>(null);
  const servicesPanelRef = useRef<HTMLElement>(null);

  // When a category opens, scroll the services panel into view. The panel
  // renders below the grid and on a typical viewport ends up under the fold —
  // without this you click a tile and nothing visible happens, which reads as
  // a broken button rather than "scroll down to see the list".
  useEffect(() => {
    if (picked && servicesPanelRef.current) {
      servicesPanelRef.current.scrollIntoView({
        behavior: 'smooth',
        block: 'nearest',
      });
    }
  }, [picked]);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setServices(await listProcessDefinitions());
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  /** category id → list of services in that category. */
  const byCategory = useMemo(() => {
    const m = new Map<CategoryId, ProcessDefinition[]>();
    CATEGORIES.forEach((c) => m.set(c.id, []));
    for (const s of services) m.get(categoryOf(s.key))!.push(s);
    return m;
  }, [services]);

  async function startService(key: string) {
    if (!authenticated) {
      login();
      return;
    }
    setStartingKey(key);
    setError(null);
    try {
      const instance = await startProcess(key);
      const tasks = await listTasksByInstance(instance.id);
      navigate(tasks.length > 0 ? `/tasks/${tasks[0].id}` : '/my-processes');
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setStartingKey(null);
    }
  }

  const pickedCategory = picked ? CATEGORIES.find((c) => c.id === picked) : null;
  const pickedServices = picked ? (byCategory.get(picked) ?? []) : [];

  return (
    <div className="catalog">
      <section className="catalog-hero">
        <div className="catalog-hero-inner">
          <div className="hero-copy">
            <span className="hero-eyebrow">{t('hero.eyebrow')}</span>
            <h1 className="catalog-hero-title">
              {t('hero.titleLine1')}
              <br />
              <span className="hero-accent">{t('hero.titleAccent')}</span>
            </h1>
            <p className="catalog-hero-sub">{t('hero.sub')}</p>
            {!authenticated && (
              <div className="hero-cta">
                <button type="button" className="hero-btn-primary" onClick={login}>
                  {t('hero.signIn')}
                </button>
                <button type="button" className="hero-btn-ghost" onClick={register}>
                  {t('hero.join')}
                </button>
              </div>
            )}
            <p className="hero-trust">
              <svg
                width="13"
                height="13"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="2"
                strokeLinecap="round"
                strokeLinejoin="round"
                aria-hidden="true"
              >
                <rect x="3" y="11" width="18" height="11" rx="2" />
                <path d="M7 11V7a5 5 0 0 1 10 0v4" />
              </svg>
              {t('hero.trust')}
            </p>
            <MobileAppCard />
          </div>
          <div className="hero-art" aria-hidden="true">
            <HeroIllustration />
          </div>
        </div>
      </section>

      <div className="hero-stats">
        <div className="hero-stat">
          <span className="hero-stat-value">{t('stats.alwaysOpen.value')}</span>
          <span className="hero-stat-label">{t('stats.alwaysOpen.label')}</span>
        </div>
        <div className="hero-stat">
          <span className="hero-stat-value">{t('stats.averageApplication.value')}</span>
          <span className="hero-stat-label">{t('stats.averageApplication.label')}</span>
        </div>
        <div className="hero-stat">
          <span className="hero-stat-value">{t('stats.digital.value')}</span>
          <span className="hero-stat-label">{t('stats.digital.label')}</span>
        </div>
        <div className="hero-stat">
          <span className="hero-stat-value">{loading ? '…' : formatNumber(services.length)}</span>
          <span className="hero-stat-label">{t('stats.liveServices.label')}</span>
        </div>
      </div>

      {error && <p className="form-error catalog-error">{error}</p>}
      {loading && !error && <p className="muted catalog-status">{t('catalog.loading')}</p>}

      {!loading && !error && (
        <>
          <section className="catalog-section-head">
            <h2>{t('catalog.heading')}</h2>
            <p>{t('catalog.sub')}</p>
          </section>
          <div className="catalog-grid">
            {CATEGORIES.map((cat) => {
              const categoryServices = byCategory.get(cat.id) ?? [];
              const count = categoryServices.length;
              const empty = count === 0;
              const single = count === 1;
              const isPicked = picked === cat.id;
              const isBusy = single && startingKey === categoryServices[0].key;
              return (
                <button
                  key={cat.id}
                  className={`cat-tile cat-${cat.id}${empty ? ' cat-empty' : ''}${
                    isPicked ? ' cat-picked' : ''
                  }`}
                  onClick={() => {
                    // One service in a category — skip the intermediate panel
                    // and start (or sign-in-then-start) directly. Multi-service
                    // categories still need a picker.
                    if (single) {
                      startService(categoryServices[0].key);
                      return;
                    }
                    setPicked(isPicked ? null : cat.id);
                  }}
                  disabled={empty || (startingKey !== null && !isBusy)}
                  aria-pressed={!single && isPicked}
                >
                  <span className="cat-icon" aria-hidden="true">
                    <CategoryIcon id={cat.id} />
                  </span>
                  <span className="cat-body">
                    <span className="cat-name">{t(`common:categories.${cat.id}.name`)}</span>
                    <span className="cat-blurb">{t(`common:categories.${cat.id}.blurb`)}</span>
                  </span>
                  <span className="cat-count">
                    {empty
                      ? t('catalog.tile.comingSoon')
                      : isBusy
                        ? t('catalog.tile.starting')
                        : single
                          ? authenticated
                            ? t('catalog.tile.start')
                            : t('catalog.tile.signInToStart')
                          : isPicked
                            ? t('catalog.tile.servicesBelow', { count })
                            : t('catalog.tile.serviceCount', { count })}
                  </span>
                </button>
              );
            })}
          </div>

          {pickedCategory && (
            <section ref={servicesPanelRef} className="cat-services">
              <div className="cat-services-head">
                <h2>{t(`common:categories.${pickedCategory.id}.name`)}</h2>
                <button className="btn btn-link" onClick={() => setPicked(null)}>
                  {t('common:actions.close')}
                </button>
              </div>
              {!authenticated && <p className="muted">{t('catalog.panel.accountNotice')}</p>}
              {pickedServices.length === 0 ? (
                <p className="empty">{t('catalog.panel.empty')}</p>
              ) : (
                <ul className="row-list">
                  {pickedServices.map((s) => (
                    <li key={s.id}>
                      <button
                        className="row"
                        onClick={() => startService(s.key)}
                        disabled={startingKey !== null}
                      >
                        <span className="row-main">
                          <span className="row-title">
                            {s.name ? translateBackendName(t, s.name) : s.key}
                          </span>
                          <span className="row-sub">
                            {t('catalog.panel.rowMeta', { key: s.key, version: s.version })}
                          </span>
                        </span>
                        <span className="row-action">
                          {startingKey === s.key
                            ? t('catalog.tile.starting')
                            : authenticated
                              ? t('catalog.tile.start')
                              : t('catalog.tile.signInToStart')}
                        </span>
                      </button>
                    </li>
                  ))}
                </ul>
              )}
            </section>
          )}
        </>
      )}
    </div>
  );
}

/**
 * "Try it on your phone" hero card — a scannable QR plus a direct link to the
 * Flutter mobile applicant app, which is served at `/mobile` outside the SPA
 * router (own container, Traefik PathPrefix). The QR encodes the app's
 * absolute URL on the current host, so it works on localhost and on
 * companylab.ai alike without hardcoding. The whole card is the link, so a
 * phone visitor can tap it and a desktop visitor can scan the code.
 */
function MobileAppCard() {
  const { t } = useTranslation('services');
  const mobileUrl = `${window.location.origin}/mobile`;
  return (
    <a className="mobile-app-card" href="/mobile" aria-label={t('mobile.ariaLabel')}>
      <span className="mobile-app-qr">
        <QRCodeSVG value={mobileUrl} size={84} bgColor="#ffffff" fgColor="#0a221c" />
      </span>
      <span className="mobile-app-text">
        <strong className="mobile-app-title">{t('mobile.title')}</strong>
        <span className="mobile-app-sub">{t('mobile.sub')}</span>
        <span className="mobile-app-link">{t('mobile.open')}</span>
      </span>
    </a>
  );
}

/**
 * Friendly civic-portal illustration for the hero — a government building
 * with an approved-document card floating in front, an orbiting dashed ring
 * and a few sparkles. Pure inline SVG, animated via the .hero-* CSS classes
 * (disabled under prefers-reduced-motion).
 */
function HeroIllustration() {
  return (
    <svg viewBox="0 0 360 300" fill="none" role="img" aria-hidden="true">
      {/* soft glow backdrop */}
      <circle cx="190" cy="150" r="130" fill="rgba(52, 211, 153, 0.08)" />
      <circle cx="190" cy="150" r="96" fill="rgba(52, 211, 153, 0.08)" />

      {/* orbiting dashed ring */}
      <g className="hero-spin">
        <circle
          cx="190"
          cy="150"
          r="126"
          stroke="rgba(255,255,255,0.18)"
          strokeWidth="1.5"
          strokeDasharray="3 10"
        />
        <circle cx="316" cy="150" r="5" fill="#34d399" />
      </g>

      {/* government building */}
      <g className="hero-float-slow">
        {/* pediment */}
        <path
          d="M110 112 L190 74 L270 112 Z"
          fill="rgba(255,255,255,0.92)"
          stroke="#0a221c"
          strokeWidth="0"
        />
        <rect x="118" y="112" width="144" height="10" rx="3" fill="#34d399" />
        {/* columns */}
        <rect x="126" y="130" width="16" height="62" rx="4" fill="rgba(255,255,255,0.85)" />
        <rect x="156" y="130" width="16" height="62" rx="4" fill="rgba(255,255,255,0.85)" />
        <rect x="186" y="130" width="16" height="62" rx="4" fill="rgba(255,255,255,0.85)" />
        <rect x="216" y="130" width="16" height="62" rx="4" fill="rgba(255,255,255,0.85)" />
        <rect x="244" y="130" width="0" height="62" rx="4" fill="rgba(255,255,255,0.85)" />
        {/* base steps */}
        <rect x="112" y="196" width="156" height="10" rx="4" fill="rgba(255,255,255,0.9)" />
        <rect x="102" y="208" width="176" height="10" rx="4" fill="rgba(255,255,255,0.75)" />
        {/* door light */}
        <circle cx="190" cy="96" r="7" fill="#34d399" />
      </g>

      {/* approved-document card */}
      <g className="hero-float">
        <rect
          x="232"
          y="150"
          width="92"
          height="112"
          rx="12"
          fill="#ffffff"
          stroke="rgba(10,34,28,0.08)"
        />
        <rect x="246" y="168" width="64" height="7" rx="3.5" fill="#cde7dd" />
        <rect x="246" y="184" width="48" height="7" rx="3.5" fill="#e3f0ea" />
        <rect x="246" y="200" width="56" height="7" rx="3.5" fill="#e3f0ea" />
        {/* check badge */}
        <g className="hero-pop">
          <circle cx="278" cy="238" r="17" fill="#34d399" />
          <path
            d="M270 238 l6 6 l11 -12"
            stroke="#0a221c"
            strokeWidth="3.5"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </g>
      </g>

      {/* small progress stepper on a chip card, echoing the case stepper */}
      <g className="hero-float-slow">
        <rect x="44" y="156" width="118" height="40" rx="20" fill="rgba(255,255,255,0.95)" />
        <circle cx="70" cy="176" r="8" fill="#005c4c" />
        <path
          d="M66.5 176 l2.6 2.6 l4.6 -5.2"
          stroke="#fff"
          strokeWidth="2.2"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
        <rect x="82" y="174" width="16" height="4" rx="2" fill="#005c4c" />
        <circle cx="106" cy="176" r="8" fill="none" stroke="#005c4c" strokeWidth="2.5" />
        <circle cx="106" cy="176" r="3" fill="#34d399" />
        <rect x="118" y="174" width="16" height="4" rx="2" fill="#cde7dd" />
        <circle cx="140" cy="176" r="8" fill="none" stroke="#cde7dd" strokeWidth="2.5" />
      </g>

      {/* sparkles */}
      <g className="hero-pop">
        <path
          d="M86 84 l3.5 9 9 3.5 -9 3.5 -3.5 9 -3.5 -9 -9 -3.5 9 -3.5 Z"
          fill="#34d399"
          opacity="0.9"
        />
      </g>
      <g className="hero-float">
        <path
          d="M308 92 l2.5 6.5 6.5 2.5 -6.5 2.5 -2.5 6.5 -2.5 -6.5 -6.5 -2.5 6.5 -2.5 Z"
          fill="rgba(255,255,255,0.7)"
        />
      </g>
      <circle cx="64" cy="232" r="4" fill="rgba(52,211,153,0.6)" />
      <circle cx="330" cy="206" r="3" fill="rgba(255,255,255,0.5)" />
    </svg>
  );
}
