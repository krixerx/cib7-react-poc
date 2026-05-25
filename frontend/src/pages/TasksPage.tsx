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

interface ServiceTree {
  def: ProcessDefinition;
  userTasks: UserTaskDef[];
  /** taskDefinitionKey → number of currently-active tasks. */
  taskCounts: Map<string, number>;
  incidents: Incident[];
  activeInstanceCount: number;
}

const ICONS = {
  service: '📋',
  task: '📝',
  incidents: '⚠️',
};

function shortId(id: string): string {
  return id.length > 8 ? `…${id.slice(-8)}` : id;
}

/**
 * Tree-style task explorer: every service is collapsible in a left sidebar;
 * picking a service expands its user tasks + an Incidents row. The right
 * pane shows whatever's selected — a service summary, a task drill-down, or
 * the per-service incident list.
 *
 * Selection state lives in URL search params (?service=…&section=…) so
 * back/forward and bookmarks work.
 */
export default function TasksPage() {
  const [params, setParams] = useSearchParams();
  const serviceKey = params.get('service') ?? '';
  const section = params.get('section');

  const [trees, setTrees] = useState<ServiceTree[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [sectionTasks, setSectionTasks] = useState<HistoricTask[]>([]);
  const [loadingSection, setLoadingSection] = useState(false);
  const [busyIncident, setBusyIncident] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const defs = await listProcessDefinitions();
      const allActiveTasks = await listTasks();
      const enriched = await Promise.all(
        defs.map(async (def) => {
          const [xml, incidents, instanceCount] = await Promise.all([
            getProcessDefinitionXml(def.key),
            listIncidents(def.id),
            countActiveProcessInstances(def.id),
          ]);
          const userTasks = parseUserTasks(xml.bpmn20Xml);
          const taskCounts = new Map<string, number>();
          allActiveTasks
            .filter((t) => t.processDefinitionId === def.id)
            .forEach((t) =>
              taskCounts.set(
                t.taskDefinitionKey,
                (taskCounts.get(t.taskDefinitionKey) ?? 0) + 1,
              ),
            );
          return {
            def,
            userTasks,
            taskCounts,
            incidents,
            activeInstanceCount: instanceCount.count,
          };
        }),
      );
      setTrees(enriched);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const selectedTree = useMemo(
    () => trees.find((t) => t.def.key === serviceKey) ?? null,
    [trees, serviceKey],
  );

  useEffect(() => {
    if (!selectedTree || !section || section === 'incidents') {
      setSectionTasks([]);
      return;
    }
    setLoadingSection(true);
    listHistoricTasksByDefinition(selectedTree.def.id, section)
      .then(setSectionTasks)
      .catch((e) => setError(e instanceof Error ? e.message : String(e)))
      .finally(() => setLoadingSection(false));
  }, [selectedTree, section]);

  function pickService(key: string) {
    setParams({ service: key });
  }

  function pickSection(key: string, sec: string) {
    setParams({ service: key, section: sec });
  }

  async function retryIncident(inc: Incident) {
    if (!inc.configuration) return;
    setBusyIncident(inc.id);
    setError(null);
    try {
      await setJobRetries(inc.configuration, 1);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusyIncident(null);
    }
  }

  const selectedTask = selectedTree && section
    ? selectedTree.userTasks.find((ut) => ut.id === section) ?? null
    : null;

  return (
    <div className="tasks-layout">
      <aside className="tasks-sidebar">
        <div className="tasks-sidebar-head">
          <h2 className="tasks-sidebar-title">Services</h2>
          <button className="btn btn-link" onClick={load} disabled={loading}>
            {loading ? 'Loading…' : 'Refresh'}
          </button>
        </div>

        {error && <p className="form-error">{error}</p>}

        {!loading && trees.length === 0 && !error && (
          <p className="empty">No services deployed.</p>
        )}

        <nav className="tree">
          {trees.map((tree) => {
            const isSelectedService = tree.def.key === serviceKey;
            return (
              <div key={tree.def.id} className="tree-group">
                <button
                  className={`tree-node tree-node-service${
                    isSelectedService && !section ? ' active' : ''
                  }`}
                  onClick={() => pickService(tree.def.key)}
                >
                  <span className="tree-icon">{ICONS.service}</span>
                  <span className="tree-label">{tree.def.name ?? tree.def.key}</span>
                  <span className="count-badge">{tree.activeInstanceCount}</span>
                </button>

                {isSelectedService && (
                  <ul className="tree-children">
                    {tree.userTasks.map((ut) => (
                      <li key={ut.id}>
                        <button
                          className={`tree-node tree-node-task${
                            section === ut.id ? ' active' : ''
                          }`}
                          onClick={() => pickSection(tree.def.key, ut.id)}
                        >
                          <span className="tree-icon">{ICONS.task}</span>
                          <span className="tree-label">{ut.name}</span>
                          <span className="count-badge">
                            {tree.taskCounts.get(ut.id) ?? 0}
                          </span>
                        </button>
                      </li>
                    ))}
                    <li>
                      <button
                        className={`tree-node tree-node-incidents${
                          section === 'incidents' ? ' active' : ''
                        }`}
                        onClick={() => pickSection(tree.def.key, 'incidents')}
                      >
                        <span className="tree-icon">{ICONS.incidents}</span>
                        <span className="tree-label">Incidents</span>
                        <span
                          className={`count-badge${
                            tree.incidents.length > 0 ? ' count-warn' : ''
                          }`}
                        >
                          {tree.incidents.length}
                        </span>
                      </button>
                    </li>
                  </ul>
                )}
              </div>
            );
          })}
        </nav>
      </aside>

      <main className="tasks-content">
        {/* ------ empty state ------ */}
        {!selectedTree && (
          <div className="card">
            <h1 className="card-title">Tasks</h1>
            <p className="muted">
              Pick a service from the sidebar to see its task names, open
              incidents, and historic instances.
            </p>
          </div>
        )}

        {/* ------ service summary ------ */}
        {selectedTree && !section && (
          <div className="card">
            <h1 className="card-title">
              <span className="content-icon">{ICONS.service}</span>{' '}
              {selectedTree.def.name ?? selectedTree.def.key}
            </h1>
            <p className="muted">
              {selectedTree.activeInstanceCount} active{' '}
              {selectedTree.activeInstanceCount === 1 ? 'process' : 'processes'} ·{' '}
              {selectedTree.incidents.length} open{' '}
              {selectedTree.incidents.length === 1 ? 'incident' : 'incidents'}.
            </p>

            <dl className="summary">
              {selectedTree.userTasks.map((ut) => (
                <div className="summary-row" key={ut.id}>
                  <dt>
                    <span className="content-icon">{ICONS.task}</span> {ut.name}
                  </dt>
                  <dd>{selectedTree.taskCounts.get(ut.id) ?? 0} active</dd>
                </div>
              ))}
              <div className="summary-row">
                <dt>
                  <span className="content-icon">{ICONS.incidents}</span> Incidents
                </dt>
                <dd
                  className={
                    selectedTree.incidents.length > 0 ? 'decision-reject' : undefined
                  }
                >
                  {selectedTree.incidents.length} open
                </dd>
              </div>
            </dl>

            <p className="muted">Click a row in the sidebar to drill down.</p>
          </div>
        )}

        {/* ------ task drill-down ------ */}
        {selectedTree && selectedTask && (
          <div className="card">
            <h1 className="card-title">
              <span className="content-icon">{ICONS.task}</span> {selectedTask.name}
            </h1>
            <p className="muted">
              {selectedTree.def.name ?? selectedTree.def.key} · active and historic
              instances of this step, newest first.
            </p>

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
                          <span className="row-action">
                            {isActive ? 'Open →' : 'View →'}
                          </span>
                        </span>
                      </Link>
                    </li>
                  );
                })}
              </ul>
            )}
          </div>
        )}

        {/* ------ per-service incidents ------ */}
        {selectedTree && section === 'incidents' && (
          <div className="card">
            <h1 className="card-title">
              <span className="content-icon">{ICONS.incidents}</span> Incidents
            </h1>
            <p className="muted">
              {selectedTree.def.name ?? selectedTree.def.key} · open incidents only.
            </p>

            {selectedTree.incidents.length === 0 ? (
              <p className="empty">No open incidents for this service.</p>
            ) : (
              <ul className="row-list">
                {selectedTree.incidents.map((inc) => {
                  const canRetry =
                    inc.incidentType === 'failedJob' && inc.configuration;
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
          </div>
        )}
      </main>
    </div>
  );
}
