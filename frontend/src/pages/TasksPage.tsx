import { useEffect, useState, useCallback } from 'react';
import { Link } from 'react-router-dom';
import {
  listProcessDefinitions,
  getProcessDefinitionXml,
  listTasks,
  type CamundaTask,
  type ProcessDefinition,
} from '../api/camundaClient';
import { parseUserTasks, type UserTaskDef } from '../api/bpmn';

interface ServiceView {
  def: ProcessDefinition;
  /** The user tasks declared in this service's BPMN model. */
  userTasks: UserTaskDef[];
}

/** Shortens a long engine id for display. */
function shortId(id: string): string {
  return id.length > 8 ? `…${id.slice(-8)}` : id;
}

/**
 * Shows every service's human tasks as groups, with the active process
 * instances waiting at each task. Opening one renders its form to validate.
 */
export default function TasksPage() {
  const [services, setServices] = useState<ServiceView[]>([]);
  const [tasks, setTasks] = useState<CamundaTask[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [defs, openTasks] = await Promise.all([listProcessDefinitions(), listTasks()]);
      const views = await Promise.all(
        defs.map(async (def) => ({
          def,
          userTasks: parseUserTasks((await getProcessDefinitionXml(def.key)).bpmn20Xml),
        })),
      );
      setServices(views);
      setTasks(openTasks);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  return (
    <div className="card card-wide">
      <div className="card-head">
        <h1 className="card-title">Tasks</h1>
        <button className="btn" onClick={load} disabled={loading}>
          Refresh
        </button>
      </div>
      <p className="muted">
        Each service's human tasks, with the active process instances waiting at
        each one. Open a process to view its form and validate it.
      </p>

      {loading && <p className="muted">Loading…</p>}
      {error && <p className="form-error">{error}</p>}

      {!loading &&
        !error &&
        services.map((sv) => (
          <section key={sv.def.id} className="service-block">
            <h2 className="service-name">{sv.def.name ?? sv.def.key}</h2>

            {sv.userTasks.map((ut) => {
              const group = tasks.filter(
                (t) =>
                  t.processDefinitionId === sv.def.id && t.taskDefinitionKey === ut.id,
              );
              return (
                <div key={ut.id} className="task-group">
                  <div className="task-group-head">
                    <span className="task-group-name">{ut.name}</span>
                    <span className="count-badge">{group.length}</span>
                  </div>

                  {group.length === 0 ? (
                    <p className="empty">No active processes at this task.</p>
                  ) : (
                    <ul className="row-list">
                      {group.map((t) => (
                        <li key={t.id}>
                          <Link to={`/tasks/${t.id}`} className="row">
                            <span className="row-main">
                              <span className="row-title">
                                Process {shortId(t.processInstanceId)}
                              </span>
                              <span className="row-sub">
                                started {new Date(t.created).toLocaleString()}
                              </span>
                            </span>
                            <span className="row-action">Open →</span>
                          </Link>
                        </li>
                      ))}
                    </ul>
                  )}
                </div>
              );
            })}
          </section>
        ))}
    </div>
  );
}
