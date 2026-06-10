import { useCallback, useEffect, useMemo, useState } from 'react';
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

      {state && (
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
      )}
    </div>
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
