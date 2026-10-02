import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type FormEvent,
  type PointerEvent,
} from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { QRCodeSVG } from 'qrcode.react';
import {
  Accessibility,
  ArrowRight,
  Check,
  ExternalLink,
  Fingerprint,
  History,
  KeyRound,
  Plus,
  Search,
  ShieldCheck,
  UserLock,
  X,
} from 'lucide-react';
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

const reducedMotion = () => window.matchMedia('(prefers-reduced-motion: reduce)').matches;

/**
 * PartA landing. Search is the main way in; the hero card shows what a case
 * looks like while it moves through the steps; below sit the six life-event
 * topics (topics with no deployed service stay visible so the catalog keeps
 * its shape), the live services with who takes part and what they cost, how a
 * case runs, and help. Anonymous browsing is allowed; starting a service
 * still routes through Keycloak.
 */
export default function ServicesPage() {
  const { t } = useTranslation('services');
  const navigate = useNavigate();
  const { authenticated, login } = useAuth();

  const [services, setServices] = useState<ProcessDefinition[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [picked, setPicked] = useState<CategoryId | null>(null);
  const [startingKey, setStartingKey] = useState<string | null>(null);
  const panelRef = useRef<HTMLElement>(null);

  // A picked topic opens its panel under the grid; bring it into view, or a
  // click looks like it did nothing.
  useEffect(() => {
    if (picked && panelRef.current) {
      panelRef.current.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
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

  const byCategory = useMemo(() => {
    const m = new Map<CategoryId, ProcessDefinition[]>();
    CATEGORIES.forEach((c) => m.set(c.id, []));
    for (const s of services) m.get(categoryOf(s.key))!.push(s);
    return m;
  }, [services]);

  // Live services in catalog order: by topic, then by name.
  const liveServices = useMemo(
    () => CATEGORIES.flatMap((c) => byCategory.get(c.id) ?? []),
    [byCategory],
  );

  const serviceName = useCallback(
    (s: ProcessDefinition) => (s.name ? translateBackendName(t, s.name) : s.key),
    [t],
  );

  const infoKeys = useMemo(
    () => new Set(Object.keys(t('live.info', { returnObjects: true }) as Record<string, unknown>)),
    [t],
  );
  const info = (key: string, field: 'summary' | 'who' | 'whoSub' | 'fee' | 'feeSub') =>
    t(`live.info.${infoKeys.has(key) ? key : 'default'}.${field}`);

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

  const startLabel = (key: string) =>
    startingKey === key
      ? t('live.starting')
      : authenticated
        ? t('live.start')
        : t('live.signInToStart');

  const pickedServices = picked ? (byCategory.get(picked) ?? []) : [];
  const rotating = t('hero.rotating', { returnObjects: true }) as string[];
  const trust = t('trust', { returnObjects: true }) as { title: string; body: string }[];
  const steps = t('how.steps', { returnObjects: true }) as { title: string; body: string }[];
  const faq = t('help.faq', { returnObjects: true }) as { q: string; a: string }[];
  const trustIcons = [KeyRound, UserLock, History, Accessibility];

  return (
    <div className="landing">
      <section className="landing-hero">
        <div className="landing-hero-copy">
          <span className="eyebrow anim-in">{t('hero.eyebrow')}</span>
          <h1 className="landing-title anim-in" style={{ '--d': '0.08s' } as CSSProperties}>
            <span className="sr-only">{t('hero.titleSr')}</span>
            <span aria-hidden="true">
              {t('hero.titleBefore')}
              <span className="rotator">
                {rotating.map((w) => (
                  <span key={w}>{w}</span>
                ))}
              </span>
              {t('hero.titleAfter')}
            </span>
          </h1>
          <p className="landing-lede anim-in" style={{ '--d': '0.18s' } as CSSProperties}>
            {t('hero.sub')}
          </p>
          <SearchBox
            services={liveServices}
            nameOf={serviceName}
            summaryOf={(s) => info(s.key, 'summary')}
            onPick={startService}
          />
          {liveServices.length > 0 && (
            <div className="popular anim-in" style={{ '--d': '0.36s' } as CSSProperties}>
              <span>{t('search.popular')}</span>
              {liveServices.slice(0, 3).map((s) => (
                <button
                  key={s.id}
                  type="button"
                  className="chip"
                  onClick={() => startService(s.key)}
                  disabled={startingKey !== null}
                >
                  {serviceName(s)}
                </button>
              ))}
            </div>
          )}
        </div>
        <div className="landing-hero-art anim-in" style={{ '--d': '0.3s' } as CSSProperties}>
          <HeroCaseCard />
        </div>
      </section>

      <div className="landing-trust">
        {trust.map((item, i) => {
          const Icon = trustIcons[i] ?? ShieldCheck;
          return (
            <div key={item.title} className="reveal">
              <Icon size={20} aria-hidden="true" />
              <span>
                <strong>{item.title}</strong>
                {item.body}
              </span>
            </div>
          );
        })}
      </div>

      {error && <p className="form-error landing-status">{error}</p>}
      {loading && !error && <p className="muted landing-status">{t('loading')}</p>}

      <section className="landing-block" id="events" aria-labelledby="events-h">
        <div className="landing-head">
          <div>
            <span className="eyebrow">{t('events.eyebrow')}</span>
            <h2 id="events-h">{t('events.heading')}</h2>
          </div>
          <p>{t('events.sub')}</p>
        </div>
        <div className="events">
          {CATEGORIES.map((cat) => {
            const list = byCategory.get(cat.id) ?? [];
            const count = list.length;
            const single = count === 1;
            const isPicked = picked === cat.id;
            return (
              <button
                key={cat.id}
                type="button"
                className={`event cat-${cat.id}${count === 0 ? ' is-empty' : ''}${
                  isPicked ? ' is-picked' : ''
                }`}
                title={t(`common:categories.${cat.id}.blurb`)}
                onClick={() => {
                  // One service in a topic: start it directly, no picker needed.
                  if (single) {
                    startService(list[0].key);
                    return;
                  }
                  setPicked(isPicked ? null : cat.id);
                }}
                onPointerMove={spotlight}
                disabled={loading || count === 0 || startingKey !== null}
                aria-pressed={!single && count > 0 ? isPicked : undefined}
              >
                <span className="event-icon">
                  <CategoryIcon id={cat.id} size={20} />
                </span>
                <span className="event-name">{t(`common:categories.${cat.id}.name`)}</span>
                <span className={`event-count${count === 0 ? ' is-soon' : ''}`}>
                  {count === 0 ? t('events.comingSoon') : t('events.online', { count })}
                </span>
              </button>
            );
          })}
        </div>

        {picked && (
          <section ref={panelRef} className="event-panel" aria-labelledby="event-panel-h">
            <div className="event-panel-head">
              <h3 id="event-panel-h">{t(`common:categories.${picked}.name`)}</h3>
              <button
                type="button"
                className="icon-btn"
                onClick={() => setPicked(null)}
                aria-label={t('panel.close')}
              >
                <X aria-hidden="true" />
              </button>
            </div>
            {!authenticated && <p className="muted">{t('panel.accountNotice')}</p>}
            {pickedServices.length === 0 ? (
              <p className="empty">{t('panel.empty')}</p>
            ) : (
              <ul className="event-panel-list">
                {pickedServices.map((s) => (
                  <li key={s.id}>
                    <span>
                      <strong>{serviceName(s)}</strong>
                      <span className="muted">{info(s.key, 'summary')}</span>
                    </span>
                    <button
                      type="button"
                      className="btn"
                      onClick={() => startService(s.key)}
                      disabled={startingKey !== null}
                    >
                      {startLabel(s.key)}
                      <ArrowRight aria-hidden="true" />
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </section>
        )}
      </section>

      {liveServices.length > 0 && (
        <section className="landing-block" id="services" aria-labelledby="live-h">
          <div className="landing-head">
            <div>
              <span className="eyebrow">{t('live.eyebrow')}</span>
              <h2 id="live-h">{t('live.heading', { count: liveServices.length })}</h2>
            </div>
          </div>
          <div className="svc-table" role="table" aria-labelledby="live-h">
            <div className="svc-row svc-row-head" role="row">
              <span role="columnheader">{t('live.colService')}</span>
              <span role="columnheader">{t('live.colWho')}</span>
              <span role="columnheader">{t('live.colFee')}</span>
              <span role="columnheader">
                <span className="sr-only">{t('live.start')}</span>
              </span>
            </div>
            {liveServices.map((s) => {
              const cat = categoryOf(s.key);
              return (
                <div key={s.id} className="svc-row" role="row">
                  <div role="cell" className="svc-main">
                    <span className={`svc-icon cat-${cat}`} aria-hidden="true">
                      <CategoryIcon id={cat} size={18} />
                    </span>
                    <span>
                      <strong>{serviceName(s)}</strong>
                      <span className="svc-sub">{info(s.key, 'summary')}</span>
                    </span>
                  </div>
                  <div role="cell" className="svc-cell">
                    <strong>{info(s.key, 'who')}</strong>
                    {info(s.key, 'whoSub')}
                  </div>
                  <div role="cell" className="svc-cell">
                    <strong>{info(s.key, 'fee')}</strong>
                    {info(s.key, 'feeSub')}
                  </div>
                  <div role="cell" className="svc-action">
                    <button
                      type="button"
                      className="btn"
                      onClick={() => startService(s.key)}
                      disabled={startingKey !== null}
                    >
                      {startLabel(s.key)}
                      <ArrowRight aria-hidden="true" />
                    </button>
                  </div>
                </div>
              );
            })}
          </div>
        </section>
      )}

      <section className="landing-block" id="how" aria-labelledby="how-h">
        <div className="landing-head">
          <div>
            <span className="eyebrow">{t('how.eyebrow')}</span>
            <h2 id="how-h">{t('how.heading')}</h2>
          </div>
          <p>{t('how.sub')}</p>
        </div>
        <ol className="how-steps">
          <span className="how-fill" aria-hidden="true" />
          {steps.map((step, i) => (
            <li key={step.title} className="reveal">
              <span className="how-num">{formatNumber(i + 1)}</span>
              <h3>{step.title}</h3>
              <p>{step.body}</p>
            </li>
          ))}
        </ol>
        <div className="landing-stats glass reveal">
          <div>
            <strong>{t('stats.alwaysOpen.value')}</strong>
            <span>{t('stats.alwaysOpen.label')}</span>
          </div>
          <div>
            <strong>{t('stats.averageApplication.value')}</strong>
            <span>{t('stats.averageApplication.label')}</span>
          </div>
          <div>
            <strong>{t('stats.digital.value')}</strong>
            <span>{t('stats.digital.label')}</span>
          </div>
          <div>
            <strong>{loading ? '…' : formatNumber(services.length)}</strong>
            <span>{t('stats.liveServices.label')}</span>
          </div>
        </div>
      </section>

      <section className="landing-block landing-help" id="help" aria-labelledby="help-h">
        <div className="panel reveal">
          <span className="eyebrow">{t('help.eyebrow')}</span>
          <h2 id="help-h" className="panel-title">
            {t('help.heading')}
          </h2>
          <div className="faq">
            {faq.map((item) => (
              <details key={item.q}>
                <summary>
                  {item.q}
                  <Plus size={18} aria-hidden="true" />
                </summary>
                <p>{item.a}</p>
              </details>
            ))}
          </div>
        </div>
        <div className="panel reveal">
          <span className="eyebrow">{t('help.contactEyebrow')}</span>
          <h2 className="panel-title">{t('help.contactHeading')}</h2>
          <p className="muted">{t('help.contactBody')}</p>
          <p className="contact-org">
            <strong>Tulepaak OÜ</strong>
            <span>{t('help.registry', { code: '12065884' })}</span>
          </p>
          <a
            className="btn"
            href="https://www.tulepaak.ee/contact"
            target="_blank"
            rel="noopener noreferrer"
          >
            {t('help.contactForm')}
            <ExternalLink aria-hidden="true" />
          </a>
          <p className="contact-status">
            <span className="muted">{t('help.status')}</span>
            <strong>
              <Check size={16} aria-hidden="true" />
              {t('help.statusOk', { count: services.length })}
            </strong>
          </p>
          <MobileAppCard />
        </div>
      </section>
    </div>
  );
}

/** Pointer-following highlight on life-event tiles (CSS reads --mx/--my). */
function spotlight(e: PointerEvent<HTMLElement>) {
  const r = e.currentTarget.getBoundingClientRect();
  e.currentTarget.style.setProperty('--mx', `${e.clientX - r.left}px`);
  e.currentTarget.style.setProperty('--my', `${e.clientY - r.top}px`);
}

/**
 * Hero search over the live services. Matches on the translated name and the
 * one-line summary. While empty and unfocused, the placeholder types out
 * example tasks; that stops for reduced-motion users.
 */
function SearchBox({
  services,
  nameOf,
  summaryOf,
  onPick,
}: {
  services: ProcessDefinition[];
  nameOf: (s: ProcessDefinition) => string;
  summaryOf: (s: ProcessDefinition) => string;
  onPick: (key: string) => void;
}) {
  const { t } = useTranslation('services');
  const [query, setQuery] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const [placeholder, setPlaceholder] = useState(t('search.placeholder'));
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    const hints = t('search.hints', { returnObjects: true }) as string[];
    setPlaceholder(t('search.placeholder'));
    if (reducedMotion() || !Array.isArray(hints) || hints.length === 0) return;
    let hint = 0;
    let chars = 0;
    let deleting = false;
    let timer: number;
    const tick = () => {
      const input = inputRef.current;
      if (input && document.activeElement !== input && !input.value) {
        const text = hints[hint];
        chars += deleting ? -1 : 1;
        setPlaceholder(text.slice(0, chars) + (chars < text.length ? '▍' : ''));
        if (!deleting && chars === text.length) {
          // The last hint is the plain placeholder: stop there.
          if (hint === hints.length - 1) return;
          deleting = true;
          timer = window.setTimeout(tick, 1600);
          return;
        }
        if (deleting && chars === 0) {
          deleting = false;
          hint += 1;
        }
      }
      timer = window.setTimeout(tick, deleting ? 28 : 55);
    };
    timer = window.setTimeout(tick, 900);
    return () => window.clearTimeout(timer);
  }, [t]);

  const q = query.trim().toLowerCase();
  const results = q
    ? services.filter((s) => `${nameOf(s)} ${summaryOf(s)} ${s.key}`.toLowerCase().includes(q))
    : [];
  const showResults = q.length > 0 || submitted;

  function onSubmit(e: FormEvent) {
    e.preventDefault();
    setSubmitted(true);
    if (results.length === 1) onPick(results[0].key);
  }

  return (
    <div className="search-wrap anim-in" style={{ '--d': '0.26s' } as CSSProperties}>
      <form className="search" role="search" onSubmit={onSubmit}>
        <label className="sr-only" htmlFor="service-search">
          {t('search.label')}
        </label>
        <Search size={20} aria-hidden="true" />
        <input
          ref={inputRef}
          id="service-search"
          type="search"
          autoComplete="off"
          placeholder={placeholder}
          value={query}
          onChange={(e) => {
            setQuery(e.target.value);
            setSubmitted(false);
          }}
        />
        <button type="submit" className="btn btn-primary">
          {t('search.submit')}
        </button>
      </form>
      {showResults && q && (
        <ul className="search-results" aria-live="polite">
          {results.length === 0 ? (
            <li className="search-empty">
              <strong>{t('search.noResults', { query: query.trim() })}</strong>
              <span>{t('search.noResultsHint')}</span>
            </li>
          ) : (
            results.map((s) => (
              <li key={s.id}>
                <button type="button" onClick={() => onPick(s.key)}>
                  <strong>{nameOf(s)}</strong>
                  <span>{summaryOf(s)}</span>
                  <ArrowRight size={16} aria-hidden="true" />
                </button>
              </li>
            ))
          )}
        </ul>
      )}
    </div>
  );
}

/**
 * Illustrative case card for the hero: plays a vehicle registration through
 * its steps so a first-time visitor sees what "follow every step" means. It
 * is labelled as an example and never shows real case data.
 */
function HeroCaseCard() {
  const { t } = useTranslation('services');
  const steps = t('caseCard.steps', { returnObjects: true }) as { title: string; who: string }[];
  const nextActions = t('caseCard.nextActions', { returnObjects: true }) as string[];
  const total = steps.length;
  const [step, setStep] = useState(() => (reducedMotion() ? total : 1));
  const cardRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (reducedMotion()) return;
    const id = window.setInterval(() => setStep((s) => (s > total ? 1 : s + 1)), 1800);
    return () => window.clearInterval(id);
  }, [total]);

  const done = Math.min(step, total);
  const finished = done >= total;

  function tilt(e: PointerEvent<HTMLDivElement>) {
    const card = cardRef.current;
    if (!card || e.pointerType !== 'mouse' || reducedMotion()) return;
    const r = card.getBoundingClientRect();
    const x = (e.clientX - r.left) / r.width - 0.5;
    const y = (e.clientY - r.top) / r.height - 0.5;
    card.style.transform = `rotateX(${-y * 5}deg) rotateY(${x * 6}deg)`;
  }

  return (
    <div className="case-stage">
      <div
        ref={cardRef}
        className="case-card glass"
        onPointerMove={tilt}
        onPointerLeave={() => {
          if (cardRef.current) cardRef.current.style.transform = '';
        }}
        role="img"
        aria-label={t('caseCard.label')}
      >
        <div className="case-card-in">
          <div className="case-card-top">
            <div>
              <strong className="case-card-title">{t('caseCard.title')}</strong>
              <span className="case-card-ref mono">
                {t('caseCard.reference')} · {t('caseCard.label')}
              </span>
            </div>
            <span className={`pill ${finished ? 'pill-ok' : 'pill-primary'}`}>
              {finished ? t('caseCard.decided') : t('caseCard.inProgress')}
            </span>
          </div>
          <div className="case-card-progress">
            <i style={{ width: `${(done / total) * 100}%` }} />
          </div>
          <ol className="case-card-steps">
            {steps.map((s, i) => (
              <li key={s.title} className={i < done ? 'is-done' : i === done ? 'is-now' : ''}>
                <span className="case-dot">{i < done && <Check size={12} strokeWidth={3} />}</span>
                <span>
                  {s.title}
                  <small>{s.who}</small>
                </span>
              </li>
            ))}
          </ol>
          <div className="case-card-foot">
            <span>
              {t('caseCard.next')} <strong>{nextActions[Math.max(0, done - 1)]}</strong>
            </span>
            <span>{t('caseCard.updated')}</span>
          </div>
        </div>
        <div className="case-float case-float-a">
          <span className="case-float-icon is-ok">
            <Check size={16} strokeWidth={2.6} />
          </span>
          <span>
            <strong>{t('caseCard.signedTitle')}</strong>
            {t('caseCard.signedSub')}
          </span>
        </div>
        <div className="case-float case-float-b">
          <span className="case-float-icon">
            <Fingerprint size={16} />
          </span>
          <span>
            <strong>{t('caseCard.idTitle')}</strong>
            {t('caseCard.idSub')}
          </span>
        </div>
      </div>
    </div>
  );
}

/**
 * "Try it on your phone": a QR code and link to the Flutter applicant app,
 * served at `/mobile` outside the SPA router. The QR encodes the absolute URL
 * on the current host, so it works on localhost and on the public demo alike.
 */
function MobileAppCard() {
  const { t } = useTranslation('services');
  const mobileUrl = `${window.location.origin}/mobile`;
  return (
    <a className="mobile-card" href="/mobile" aria-label={t('mobile.ariaLabel')}>
      <span className="mobile-card-qr">
        <QRCodeSVG value={mobileUrl} size={72} bgColor="#ffffff" fgColor="#0b1b2f" />
      </span>
      <span className="mobile-card-text">
        <strong>{t('mobile.title')}</strong>
        <span>{t('mobile.sub')}</span>
        <span className="mobile-card-link">
          {t('mobile.open')}
          <ArrowRight size={14} aria-hidden="true" />
        </span>
      </span>
    </a>
  );
}
