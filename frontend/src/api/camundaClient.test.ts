import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// Controllable stand-in for the Keycloak singleton: `auth.authenticated`
// drives whether request() attaches a Bearer header.
const auth = vi.hoisted(() => ({ authenticated: false, token: 'tok-123' }));

vi.mock('../auth/keycloak', () => ({
  keycloak: auth,
  ensureFreshToken: vi.fn(async () => auth.token),
}));

import {
  completeTask,
  getHistoricVariable,
  listProcessDefinitions,
  listWorklist,
} from './camundaClient';

type FetchArgs = [string, RequestInit | undefined];

const fetchMock = vi.fn();

function jsonResponse(payload: unknown, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    statusText: 'OK',
    text: async () => JSON.stringify(payload),
  };
}

beforeEach(() => {
  auth.authenticated = false;
  fetchMock.mockReset();
  fetchMock.mockResolvedValue(jsonResponse([]));
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function lastCall(): FetchArgs {
  return fetchMock.mock.calls[fetchMock.mock.calls.length - 1] as FetchArgs;
}

describe('request plumbing', () => {
  it('omits the Authorization header for the anonymous landing-page call', async () => {
    await listProcessDefinitions();

    const [url, init] = lastCall();
    expect(url).toBe(
      '/engine-rest/process-definition?latestVersion=true&sortBy=name&sortOrder=asc',
    );
    expect((init?.headers as Record<string, string>).Authorization).toBeUndefined();
  });

  it('attaches a fresh Bearer token once signed in', async () => {
    auth.authenticated = true;

    await listProcessDefinitions();

    const [, init] = lastCall();
    expect((init?.headers as Record<string, string>).Authorization).toBe('Bearer tok-123');
  });

  it('forces cache no-store so post-action refetches cannot serve stale data', async () => {
    await listProcessDefinitions();

    const [, init] = lastCall();
    expect(init?.cache).toBe('no-store');
    expect((init?.headers as Record<string, string>)['Content-Type']).toBe('application/json');
  });

  it('throws with status and body text on a non-2xx answer', async () => {
    fetchMock.mockResolvedValue({
      ok: false,
      status: 403,
      statusText: 'Forbidden',
      text: async () => 'no grants for you',
    });

    await expect(listProcessDefinitions()).rejects.toThrow('CIB seven REST 403: no grants for you');
  });

  it('falls back to statusText when the error body is empty', async () => {
    fetchMock.mockResolvedValue({
      ok: false,
      status: 500,
      statusText: 'Internal Server Error',
      text: async () => '',
    });

    await expect(listProcessDefinitions()).rejects.toThrow(
      'CIB seven REST 500: Internal Server Error',
    );
  });

  it('treats the 204 from task completion as void instead of parsing JSON', async () => {
    fetchMock.mockResolvedValue({
      ok: true,
      status: 204,
      statusText: 'No Content',
      text: async () => '',
    });

    await expect(completeTask('t-1', {})).resolves.toBeUndefined();

    const [url, init] = lastCall();
    expect(url).toBe('/engine-rest/task/t-1/complete');
    expect(init?.method).toBe('POST');
  });
});

describe('getHistoricVariable', () => {
  it('asks for serialized values and returns the first match', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse([{ id: 'v1', name: 'firstName', value: 'Ants', type: 'String' }]),
    );

    const v = await getHistoricVariable('pi-1', 'firstName');

    expect(v?.value).toBe('Ants');
    const [url] = lastCall();
    expect(url).toBe(
      '/engine-rest/history/variable-instance?processInstanceId=pi-1&variableName=firstName&deserializeValues=false',
    );
  });

  it('returns null (not undefined) when the variable was never written', async () => {
    fetchMock.mockResolvedValue(jsonResponse([]));

    await expect(getHistoricVariable('pi-1', 'firstName')).resolves.toBeNull();
  });
});

