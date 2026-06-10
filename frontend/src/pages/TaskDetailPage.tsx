import { useCallback, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthProvider';
import TaskDetailView, { type TaskLoadedInfo } from './TaskDetailView';
import CaseDetailLayout from '../components/CaseDetailLayout';
import { categoryOf } from '../services/categories';

/**
 * Thin route wrapper around TaskDetailView. Deep links such as
 * /tasks/{taskId} keep working unchanged; the civil-servant worklist
 * (TasksPage) renders the same TaskDetailView inline in its right pane.
 *
 * Wraps the view in CaseDetailLayout so the page shows the same unified
 * top rail + sticky Documents sidebar as CompletedProcessPage — the form
 * card on the left, Documents on the right, both anchored under a single
 * category-tinted header.
 *
 * After a successful complete the applicant goes back to /my-processes
 * and the civil servant goes back to the worklist root.
 */
export default function TaskDetailPage() {
  const { taskId } = useParams<{ taskId: string }>();
  const navigate = useNavigate();
  const { isCivilServant } = useAuth();
  const listPath = isCivilServant ? '/' : '/my-processes';

  const [info, setInfo] = useState<TaskLoadedInfo | null>(null);

  const onLoaded = useCallback((i: TaskLoadedInfo) => setInfo(i), []);

  if (!taskId) {
    return (
      <div className="card">
        <p className="form-error">No task id.</p>
      </div>
    );
  }

  return (
    <CaseDetailLayout
      category={info ? categoryOf(info.processDefinitionKey) : null}
      serviceName={info?.serviceName ?? null}
      title="Action required"
      outcome={info?.outcome ?? null}
      isInFlight
      onBack={() => navigate(listPath)}
      processInstanceId={info?.processInstanceId ?? null}
    >
      <TaskDetailView
        taskId={taskId}
        onCompleted={() => navigate(listPath)}
        hideDocuments
        hideOwnHeader
        onLoaded={onLoaded}
      />
    </CaseDetailLayout>
  );
}
