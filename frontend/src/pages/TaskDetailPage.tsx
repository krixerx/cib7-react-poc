import { useParams, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthProvider';
import TaskDetailView from './TaskDetailView';

/**
 * Thin route wrapper around TaskDetailView. Deep links such as
 * /tasks/{taskId} keep working unchanged; the civil-servant worklist
 * (TasksPage) renders the same TaskDetailView inline in its right pane.
 *
 * After a successful complete the applicant goes back to /my-processes and
 * the civil servant goes back to the worklist root.
 */
export default function TaskDetailPage() {
  const { taskId } = useParams<{ taskId: string }>();
  const navigate = useNavigate();
  const { isCivilServant } = useAuth();
  const listPath = isCivilServant ? '/' : '/my-processes';

  if (!taskId) {
    return (
      <div className="card">
        <p className="form-error">No task id.</p>
      </div>
    );
  }

  return (
    <TaskDetailView
      taskId={taskId}
      onCompleted={() => navigate(listPath)}
      topSlot={
        <button className="btn" onClick={() => navigate(listPath)}>
          Back
        </button>
      }
    />
  );
}
