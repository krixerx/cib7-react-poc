import { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ArrowRight, Bot, Check, CreditCard, Hourglass, UserRound } from 'lucide-react';
import { useNavigate } from 'react-router';
import { getPaymentLink } from '../api/paymentLinkApi';
import { formatDateTime } from '../i18n/format';
import { translateBackendName } from '../i18n/backendNames';
import {
  getHistoricProcessInstance,
  getProcessDefinitionXml,
  listActivityInstances,
  listVariableUpdates,
  type HistoricActivityInstance,
  type VariableUpdate,
} from '../api/camundaClient';
import { nextSteps, parseFlowGraph, type FlowNode } from '../api/bpmn';

/**
 * "Case progress" card — the answer to "where is my case right now and how
 * did it get there?" without opening Cockpit.
 *
 * Renders the engine's activity-instance history as a vertical timeline:
 *
 *   - completed steps (who completed a user task and when; review steps get
 *     an approved / sent-back chip derived from the `decision` variable's
 *     write history),
 *   - the activity the case is parked on right now (pulsing marker; a
 *     "Pay state fee" call-to-action when it's the payment wait state),
 *   - the expected next steps, walked forward through the BPMN
 *     sequence-flow graph (one likely path — branching gateways are
 *     resolved heuristically, see bpmn.ts nextSteps).
 *
 * Shared by both roles: the applicant's case views (PartA) and the
 * civil-servant worklist detail (PartB) embed the same component.
 */
export interface ProcessTimelineProps {
  processInstanceId: string;
}

/** Activity types a human reads as a "step" of the case. */
const DISPLAY_TYPES = new Set([
  'startEvent',
  'userTask',
  'serviceTask',
  'sendTask',
  'receiveTask',
  'businessRuleTask',
  'scriptTask',
  'callActivity',
  'noneEndEvent',
  'messageEndEvent',
  'errorEndEvent',
  'terminateEndEvent',
]);

/**
 * Who does the work in a step: a person (user task), the system on its own
 * (service, send, script and DMN tasks), or an outside party the case waits
 * for (receive tasks such as the payment callback or a co-owner's consent).
 * Events, gateways and subprocesses have no kind.
 */
type StepKind = 'human' | 'system' | 'external';

const KIND_BY_TYPE: Record<string, StepKind> = {
  userTask: 'human',
  serviceTask: 'system',
  sendTask: 'system',
  scriptTask: 'system',
  businessRuleTask: 'system',
  receiveTask: 'external',
};

const KIND_ICONS = { human: UserRound, system: Bot, external: Hourglass } as const;

function stepKind(type: string): StepKind | null {
  return KIND_BY_TYPE[type] ?? null;
}

/** The payment wait state in both shipped BPMNs — drives the pay CTA. */
const PAYMENT_ACTIVITY_ID = 'Task_WaitForPayment';

interface TimelineRow {
  key: string;
  activityId: string;
  name: string;
  type: string;
  /** Consecutive executions of the same activity collapsed (reminder loops, multi-instance). */
  count: number;
  open: boolean;
  canceled: boolean;
  assignee: string | null;
  startTime: string;
  endTime: string | null;
  /** "approve" | "sendback" for completed review tasks, when derivable. */
  decision: string | null;
}

interface LoadedState {
  rows: TimelineRow[];
  upcoming: FlowNode[];
  paymentDue: boolean;
  ended: boolean;
}

