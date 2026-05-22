import { useEffect, useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  listProcessDefinitions,
  startProcess,
  listTasksByInstance,
  type ProcessDefinition,
} from '../api/camundaClient';

/**
 * Lists deployed process definitions ("services"). Picking one starts a new
 * process instance and opens its first task — the applicant form.
 */
export default function ServicesPage() {
  const navigate = useNavigate();
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
    setStartingKey(key);
    setError(null);
    try {
      const instance = await startProcess(key);
      const tasks = await listTasksByInstance(instance.id);
      navigate(tasks.length > 0 ? `/tasks/${tasks[0].id}` : '/tasks');
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
                  {startingKey === s.key ? 'Starting…' : 'Start →'}
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
