import { useEffect, useState, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  getTask,
  getTaskVariables,
  completeTask,
  type CamundaTask,
  type CamundaVariables,
} from '../api/camundaClient';
import { formRegistry, parseFormId } from '../forms/registry';

/** Unwraps CIB seven `{value,type}` variables into plain values for forms. */
function unwrap(variables: CamundaVariables): Record<string, unknown> {
  return Object.fromEntries(Object.entries(variables).map(([k, v]) => [k, v.value]));
}

/** Renders one task: resolves its formKey to a React form and completes it. */
export default function TaskDetailPage() {
  const { taskId } = useParams<{ taskId: string }>();
  const navigate = useNavigate();

  const [task, setTask] = useState<CamundaTask | null>(null);
  const [data, setData] = useState<Record<string, unknown>>({});
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!taskId) return;
    setLoading(true);
    setError(null);
    try {
      const [t, vars] = await Promise.all([getTask(taskId), getTaskVariables(taskId)]);
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
    if (!taskId) return;
    setSubmitting(true);
    setError(null);
    try {
      await completeTask(taskId, variables);
      navigate('/tasks');
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setSubmitting(false);
    }
  }

  if (loading) {
    return (
      <div className="card">
        <p className="muted">Loading task…</p>
      </div>
    );
  }

  if (!task) {
    return (
      <div className="card">
        <p className="form-error">{error ?? 'Task not found.'}</p>
        <div className="form-actions">
          <button className="btn" onClick={() => navigate('/tasks')}>
            Back to tasks
          </button>
        </div>
      </div>
    );
  }

  const formId = parseFormId(task.formKey);
  const Form = formId ? formRegistry[formId] : undefined;

  return (
    <div className="card">
      <div className="card-head">
        <h1 className="card-title">{task.name}</h1>
        <button className="btn" onClick={() => navigate('/tasks')}>
          Back
        </button>
      </div>

      {error && <p className="form-error">{error}</p>}

      {Form ? (
        <Form task={task} data={data} onComplete={handleComplete} submitting={submitting} />
      ) : (
        <p className="form-error">
          No React form is registered for formKey <code>{task.formKey ?? '(none)'}</code>.
        </p>
      )}
    </div>
  );
}
