/**
 * Thin typed client for the CIB seven REST API (`/engine-rest`).
 *
 * The browser always calls the same-origin path `/engine-rest/...`; the Vite
 * dev server (vite.config.ts) and nginx (nginx.conf) proxy it to the backend.
 */

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

export type CamundaVariableType = 'String' | 'Integer' | 'Long' | 'Double' | 'Boolean';

/** A CIB seven typed variable: `{ value, type }`. */
export interface CamundaVariable {
  value: unknown;
  type: CamundaVariableType;
}

export type CamundaVariables = Record<string, CamundaVariable>;

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(BASE + path, {
    headers: { 'Content-Type': 'application/json' },
    ...init,
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
