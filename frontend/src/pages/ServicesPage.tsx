import { useEffect, useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  listProcessDefinitions,
  startProcess,
  listTasksByInstance,
  type ProcessDefinition,
} from '../api/camundaClient';
import { useAuth } from '../auth/AuthProvider';

/**
 * Lists deployed process definitions ("services"). The list itself is
 * fetched anonymously so first-time visitors can see what's on offer before
 * signing in. Picking one requires authentication: anonymous users are
 * redirected to Keycloak and resume on the same page.
 */
export default function ServicesPage() {
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
      // Drop the applicant straight into their new task; if the engine raced
      // past it (e.g. service task in flight) send them to My processes.
      navigate(tasks.length > 0 ? `/tasks/${tasks[0].id}` : '/my-processes');
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setStartingKey(null);
    }
  }

  return (
    <div className="card">
      <div className="card-head">
        <h1 className="card-title">Services</h1>
        <button className="btn" onClick={load} disabled={loading}>
          Refresh
        </button>
      </div>
      <p className="muted">
        Pick a service to start. You fill in the applicant form first; confirming
        it sends a new process instance into the workflow.
        {!authenticated &&
          ' Starting a service requires an account — register or sign in from the top right.'}
      </p>

      {loading && <p className="muted">Loading…</p>}
      {error && <p className="form-error">{error}</p>}
      {!loading && !error && services.length === 0 && (
        <p className="muted">No process definitions are deployed.</p>
      )}

      {services.length > 0 && (
        <ul className="row-list">
          {services.map((s) => (
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
    </div>
  );
}
