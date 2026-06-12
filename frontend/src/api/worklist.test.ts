import { describe, expect, it, vi } from 'vitest';

// camundaClient pulls in the Keycloak singleton, whose module initialiser
// touches `window` — absent in the node test environment. statusFor is pure,
// so stub the auth module out entirely.
vi.mock('../auth/keycloak', () => ({
  keycloak: { authenticated: false },
  ensureFreshToken: vi.fn(),
}));

import { statusFor, type HistoricProcessInstance } from './camundaClient';

function instance(overrides: Partial<HistoricProcessInstance> = {}): HistoricProcessInstance {
  return {
    id: 'pi-1',
    processDefinitionId: 'vehicleRegistration:3:abc123',
    processDefinitionKey: 'vehicleRegistration',
    startTime: '2026-06-01T10:00:00.000+0000',
    endTime: null,
    state: 'ACTIVE',
    endActivityId: null,
    startUserId: 'lisa',
    durationInMillis: null,
    ...overrides,
  };
}

function ended(overrides: Partial<HistoricProcessInstance> = {}): HistoricProcessInstance {
  return instance({
    endTime: '2026-06-01T10:30:00.000+0000',
    state: 'COMPLETED',
    durationInMillis: 30 * 60 * 1000,
    ...overrides,
  });
}

describe('statusFor', () => {
  it('marks an active instance without incidents as pending', () => {
    expect(statusFor(instance(), false)).toBe('pending');
  });

  it('marks an active instance with an open incident as incident', () => {
    expect(statusFor(instance(), true)).toBe('incident');
  });

  it('marks an instance ended at EndEvent_Approved as confirmed', () => {
    expect(statusFor(ended({ endActivityId: 'EndEvent_Approved' }), false)).toBe('confirmed');
  });

  it('marks an instance ended at a Reject-named end event as rejected', () => {
    expect(statusFor(ended({ endActivityId: 'EndEvent_Rejected' }), false)).toBe('rejected');
    // The match is case-insensitive and anywhere in the id.
    expect(statusFor(ended({ endActivityId: 'end_application_rejected' }), false)).toBe('rejected');
  });

  it('defaults other end activities to confirmed (reminder-timer race)', () => {
    // A non-interrupting R/PT2M reminder timer can win the endActivityId
    // field on a long-pending approval — that must not read as rejected.
    expect(statusFor(ended({ endActivityId: 'EndEvent_ReminderSent' }), false)).toBe('confirmed');
  });

  it('treats an ended instance with no endActivityId as confirmed', () => {
    expect(statusFor(ended({ endActivityId: null }), false)).toBe('confirmed');
  });

  it('ignores incidents once the instance has ended', () => {
    // `incident` only makes sense for live cases; history wins after the end.
    expect(statusFor(ended({ endActivityId: 'EndEvent_Approved' }), true)).toBe('confirmed');
  });
});
