import { useEffect, useState, useCallback } from 'react';
import {
  getTask,
  getTaskVariables,
  completeTask,
  type CamundaTask,
  type CamundaVariables,
} from '../api/camundaClient';
import { formRegistry, parseFormId } from '../forms/registry';

/**
 * Renders one task: resolves its formKey to a React form and lets the user
 * complete it. Shared by the standalone route page (TaskDetailPage) and the
 * embedded right-pane on the civil-servant worklist (TasksPage).
 *
 * `onCompleted` fires after a successful complete so the host can navigate
 * or refresh the worklist; the standalone route uses it to go back to the
 * list, the embedded view uses it to clear selection and refetch.
 */
export interface TaskDetailViewProps {
  taskId: string;
  onCompleted: () => void;
  /** Optional element rendered above the form (e.g. a Back button on the standalone route). */
  topSlot?: React.ReactNode;
}

export default function TaskDetailView({
  taskId,
  onCompleted,
  topSlot,
}: TaskDetailViewProps) {
  const [task, setTask] = useState<CamundaTask | null>(null);
  const [data, setData] = useState<Record<string, unknown>>({});
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    setTask(null);
    setData({});
    try {
      const [t, vars] = await Promise.all([
        getTask(taskId),
        getTaskVariables(taskId),
      ]);
      setTask(t);
      setData(unwrap(vars));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, [taskId]);

  useEffect(() => {
    load();
  }, [load]);

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
        {topSlot}
        <p className="muted">Loading task…</p>
      </div>
    );
  }

  if (!task) {
    return (
      <div className="card">
        {topSlot}
        <p className="form-error">{error ?? 'Task not found.'}</p>
      </div>
    );
  }

  const formId = parseFormId(task.formKey);
  const Form = formId ? formRegistry[formId] : undefined;

  return (
    <div className="card">
      <div className="card-head">
        <h1 className="card-title">{task.name}</h1>
        {topSlot}
      </div>

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
  );
}

/** Unwraps CIB seven `{value,type}` variables into plain values for forms. */
function unwrap(variables: CamundaVariables): Record<string, unknown> {
  return Object.fromEntries(
    Object.entries(variables).map(([k, v]) => [k, v.value]),
  );
}
