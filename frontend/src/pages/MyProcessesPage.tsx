import { useCallback, useEffect, useMemo, useState, type MouseEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router-dom';
import {
  ArrowRight,
  ChevronRight,
  CreditCard,
  FolderOpen,
  PenLine,
  RotateCcw,
  RotateCw,
  Trash2,
} from 'lucide-react';
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
import { deleteDraftCase, listDraftCaseIds } from '../api/draftCaseApi';
import { getPaymentLink } from '../api/paymentLinkApi';
import { translateBackendName } from '../i18n/backendNames';
import { formatDate } from '../i18n/format';
import { categoryOf, type CategoryId } from '../services/categories';
import { CategoryIcon } from '../services/CategoryIcon';

/**
 * PartA — the applicant's "My cases" page. Action-first inbox: a
 * "Needs your attention" zone of category-tinted cards puts cases waiting on
 * the user up top; quieter rows show work parked with the back office;
 * completed work collapses into a disclosure.
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
  /** Started but never submitted; the applicant may still delete it. */
  isDraft: boolean;
}

/** i18n keys for the status pills; shared labels come from `common`. */
const STATUS_LABEL_KEYS: Record<RowStatus, string> = {
  'awaiting-submission': 'statuses.awaitingSubmission',
  'sent-back': 'statuses.sentBack',
  'payment-needed': 'common:status.paymentRequired',
  'waiting-signatures': 'statuses.waitingSignatures',
  'under-review': 'common:status.underReview',
  processing: 'statuses.processing',
  approved: 'common:status.approved',
  ended: 'statuses.ended',
};

const STATUS_PILL_CLASS: Record<RowStatus, string> = {
  'awaiting-submission': 'pill pill-primary',
  'sent-back': 'pill pill-warn',
  'payment-needed': 'pill pill-warn',
  'waiting-signatures': 'pill pill-primary',
  'under-review': 'pill pill-primary',
  processing: 'pill pill-primary',
  approved: 'pill pill-ok',
  ended: 'pill',
};

/** The payment wait state in both shipped BPMNs. */
const PAYMENT_ACTIVITY_ID = 'Task_WaitForPayment';

