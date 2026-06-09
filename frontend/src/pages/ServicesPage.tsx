import { useEffect, useMemo, useRef, useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  listProcessDefinitions,
  startProcess,
  listTasksByInstance,
  type ProcessDefinition,
} from '../api/camundaClient';
import { useAuth } from '../auth/AuthProvider';
import { CATEGORIES, categoryOf, type CategoryId } from '../services/categories';
import { CategoryIcon } from '../services/CategoryIcon';

/**
 * PartA landing — life-event catalog. Six category tiles let citizens pick a
 * topic before drilling into the specific service; tiles with no services
 * deployed yet stay visible so the catalog shape is stable as new BPMN models
 * land. Anonymous browsing is allowed; starting still routes through Keycloak.
 */
export default function ServicesPage() {
  const navigate = useNavigate();
  const { authenticated, login } = useAuth();

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
  const pickedServices = picked ? byCategory.get(picked) ?? [] : [];

  return (
    <div className="catalog">
      <section className="catalog-hero">
        <h1 className="catalog-hero-title">What can we help you with today?</h1>
        <p className="catalog-hero-sub">Pick a topic to see all services in that area.</p>
      </section>

      {error && <p className="form-error catalog-error">{error}</p>}
      {loading && !error && <p className="muted catalog-status">Loading services…</p>}

      {!loading && !error && (
        <>
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
                    <span className="cat-name">{cat.name}</span>
                    <span className="cat-blurb">{cat.blurb}</span>
                  </span>
                  <span className="cat-count">
                    {empty
                      ? 'Coming soon'
                      : isBusy
                        ? 'Starting…'
                        : single
                          ? authenticated
                            ? 'Start →'
                            : 'Sign in to start →'
                          : isPicked
                            ? `↓ ${count} services below`
                            : `${count} services`}
                  </span>
                </button>
              );
            })}
          </div>

          {pickedCategory && (
            <section ref={servicesPanelRef} className="cat-services">
              <div className="cat-services-head">
                <h2>{pickedCategory.name}</h2>
                <button className="btn btn-link" onClick={() => setPicked(null)}>
                  Close
                </button>
              </div>
              {!authenticated && (
                <p className="muted">
                  Starting a service requires an account — register or sign in from the
                  top right.
                </p>
              )}
              {pickedServices.length === 0 ? (
                <p className="empty">No services in this category yet.</p>
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
                          <span className="row-title">{s.name ?? s.key}</span>
                          <span className="row-sub">
                            key: {s.key} · version {s.version}
                          </span>
                        </span>
                        <span className="row-action">
                          {startingKey === s.key
                            ? 'Starting…'
                            : authenticated
                              ? 'Start →'
                              : 'Sign in to start →'}
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

