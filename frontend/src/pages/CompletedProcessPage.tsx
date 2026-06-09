import { useCallback, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useAuth } from '../auth/AuthProvider';
import ProcessHistoryView from './ProcessHistoryView';
import DocumentsCard from '../components/DocumentsCard';
import { categoryOf } from '../services/categories';
import { CategoryIcon } from '../services/CategoryIcon';

/**
 * Route wrapper around ProcessHistoryView for the deep-link
 * `/processes/:processInstanceId`. Owns the standalone page layout: a
 * unified top rail (back · service eyebrow · status pill) above a
 * two-column grid (form on the left, Documents sticky sidebar on the
 * right). The civil-servant worklist still embeds the same
 * ProcessHistoryView in its right pane — but inline, with no extra
 * chrome, since the worklist already wraps it.
 *
 * Back target adapts to who's viewing: civil servants land on the
 * worklist root; applicants land on My processes.
 */
export default function CompletedProcessPage() {
  const { processInstanceId } = useParams<{ processInstanceId: string }>();
  const navigate = useNavigate();
  const { isCivilServant } = useAuth();
  const backPath = isCivilServant ? '/' : '/my-processes';

  const [info, setInfo] = useState<{
    serviceName: string;
    outcome: string;
    isInFlight: boolean;
    processDefinitionKey: string;
  } | null>(null);

  const onLoaded = useCallback(
    (i: {
      serviceName: string;
      outcome: string;
      isInFlight: boolean;
      processDefinitionKey: string;
    }) => setInfo(i),
    [],
  );

  if (!processInstanceId) {
    return (
      <div className="card">
        <p className="form-error">No process id.</p>
      </div>
    );
  }

  const category = info ? categoryOf(info.processDefinitionKey) : null;

  return (
    <div className="case-detail">
      <header className={`case-detail-head${category ? ` cat-${category}` : ''}`}>
        <button
          type="button"
          className="case-detail-back"
          onClick={() => navigate(backPath)}
          aria-label="Back"
        >
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <path d="M19 12H5" />
            <path d="m12 19-7-7 7-7" />
          </svg>
          <span>Back</span>
        </button>
        <div className="case-detail-bread">
          {category && (
            <span className="case-detail-eyebrow">
              <span className="case-detail-eyebrow-icon" aria-hidden="true">
                <CategoryIcon id={category} size={14} />
              </span>
              {info!.serviceName}
            </span>
          )}
          <h1 className="case-detail-title">Your submission</h1>
        </div>
        {info && (
          <span
            className={`case-detail-status${
              info.isInFlight ? ' in-flight' : ' ended'
            }`}
          >
            {info.outcome}
          </span>
        )}
      </header>

      <div className="case-detail-grid">
        <main className="case-detail-main">
          <ProcessHistoryView
            processInstanceId={processInstanceId}
            hideDocuments
            onLoaded={onLoaded}
          />
        </main>
        <aside className="case-detail-sidebar">
          <DocumentsCard processInstanceId={processInstanceId} />
        </aside>
      </div>
    </div>
  );
}
