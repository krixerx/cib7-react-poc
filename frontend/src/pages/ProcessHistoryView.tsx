import { useCallback, useEffect, useState } from 'react';
import {
  getHistoricProcessInstance,
  getProcessDefinitionXml,
  listHistoricTasks,
  listHistoricVariables,
  type CamundaTask,
  type HistoricProcessInstance,
  type HistoricTask,
  type HistoricVariableInstance,
} from '../api/camundaClient';
import { parseActivityNames, parseUserTasks } from '../api/bpmn';
import { formRegistry, parseFormId } from '../forms/registry';

/**
 * Read-only view of a process instance. Loads the historic variables and
 * renders the LAST completed user task's React form with `readOnly` set, so
 * the viewer sees the same fields the case actually carried but can't edit
 * them. Works for both ended cases and in-flight cases (e.g. an applicant
 * watching a case that's parked with the back office).
 *
 * Used as the route body of CompletedProcessPage (deep-link `/processes/:id`)
 * AND inlined in the civil-servant worklist's right pane when a selected
 * case has no active user task and no incidents.
 */
export interface ProcessHistoryViewProps {
  processInstanceId: string;
  /** Optional element rendered on the right of the card head (e.g. a Close/Back button). */
  topSlot?: React.ReactNode;
}

interface LoadedState {
  pi: HistoricProcessInstance;
  /** The last completed user task — its form drives the read-only render. */
  lastTask: HistoricTask;
  /** Historic variables collapsed to plain values keyed by name. */
  data: Record<string, unknown>;
  /** Form key resolved from the BPMN model for `lastTask.taskDefinitionKey`. */
  formKey: string | null;
  /** End-event label for ended cases, or "Currently with <step>" for in-flight. */
  outcome: string;
}

function unwrap(vars: HistoricVariableInstance[]): Record<string, unknown> {
  return Object.fromEntries(vars.map((v) => [v.name, v.value]));
}

/** Builds a CamundaTask shape from a historic task so the form contract holds. */
function synthesizeTask(ht: HistoricTask, formKey: string | null): CamundaTask {
  return {
    id: ht.id,
    name: ht.name,
    created: ht.startTime,
    processInstanceId: ht.processInstanceId,
    processDefinitionId: ht.processDefinitionId,
    taskDefinitionKey: ht.taskDefinitionKey,
    formKey,
    assignee: ht.assignee,
  };
}

export default function ProcessHistoryView({
  processInstanceId,
  topSlot,
}: ProcessHistoryViewProps) {
  const [state, setState] = useState<LoadedState | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    setState(null);
    try {
      const pi = await getHistoricProcessInstance(processInstanceId);
      const [tasks, vars, xml] = await Promise.all([
        listHistoricTasks(processInstanceId),
        listHistoricVariables(processInstanceId),
        getProcessDefinitionXml(pi.processDefinitionKey),
      ]);
      const completedTasks = tasks.filter((t) => t.endTime);
      if (completedTasks.length === 0) {
        throw new Error('No user task has been completed on this case yet.');
      }
      const lastTask = completedTasks[0]; // sorted desc by endTime
      const userTasks = parseUserTasks(xml.bpmn20Xml);
      const formKey =
        userTasks.find((ut) => ut.id === lastTask.taskDefinitionKey)?.formKey ?? null;
      const activityNames = parseActivityNames(xml.bpmn20Xml);
      const activeTask = tasks.find((t) => !t.endTime);
      const outcome = pi.endTime
        ? pi.endActivityId
          ? activityNames.get(pi.endActivityId) ?? pi.endActivityId
          : pi.state
        : activeTask
          ? `Currently with ${activeTask.name}`
          : 'In progress';
      setState({ pi, lastTask, data: unwrap(vars), formKey, outcome });
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, [processInstanceId]);

  useEffect(() => {
    load();
  }, [load]);

  if (loading) {
    return (
      <div className="card">
        {topSlot && <div className="card-head">{topSlot}</div>}
        <p className="muted">Loading process…</p>
      </div>
    );
  }

  if (!state) {
    return (
      <div className="card">
        {topSlot && <div className="card-head">{topSlot}</div>}
        <p className="form-error">{error ?? 'Process not found.'}</p>
      </div>
    );
  }

  const isInFlight = state.pi.endTime === null;
  const formId = parseFormId(state.formKey);
  const Form = formId ? formRegistry[formId] : undefined;
  const stubTask = synthesizeTask(state.lastTask, state.formKey);
  const stampDate = isInFlight
    ? state.lastTask.endTime ?? state.pi.startTime
    : state.pi.endTime!;

  return (
    <div className="card">
      <div className="card-head">
        <h1 className="card-title">{state.lastTask.name}</h1>
        {topSlot}
      </div>
      <p className="muted">
        {isInFlight ? 'Submitted' : 'Completed'} {new Date(stampDate).toLocaleString()} ·{' '}
        <strong>{state.outcome}</strong>
      </p>

      {Form ? (
        <Form
          task={stubTask}
          data={state.data}
          onComplete={() => Promise.resolve()}
          submitting={false}
          readOnly
        />
      ) : (
        <p className="form-error">
          No React form is registered for formKey{' '}
          <code>{state.formKey ?? '(none)'}</code>.
        </p>
      )}
    </div>
  );
}
