import { useCallback, useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
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

interface LoadedState {
  pi: HistoricProcessInstance;
  /** The last user task the process visited (newest end-time). */
  lastTask: HistoricTask;
  /** Historic variables collapsed to plain values keyed by name. */
  data: Record<string, unknown>;
  /** Form key resolved from the BPMN model for `lastTask.taskDefinitionKey`. */
  formKey: string | null;
  /** End-event id resolved to a human-readable label, e.g. "Application approved". */
  outcome: string;
}

function unwrap(vars: HistoricVariableInstance[]): Record<string, unknown> {
  return Object.fromEntries(vars.map((v) => [v.name, v.value]));
}

/** Synthesizes a CamundaTask stub from a historic task so the form contract holds. */
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

/**
 * Opens a finished process instance and renders the last user task's form in
 * read-only mode, populated with the process's historic variables.
 */
export default function CompletedProcessPage() {
  const { processInstanceId } = useParams<{ processInstanceId: string }>();
  const navigate = useNavigate();

  const [state, setState] = useState<LoadedState | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!processInstanceId) return;
    setLoading(true);
    setError(null);
    try {
      const pi = await getHistoricProcessInstance(processInstanceId);
      const [tasks, vars, xml] = await Promise.all([
        listHistoricTasks(processInstanceId),
        listHistoricVariables(processInstanceId),
        // We need the BPMN model to map taskDefinitionKey → formKey and the
        // endActivityId → outcome label.
        getProcessDefinitionXml(pi.processDefinitionKey),
      ]);
      const completedTasks = tasks.filter((t) => t.endTime);
      if (completedTasks.length === 0) {
        throw new Error('This process completed without any user tasks.');
      }
      const lastTask = completedTasks[0]; // sorted desc by endTime
      const userTasks = parseUserTasks(xml.bpmn20Xml);
      const formKey = userTasks.find((ut) => ut.id === lastTask.taskDefinitionKey)?.formKey ?? null;
      const activityNames = parseActivityNames(xml.bpmn20Xml);
      const outcome = pi.endActivityId
        ? activityNames.get(pi.endActivityId) ?? pi.endActivityId
        : pi.state;
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
        <p className="muted">Loading process…</p>
      </div>
    );
  }

  if (!state) {
    return (
      <div className="card">
        <p className="form-error">{error ?? 'Process not found.'}</p>
        <div className="form-actions">
          <button className="btn" onClick={() => navigate('/tasks')}>
            Back to tasks
          </button>
        </div>
      </div>
    );
  }

  const formId = parseFormId(state.formKey);
  const Form = formId ? formRegistry[formId] : undefined;
  const stubTask = synthesizeTask(state.lastTask, state.formKey);

  return (
    <div className="card">
      <div className="card-head">
        <h1 className="card-title">{state.lastTask.name}</h1>
        <button className="btn" onClick={() => navigate('/tasks')}>
          Back
        </button>
      </div>
      <p className="muted">
        Completed {new Date(state.pi.endTime).toLocaleString()} ·{' '}
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
