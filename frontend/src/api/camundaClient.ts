/**
 * Thin typed client for the CIB seven REST API (`/engine-rest`).
 *
 * The browser always calls the same-origin path `/engine-rest/...`; the Vite
 * dev server (vite.config.ts) and nginx (nginx.conf) proxy it to the backend.
 *
 * Most requests carry a Keycloak-issued bearer token; the backend's
 * RestApiSecurityConfig validates it before any handler runs. The single
 * exception is the anonymous services-list call (GET /process-definition) —
 * the SPA fires it on the public landing page before the user has signed in
 * (PublicEngineRestSecurityConfig on the backend). When `keycloak.authenticated`
 * is false we therefore omit the Authorization header instead of refusing
 * to call.
 */

import { keycloak, ensureFreshToken } from '../auth/keycloak';

const BASE = '/engine-rest';

/** A deployed process definition — surfaced in the UI as a "service". */
export interface ProcessDefinition {
  id: string;
  key: string;
  name: string | null;
  version: number;
}

/** A user task as returned by `GET /task`. */
export interface CamundaTask {
  id: string;
  name: string;
  created: string;
  processInstanceId: string;
  processDefinitionId: string;
  /** BPMN id of the user task — matches a <userTask> id in the model. */
  taskDefinitionKey: string;
  /** e.g. "react:owner-vehicle" — see forms/registry.ts. */
  formKey: string | null;
  assignee: string | null;
}

export type CamundaVariableType =
  | 'String'
  | 'Integer'
  | 'Long'
  | 'Double'
  | 'Boolean'
  // CIB seven Spin Json. `value` is a JSON-encoded string; the engine
  // deserialises it via Spin and BPMN sees a SpinJsonNode (so JUEL
  // expressions like ${additionalOwners.elements()} work).
  | 'Json';

/** A CIB seven typed variable: `{ value, type }`. */
export interface CamundaVariable {
  value: unknown;
  type: CamundaVariableType;
}

export type CamundaVariables = Record<string, CamundaVariable>;

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const token = keycloak.authenticated ? await ensureFreshToken() : null;

  // `cache: 'no-store'` matters more than it looks. /engine-rest GETs don't
  // set Cache-Control: no-store, so the browser is free to serve a stale
  // response — particularly bad on the civil-servant worklist where we
  // refetch right after completing a task: an HTTP-cached response shows
  // the just-completed task as still pending until the user does a full
  // page reload. Forcing no-store closes that gap. Callers can still
  // override via `init.cache` if they ever want HTTP caching back.
  const res = await fetch(BASE + path, {
    cache: 'no-store',
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(init?.headers ?? {}),
    },
  });

  if (!res.ok) {
    const body = await res.text();
    throw new Error(`CIB seven REST ${res.status}: ${body || res.statusText}`);
  }

  // `complete` returns 204 No Content.
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

/** Lists the latest version of every deployed process definition. */
export function listProcessDefinitions(): Promise<ProcessDefinition[]> {
  return request('/process-definition?latestVersion=true&sortBy=name&sortOrder=asc');
}

/** Fetches the raw BPMN XML of a process definition (used to read its tasks). */
export function getProcessDefinitionXml(key: string): Promise<{ id: string; bpmn20Xml: string }> {
  return request(`/process-definition/key/${key}/xml`);
}

/** Starts a new instance of the given process definition. */
export function startProcess(key: string): Promise<{ id: string }> {
  return request(`/process-definition/key/${key}/start`, {
    method: 'POST',
    body: JSON.stringify({}),
  });
}

/** Lists all open user tasks, newest first. */
export function listTasks(): Promise<CamundaTask[]> {
  return request('/task?sortBy=created&sortOrder=desc');
}

/** Lists the open tasks of a single process instance. */
export function listTasksByInstance(processInstanceId: string): Promise<CamundaTask[]> {
  return request(`/task?processInstanceId=${processInstanceId}`);
}

/** Fetches a single task, including its `formKey`. */
export function getTask(id: string): Promise<CamundaTask> {
  return request(`/task/${id}`);
}

/** Fetches the process variables visible to a task's form. */
export function getTaskVariables(id: string): Promise<CamundaVariables> {
  // deserializeValues=false — see listHistoricVariables for why.
  return request(`/task/${id}/form-variables?deserializeValues=false`);
}

