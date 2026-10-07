import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import { useTranslation } from 'react-i18next';
import { catalog } from '../pack/catalog';
import { ArrowRight } from 'lucide-react';
import {
  listProcessDefinitions,
  startProcess,
  listTasksByInstance,
  type ProcessDefinition,
} from '../api/camundaClient';
import { useAuth } from '../auth/AuthProvider';
import { CATEGORIES, categoryOf } from '../services/categories';
import { CategoryIcon } from '../services/CategoryIcon';
import { translateBackendName } from '../i18n/backendNames';

/**
 * PartA landing: the list of live services and nothing else, so an applicant
 * sees what they can start the moment the page opens. Anonymous browsing is
 * allowed; starting a service still routes through Keycloak.
 */
export default function ServicesPage() {
  const { t } = useTranslation('services');
  const navigate = useNavigate();
  const { authenticated, login } = useAuth();

  const [services, setServices] = useState<ProcessDefinition[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [startingKey, setStartingKey] = useState<string | null>(null);

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

  // Catalog order: by topic, then in engine order within a topic.
  const ordered = useMemo(
    () => CATEGORIES.flatMap((c) => services.filter((s) => categoryOf(s.key) === c.id)),
    [services],
  );

  const infoKeys = useMemo(() => new Set(Object.keys(catalog()?.services ?? {})), []);
  // Summaries come from the service pack's catalog texts; unknown services get none.
  const summary = (key: string) => (infoKeys.has(key) ? t(`catalog:services.${key}.summary`) : '');

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

  return (
    <div className="landing">
      <h1 className="landing-title">{t('heading')}</h1>

      {catalog() === null && <p className="form-error">{t('noPack')}</p>}
      {error && <p className="form-error">{error}</p>}
      {loading && !error && <p className="muted">{t('loading')}</p>}
      {!loading && !error && ordered.length === 0 && <p className="muted">{t('empty')}</p>}

      {ordered.length > 0 && (
        <ul className="svc-list">
          {ordered.map((s) => {
            const cat = categoryOf(s.key);
            const text = summary(s.key);
            return (
              <li key={s.id} className="svc-row">
                <span className={`svc-icon cat-${cat}`} aria-hidden="true">
                  <CategoryIcon id={cat} size={18} />
                </span>
                <span className="svc-main">
                  <strong>{s.name ? translateBackendName(t, s.name) : s.key}</strong>
                  {text && <span className="svc-sub">{text}</span>}
                </span>
                <button
                  type="button"
                  className="btn"
                  onClick={() => startService(s.key)}
                  disabled={startingKey !== null}
                >
                  {startingKey === s.key
                    ? t('starting')
                    : authenticated
                      ? t('start')
                      : t('signInToStart')}
                  <ArrowRight aria-hidden="true" />
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