describe('listWorklist', () => {
  // Three cases covering the three row shapes:
  //   pi-active-task: live, has an open user task
  //   pi-ended:       finished at EndEvent_Approved, has a (stale) incident row
  //   pi-waiting:     live, parked on a receive task, unknown definition id
  const definitions = [
    { id: 'def-1', key: 'vehicleRegistration', name: 'Vehicle registration', version: 3 },
  ];

  const instances = [
    {
      id: 'pi-active-task',
      processDefinitionId: 'def-1',
      processDefinitionKey: 'vehicleRegistration',
      startTime: '2026-06-10T08:00:00.000+0000',
      endTime: null,
      state: 'ACTIVE',
      endActivityId: null,
      startUserId: 'lisa',
      durationInMillis: null,
    },
    {
      id: 'pi-ended',
      processDefinitionId: 'def-1',
      processDefinitionKey: 'vehicleRegistration',
      startTime: '2026-06-09T08:00:00.000+0000',
      endTime: '2026-06-09T09:00:00.000+0000',
      state: 'COMPLETED',
      endActivityId: 'EndEvent_Approved',
      startUserId: 'bart',
      durationInMillis: 3_600_000,
    },
    {
      id: 'pi-waiting',
      processDefinitionId: 'def-gone',
      processDefinitionKey: 'businessRegistration',
      startTime: '2026-06-08T08:00:00.000+0000',
      endTime: null,
      state: 'ACTIVE',
      endActivityId: null,
      startUserId: 'maggie',
      durationInMillis: null,
    },
  ];

  const incidents = [
    {
      id: 'inc-1',
      processDefinitionId: 'def-1',
      processInstanceId: 'pi-ended',
      executionId: 'ex-1',
      incidentTimestamp: '2026-06-09T08:30:00.000+0000',
      incidentType: 'failedJob',
      activityId: 'Task_SendEmail',
      incidentMessage: 'SMTP down',
      configuration: 'job-1',
    },
  ];

  const openWaits = [
    {
      id: 'ai-1',
      processInstanceId: 'pi-waiting',
      activityId: 'Wait_FounderSignatures',
      activityName: 'Wait for co-founder signatures',
      activityType: 'receiveTask',
      startTime: '2026-06-08T08:10:00.000+0000',
      endTime: null,
    },
  ];

  const namesByInstance: Record<string, Record<string, unknown>> = {
    'pi-active-task': { firstName: 'Ants', lastName: 'Avaldaja' },
    'pi-ended': { firstName: 'Lisa' },
    // Numeric junk must not leak into the display name.
    'pi-waiting': { firstName: 38_000, lastName: 'Kask' },
  };

  beforeEach(() => {
    fetchMock.mockImplementation(async (url: string) => {
      if (url.startsWith('/engine-rest/process-definition')) return jsonResponse(definitions);
      if (url.startsWith('/engine-rest/history/process-instance')) return jsonResponse(instances);
      if (url.startsWith('/engine-rest/incident')) return jsonResponse(incidents);
      if (url.startsWith('/engine-rest/history/activity-instance')) return jsonResponse(openWaits);
      if (url.startsWith('/engine-rest/history/variable-instance')) {
        const qs = new URLSearchParams(url.split('?')[1]);
        const value = namesByInstance[qs.get('processInstanceId')!]?.[qs.get('variableName')!];
        return jsonResponse(
          value === undefined
            ? []
            : [{ id: 'v', name: qs.get('variableName'), value, type: 'String' }],
        );
      }
      if (url.startsWith('/engine-rest/task?processInstanceId=pi-active-task')) {
        return jsonResponse([
          {
            id: 't-1',
            name: 'Review application',
            created: '2026-06-10T08:05:00.000+0000',
            processInstanceId: 'pi-active-task',
            processDefinitionId: 'def-1',
            taskDefinitionKey: 'Task_Review',
            formKey: 'react:vehicle-review',
            assignee: null,
          },
        ]);
      }
      if (url.startsWith('/engine-rest/task?processInstanceId=')) return jsonResponse([]);
      throw new Error(`Unrouted test URL: ${url}`);
    });
  });

  it('joins names, tasks, waits, and incidents into one row per instance', async () => {
    const rows = await listWorklist();
    expect(rows.map((r) => r.processInstanceId)).toEqual([
      'pi-active-task',
      'pi-ended',
      'pi-waiting',
    ]);

    const [active, ended, waiting] = rows;

    expect(active.serviceName).toBe('Vehicle registration');
    expect(active.applicantName).toBe('Ants Avaldaja');
    expect(active.status).toBe('pending');
    expect(active.currentTask).toEqual({
      id: 't-1',
      name: 'Review application',
      taskDefinitionKey: 'Task_Review',
      assignee: null,
    });
    expect(active.waitingOn).toBeNull();

    // Ended case: a leftover incident row must not flip history to
    // "incident", and a half-written name renders without padding.
    expect(ended.status).toBe('confirmed');
    expect(ended.incidents).toHaveLength(1);
    expect(ended.applicantName).toBe('Lisa');
    expect(ended.currentTask).toBeNull();
    expect(ended.waitingOn).toBeNull();

    // Waiting case: no open user task → the receive-task wait labels the row;
    // an unknown definition id falls back to the key for the service name.
    expect(waiting.serviceName).toBe('businessRegistration');
    expect(waiting.applicantName).toBe('Kask');
    expect(waiting.status).toBe('pending');
    expect(waiting.currentTask).toBeNull();
    expect(waiting.waitingOn).toEqual({
      activityId: 'Wait_FounderSignatures',
      name: 'Wait for co-founder signatures',
    });
  });

  it('does not query open tasks for ended instances', async () => {
    await listWorklist();

    const taskQueries = fetchMock.mock.calls
      .map((c) => c[0] as string)
      .filter((u) => u.startsWith('/engine-rest/task?processInstanceId='));
    expect(taskQueries.sort()).toEqual([
      '/engine-rest/task?processInstanceId=pi-active-task',
      '/engine-rest/task?processInstanceId=pi-waiting',
    ]);
  });
});
