import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  getHistoricVariable,
  listHistoricProcessInstancesByStarter,
  listProcessDefinitions,
  listTasksByInstance,
  type CamundaTask,
  type HistoricProcessInstance,
  type ProcessDefinition,
} from '../api/camundaClient';
import { useAuth } from '../auth/AuthProvider';

/**
 * PartA — the applicant's "My processes" page. Lists every process instance
 * they started, with a status pill computed from the active task (if any) +
 * the `sendBackReason` variable. When an applicant task is active they see
 * an "Open" link; otherwise the row just shows where the case is parked.
 */

type RowStatus =
  | 'awaiting-submission'
  | 'sent-back'
  | 'under-review'
  | 'processing'
  | 'approved'
  | 'ended';

interface ProcessRow {
  pi: HistoricProcessInstance;
  serviceName: string;
  status: RowStatus;
  /** Set when there's an active applicant task the user can open. */
  openTaskId: string | null;
}

const STATUS_LABELS: Record<RowStatus, string> = {
  'awaiting-submission': 'Awaiting submission',
  'sent-back': 'Sent back for corrections',
  'under-review': 'Under review',
  'processing': 'Processing',
  'approved': 'Approved',
  'ended': 'Ended',
};

const STATUS_PILL_CLASS: Record<RowStatus, string> = {
  'awaiting-submission': 'status-pill status-active',
  'sent-back': 'status-pill status-warn',
  'under-review': 'status-pill status-info',
  'processing': 'status-pill status-info',
  'approved': 'status-pill status-done',
  'ended': 'status-pill status-done',
};

function shortId(id: string): string {
  return id.length > 8 ? `…${id.slice(-8)}` : id;
}

async function buildRow(
  pi: HistoricProcessInstance,
  serviceName: string,
): Promise<ProcessRow> {
  if (pi.endTime) {
    const status: RowStatus =
      pi.endActivityId === 'EndEvent_Approved' ? 'approved' : 'ended';
    return { pi, serviceName, status, openTaskId: null };
  }

  // Active instance — determine where it's parked.
  const [tasks, sendBackVar] = await Promise.all([
    listTasksByInstance(pi.id),
    getHistoricVariable(pi.id, 'sendBackReason'),
  ]);

  const applicantTask: CamundaTask | undefined = tasks.find(
    (t) => t.taskDefinitionKey === 'Task_SubmitDetails',
  );
  const reviewTask: CamundaTask | undefined = tasks.find(
    (t) => t.taskDefinitionKey === 'Task_Review',
  );

  if (applicantTask) {
    const reason = sendBackVar?.value ? String(sendBackVar.value) : '';
    return {
      pi,
      serviceName,
      status: reason ? 'sent-back' : 'awaiting-submission',
      openTaskId: applicantTask.id,
    };
  }
  if (reviewTask) {
    return { pi, serviceName, status: 'under-review', openTaskId: null };
  }
  return { pi, serviceName, status: 'processing', openTaskId: null };
}

export default function MyProcessesPage() {
  const { username } = useAuth();
  const [rows, setRows] = useState<ProcessRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [defs, instances] = await Promise.all([
        listProcessDefinitions(),
        listHistoricProcessInstancesByStarter(username),
      ]);
      const nameByDefId = new Map<string, string>(
        defs.map((d: ProcessDefinition) => [d.id, d.name ?? d.key]),
      );
      const built = await Promise.all(
        instances.map((pi) =>
          buildRow(pi, nameByDefId.get(pi.processDefinitionId) ?? pi.processDefinitionKey),
        ),
      );
      setRows(built);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, [username]);

  useEffect(() => {
    load();
  }, [load]);

  return (
    <div className="card card-wide">
      <div className="card-head">
        <h1 className="card-title">My processes</h1>
        <button className="btn" onClick={load} disabled={loading}>
          Refresh
        </button>
      </div>
      <p className="muted">
        Every process you've started. Open the ones that are waiting on you —
        the rest are with the back office.
      </p>

      {loading && <p className="muted">Loading…</p>}
      {error && <p className="form-error">{error}</p>}

      {!loading && !error && rows.length === 0 && (
        <p className="empty">
          You haven't started any processes yet. Pick one on the{' '}
          <Link to="/">Services</Link> page.
        </p>
      )}

      {!loading && !error && rows.length > 0 && (
        <ul className="row-list">
          {rows.map((r) => {
            const isOpenable = r.openTaskId !== null;
            const isEnded = r.pi.endTime !== null;
            // In-flight cases without an open applicant task (parked with the
            // back office or in the owner-confirmation subprocess) still get a
            // link so the applicant can review what they submitted in
            // read-only mode. Same /processes/:id route as ended cases —
            // CompletedProcessPage handles both shapes.
            const to = isOpenable
              ? `/tasks/${r.openTaskId}`
              : `/processes/${r.pi.id}`;
            const action = isOpenable ? 'Open →' : isEnded ? 'View →' : 'View submission →';

            const content = (
              <>
                <span className="row-main">
                  <span className="row-title">
                    {r.serviceName} · {shortId(r.pi.id)}
                  </span>
                  <span className="row-sub">
                    Started {new Date(r.pi.startTime).toLocaleString()}
                    {r.pi.endTime && (
                      <>
                        {' '}
                        <span className="muted">·</span> ended{' '}
                        {new Date(r.pi.endTime).toLocaleString()}
                      </>
                    )}
                  </span>
                </span>
                <span className="row-right">
                  <span className={STATUS_PILL_CLASS[r.status]}>
                    {STATUS_LABELS[r.status]}
                  </span>
                  {action && <span className="row-action">{action}</span>}
                </span>
              </>
            );

            return (
              <li key={r.pi.id}>
                <Link to={to} className="row">
                  {content}
                </Link>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