function buildRows(
  activities: HistoricActivityInstance[],
  decisions: VariableUpdate[],
): TimelineRow[] {
  const interesting = activities.filter(
    (a) => DISPLAY_TYPES.has(a.activityType) && !!a.activityName && !a.activityId.includes('#'),
  );

  const rows: TimelineRow[] = [];
  for (const a of interesting) {
    const last = rows[rows.length - 1];
    if (last && last.activityId === a.activityId) {
      // Collapse consecutive repeats — reviewer reminder loops, one receive
      // task per co-owner, etc. The group counts as "open" if any member is.
      last.count += 1;
      last.open = last.open || a.endTime === null;
      last.endTime = a.endTime ?? last.endTime;
      last.assignee = a.assignee ?? last.assignee;
      continue;
    }
    rows.push({
      key: a.id,
      activityId: a.activityId,
      name: a.activityName ?? a.activityId,
      type: a.activityType,
      count: 1,
      open: a.endTime === null,
      canceled: a.canceled,
      assignee: a.assignee,
      startTime: a.startTime,
      endTime: a.endTime,
      decision: null,
    });
  }

  // Pair completed review tasks with the decision-variable write history.
  // Only 'approve' / 'sendback' writes count (resubmission resets write
  // null); the k-th completed review pairs with the k-th real decision.
  const realDecisions = decisions
    .map((d) => (typeof d.value === 'string' ? d.value : null))
    .filter((v): v is string => v === 'approve' || v === 'sendback');
  let reviewIdx = 0;
  for (const row of rows) {
    if (row.type === 'userTask' && /review/i.test(row.activityId) && !row.open) {
      row.decision = realDecisions[reviewIdx] ?? null;
      reviewIdx += 1;
    }
  }

  return rows;
}

