import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { listWorklist, setJobRetries, type Incident, type WorklistRow } from '../api/camundaClient';
import { useAuth } from '../auth/AuthProvider';
import TaskDetailView from './TaskDetailView';
import ProcessHistoryView from './ProcessHistoryView';

/**
 * PartB — civil-servant worklist. Two-pane layout: a filterable, applicant-
 * searchable, date-desc-sorted list on the left; the selected case's task
 * form on the right.
 *
 * Selection lives in `?case=<processInstanceId>` so back/forward and reloads
 * preserve the open case. Filters live in component state — they reset on
 * reload, which is what worklist users normally want (you come back to the
 * top of the queue, not yesterday's filter).
 *
 * Decision-state taxonomy mirrors the civil-servant mental model rather than
 * Camunda internals: pending = case in flight, confirmed = ended at
 * EndEvent_Approved, rejected = ended at any other end event. The same status
 * machinery powers the Status filter and the per-row pill.
 */

type StatusFilter = WorklistRow['status']; // 'pending' | 'incident' | 'confirmed' | 'rejected'

const STATUS_LABEL: Record<StatusFilter, string> = {
  pending: 'Pending',
  incident: 'Incident',
  confirmed: 'Confirmed',
  rejected: 'Rejected',
};

const STATUS_ORDER: StatusFilter[] = ['pending', 'incident', 'confirmed', 'rejected'];

/** ABBR for the avatar circle — first letter of first name + first letter of last name. */
function initials(name: string): string {
  const parts = name.trim().split(/\s+/);
  if (parts.length === 0 || !parts[0]) return '?';
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}

/** Deterministic palette index 0–5 from the applicant id; stable across reloads. */
function avatarTone(seed: string): number {
  let h = 0;
  for (let i = 0; i < seed.length; i++) h = (h * 31 + seed.charCodeAt(i)) | 0;
  return Math.abs(h) % 6;
}

/** "Jun 5, 14:22" — short, locale-light, no year unless older than this year. */
function shortDate(iso: string): string {
  const d = new Date(iso);
  const now = new Date();
  const sameYear = d.getFullYear() === now.getFullYear();
  const opts: Intl.DateTimeFormatOptions = sameYear
    ? { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }
    : { year: 'numeric', month: 'short', day: 'numeric' };
  return d.toLocaleString(undefined, opts);
}

