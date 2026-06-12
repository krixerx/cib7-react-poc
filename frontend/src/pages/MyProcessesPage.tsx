import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  getHistoricVariable,
  listHistoricProcessInstancesByStarter,
  listProcessDefinitions,
  listTasksByInstance,
  listUnfinishedReceiveTasks,
  type CamundaTask,
  type HistoricProcessInstance,
  type ProcessDefinition,
} from '../api/camundaClient';
import { useAuth } from '../auth/AuthProvider';
import { categoryOf, type CategoryId } from '../services/categories';
import { CategoryIcon } from '../services/CategoryIcon';

/**
 * PartA — the applicant's "My processes" page. Action-first inbox: a
 * "Needs your attention" zone of large category-tinted cards puts cases
 * waiting on the user up top; quieter rows show in-progress work parked
 * with the back office; completed work collapses into a disclosure.
 */

type RowStatus =
  | 'awaiting-submission'
  | 'sent-back'
  | 'payment-needed'
  | 'waiting-signatures'
  | 'under-review'
  | 'processing'
  | 'approved'
  | 'ended';

type Bucket = 'attention' | 'progress' | 'done';

const BUCKET_OF: Record<RowStatus, Bucket> = {
  'awaiting-submission': 'attention',
  'sent-back': 'attention',
  'payment-needed': 'attention',
  'waiting-signatures': 'progress',
  'under-review': 'progress',
  processing: 'progress',
  approved: 'done',
  ended: 'done',
};

interface ProcessRow {
  pi: HistoricProcessInstance;
  serviceName: string;
  category: CategoryId;
  status: RowStatus;
  /** Reason text when the back office sent it back. */
  sendBackReason: string | null;
  /** Set when there's an active applicant task the user can open. */
  openTaskId: string | null;
  /** Name of the open wait state, e.g. "Wait for co-owner signature". */
  waitingOn: string | null;
}

const STATUS_LABELS: Record<RowStatus, string> = {
  'awaiting-submission': 'Awaiting submission',
  'sent-back': 'Sent back for corrections',
  'payment-needed': 'Payment required',
  'waiting-signatures': 'Waiting for signatures',
  'under-review': 'Under review',
  processing: 'Processing',
  approved: 'Approved',
  ended: 'Ended',
};

const STATUS_PILL_CLASS: Record<RowStatus, string> = {
  'awaiting-submission': 'status-pill status-active',
  'sent-back': 'status-pill status-warn',
  'payment-needed': 'status-pill status-warn',
  'waiting-signatures': 'status-pill status-info',
  'under-review': 'status-pill status-info',
  processing: 'status-pill status-info',
  approved: 'status-pill status-done',
  ended: 'status-pill status-done',
};

/** The payment wait state in both shipped BPMNs. */
const PAYMENT_ACTIVITY_ID = 'Task_WaitForPayment';

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
  });
}