function stepTime(iso: string): string {
  return formatDateTime(iso, {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

export default function ProcessTimeline({ processInstanceId }: ProcessTimelineProps) {
  const { t } = useTranslation('components');
  const navigate = useNavigate();
  const [state, setState] = useState<LoadedState | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [payError, setPayError] = useState<string | null>(null);

  // The pay link carries a capability token only the backend can mint, and
  // only for the user who started the case (docs/security.md rule 3).
  async function openPayment() {
    setPayError(null);
    try {
      navigate(await getPaymentLink(processInstanceId));
    } catch {
      setPayError(t('timeline.paymentLinkUnavailable'));
    }
  }

  const load = useCallback(async () => {
    setState(null);
    setError(null);
    try {
      const pi = await getHistoricProcessInstance(processInstanceId);
      const [activities, decisions, xml] = await Promise.all([
        listActivityInstances(processInstanceId),
        listVariableUpdates(processInstanceId, 'decision'),
        getProcessDefinitionXml(pi.processDefinitionKey),
      ]);

      const rows = buildRows(activities, decisions);

      // Where is the case parked? First open activity drives both the pay
      // CTA and the forward walk through the BPMN graph.
      const openActivities = activities.filter(
        (a) => a.endTime === null && !a.activityId.includes('#') && a.activityType !== 'subProcess',
      );
      const anchor = openActivities[0] ?? null;
      const upcoming =
        pi.endTime === null && anchor
          ? nextSteps(parseFlowGraph(xml.bpmn20Xml), anchor.activityId, 4)
          : [];

      setState({
        rows,
        upcoming,
        paymentDue: openActivities.some((a) => a.activityId === PAYMENT_ACTIVITY_ID),
        ended: pi.endTime !== null,
      });
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [processInstanceId]);

  useEffect(() => {
    load();
  }, [load]);

  if (error) {
    return (
      <div className="card timeline-card">
        <h2 className="card-title">{t('timeline.title')}</h2>
        <p className="form-error">{error}</p>
      </div>
    );
  }

  return (
    <div className="card timeline-card">
      <div className="card-head">
        <h2 className="card-title">{t('timeline.title')}</h2>
      </div>

      {!state && <p className="muted">{t('timeline.loading')}</p>}

      {state && state.paymentDue && (
        <div className="pay-alert">
          <span className="pay-alert-icon" aria-hidden="true">
            <CreditCard size={20} />
          </span>
          <span className="pay-alert-body">
            <strong>{t('timeline.paymentRequiredTitle')}</strong>{' '}
            {t('timeline.paymentRequiredBody')}
          </span>
          <button type="button" className="btn btn-primary pay-alert-btn" onClick={openPayment}>
            {t('timeline.openPaymentPage')}
            <ArrowRight aria-hidden="true" />
          </button>
        </div>
      )}
      {payError && <p className="form-error">{payError}</p>}

      {state && <Stepper state={state} />}

      {state && (
        <details className="tl-details">
          <summary>{t('timeline.fullHistory')}</summary>
          <ol className="timeline">
            {state.rows.map((row) => (
              <TimelineItem key={row.key} row={row} />
            ))}
            {state.upcoming.length > 0 && (
              <li className="tl-upcoming-head" aria-hidden="true">
                {t('timeline.expectedNextSteps')}{' '}
                <span className="muted">{t('timeline.onePossiblePath')}</span>
              </li>
            )}
            {state.upcoming.map((step) => (
              <li key={`up-${step.id}`} className="tl-item upcoming">
                <span className="tl-dot" aria-hidden="true" />
                <span className="tl-body">
                  <span className="tl-name">
                    <KindBadge type={step.type} />
                    {translateBackendName(t, step.name)}
                  </span>
                </span>
              </li>
            ))}
          </ol>
        </details>
      )}
    </div>
  );
}

/** Activity types that read as a *milestone* — the horizontal stepper hides
 * the machinery (emails, PDF generation, storage) the full history keeps. */
const MILESTONE_TYPES = new Set([
  'startEvent',
  'userTask',
  'receiveTask',
  'businessRuleTask',
  'noneEndEvent',
  'messageEndEvent',
  'errorEndEvent',
  'terminateEndEvent',
]);

/** BPMN localNames (upcoming steps come from the model, not from history). */
const MILESTONE_MODEL_TYPES = new Set([
  'userTask',
  'receiveTask',
  'businessRuleTask',
  'subProcess',
  'endEvent',
]);

/**
 * Compact horizontal milestone bar — dots joined by a progress line, one
 * label per milestone, completed in green, the current wait pulsing, the
 * expected remainder grayed out. Service-task detail lives in the
 * "Full history" disclosure below it.
 */
function Stepper({ state }: { state: LoadedState }) {
  const { t } = useTranslation('components');
  const past = state.rows.filter((r) => MILESTONE_TYPES.has(r.type) && !r.canceled);
  const future = state.upcoming.filter(
    (s) => MILESTONE_MODEL_TYPES.has(s.type) && !past.some((p) => p.activityId === s.id),
  );
  const listRef = useRef<HTMLOListElement>(null);

  // A long case overflows the horizontal stepper, and the step the case is
  // parked on is usually past the visible edge. Scroll the list itself (not
  // the page) so that step sits in the middle.
  useEffect(() => {
    const list = listRef.current;
    const active = list?.querySelector<HTMLElement>('.step.active');
    if (!list || !active || list.scrollWidth <= list.clientWidth) return;
    const centred = active.offsetLeft + active.offsetWidth / 2 - list.clientWidth / 2;
    // RTL scroll offsets run from 0 at the start edge down to negative values.
    const rtl = getComputedStyle(list).direction === 'rtl';
    list.scrollLeft = rtl ? centred - (list.scrollWidth - list.clientWidth) : centred;
  }, [state]);

  return (
    <ol className="stepper" ref={listRef}>
      {past.map((row) => {
        const meta = row.open
          ? t('timeline.since', { time: stepTime(row.startTime) })
          : row.type === 'userTask' && row.assignee
            ? `${row.assignee} · ${stepTime(row.endTime ?? row.startTime)}`
            : stepTime(row.endTime ?? row.startTime);
        const kind = stepKind(row.type);
        return (
          <li key={row.key} className={`step ${row.open ? 'active' : 'done'}`}>
            <span className="step-dot" aria-hidden="true">
              {kind ? <KindIcon kind={kind} size={16} /> : !row.open && <CheckIcon />}
            </span>
            <span className="step-label">
              <KindLabel kind={kind} />
              {translateBackendName(t, row.name)}
              {row.count > 1 && (
                <span className="tl-count"> {t('timeline.repeat', { count: row.count })}</span>
              )}
            </span>
            {row.decision === 'approve' && (
              <span className="tl-chip approved">{t('common:status.approved')}</span>
            )}
            {row.decision === 'sendback' && (
              <span className="tl-chip sentback">{t('common:status.sentBack')}</span>
            )}
            {row.open && <span className="tl-chip waiting">{t('common:status.inProgress')}</span>}
            <span className="step-meta">{meta}</span>
          </li>
        );
      })}
      {future.map((step, i) => {
        const kind = stepKind(step.type);
        return (
          <li key={`up-${step.id}`} className="step upcoming">
            <span className="step-dot" aria-hidden="true">
              {kind ? (
                <KindIcon kind={kind} size={16} />
              ) : (
                <span className="step-num">{past.length + i + 1}</span>
              )}
            </span>
            <span className="step-label">
              <KindLabel kind={kind} />
              {translateBackendName(t, step.name)}
            </span>
            <span className="step-meta">{t('timeline.upcoming')}</span>
          </li>
        );
      })}
    </ol>
  );
}

function CheckIcon() {
  return <Check size={13} strokeWidth={3.5} aria-hidden="true" />;
}

function KindIcon({ kind, size }: { kind: StepKind; size: number }) {
  const Icon = KIND_ICONS[kind];
  return <Icon size={size} strokeWidth={2.25} aria-hidden="true" />;
}

/** The icon is decorative, so screen readers get the kind as text instead. */
function KindLabel({ kind }: { kind: StepKind | null }) {
  const { t } = useTranslation('components');
  if (!kind) return null;
  return <span className="sr-only">{t(`timeline.kind.${kind}`)}: </span>;
}

/** Small kind badge beside a name in the full-history list. */
function KindBadge({ type }: { type: string }) {
  const { t } = useTranslation('components');
  const kind = stepKind(type);
  if (!kind) return null;
  return (
    <span className={`tl-kind ${kind}`} title={t(`timeline.kind.${kind}`)}>
      <KindIcon kind={kind} size={13} />
      <span className="sr-only">{t(`timeline.kind.${kind}`)}: </span>
    </span>
  );
}

function TimelineItem({ row }: { row: TimelineRow }) {
  const { t } = useTranslation('components');
  const stateClass = row.open ? 'active' : row.canceled ? 'canceled' : 'done';
  const isEnd = row.type.endsWith('EndEvent');

  let meta: string;
  if (row.open) {
    meta = t('timeline.waitingSince', { time: stepTime(row.startTime) });
  } else if (row.type === 'userTask') {
    meta = `${
      row.assignee ? t('timeline.completedBy', { assignee: row.assignee }) : t('timeline.completed')
    }${row.endTime ? ` · ${stepTime(row.endTime)}` : ''}`;
  } else if (row.canceled) {
    meta = t('timeline.skipped');
  } else {
    meta = row.endTime ? stepTime(row.endTime) : stepTime(row.startTime);
  }

  return (
    <li className={`tl-item ${stateClass}${isEnd ? ' end' : ''}`}>
      <span className="tl-dot" aria-hidden="true" />
      <span className="tl-body">
        <span className="tl-name">
          <KindBadge type={row.type} />
          {translateBackendName(t, row.name)}
          {row.count > 1 && (
            <span className="tl-count"> {t('timeline.repeat', { count: row.count })}</span>
          )}
          {row.decision === 'approve' && (
            <span className="tl-chip approved">{t('common:status.approved')}</span>
          )}
          {row.decision === 'sendback' && (
            <span className="tl-chip sentback">{t('common:status.sentBack')}</span>
          )}
          {row.open && <span className="tl-chip waiting">{t('common:status.inProgress')}</span>}
        </span>
        <span className="tl-meta">{meta}</span>
      </span>
    </li>
  );
}
