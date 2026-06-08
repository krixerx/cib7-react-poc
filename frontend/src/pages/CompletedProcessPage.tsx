import { useNavigate, useParams } from 'react-router-dom';
import { useAuth } from '../auth/AuthProvider';
import ProcessHistoryView from './ProcessHistoryView';

/**
 * Route wrapper around ProcessHistoryView for the deep-link
 * `/processes/:processInstanceId`. The civil-servant worklist renders the
 * same ProcessHistoryView inline in its right pane.
 *
 * Back target adapts to who's viewing: civil servants land on the worklist
 * root; applicants land on My processes.
 */
export default function CompletedProcessPage() {
  const { processInstanceId } = useParams<{ processInstanceId: string }>();
  const navigate = useNavigate();
  const { isCivilServant } = useAuth();
  const backPath = isCivilServant ? '/' : '/my-processes';

  if (!processInstanceId) {
    return (
      <div className="card">
        <p className="form-error">No process id.</p>
      </div>
    );
  }

  return (
    <ProcessHistoryView
      processInstanceId={processInstanceId}
      topSlot={
        <button className="btn" onClick={() => navigate(backPath)}>
          Back
        </button>
      }
    />
  );
}
