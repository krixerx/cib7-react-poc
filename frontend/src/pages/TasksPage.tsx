import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import {
  countActiveProcessInstances,
  getProcessDefinitionXml,
  listHistoricTasksByDefinition,
  listIncidents,
  listProcessDefinitions,
  listTasks,
  setJobRetries,
  type CamundaTask,
  type HistoricTask,
  type Incident,
  type ProcessDefinition,
} from '../api/camundaClient';
import { parseUserTasks, type UserTaskDef } from '../api/bpmn';

interface ServiceOverview {
  def: ProcessDefinition;
  userTasks: UserTaskDef[];
  /** Active tasks across all running instances of this service. */
  activeTasks: CamundaTask[];
  incidents: Incident[];
  activeInstanceCount: number;
}

function shortId(id: string): string {
  return id.length > 8 ? `…${id.slice(-8)}` : id;
}

/**
 * Hierarchical view of tasks: a service dropdown selects which service to look
 * at; below that, a list of that service's user-task names (plus an Incidents
 * row), each badged with its active count. Clicking a row drills down to a
 * list of task instances (active + completed) or the incident list.
 *
 * State lives in the URL search params (`?service=…&section=…`) so navigation
 * back/forward and bookmarks work.
 */
export default function TasksPage() {
  const [params, setParams] = useSearchParams();
  const serviceKey = params.get('service') ?? '';
  const section = params.get('section'); // null | taskDefinitionKey | 'incidents'

  const [services, setServices] = useState<ProcessDefinition[]>([]);
  const [overview, setOverview] = useState<ServiceOverview | null>(null);
  const [sectionTasks, setSectionTasks] = useState<HistoricTask[]>([]);
  const [busyIncident, setBusyIncident] = useState<string | null>(null);
  const [loadingServices, setLoadingServices] = useState(true);
  const [loadingOverview, setLoadingOverview] = useState(false);
  const [loadingSection, setLoadingSection] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Load the service dropdown options once.
  useEffect(() => {
    listProcessDefinitions()
      .then((defs) => setServices(defs))
      .catch((e) => setError(e instanceof Error ? e.message : String(e)))
      .finally(() => setLoadingServices(false));
  }, []);

  const selectedDef = useMemo(
    () => services.find((d) => d.key === serviceKey) ?? null,
    [services, serviceKey],
  );

  // Load per-service overview (task names + counts + incidents) when the
  // selected service changes.
  const loadOverview = useCallback(async () => {
    if (!selectedDef) {
      setOverview(null);
      return;
    }
    setLoadingOverview(true);
    setError(null);
    try {
      const [xml, activeTasks, incidents, instanceCount] = await Promise.all([
        getProcessDefinitionXml(selectedDef.key),
        listTasks(),
        listIncidents(selectedDef.id),
        countActiveProcessInstances(selectedDef.id),
      ]);
      setOverview({
        def: selectedDef,
        userTasks: parseUserTasks(xml.bpmn20Xml),
        activeTasks: activeTasks.filter((t) => t.processDefinitionId === selectedDef.id),
        incidents,
        activeInstanceCount: instanceCount.count,
      });
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoadingOverview(false);
    }
  }, [selectedDef]);

  useEffect(() => {
    loadOverview();
  }, [loadOverview]);

  // Load the drill-down list when a user-task section is selected.
  useEffect(() => {
    if (!overview || !section || section === 'incidents') {
      setSectionTasks([]);
      return;
    }
    setLoadingSection(true);
    listHistoricTasksByDefinition(overview.def.id, section)
      .then(setSectionTasks)
      .catch((e) => setError(e instanceof Error ? e.message : String(e)))
      .finally(() => setLoadingSection(false));
  }, [overview, section]);

  function pickService(key: string) {
    if (key) setParams({ service: key });
    else setParams({});
  }

  function pickSection(value: string | null) {
    const next = new URLSearchParams(params);
    if (value) next.set('section', value);
    else next.delete('section');
    setParams(next);
  }

  async function retryIncident(inc: Incident) {
    if (!inc.configuration) return;
    setBusyIncident(inc.id);
    setError(null);
    try {
      await setJobRetries(inc.configuration, 1);
      await loadOverview();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusyIncident(null);
    }
  }

  const activeBySectionKey = useMemo(() => {
    const m = new Map<string, number>();
    overview?.activeTasks.forEach((t) =>
      m.set(t.taskDefinitionKey, (m.get(t.taskDefinitionKey) ?? 0) + 1),
    );
    return m;
  }, [overview]);

  const selectedUserTask = section
    ? overview?.userTasks.find((ut) => ut.id === section) ?? null
    : null;

  return (
    <div className="card card-wide">
      <div className="card-head">
        <h1 className="card-title">Tasks</h1>
        <button
          className="btn"
          onClick={loadOverview}
          disabled={!selectedDef || loadingOverview}
        >
          Refresh
        </button>
      </div>

      <label className="field">
        <span className="field-label">Service</span>
        <select
          className="field-input"
          value={serviceKey}
          onChange={(e) => pickService(e.target.value)}
          disabled={loadingServices}
        >
          <option value="">— select a service —</option>
          {services.map((s) => (
            <option key={s.id} value={s.key}>
              {s.name ?? s.key}
            </option>
          ))}
        </select>
      </label>

      {error && <p className="form-error">{error}</p>}

      {!serviceKey && !loadingServices && (
        <p className="muted">Select a service above to view its tasks.</p>
      )}

      {selectedDef && (
        <section className="service-block">
          <h2 className="service-name">
            <span>{selectedDef.name ?? selectedDef.key}</span>
            <span className="count-badge">{overview?.activeInstanceCount ?? '…'}</span>
          </h2>

          {loadingOverview && <p className="muted">Loading…</p>}

          {/* ------ section list ------ */}
          {!loadingOverview && overview && !section && (
            <ul className="row-list">
              {overview.userTasks.map((ut) => (
                <li key={ut.id}>
                  <button className="row" onClick={() => pickSection(ut.id)}>
                    <span className="row-main">
                      <span className="row-title">{ut.name}</span>
                    </span>
                    <span className="row-right">
                      <span className="count-badge">
                        {activeBySectionKey.get(ut.id) ?? 0}
                      </span>
                      <span className="row-action">Open →</span>
                    </span>
                  </button>
                </li>
              ))}
              <li>
                <button className="row" onClick={() => pickSection('incidents')}>
                  <span className="row-main">
                    <span className="row-title">Incidents</span>
                  </span>
                  <span className="row-right">
                    <span className="count-badge">{overview.incidents.length}</span>
                    <span className="row-action">Open →</span>
                  </span>
                </button>
              </li>
            </ul>
          )}

          {/* ------ drill-down: task instances ------ */}
          {!loadingOverview && overview && selectedUserTask && (
            <>
              <button className="btn btn-link section-back" onClick={() => pickSection(null)}>
                ← Back · {selectedUserTask.name}
              </button>

              {loadingSection && <p className="muted">Loading…</p>}

              {!loadingSection && sectionTasks.length === 0 && (
                <p className="empty">No tasks at this step yet.</p>
              )}

              {!loadingSection && sectionTasks.length > 0 && (
                <ul className="row-list">
                  {sectionTasks.map((t) => {
                    const isActive = t.endTime === null;
                    const to = isActive
                      ? `/tasks/${t.id}`
                      : `/processes/${t.processInstanceId}`;
                    return (
                      <li key={t.id}>
                        <Link to={to} className="row">
                          <span className="row-main">
                            <span className="row-title">
                              Process {shortId(t.processInstanceId)}
                            </span>
                            <span className="row-sub">
                              {isActive
                                ? `Active since ${new Date(t.startTime).toLocaleString()}`
                                : `Ended ${new Date(t.endTime!).toLocaleString()}`}
                            </span>
                          </span>
                          <span className="row-right">
                            <span
                              className={`status-pill ${
                                isActive ? 'status-active' : 'status-done'
                              }`}
                            >
                              {isActive ? 'Active' : 'Completed'}
                            </span>
                            <span className="row-action">{isActive ? 'Open →' : 'View →'}</span>
                          </span>
                        </Link>
                      </li>
                    );
                  })}
                </ul>
              )}
            </>
          )}

          {/* ------ drill-down: incidents ------ */}
          {!loadingOverview && overview && section === 'incidents' && (
            <>
              <button className="btn btn-link section-back" onClick={() => pickSection(null)}>
                ← Back · Incidents
              </button>

              {overview.incidents.length === 0 ? (
                <p className="empty">No open incidents for this service.</p>
              ) : (
                <ul className="row-list">
                  {overview.incidents.map((inc) => {
                    const canRetry = inc.incidentType === 'failedJob' && inc.configuration;
                    return (
                      <li key={inc.id} className="incident">
                        <div className="incident-head">
                          <span className="row-title">
                            {inc.activityId ?? '(process scope)'}
                          </span>
                          <span className="row-sub">
                            {inc.incidentType} <span className="muted">·</span>{' '}
                            Process {shortId(inc.processInstanceId)}{' '}
                            <span className="muted">·</span>{' '}
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
                              onClick={() => retryIncident(inc)}
                              disabled={busyIncident === inc.id}
                            >
                              {busyIncident === inc.id ? 'Retrying…' : 'Retry'}
                            </button>
                          ) : (
                            <span className="muted">
                              No retry available for this incident type.
                            </span>
                          )}
                        </div>
                      </li>
                    );
                  })}
                </ul>
              )}
            </>
          )}
        </section>
      )}
    </div>
  );
}
