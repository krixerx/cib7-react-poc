// @vitest-environment happy-dom
//
// Renders the statistics page against canned reports for the period and the
// one before it: the indicators, their changes, the attention strip, the flow
// and the tables must all carry the numbers in text, so nothing on the page is
// readable from colour alone.

import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import '../i18n';
import type { StatisticsFilter, StatisticsReport } from '../api/statisticsApi';
import { presetRange, previousRange } from './statisticsRange';

const outcomes = (approved: number, rejected: number) => ({
  approved,
  rejected,
  returned: 1,
  touchless: 2,
  reachedBackOffice: 7,
  avgLeadTimeMs: 3_600_000,
});

const report: StatisticsReport = {
  from: '2026-10-01',
  to: '2026-10-02',
  zone: 'UTC',
  truncated: false,
  totals: { started: 10, completed: 6, inProgress: 2, failed: 1, cancelled: 1 },
  outcomes: outcomes(4, 2),
  services: [{ key: 'vehicleRegistration', name: 'Vehicle Registration' }],
  tasks: [
    { id: 'vehicleRegistration:Task_Review', serviceKey: 'vehicleRegistration', name: 'Review' },
  ],
  perService: [
    {
      key: 'vehicleRegistration',
      name: 'Vehicle Registration',
      counts: { started: 10, completed: 6, inProgress: 2, failed: 1, cancelled: 1 },
      outcomes: outcomes(4, 2),
    },
  ],
  perDay: [
    {
      date: '2026-10-01',
      counts: { started: 4, completed: 4, inProgress: 0, failed: 0, cancelled: 0 },
      outcomes: outcomes(3, 1),
    },
    {
      date: '2026-10-02',
      counts: { started: 6, completed: 2, inProgress: 2, failed: 1, cancelled: 1 },
      outcomes: outcomes(1, 1),
    },
  ],
  flow: {
    backOffice: { approved: 2, rejected: 2, inProgress: 2, failed: 1, cancelled: 0 },
    direct: { approved: 2, rejected: 0, inProgress: 0, failed: 0, cancelled: 1 },
  },
  backOffice: { done: 5, waiting: 2, avgTaskMs: 90_000, oldestWaitingSince: null },
  taskStats: [
    {
      id: 'vehicleRegistration:Task_Review',
      serviceKey: 'vehicleRegistration',
      name: 'Review',
      completed: 2,
      waiting: 1,
      avgDurationMs: 90_000,
    },
  ],
  failures: [
    {
      time: '2026-10-02T09:00:00.000+0000',
      serviceKey: 'vehicleRegistration',
      serviceName: 'Vehicle Registration',
      processInstanceId: 'pi-12345678',
      businessKey: 'BK-7',
      task: 'Look up vehicle in registry',
      type: 'failedJob',
      message: 'Connection refused',
    },
  ],
};

// The period before: everything approved and nothing failed.
const previous: StatisticsReport = {
  ...report,
  totals: { started: 10, completed: 6, inProgress: 3, failed: 0, cancelled: 1 },
  outcomes: outcomes(6, 0),
  failures: [],
};

const fetchStatistics = vi.fn(async (filter: StatisticsFilter) =>
  filter.from === previousRange(presetRange('last7', new Date())).from ? previous : report,
);
vi.mock('../api/statisticsApi', () => ({
  fetchStatistics: (filter: StatisticsFilter) => fetchStatistics(filter),
}));

const { default: StatisticsPage } = await import('./StatisticsPage');

describe('StatisticsPage', () => {
  let container: HTMLDivElement;
  let root: Root;

  beforeEach(() => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
  });

  afterEach(() => {
    act(() => root.unmount());
    container.remove();
  });

  const texts = (selector: string) =>
    [...container.querySelectorAll(selector)].map((e) => e.textContent);

  it('renders the report numbers and their changes as text', async () => {
    await act(async () => {
      root.render(
        <MemoryRouter>
          <StatisticsPage />
        </MemoryRouter>,
      );
    });

    // The period and the one before it, with the same filters.
    expect(fetchStatistics).toHaveBeenCalledTimes(2);
    expect(fetchStatistics).toHaveBeenCalledWith(
      expect.objectContaining({ services: [], tasks: [] }),
    );

    expect(texts('.stats-kpi-value')).toEqual(['10', '6', '1 h', '67%', '10%', '33%', '1']);
    const approval = [...container.querySelectorAll('.stats-kpi')].find((k) =>
      k.textContent?.startsWith('Approval rate'),
    );
    expect(approval?.querySelector('.stats-kpi-change')?.textContent).toBe(
      '−33 pp vs previous period',
    );
    expect(approval?.querySelector('.tone-bad')).not.toBeNull();

    // Failed cases, the back-office queue and the approval drop need attention.
    expect(texts('.stats-alert-label')).toEqual([
      'Failed cases waiting for a fix',
      'Back-office tasks waiting',
      'Approval rate',
    ]);

    expect(texts('.stats-flow-label')).toEqual(
      expect.arrayContaining(['Back office 7', 'No back office 3', 'Approved 4', 'Rejected 2']),
    );
    expect(container.querySelectorAll('.stats-days-plot li')).toHaveLength(2);
    expect(container.textContent).toContain('2 min');

    const failure = container.querySelector('.stats-table a');
    expect(failure?.getAttribute('href')).toBe('/processes/pi-12345678');
    expect(failure?.textContent).toBe('BK-7');
    expect(container.textContent).toContain('Connection refused');
  });

  it('shows the chosen indicator in the trend chart', async () => {
    await act(async () => {
      root.render(
        <MemoryRouter>
          <StatisticsPage />
        </MemoryRouter>,
      );
    });

    expect(container.querySelector('#stats-trend')?.textContent).toBe('Started');
    const approval = [...container.querySelectorAll<HTMLButtonElement>('.stats-kpi')].find((k) =>
      k.textContent?.startsWith('Approval rate'),
    )!;
    await act(async () => approval.click());
    expect(container.querySelector('#stats-trend')?.textContent).toBe('Approval rate');
    expect(approval.getAttribute('aria-pressed')).toBe('true');
  });
});
