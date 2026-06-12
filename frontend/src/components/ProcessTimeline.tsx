import { useCallback, useEffect, useState } from 'react';
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
    (a) =>
      DISPLAY_TYPES.has(a.activityType) &&
      !!a.activityName &&
      !a.activityId.includes('#'),
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
  return new Date(iso).toLocaleString(undefined, {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

export default function ProcessTimeline({ processInstanceId }: ProcessTimelineProps) {
  const [state, setState] = useState<LoadedState | null>(null);
  const [error, setError] = useState<string | null>(null);

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
        <h2 className="card-title">Case progress</h2>
        <p className="form-error">{error}</p>
      </div>
    );
  }

  return (
    <div className="card timeline-card">
      <div className="card-head">
        <h2 className="card-title">Case progress</h2>
      </div>

      {!state && <p className="muted">Loading progress…</p>}

      {state && state.paymentDue && (
        <div className="pay-alert">
          <span className="pay-alert-icon" aria-hidden="true">💳</span>
          <span className="pay-alert-body">
            <strong>State fee payment required.</strong> The case stays parked
            until the fee is paid.
          </span>
          <a
            className="btn btn-primary pay-alert-btn"
            href={`/pay/${processInstanceId}`}
            target="_blank"
            rel="noopener noreferrer"
          >
            Open payment page →
          </a>
        </div>
      )}

      {state && <Stepper state={state} />}

      {state && (
        <details className="tl-details">
          <summary>Full history</summary>
          <ol className="timeline">
            {state.rows.map((row) => (
              <TimelineItem key={row.key} row={row} />
            ))}
            {state.upcoming.length > 0 && (
              <li className="tl-upcoming-head" aria-hidden="true">
                Expected next steps <span className="muted">· one possible path</span>
              </li>
            )}
            {state.upcoming.map((step) => (
              <li key={`up-${step.id}`} className="tl-item upcoming">
                <span className="tl-dot" aria-hidden="true" />
                <span className="tl-body">
                  <span className="tl-name">{step.name}</span>
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
  const past = state.rows.filter((r) => MILESTONE_TYPES.has(r.type) && !r.canceled);
  const future = state.upcoming.filter(
    (s) => MILESTONE_MODEL_TYPES.has(s.type) && !past.some((p) => p.activityId === s.id),
  );

  return (
    <ol className="stepper">
      {past.map((row) => {
        const meta = row.open
          ? `since ${stepTime(row.startTime)}`
          : row.type === 'userTask' && row.assignee
            ? `${row.assignee} · ${stepTime(row.endTime ?? row.startTime)}`
            : stepTime(row.endTime ?? row.startTime);
        return (
          <li key={row.key} className={`step ${row.open ? 'active' : 'done'}`}>
            <span className="step-dot" aria-hidden="true">
              {!row.open && <CheckIcon />}
            </span>
            <span className="step-label">
              {row.name}
              {row.count > 1 && <span className="tl-count"> ×{row.count}</span>}
            </span>
            {row.decision === 'approve' && <span className="tl-chip approved">Approved</span>}
            {row.decision === 'sendback' && <span className="tl-chip sentback">Sent back</span>}
            {row.open && <span className="tl-chip waiting">In progress</span>}
            <span className="step-meta">{meta}</span>
          </li>
        );
      })}
      {future.map((step, i) => (
        <li key={`up-${step.id}`} className="step upcoming">
          <span className="step-dot" aria-hidden="true">
            <span className="step-num">{past.length + i + 1}</span>
          </span>
          <span className="step-label">{step.name}</span>
          <span className="step-meta">upcoming</span>
        </li>
      ))}
    </ol>
  );
}

function CheckIcon() {
  return (
    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth={3.5} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M5 12l5 5L20 7" />
    </svg>
  );
}

function TimelineItem({ row }: { row: TimelineRow }) {
  const stateClass = row.open ? 'active' : row.canceled ? 'canceled' : 'done';
  const isEnd = row.type.endsWith('EndEvent');

  let meta: string;
  if (row.open) {
    meta = `Waiting since ${stepTime(row.startTime)}`;
  } else if (row.type === 'userTask') {
    meta = `Completed${row.assignee ? ` by ${row.assignee}` : ''}${
      row.endTime ? ` · ${stepTime(row.endTime)}` : ''
    }`;
  } else if (row.canceled) {
    meta = 'Skipped';
  } else {
    meta = row.endTime ? stepTime(row.endTime) : stepTime(row.startTime);
  }

  return (
    <li className={`tl-item ${stateClass}${isEnd ? ' end' : ''}`}>
      <span className="tl-dot" aria-hidden="true" />
      <span className="tl-body">
        <span className="tl-name">
          {row.name}
          {row.count > 1 && <span className="tl-count"> ×{row.count}</span>}
          {row.decision === 'approve' && <span className="tl-chip approved">Approved</span>}
          {row.decision === 'sendback' && <span className="tl-chip sentback">Sent back</span>}
          {row.open && <span className="tl-chip waiting">In progress</span>}
        </span>
        <span className="tl-meta">{meta}</span>
      </span>
    </li>
  );
}