async function buildRow(
  pi: HistoricProcessInstance,
  serviceName: string,
  category: CategoryId,
  username: string,
  waitingOn: { activityId: string; name: string } | null,
  isDraft: boolean,
): Promise<ProcessRow> {
  const base = {
    pi,
    serviceName,
    category,
    sendBackReason: null,
    openTaskId: null,
    waitingOn: null,
    isDraft,
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
  const { t } = useTranslation('my-processes');
  const { username } = useAuth();
  const [rows, setRows] = useState<ProcessRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [defs, instances, openWaits, draftIds] = await Promise.all([
        listProcessDefinitions(),
        listHistoricProcessInstancesByStarter(username),
        listUnfinishedReceiveTasks(),
        // Without the draft list the page still works, just without Delete.
        listDraftCaseIds().catch(() => new Set<string>()),
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
            draftIds.has(pi.id),
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
          <h1 className="mp-title">{t('head.title')}</h1>
          {showContent && rows.length > 0 && (
            <p className="mp-kpi">
              <span className={`mp-kpi-strong${buckets.attention.length > 0 ? ' alert' : ''}`}>
                {t('head.kpi.waiting', { count: buckets.attention.length })}
              </span>
              {' · '}
              <span className="mp-kpi-strong">
                {t('head.kpi.inProgress', { count: buckets.progress.length })}
              </span>
              {' · '}
              <span className="mp-kpi-strong">
                {t('head.kpi.completed', { count: buckets.done.length })}
              </span>
            </p>
          )}
        </div>
        <button type="button" className="btn" onClick={load} disabled={loading}>
          <RotateCw aria-hidden="true" />
          {t('common:actions.refresh')}
        </button>
      </div>

      {loading && <p className="muted">{t('common:feedback.loading')}</p>}
      {error && <p className="form-error">{t('errors.loadFailed', { message: error })}</p>}

      {isEmpty && (
        <div className="empty-state">
          <span className="empty-state-icon" aria-hidden="true">
            <FolderOpen size={26} />
          </span>
          <h2>{t('empty.title')}</h2>
          <p>{t('empty.body')}</p>
          <Link to="/" className="btn btn-primary">
            {t('empty.cta')}
            <ArrowRight aria-hidden="true" />
          </Link>
        </div>
      )}

      {showContent && buckets.attention.length > 0 && (
        <section className="mp-section">
          <h2 className="mp-section-title">{t('sections.attention')}</h2>
          <div className="mp-action-grid">
            {buckets.attention.map((r) => (
              <ActionCard
                key={r.pi.id}
                row={r}
                onDeleted={() => setRows((prev) => prev.filter((p) => p.pi.id !== r.pi.id))}
              />
            ))}
          </div>
        </section>
      )}

      {showContent && buckets.progress.length > 0 && (
        <section className="mp-section">
          <h2 className="mp-section-title">{t('sections.progress')}</h2>
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
            {t('sections.completed')} <span className="muted">· {buckets.done.length}</span>
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

function ActionCard({ row, onDeleted }: { row: ProcessRow; onDeleted: () => void }) {
  const { t } = useTranslation('my-processes');
  const [confirming, setConfirming] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const isSentBack = row.status === 'sent-back';
  const isPayment = row.status === 'payment-needed';
  const navigate = useNavigate();
  // Active applicant task → /tasks; payment wait → the public pay page,
  // whose link the backend mints for the case's starter; otherwise
  // /processes for read-only (also the fallback if no pay link is issued).
  const to = row.openTaskId ? `/tasks/${row.openTaskId}` : `/processes/${row.pi.id}`;
  const payInstead = isPayment && !row.openTaskId;
  async function openPayment(e: MouseEvent) {
    e.preventDefault();
    try {
      navigate(await getPaymentLink(row.pi.id));
    } catch {
      navigate(to);
    }
  }
  async function confirmDelete() {
    setDeleting(true);
    setDeleteError(null);
    try {
      await deleteDraftCase(row.pi.id);
      onDeleted();
    } catch (e) {
      setDeleteError(t('draft.deleteFailed', { message: e instanceof Error ? e.message : '' }));
      setDeleting(false);
    }
  }
  const card = (
    <Link
      to={to}
      onClick={payInstead ? openPayment : undefined}
      className={`mp-action cat-${row.category}${isSentBack || isPayment ? ' sent-back' : ''}`}
    >
      <span className="mp-action-icon" aria-hidden="true">
        <CategoryIcon id={row.category} size={28} />
      </span>
      <span className="mp-action-body">
        <span className="mp-action-title">{translateBackendName(t, row.serviceName)}</span>
        <span className="mp-action-status">
          {isPayment ? (
            <CreditCard size={15} aria-hidden="true" />
          ) : isSentBack ? (
            <RotateCcw size={15} aria-hidden="true" />
          ) : (
            <PenLine size={15} aria-hidden="true" />
          )}
          {isPayment
            ? t('card.paymentNeeded')
            : isSentBack
              ? t('card.sentBack')
              : row.isDraft
                ? t('card.draft')
                : t('card.awaitingSubmission')}
        </span>
        {row.sendBackReason && (
          <span className="mp-action-reason">
            {t('card.reason', { reason: row.sendBackReason })}
          </span>
        )}
        <span className="mp-action-meta">
          {t('meta.started', { date: formatDate(row.pi.startTime) })}
        </span>
      </span>
      <span className="mp-action-cta">
        {isPayment ? t('card.ctaPay') : t('card.ctaOpen')}
        <ArrowRight size={16} aria-hidden="true" />
      </span>
    </Link>
  );
  if (!row.isDraft) return card;
  // The delete controls sit beside the card link, not inside it: a button
  // nested in an <a> is invalid and would also trigger the navigation.
  return (
    <div className="mp-action-wrap">
      {card}
      {confirming ? (
        <div className="mp-draft-confirm" role="group" aria-label={t('draft.confirm')}>
          <span>{t('draft.confirm')}</span>
          <button
            type="button"
            className="btn btn-small btn-danger"
            onClick={confirmDelete}
            disabled={deleting}
          >
            {t('draft.delete')}
          </button>
          <button
            type="button"
            className="btn btn-small"
            onClick={() => setConfirming(false)}
            disabled={deleting}
          >
            {t('draft.keep')}
          </button>
          {deleteError && <p className="form-error">{deleteError}</p>}
        </div>
      ) : (
        <button
          type="button"
          className="btn btn-small btn-ghost mp-draft-delete"
          onClick={() => setConfirming(true)}
        >
          <Trash2 size={15} aria-hidden="true" />
          {t('draft.delete')}
        </button>
      )}
    </div>
  );
}

function ProgressRow({ row, compact = false }: { row: ProcessRow; compact?: boolean }) {
  const { t } = useTranslation('my-processes');
  const isEnded = row.pi.endTime !== null;
  const to = `/processes/${row.pi.id}`;
  const action = isEnded ? t('row.view') : t('row.viewSubmission');
  return (
    <Link to={to} className={`mp-row cat-${row.category}${compact ? ' compact' : ''}`}>
      <span className="mp-row-icon" aria-hidden="true">
        <CategoryIcon id={row.category} size={20} />
      </span>
      <span className="mp-row-body">
        <span className="mp-row-title">{translateBackendName(t, row.serviceName)}</span>
        <span className="mp-row-meta">
          {isEnded
            ? t('meta.ended', { date: formatDate(row.pi.endTime!) })
            : t('meta.started', { date: formatDate(row.pi.startTime) })}
          {!isEnded && row.waitingOn && ` · ${translateBackendName(t, row.waitingOn)}`}
        </span>
      </span>
      <span className="mp-row-right">
        <span className={STATUS_PILL_CLASS[row.status]}>{t(STATUS_LABEL_KEYS[row.status])}</span>
        <span className="mp-row-action">
          {action}
          <ChevronRight size={16} aria-hidden="true" />
        </span>
      </span>
    </Link>
  );
}
