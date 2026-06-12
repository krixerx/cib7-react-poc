import type { ReactNode } from 'react';
import DocumentsCard from './DocumentsCard';
import { CategoryIcon } from '../services/CategoryIcon';
import type { CategoryId } from '../services/categories';

/**
 * Shared chrome for case-detail pages — a category-tinted top rail (Back ·
 * service eyebrow · page title · status pill) above a two-column grid that
 * keeps the Documents sidebar sticky as the main column scrolls.
 *
 * Pages fetch their own data (an active task for TaskDetailPage, the
 * historic process instance for CompletedProcessPage) and pass the
 * resolved chrome values down as props; this component renders. Sidebar
 * is rendered blank (empty grid column) while `processInstanceId` is
 * null so the layout doesn't reflow when the data finishes loading.
 *
 * Not used inside TasksPage's right pane — the worklist owns its own
 * layout and embeds TaskDetailView / ProcessHistoryView directly with
 * their default in-line Documents card behaviour.
 */
export interface CaseDetailLayoutProps {
  /** Drives the top-rail tint and the eyebrow icon. */
  category: CategoryId | null;
  /** Process definition `name=` from BPMN, shown in the eyebrow. */
  serviceName: string | null;
  /** Page title — e.g. "Your submission" (history) or "Action required" (task). */
  title: string;
  /** Status pill text — e.g. an end-event name or "Currently with X". */
  outcome: string | null;
  /** True → pulsing pill. False → green ended pill. */
  isInFlight: boolean;
  /** Back button click handler. */
  onBack: () => void;
  /** Process instance id for the sidebar DocumentsCard. Null while loading. */
  processInstanceId: string | null;
  /** Main column content. */
  children: ReactNode;
}

export default function CaseDetailLayout({
  category,
  serviceName,
  title,
  outcome,
  isInFlight,
  onBack,
  processInstanceId,
  children,
}: CaseDetailLayoutProps) {
  return (
    <div className="case-detail">
      <header className={`case-detail-head${category ? ` cat-${category}` : ''}`}>
        <button type="button" className="case-detail-back" onClick={onBack} aria-label="Back">
          <svg
            width="18"
            height="18"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <path d="M19 12H5" />
            <path d="m12 19-7-7 7-7" />
          </svg>
          <span>Back</span>
        </button>
        <div className="case-detail-bread">
          {category && serviceName && (
            <span className="case-detail-eyebrow">
              <span className="case-detail-eyebrow-icon" aria-hidden="true">
                <CategoryIcon id={category} size={14} />
              </span>
              {serviceName}
            </span>
          )}
          <h1 className="case-detail-title">{title}</h1>
        </div>
        {outcome && (
          <span className={`case-detail-status${isInFlight ? ' in-flight' : ' ended'}`}>
            {outcome}
          </span>
        )}
      </header>

      <div className="case-detail-grid">
        <main className="case-detail-main">{children}</main>
        <aside className="case-detail-sidebar">
          {processInstanceId && <DocumentsCard processInstanceId={processInstanceId} />}
        </aside>
      </div>
    </div>
  );
}