/** Completes a task, writing the given typed variables into the process. */
export function completeTask(id: string, variables: CamundaVariables): Promise<void> {
  return request(`/task/${id}/complete`, {
    method: 'POST',
    body: JSON.stringify({ variables }),
  });
}

/** An open incident as returned by `GET /incident`. */
export interface Incident {
  id: string;
  processDefinitionId: string;
  processInstanceId: string;
  executionId: string;
  incidentTimestamp: string;
  /** e.g. "failedJob", "failedExternalTask". */
  incidentType: string;
  /** BPMN id of the activity that produced the incident (or null at process scope). */
  activityId: string | null;
  /**
   * Type-specific id: the failed job id for "failedJob", the external task id
   * for "failedExternalTask". This is the id we PUT retries to.
   */
  configuration: string | null;
  incidentMessage: string | null;
}

/**
 * Lists open incidents, newest first. Pass `processDefinitionId` to scope to
 * a single service.
 */
export function listIncidents(processDefinitionId?: string): Promise<Incident[]> {
  const qs = new URLSearchParams({
    sortBy: 'incidentTimestamp',
    sortOrder: 'desc',
  });
  if (processDefinitionId) qs.set('processDefinitionId', processDefinitionId);
  return request(`/incident?${qs}`);
}

/** Returns the number of active process instances for a service. */
export function countActiveProcessInstances(processDefinitionId: string): Promise<{ count: number }> {
  return request(
    `/process-instance/count?processDefinitionId=${processDefinitionId}&active=true`,
  );
}

/**
 * Resets a failed job's retry counter so the job executor will pick it up
 * again. For a "failedJob" incident, pass `incident.configuration` as `jobId`.
 */
export function setJobRetries(jobId: string, retries: number): Promise<void> {
  return request(`/job/${jobId}/retries`, {
    method: 'PUT',
    body: JSON.stringify({ retries }),
  });
}

/** A process instance as returned by `GET /history/process-instance`. */
export interface HistoricProcessInstance {
  id: string;
  processDefinitionId: string;
  processDefinitionKey: string;
  startTime: string;
  /** `null` while the instance is still running. */
  endTime: string | null;
  /** "ACTIVE", "COMPLETED", "EXTERNALLY_TERMINATED", "INTERNALLY_TERMINATED", … */
  state: string;
  /** BPMN id of the end event the instance terminated at (null while active). */
  endActivityId: string | null;
  /** Keycloak username of the applicant who started the instance, when the BPMN start event has `camunda:initiator`. */
  startUserId: string | null;
  durationInMillis: number | null;
}

/**
 * Lists finished process instances for a definition, newest end-time first.
 * Capped to `maxResults` rows to keep the page light.
 */
export function listFinishedProcessInstances(
  processDefinitionId: string,
  maxResults = 50,
): Promise<HistoricProcessInstance[]> {
  const qs = new URLSearchParams({
    processDefinitionId,
    finished: 'true',
    sortBy: 'endTime',
    sortOrder: 'desc',
    maxResults: String(maxResults),
  });
  return request(`/history/process-instance?${qs}`);
}

/**
 * Lists historic process instances started by a given user — both active
 * (endTime null) and finished. Newest start-time first.
 *
 * Powers the PartA "My processes" page: only the applicant's own instances.
 * Relies on `camunda:initiator="initiator"` on the BPMN start event so the
 * engine writes `startUserId` for the instance.
 */
export function listHistoricProcessInstancesByStarter(
  startedBy: string,
  maxResults = 100,
): Promise<HistoricProcessInstance[]> {
  const qs = new URLSearchParams({
    startedBy,
    sortBy: 'startTime',
    sortOrder: 'desc',
    maxResults: String(maxResults),
  });
  return request(`/history/process-instance?${qs}`);
}

/** A single historic process instance lookup (used by the completed-process page). */
export function getHistoricProcessInstance(id: string): Promise<HistoricProcessInstance> {
  return request(`/history/process-instance/${id}`);
}

/**
 * Counts the historic process instances started by a user — both active and
 * finished. Lighter than `listHistoricProcessInstancesByStarter` (the engine
 * returns one integer, no row payload), used for the "My processes (N)"
 * badge in the nav.
 */
