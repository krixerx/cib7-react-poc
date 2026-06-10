import { useCallback, useEffect, useState } from 'react';
import {
  listIncidents,
  listProcessDefinitions,
  getProcessDefinitionXml,
  setJobRetries,
  type Incident,
  type ProcessDefinition,
} from '../api/camundaClient';
import { parseActivityNames } from '../api/bpmn';

interface DefinitionInfo {
  def: ProcessDefinition;
  /** BPMN element id → display name (e.g. "Task_GetPrice" → "Look up vehicle in registry"). */
  activityNames: Map<string, string>;
}

function shortId(id: string): string {
  return id.length > 8 ? `…${id.slice(-8)}` : id;
}

/**
 * Lists every open incident with its process, activity, error message, and
 * — for failedJob incidents — a button that resets the job retry counter so
 * the engine picks the job up again.
 */
export default function IncidentsPage() {
  const [defs, setDefs] = useState<Map<string, DefinitionInfo>>(new Map());
  const [incidents, setIncidents] = useState<Incident[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [defList, open] = await Promise.all([listProcessDefinitions(), listIncidents()]);
      const infos = await Promise.all(
        defList.map(async (def) => {
          const xml = (await getProcessDefinitionXml(def.key)).bpmn20Xml;
          return [def.id, { def, activityNames: parseActivityNames(xml) }] as const;
        }),
      );
      setDefs(new Map(infos));
      setIncidents(open);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  async function retry(inc: Incident) {
    if (!inc.configuration) return;
    setBusyId(inc.id);
    setError(null);
    try {
      // The engine increments the retry attempt count; 1 is enough to make the
      // job executor pick up a job currently stuck at retries=0.
      await setJobRetries(inc.configuration, 1);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusyId(null);
    }
  }

  return (
    <div className="card card-wide">
      <div className="card-head">
        <h1 className="card-title">Incidents</h1>
        <button className="btn" onClick={load} disabled={loading}>
          Refresh
        </button>
      </div>
      <p className="muted">
        Open incidents across all running processes. Use <strong>Retry</strong>{' '}
        to give a failed service-task job another attempt.
      </p>

      {loading && <p className="muted">Loading…</p>}
      {error && <p className="form-error">{error}</p>}

      {!loading && !error && incidents.length === 0 && (
        <p className="empty">No open incidents.</p>
      )}

      {!loading && !error && incidents.length > 0 && (
        <ul className="row-list">
          {incidents.map((inc) => {
            const info = defs.get(inc.processDefinitionId);
            const serviceName = info?.def.name ?? info?.def.key ?? inc.processDefinitionId;
            const activityName = inc.activityId
              ? info?.activityNames.get(inc.activityId) ?? inc.activityId
              : '(process scope)';
            const canRetry = inc.incidentType === 'failedJob' && inc.configuration;

            return (
              <li key={inc.id} className="incident">
                <div className="incident-head">
                  <span className="row-title">
                    {serviceName} <span className="muted">·</span> {activityName}
                  </span>
                  <span className="row-sub">
                    {inc.incidentType} <span className="muted">·</span>{' '}
                    Process {shortId(inc.processInstanceId)} <span className="muted">·</span>{' '}
                    {new Date(inc.incidentTimestamp).toLocaleString()}
                  </span>
                </div>
                {inc.incidentMessage && (
                  <p className="incident-message">{inc.incidentMessage}</p>
                )}
                <div className="incident-actions">
                  {canRetry ? (
                    <button
                      className="btn btn-primary"
                      onClick={() => retry(inc)}
                      disabled={busyId === inc.id}
                    >
                      {busyId === inc.id ? 'Retrying…' : 'Retry'}
                    </button>
                  ) : (
                    <span className="muted">No retry available for this incident type.</span>
                  )}
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
