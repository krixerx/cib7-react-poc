/**
 * Thin typed client for the CIB seven REST API (`/engine-rest`).
 *
 * The browser always calls the same-origin path `/engine-rest/...`; the Vite
 * dev server (vite.config.ts) and nginx (nginx.conf) proxy it to the backend.
 * Each request carries a Keycloak-issued bearer token; the backend's
 * RestApiSecurityConfig validates it before any handler runs.
 */

import { ensureFreshToken } from '../auth/keycloak';

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
  /** e.g. "react:personal-details" — see forms/registry.ts. */
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
  const token = await ensureFreshToken();

  const res = await fetch(BASE + path, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
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
  return request(`/task/${id}/form-variables`);
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
  return request(`/history/variable-instance?processInstanceId=${processInstanceId}`);
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
  const qs = new URLSearchParams({ processInstanceId, variableName });
  const items: HistoricVariableInstance[] = await request(
    `/history/variable-instance?${qs}`,
  );
  return items[0] ?? null;
}