async function buildRow(
  pi: HistoricProcessInstance,
  serviceName: string,
  category: CategoryId,
  username: string,
  waitingOn: { activityId: string; name: string } | null,
): Promise<ProcessRow> {
  const base = {
    pi,
    serviceName,
    category,
    sendBackReason: null,
    openTaskId: null,
    waitingOn: null,
  };
  if (pi.endTime) {
    const status: RowStatus = pi.endActivityId === 'EndEvent_Approved' ? 'approved' : 'ended';
    return { ...base, status };
  }

  // Active instance — determine where it's parked.
  const [tasks, sendBackVar] = await Promise.all([
    listTasksByInstance(pi.id),
    getHistoricVariable(pi.id, 'sendBackReason'),
  ]);

  // Applicant tasks are assigned to ${initiator} in both BPMNs, so "a task
  // assigned to me" generalises across services (Task_SubmitDetails,
  // Task_SubmitBusinessDetails, …) without hardcoding ids; any other open
  // task belongs to the back office.
  const applicantTask: CamundaTask | undefined = tasks.find((t) => t.assignee === username);
  const backOfficeTask: CamundaTask | undefined = tasks.find((t) => t.assignee !== username);

  if (applicantTask) {
    const reason = sendBackVar?.value ? String(sendBackVar.value) : '';
    return {
      ...base,
      status: reason ? 'sent-back' : 'awaiting-submission',
      sendBackReason: reason || null,
      openTaskId: applicantTask.id,
    };
  }
  if (waitingOn?.activityId === PAYMENT_ACTIVITY_ID) {
    return { ...base, status: 'payment-needed', waitingOn: waitingOn.name };
  }
  if (waitingOn) {
    return { ...base, status: 'waiting-signatures', waitingOn: waitingOn.name };
  }
  if (backOfficeTask) {
    return { ...base, status: 'under-review' };
  }
  return { ...base, status: 'processing' };
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
      const [defs, instances, openWaits] = await Promise.all([
        listProcessDefinitions(),
        listHistoricProcessInstancesByStarter(username),
        listUnfinishedReceiveTasks(),
      ]);
      const defById = new Map<string, ProcessDefinition>(defs.map((d) => [d.id, d]));
      // First open receive task per case — one batched call for the page.
      const waitByPI = new Map<string, { activityId: string; name: string }>();
      for (const w of openWaits) {
        if (!waitByPI.has(w.processInstanceId)) {
          waitByPI.set(w.processInstanceId, {
            activityId: w.activityId,
            name: w.activityName ?? w.activityId,
          });
        }
      }
      const built = await Promise.all(
        instances.map((pi) => {
          const def = defById.get(pi.processDefinitionId);
          const name = def?.name ?? def?.key ?? pi.processDefinitionKey;
          return buildRow(
            pi,
            name,
            categoryOf(def?.key ?? pi.processDefinitionKey),
            username,
            waitByPI.get(pi.id) ?? null,
          );
        }),
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

  const buckets = useMemo(() => {
    const grouped: Record<Bucket, ProcessRow[]> = { attention: [], progress: [], done: [] };
    for (const r of rows) grouped[BUCKET_OF[r.status]].push(r);
    const byNewest = (a: ProcessRow, b: ProcessRow) =>
      new Date(b.pi.startTime).getTime() - new Date(a.pi.startTime).getTime();
    grouped.attention.sort(byNewest);
    grouped.progress.sort(byNewest);
    grouped.done.sort((a, b) => {
      const ae = a.pi.endTime ? new Date(a.pi.endTime).getTime() : 0;
      const be = b.pi.endTime ? new Date(b.pi.endTime).getTime() : 0;
      return be - ae;
    });
    return grouped;
  }, [rows]);

  const showContent = !loading && !error;
  const isEmpty = showContent && rows.length === 0;

  return (
    <div className="mp">
      <div className="mp-head">
        <div>
          <h1 className="mp-title">My processes</h1>
          {showContent && rows.length > 0 && (
            <p className="mp-kpi">
              <span className={`mp-kpi-strong${buckets.attention.length > 0 ? ' alert' : ''}`}>
                {buckets.attention.length} waiting on you
              </span>
              {' · '}
              <span className="mp-kpi-strong">{buckets.progress.length} in progress</span>
              {' · '}
              <span className="mp-kpi-strong">{buckets.done.length} completed</span>
            </p>
          )}
        </div>
        <button className="btn" onClick={load} disabled={loading}>
          Refresh
        </button>
      </div>

      {loading && <p className="muted">Loading…</p>}
      {error && <p className="form-error">{error}</p>}

      {isEmpty && (
        <p className="empty">
          You haven't started any processes yet. Pick one on the <Link to="/">Services</Link> page.
        </p>
      )}

      {showContent && buckets.attention.length > 0 && (
        <section className="mp-section">
          <h2 className="mp-section-title">Needs your attention</h2>
          <div className="mp-action-grid">
            {buckets.attention.map((r) => (
              <ActionCard key={r.pi.id} row={r} />
            ))}
          </div>
        </section>
      )}

      {showContent && buckets.progress.length > 0 && (
        <section className="mp-section">
          <h2 className="mp-section-title">In progress · with the back office</h2>
          <div className="mp-row-list">
            {buckets.progress.map((r) => (
              <ProgressRow key={r.pi.id} row={r} />
            ))}
          </div>
        </section>
      )}

      {showContent && buckets.done.length > 0 && (
        <details
          className="mp-completed"
          open={buckets.attention.length === 0 && buckets.progress.length === 0}
        >
          <summary>
            Completed <span className="muted">· {buckets.done.length}</span>
          </summary>
          <div className="mp-completed-body">
            {buckets.done.map((r) => (
              <ProgressRow key={r.pi.id} row={r} compact />
            ))}
          </div>
        </details>
      )}
    </div>
  );
}

function ActionCard({ row }: { row: ProcessRow }) {
  const isSentBack = row.status === 'sent-back';
  const isPayment = row.status === 'payment-needed';
  // Active applicant task → /tasks; payment wait → the public pay page;
  // otherwise /processes for read-only.
  const to = row.openTaskId
    ? `/tasks/${row.openTaskId}`
    : isPayment
      ? `/pay/${row.pi.id}`
      : `/processes/${row.pi.id}`;
  return (
    <Link
      to={to}
      className={`mp-action cat-${row.category}${isSentBack || isPayment ? ' sent-back' : ''}`}
    >
      <span className="mp-action-icon" aria-hidden="true">
        <CategoryIcon id={row.category} size={28} />
      </span>
      <span className="mp-action-body">
        <span className="mp-action-title">{row.serviceName}</span>
        <span className="mp-action-status">
          {isPayment
            ? '💳 State fee payment required'
            : isSentBack
              ? '⚠ Sent back for corrections'
              : '◆ Awaiting your submission'}
        </span>
        {row.sendBackReason && <span className="mp-action-reason">“{row.sendBackReason}”</span>}
        <span className="mp-action-meta">Started {formatDate(row.pi.startTime)}</span>
      </span>
      <span className="mp-action-cta">{isPayment ? 'Pay →' : 'Open →'}</span>
    </Link>
  );
}

function ProgressRow({ row, compact = false }: { row: ProcessRow; compact?: boolean }) {
  const isEnded = row.pi.endTime !== null;
  const to = `/processes/${row.pi.id}`;
  const action = isEnded ? 'View →' : 'View submission →';
  return (
    <Link to={to} className={`mp-row cat-${row.category}${compact ? ' compact' : ''}`}>
      <span className="mp-row-icon" aria-hidden="true">
        <CategoryIcon id={row.category} size={20} />
      </span>
      <span className="mp-row-body">
        <span className="mp-row-title">{row.serviceName}</span>
        <span className="mp-row-meta">
          {isEnded
            ? `Ended ${formatDate(row.pi.endTime!)}`
            : `Started ${formatDate(row.pi.startTime)}`}
          {!isEnded && row.waitingOn && ` · ${row.waitingOn}`}
        </span>
      </span>
      <span className="mp-row-right">
        <span className={STATUS_PILL_CLASS[row.status]}>{STATUS_LABELS[row.status]}</span>
        <span className="row-action">{action}</span>
      </span>
    </Link>
  );
}