/** "3d", "5h", "12m" — coarse age for a small badge. */
function ageBadge(iso: string): string {
  const ms = Date.now() - new Date(iso).getTime();
  const minutes = Math.floor(ms / 60_000);
  if (minutes < 60) return `${Math.max(minutes, 1)}m`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h`;
  return `${Math.floor(hours / 24)}d`;
}

function shortCaseId(processInstanceId: string): string {
  return processInstanceId.slice(0, 8);
}

export default function TasksPage() {
  const { username } = useAuth();
  const [params, setParams] = useSearchParams();
  const selectedCaseId = params.get('case');

  const [rows, setRows] = useState<WorklistRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Filters
  const [serviceFilter, setServiceFilter] = useState<Set<string>>(new Set());
  const [taskFilter, setTaskFilter] = useState<Set<string>>(new Set());
  const [statusFilter, setStatusFilter] = useState<Set<StatusFilter>>(new Set());
  const [applicantQuery, setApplicantQuery] = useState('');
  const [myCasesOnly, setMyCasesOnly] = useState(false);

  // Open-dropdown state — only one panel open at a time.
  const [openMenu, setOpenMenu] = useState<'service' | 'task' | 'status' | null>(null);

  // Close the open filter menu on any click outside the filters block, or on
  // Escape. Document-level so clicks in the detail pane dismiss it too.
  const filtersRef = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    if (openMenu === null) return;
    function onPointerDown(e: PointerEvent) {
      if (filtersRef.current && !filtersRef.current.contains(e.target as Node)) {
        setOpenMenu(null);
      }
    }
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') setOpenMenu(null);
    }
    document.addEventListener('pointerdown', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('pointerdown', onPointerDown);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [openMenu]);

  // Incident retry: track which job-id is mid-flight so the button can show a busy state.
  const [busyIncidentId, setBusyIncidentId] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setRows(await listWorklist());
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  // Distinct values powering the filter dropdowns.
  const services = useMemo(() => {
    const m = new Map<string, string>();
    for (const r of rows) m.set(r.processDefinitionKey, r.serviceName);
    return Array.from(m, ([key, name]) => ({ key, name })).sort((a, b) =>
      a.name.localeCompare(b.name),
    );
  }, [rows]);

  const tasks = useMemo(() => {
    const m = new Map<string, string>();
    for (const r of rows) {
      if (r.currentTask) m.set(r.currentTask.taskDefinitionKey, r.currentTask.name);
    }
    return Array.from(m, ([key, name]) => ({ key, name })).sort((a, b) =>
      a.name.localeCompare(b.name),
    );
  }, [rows]);

  // Applied rows after filters + search + sort. The base list is already
  // server-sorted by startTime desc, so we only re-sort if needed (we don't).
  const filteredRows = useMemo(() => {
    const q = applicantQuery.trim().toLowerCase();
    return rows.filter((r) => {
      if (serviceFilter.size > 0 && !serviceFilter.has(r.processDefinitionKey)) return false;
      if (taskFilter.size > 0) {
        if (!r.currentTask) return false;
        if (!taskFilter.has(r.currentTask.taskDefinitionKey)) return false;
      }
      if (statusFilter.size > 0 && !statusFilter.has(r.status)) return false;
      if (myCasesOnly && r.currentTask?.assignee !== username) return false;
      if (q && !r.applicantName.toLowerCase().includes(q)) return false;
      return true;
    });
  }, [rows, serviceFilter, taskFilter, statusFilter, applicantQuery, myCasesOnly, username]);

  const resetFilters = () => {
    setServiceFilter(new Set());
    setTaskFilter(new Set());
    setStatusFilter(new Set());
    setApplicantQuery('');
    setMyCasesOnly(false);
  };

  const filtersActive =
    serviceFilter.size > 0 ||
    taskFilter.size > 0 ||
    statusFilter.size > 0 ||
    applicantQuery.length > 0 ||
    myCasesOnly;

  function pickCase(processInstanceId: string) {
    const next = new URLSearchParams(params);
    next.set('case', processInstanceId);
    setParams(next, { replace: false });
  }

  function clearCase() {
    const next = new URLSearchParams(params);
    next.delete('case');
    setParams(next, { replace: false });
  }

  const selected = selectedCaseId
    ? (rows.find((r) => r.processInstanceId === selectedCaseId) ?? null)
    : null;

  async function retryIncident(inc: Incident) {
    if (!inc.configuration) return;
    setBusyIncidentId(inc.id);
    setError(null);
    try {
      await setJobRetries(inc.configuration, 1);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusyIncidentId(null);
    }
  }

  return (
    <div className="worklist">
      <aside className="worklist-list">
        <div className="worklist-list-head">
          <span className="worklist-list-title">Process list</span>
          <span className={`worklist-list-count${loading ? ' refreshing' : ''}`} aria-live="polite">
            {loading
              ? rows.length > 0
                ? 'Refreshing…'
                : 'Loading…'
              : filtersActive
                ? `${filteredRows.length} of ${rows.length}`
                : `${rows.length}`}
          </span>
          <button
            type="button"
            className="worklist-refresh"
            onClick={() => load()}
            disabled={loading}
            title="Refresh list"
            aria-label="Refresh list"
          >
            <svg
              width="14"
              height="14"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth={2}
              strokeLinecap="round"
              strokeLinejoin="round"
              aria-hidden="true"
            >
              <path d="M21 12a9 9 0 1 1-3-6.7" />
              <path d="M21 3v6h-6" />
            </svg>
          </button>
          <span className="scope-toggle">
            <span id="my-cases-label">My cases</span>
            <span
              className={`scope-switch${myCasesOnly ? ' on' : ''}`}
              role="switch"
              aria-checked={myCasesOnly}
              aria-labelledby="my-cases-label"
              tabIndex={0}
              onClick={() => setMyCasesOnly((v) => !v)}
              onKeyDown={(e) => {
                if (e.key === ' ' || e.key === 'Enter') {
                  e.preventDefault();
                  setMyCasesOnly((v) => !v);
                }
              }}
            />
          </span>
        </div>

        <div className="worklist-filters" ref={filtersRef}>
          <div className="filter-row">
            <FilterPill
              label="Service"
              values={Array.from(serviceFilter).map(
                (k) => services.find((s) => s.key === k)?.name ?? k,
              )}
              open={openMenu === 'service'}
              onToggle={() => setOpenMenu(openMenu === 'service' ? null : 'service')}
            >
              {services.length === 0 ? (
                <div className="dd-empty">No services in view yet.</div>
              ) : (
                services.map((s) => (
                  <DropdownItem
                    key={s.key}
                    checked={serviceFilter.has(s.key)}
                    onToggle={() => toggleSet(serviceFilter, s.key, setServiceFilter)}
                  >
                    {s.name}
                  </DropdownItem>
                ))
              )}
            </FilterPill>

            <FilterPill
              label="Task"
              values={Array.from(taskFilter).map((k) => tasks.find((t) => t.key === k)?.name ?? k)}
              open={openMenu === 'task'}
              onToggle={() => setOpenMenu(openMenu === 'task' ? null : 'task')}
            >
              {tasks.length === 0 ? (
                <div className="dd-empty">No active tasks in view.</div>
              ) : (
                tasks.map((t) => (
                  <DropdownItem
                    key={t.key}
                    checked={taskFilter.has(t.key)}
                    onToggle={() => toggleSet(taskFilter, t.key, setTaskFilter)}
                  >
                    {t.name}
                  </DropdownItem>
                ))
              )}
            </FilterPill>

            <FilterPill
              label="Status"
              values={Array.from(statusFilter).map((s) => STATUS_LABEL[s])}
              open={openMenu === 'status'}
              onToggle={() => setOpenMenu(openMenu === 'status' ? null : 'status')}
            >
              {STATUS_ORDER.map((s) => (
                <DropdownItem
                  key={s}
                  checked={statusFilter.has(s)}
                  onToggle={() => toggleSet(statusFilter, s, setStatusFilter)}
                >
                  <span className={`status-dot status-dot-${s}`} aria-hidden="true" />
                  {STATUS_LABEL[s]}
                </DropdownItem>
              ))}
            </FilterPill>

            <button
              type="button"
              className="filter-reset"
              onClick={resetFilters}
              disabled={!filtersActive}
            >
              Reset
            </button>
          </div>

          <div className="worklist-search">
            <SearchIcon />
            <input
              type="search"
              placeholder="Search by applicant name…"
              value={applicantQuery}
              onChange={(e) => setApplicantQuery(e.target.value)}
            />
          </div>
        </div>

        <div className="worklist-sort-line">
          Newest first
          <span className="worklist-sort-pill">Date submitted ↓</span>
        </div>

        {error && <p className="form-error worklist-error">{error}</p>}

        <ul className="worklist-rows">
          {!loading && filteredRows.length === 0 && (
            <li className="empty worklist-empty">
              {rows.length === 0 ? 'No processes yet.' : 'No cases match the current filters.'}
            </li>
          )}
          {filteredRows.map((r) => (
            <li key={r.processInstanceId}>
              <button
                className={`worklist-row${
                  r.processInstanceId === selectedCaseId ? ' selected' : ''
                }`}
                onClick={() => pickCase(r.processInstanceId)}
              >
                <span
                  className={`worklist-avatar tone-${avatarTone(
                    r.startUserId ?? r.processInstanceId,
                  )}`}
                  aria-hidden="true"
                >
                  {initials(r.applicantName || r.startUserId || '?')}
                </span>
                <span className="worklist-meta">
                  <span className="worklist-name">
                    {r.applicantName || r.startUserId || '(no name)'}
                  </span>
                  <span className="worklist-service">
                    {r.serviceName}
                    {r.currentTask && (
                      <>
                        <span className="worklist-step-sep">·</span>
                        {r.currentTask.name}
                      </>
                    )}
                    {!r.currentTask && r.waitingOn && (
                      <>
                        <span className="worklist-step-sep">·</span>
                        <span className="worklist-waiting">⏳ {r.waitingOn.name}</span>
                      </>
                    )}
                  </span>
                  <span className="worklist-date">
                    CASE-{shortCaseId(r.processInstanceId)} · {shortDate(r.startTime)}
                  </span>
                </span>
                <span className="worklist-right">
                  <span className={`status-tag status-${r.status}`}>
                    <span className="status-dot" aria-hidden="true" />
                    {STATUS_LABEL[r.status]}
                  </span>
                  <span className="worklist-age">{ageBadge(r.startTime)}</span>
                </span>
              </button>
            </li>
          ))}
        </ul>
      </aside>

      <main className="worklist-detail">
        {!selected && (
          <div className="card worklist-empty-state">
            <h1 className="card-title">Pick a case from the left</h1>
            <p className="muted">
              {filteredRows.length} {filteredRows.length === 1 ? 'case' : 'cases'} in view. Filter
              by service, task or status, or search by applicant name. Pick one to open its form
              here.
            </p>
          </div>
        )}

        {selected && selected.incidents.length > 0 && (
          <IncidentBlock
            applicantName={selected.applicantName || selected.startUserId || 'Case'}
            serviceName={selected.serviceName}
            caseShortId={shortCaseId(selected.processInstanceId)}
            incidents={selected.incidents}
            busyIncidentId={busyIncidentId}
            onRetry={retryIncident}
            onClose={clearCase}
          />
        )}

        {selected && !selected.currentTask && selected.incidents.length === 0 && (
          <ProcessHistoryView
            processInstanceId={selected.processInstanceId}
            topSlot={
              <button className="btn" onClick={clearCase}>
                Close
              </button>
            }
          />
        )}

        {selected && selected.currentTask && (
          <TaskDetailView
            taskId={selected.currentTask.id}
            onCompleted={() => {
              clearCase();
              load();
            }}
            topSlot={
              <button className="btn" onClick={clearCase}>
                Close
              </button>
            }
          />
        )}
      </main>
    </div>
  );
}

/** Toggle a value in a Set state, immutably. */
function toggleSet<T>(current: Set<T>, value: T, setter: (next: Set<T>) => void) {
  const next = new Set(current);
  if (next.has(value)) next.delete(value);
  else next.add(value);
  setter(next);
}

interface FilterPillProps {
  label: string;
  values: string[];
  open: boolean;
  onToggle: () => void;
  children: React.ReactNode;
}

function FilterPill({ label, values, open, onToggle, children }: FilterPillProps) {
  const active = values.length > 0;
  const summary =
    values.length === 0
      ? 'All'
      : values.length <= 2
        ? values.join(', ')
        : `${values[0]} +${values.length - 1}`;
  return (
    <div className={`filter-pill-wrap${open ? ' open' : ''}`}>
      <button
        type="button"
        className={`filter-pill${active ? ' active' : ''}`}
        onClick={onToggle}
        aria-haspopup="listbox"
        aria-expanded={open}
      >
        <span className="filter-pill-label">{label}</span>
        <span className="filter-pill-summary">{summary}</span>
        {active && <span className="filter-pill-badge">{values.length}</span>}
        <ChevronIcon />
      </button>
      {open && (
        <div className="filter-dropdown" role="listbox">
          {children}
        </div>
      )}
    </div>
  );
}

interface DropdownItemProps {
  checked: boolean;
  onToggle: () => void;
  children: React.ReactNode;
}

function DropdownItem({ checked, onToggle, children }: DropdownItemProps) {
  return (
    <button
      type="button"
      className={`dd-item${checked ? ' checked' : ''}`}
      onClick={onToggle}
      role="option"
      aria-selected={checked}
    >
      <span className={`dd-checkbox${checked ? ' on' : ''}`} aria-hidden="true">
        {checked && (
          <svg
            width="10"
            height="10"
            viewBox="0 0 24 24"
            fill="none"
            stroke="white"
            strokeWidth={3}
          >
            <path d="M5 12l5 5L20 7" />
          </svg>
        )}
      </span>
      <span className="dd-item-label">{children}</span>
    </button>
  );
}

function ChevronIcon() {
  return (
    <svg
      width="10"
      height="10"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={2.5}
      aria-hidden="true"
    >
      <path d="m6 9 6 6 6-6" />
    </svg>
  );
}

function SearchIcon() {
  return (
    <svg
      width="13"
      height="13"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      aria-hidden="true"
    >
      <circle cx="11" cy="11" r="7" />
      <path d="m21 21-4.3-4.3" />
    </svg>
  );
}

interface IncidentBlockProps {
  applicantName: string;
  serviceName: string;
  caseShortId: string;
  incidents: Incident[];
  busyIncidentId: string | null;
  onRetry: (inc: Incident) => void;
  onClose: () => void;
}

/**
 * Detail-pane block for a case that has open incidents. When the case is
 * stuck at a failed service task there's no user task to render — the
 * block surfaces the error message and a Retry button per incident so the
 * civil servant can unblock it without leaving the worklist.
 */
function IncidentBlock({
  applicantName,
  serviceName,
  caseShortId,
  incidents,
  busyIncidentId,
  onRetry,
  onClose,
}: IncidentBlockProps) {
  return (
    <div className="card card-incident">
      <div className="card-head">
        <h1 className="card-title">
          <span className="card-incident-icon" aria-hidden="true">
            ⚑
          </span>
          {applicantName}
        </h1>
        <button className="btn" onClick={onClose}>
          Close
        </button>
      </div>
      <p className="muted">
        {serviceName} · CASE-{caseShortId} ·{' '}
        <span className="status-tag status-incident">
          <span className="status-dot" aria-hidden="true" />
          {incidents.length === 1 ? '1 open incident' : `${incidents.length} open incidents`}
        </span>
      </p>

      <ul className="incident-list">
        {incidents.map((inc) => {
          const canRetry = inc.incidentType === 'failedJob' && !!inc.configuration;
          const busy = busyIncidentId === inc.id;
          return (
            <li key={inc.id} className="incident">
              <div className="incident-head">
                <span className="row-title">{inc.activityId ?? '(process scope)'}</span>
                <span className="row-sub">
                  {inc.incidentType} <span className="muted">·</span>{' '}
                  {new Date(inc.incidentTimestamp).toLocaleString()}
                </span>
              </div>
              {inc.incidentMessage && <p className="incident-message">{inc.incidentMessage}</p>}
              <div className="incident-actions">
                {canRetry ? (
                  <button className="btn btn-primary" onClick={() => onRetry(inc)} disabled={busy}>
                    {busy ? 'Retrying…' : 'Retry'}
                  </button>
                ) : (
                  <span className="muted">No retry available for this incident type.</span>
                )}
              </div>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