export function countHistoricProcessInstancesByStarter(
  startedBy: string,
): Promise<{ count: number }> {
  const qs = new URLSearchParams({ startedBy });
  return request(`/history/process-instance/count?${qs}`);
}

/** A historic user task as returned by `GET /history/task`. */
export interface HistoricTask {
  id: string;
  name: string;
  /** BPMN id of the user task. */
  taskDefinitionKey: string;
  processInstanceId: string;
  processDefinitionId: string;
  assignee: string | null;
  startTime: string;
  endTime: string | null;
}

/** Lists every historic user task of a process instance, newest end-time first. */
export function listHistoricTasks(processInstanceId: string): Promise<HistoricTask[]> {
  return request(
    `/history/task?processInstanceId=${processInstanceId}&sortBy=endTime&sortOrder=desc`,
  );
}

/**
 * Lists every historic user task with a given `taskDefinitionKey` across all
 * instances of a process definition — both active (endTime null) and finished.
 * Sorted by start time descending, so the newest occurrence is first.
 */
export function listHistoricTasksByDefinition(
  processDefinitionId: string,
  taskDefinitionKey: string,
  maxResults = 100,
): Promise<HistoricTask[]> {
  const qs = new URLSearchParams({
    processDefinitionId,
    taskDefinitionKey,
    sortBy: 'startTime',
    sortOrder: 'desc',
    maxResults: String(maxResults),
  });
  return request(`/history/task?${qs}`);
}

/** One historic process variable. The latest value of each variable in scope. */
export interface HistoricVariableInstance {
  id: string;
  name: string;
  type: CamundaVariableType;
  value: unknown;
  processInstanceId: string;
}

/** Returns the latest value of each process variable that the instance ever had. */
export function listHistoricVariables(
  processInstanceId: string,
): Promise<HistoricVariableInstance[]> {
  // deserializeValues=false → engine returns Spin Json variables as their
  // raw JSON string instead of a Spin JsonNode metadata wrapper. With the
  // default (true), `value` for a Json-typed variable comes back as
  // { boolean, object, nodeType: 'ARRAY', array, ... } — Spin's introspection
  // shape — not the underlying array. The SPA's parsers (parseBoardMembers
  // etc.) handle the string form natively, so this keeps a single code path.
  return request(
    `/history/variable-instance?processInstanceId=${processInstanceId}&deserializeValues=false`,
  );
}

/**
 * Fetches one named variable from a process instance's history; returns
 * `null` if the variable was never set on the instance. Lighter than
 * fetching every variable when only one is needed.
 */
export async function getHistoricVariable(
  processInstanceId: string,
  variableName: string,
): Promise<HistoricVariableInstance | null> {
  const qs = new URLSearchParams({
    processInstanceId,
    variableName,
    deserializeValues: 'false',
  });
  const items: HistoricVariableInstance[] = await request(
    `/history/variable-instance?${qs}`,
  );
  return items[0] ?? null;
}

/**
 * Lists the newest N process instances (any state) sorted by start time desc.
 * Powers the civil-servant worklist on the redesigned Tasks page; pair with
 * variable + task lookups to build a `WorklistRow` per instance.
 */
export function listRecentProcessInstances(
  maxResults = 100,
): Promise<HistoricProcessInstance[]> {
  const qs = new URLSearchParams({
    sortBy: 'startTime',
    sortOrder: 'desc',
    maxResults: String(maxResults),
  });
  return request(`/history/process-instance?${qs}`);
}

/** A row in the civil-servant worklist — denormalised view over a single case. */
export interface WorklistRow {
  processInstanceId: string;
  processDefinitionId: string;
  processDefinitionKey: string;
  serviceName: string;
  /** "First Last" derived from process variables, or empty if neither is set. */
  applicantName: string;
  /** Keycloak username of the applicant who started the case. */
  startUserId: string | null;
  startTime: string;
  endTime: string | null;
  /**
   * Decision-state of the case for civil-servant filtering. `incident` takes
   * precedence over `pending` when at least one open incident exists, so the
   * worklist surfaces stuck cases without having to dig.
   */
  status: 'pending' | 'incident' | 'confirmed' | 'rejected';
  /** The currently-active user task for the case, or null if none (service task in flight, or case ended). */
  currentTask: {
    id: string;
    name: string;
    taskDefinitionKey: string;
    assignee: string | null;
  } | null;
  /** Open incidents on the case (mostly failedJob from service tasks). Empty when healthy. */
  incidents: Incident[];
}

