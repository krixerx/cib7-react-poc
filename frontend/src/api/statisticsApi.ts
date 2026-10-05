/**
 * Typed client for `GET /api/statistics`. The backend aggregates engine
 * history server-side and answers only callers with the `statistics-viewer`
 * realm role, so this page never queries `/engine-rest` itself.
 */

import { keycloak, ensureFreshToken } from '../auth/keycloak';

export interface StatusCounts {
  started: number;
  completed: number;
  inProgress: number;
  failed: number;
  cancelled: number;
}

/**
 * What happened to the cases beyond their status. Approved and rejected split
 * the completed cases; touchless cases completed without a back-office task;
 * returned cases had the applicant redo a task.
 */
export interface Outcomes {
  approved: number;
  rejected: number;
  returned: number;
  touchless: number;
  reachedBackOffice: number;
  avgLeadTimeMs: number | null;
}

export interface PathCounts {
  approved: number;
  rejected: number;
  inProgress: number;
  failed: number;
  cancelled: number;
}

/** Back-office user tasks as a team; there are no per-person figures. */
export interface BackOffice {
  done: number;
  waiting: number;
  avgTaskMs: number | null;
  oldestWaitingSince: string | null;
}

export interface ServiceOption {
  key: string;
  name: string;
}

export interface TaskOption {
  /** `<processDefinitionKey>:<activityId>` of the task's own definition. */
  id: string;
  serviceKey: string;
  name: string;
}

export interface TaskStats extends TaskOption {
  completed: number;
  waiting: number;
  avgDurationMs: number | null;
}

export interface Failure {
  time: string | null;
  serviceKey: string;
  serviceName: string;
  processInstanceId: string;
  businessKey: string | null;
  task: string | null;
  type: string | null;
  message: string | null;
}

export interface StatisticsReport {
  from: string;
  to: string;
  zone: string;
  truncated: boolean;
  totals: StatusCounts;
  outcomes: Outcomes;
  services: ServiceOption[];
  tasks: TaskOption[];
  perService: { key: string; name: string; counts: StatusCounts; outcomes: Outcomes }[];
  perDay: { date: string; counts: StatusCounts; outcomes: Outcomes }[];
  flow: { backOffice: PathCounts; direct: PathCounts };
  backOffice: BackOffice;
  taskStats: TaskStats[];
  failures: Failure[];
}

export interface StatisticsFilter {
  /** Inclusive calendar days, YYYY-MM-DD, in `zone`. */
  from: string;
  to: string;
  zone: string;
  services: string[];
  tasks: string[];
}

export class StatisticsApiError extends Error {
  readonly httpStatus: number;
  constructor(httpStatus: number, message: string) {
    super(message);
    this.httpStatus = httpStatus;
  }
}

/** Fetches one aggregated report for the given filter. */
export async function fetchStatistics(filter: StatisticsFilter): Promise<StatisticsReport> {
  const params = new URLSearchParams({ from: filter.from, to: filter.to, zone: filter.zone });
  filter.services.forEach((s) => params.append('service', s));
  filter.tasks.forEach((t) => params.append('task', t));
  const token = keycloak.authenticated ? await ensureFreshToken() : null;
  const res = await fetch(`/api/statistics?${params.toString()}`, {
    cache: 'no-store',
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
  if (!res.ok) {
    let message = res.statusText || 'Request failed';
    try {
      const body = (await res.json()) as { message?: string };
      if (body.message) message = body.message;
    } catch {
      // Not JSON (e.g. a 403 from the security chain); keep the status text.
    }
    throw new StatisticsApiError(res.status, message);
  }
  return (await res.json()) as StatisticsReport;
}
