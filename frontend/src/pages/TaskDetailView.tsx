import { useEffect, useState, useCallback } from 'react';
import {
  getProcessDefinitionXml,
  getTask,
  getTaskVariables,
  completeTask,
  type CamundaTask,
  type CamundaVariables,
} from '../api/camundaClient';
import { parseProcessName } from '../api/bpmn';
import { formRegistry, parseFormId } from '../forms/registry';
import DocumentsCard from '../components/DocumentsCard';
import ProcessTimeline from '../components/ProcessTimeline';

/**
 * Renders one task: resolves its formKey to a React form and lets the user
 * complete it. Shared by the standalone route page (TaskDetailPage) and the
 * embedded right-pane on the civil-servant worklist (TasksPage).
 *
 * `onCompleted` fires after a successful complete so the host can navigate
 * or refresh the worklist; the standalone route uses it to go back to the
 * list, the embedded view uses it to clear selection and refetch.
 *
 * Mirrors ProcessHistoryView's optional chrome handover (`hideDocuments`,
 * `hideOwnHeader`, `onLoaded`) so a parent page (TaskDetailPage) can wrap
 * the view in CaseDetailLayout and own the unified header / sticky
 * Documents sidebar.
 */
export interface TaskDetailViewProps {
  taskId: string;
  onCompleted: () => void;
  /** Optional element rendered above the form (e.g. a Back button on the standalone route). */
  topSlot?: React.ReactNode;
  /** When true, the parent is rendering the Documents card itself (e.g. in a sidebar). */
  hideDocuments?: boolean;
  /** When true, skip the form card's own header — parent owns the page title. */
  hideOwnHeader?: boolean;
  /** Fires once task + BPMN have loaded so the parent can surface service name / outcome. */
  onLoaded?: (info: TaskLoadedInfo) => void;
}

export interface TaskLoadedInfo {
  processInstanceId: string;
  processDefinitionKey: string;
  serviceName: string;
  outcome: string;
  isInFlight: boolean;
}

export default function TaskDetailView({
  taskId,
  onCompleted,
  topSlot,
  hideDocuments = false,
  hideOwnHeader = false,
  onLoaded,
}: TaskDetailViewProps) {
  const [task, setTask] = useState<CamundaTask | null>(null);
  const [data, setData] = useState<Record<string, unknown>>({});
  const [serviceName, setServiceName] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    setTask(null);
    setData({});
    setServiceName(null);
    try {
      const [t, vars] = await Promise.all([
        getTask(taskId),
        getTaskVariables(taskId),
      ]);
      setTask(t);
      setData(unwrap(vars));
      // BPMN fetch is only needed for the parent's chrome (eyebrow). The
      // form itself doesn't read serviceName, so this runs after the form
      // is already renderable — failure here is non-fatal.
      try {
        const key = processDefinitionKeyFromId(t.processDefinitionId);
        const xml = await getProcessDefinitionXml(key);
        setServiceName(parseProcessName(xml.bpmn20Xml));
      } catch {
        setServiceName(null);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, [taskId]);

  useEffect(() => {
    load();
  }, [load]);

  useEffect(() => {
    if (task && onLoaded) {
      onLoaded({
        processInstanceId: task.processInstanceId,
        processDefinitionKey: processDefinitionKeyFromId(task.processDefinitionId),
        serviceName: serviceName ?? task.name,
        outcome: task.name,
        isInFlight: true,
      });
    }
  }, [task, serviceName, onLoaded]);

  async function handleComplete(variables: CamundaVariables) {
    setSubmitting(true);
    setError(null);
    try {
      await completeTask(taskId, variables);
      onCompleted();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setSubmitting(false);
    }
  }

  if (loading) {
    return (
      <div className="card">
        {topSlot && !hideOwnHeader && <div className="card-head">{topSlot}</div>}
        <p className="muted">Loading task…</p>
      </div>
    );
  }

  if (!task) {
    return (
      <div className="card">
        {topSlot && !hideOwnHeader && <div className="card-head">{topSlot}</div>}
        <p className="form-error">{error ?? 'Task not found.'}</p>
      </div>
    );
  }

  const formId = parseFormId(task.formKey);
  const Form = formId ? formRegistry[formId] : undefined;

  return (
    <>
      <div className="card">
        {!hideOwnHeader && (
          <div className="card-head">
            <h1 className="card-title">{task.name}</h1>
            {topSlot}
          </div>
        )}

        {error && <p className="form-error">{error}</p>}

        {Form ? (
          <Form
            task={task}
            data={data}
            onComplete={handleComplete}
            submitting={submitting}
          />
        ) : (
          <p className="form-error">
            No React form is registered for formKey <code>{task.formKey ?? '(none)'}</code>.
          </p>
        )}
      </div>
      {/* The open form is the action — the case history below is the context
          a reviewer wants before deciding (who submitted what, when, which
          loops the case already took). */}
      <ProcessTimeline processInstanceId={task.processInstanceId} />
      {!hideDocuments && <DocumentsCard processInstanceId={task.processInstanceId} />}
    </>
  );
}

/** Unwraps CIB seven `{value,type}` variables into plain values for forms. */
function unwrap(variables: CamundaVariables): Record<string, unknown> {
  return Object.fromEntries(
    Object.entries(variables).map(([k, v]) => [k, v.value]),
  );
}

/** A processDefinitionId is "<key>:<version>:<id>" — split out the key. */
function processDefinitionKeyFromId(processDefinitionId: string): string {
  return processDefinitionId.split(':')[0] ?? processDefinitionId;
}