/**
 * Maps a process instance plus its incident count to the worklist status.
 *
 *   active + ≥1 open incident                          → incident
 *   active + no incidents                              → pending
 *   ended at an end event whose id mentions `Reject`   → rejected
 *   ended at any other end event                       → confirmed
 *
 * Why default-confirmed instead of default-rejected: the BPMNs that ship
 * today (vehicle-registration, business-registration) have no terminal
 * "rejected" end event — the civil servant's send-back loops the case
 * back to the applicant, it never ends in a rejected state. The only
 * terminal is EndEvent_Approved.
 *
 * The wrinkle: vehicle-registration has a non-interrupting repeating timer
 * (R/PT2M) on Task_Review that spawns reminder branches terminating at
 * EndEvent_ReminderSent. When the civil servant takes long enough that
 * reminders fire and then approves, both EndEvent_ReminderSent and
 * EndEvent_Approved get reached and the engine's `endActivityId` can
 * record either one — empirically it often records the reminder end,
 * which used to make every long-pending approval show up as "rejected"
 * in the worklist.
 *
 * Defaulting to confirmed and only flagging rejection on an explicit
 * `Reject`-named end event protects against that race and stays
 * forward-compatible: a future BPMN that adds an EndEvent_Rejected (or
 * similar) gets correctly tagged without any code change.
 */
function statusFor(
  pi: HistoricProcessInstance,
  hasIncidents: boolean,
): WorklistRow['status'] {
  if (pi.endTime === null) return hasIncidents ? 'incident' : 'pending';
  if (pi.endActivityId && /reject/i.test(pi.endActivityId)) return 'rejected';
  return 'confirmed';
}

/**
 * Builds the civil-servant worklist: every recent process instance, with the
 * applicant name from `firstName` + `lastName` history variables, the
 * currently-open user task (for active cases), and any open incidents.
 * Fan-out is parallel — one variables-lookup and (for active) one tasks-lookup
 * per instance. Incidents are fetched once for the whole engine and joined
 * client-side.
 *
 * `maxResults` caps the underlying history query; pair it with the page's
 * paginator once we add one.
 */
export async function listWorklist(maxResults = 100): Promise<WorklistRow[]> {
  const [defs, instances, allIncidents] = await Promise.all([
    listProcessDefinitions(),
    listRecentProcessInstances(maxResults),
    listIncidents(),
  ]);

  const nameByDefId = new Map(defs.map((d) => [d.id, d.name ?? d.key]));

  const incidentsByPI = new Map<string, Incident[]>();
  for (const inc of allIncidents) {
    const list = incidentsByPI.get(inc.processInstanceId);
    if (list) list.push(inc);
    else incidentsByPI.set(inc.processInstanceId, [inc]);
  }

  return Promise.all(
    instances.map(async (pi): Promise<WorklistRow> => {
      const isActive = pi.endTime === null;
      const [firstNameVar, lastNameVar, activeTasks] = await Promise.all([
        getHistoricVariable(pi.id, 'firstName'),
        getHistoricVariable(pi.id, 'lastName'),
        isActive ? listTasksByInstance(pi.id) : Promise.resolve([] as CamundaTask[]),
      ]);

      const first = typeof firstNameVar?.value === 'string' ? firstNameVar.value : '';
      const last = typeof lastNameVar?.value === 'string' ? lastNameVar.value : '';
      const applicantName = `${first} ${last}`.trim();

      const t = activeTasks[0];
      const currentTask = t
        ? {
            id: t.id,
            name: t.name,
            taskDefinitionKey: t.taskDefinitionKey,
            assignee: t.assignee,
          }
        : null;

      const incidents = incidentsByPI.get(pi.id) ?? [];

      return {
        processInstanceId: pi.id,
        processDefinitionId: pi.processDefinitionId,
        processDefinitionKey: pi.processDefinitionKey,
        serviceName: nameByDefId.get(pi.processDefinitionId) ?? pi.processDefinitionKey,
        applicantName,
        startUserId: pi.startUserId,
        startTime: pi.startTime,
        endTime: pi.endTime,
        status: statusFor(pi, incidents.length > 0),
        currentTask,
        incidents,
      };
    }),
  );
}
