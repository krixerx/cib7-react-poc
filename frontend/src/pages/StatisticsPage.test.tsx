// @vitest-environment happy-dom
//
// Renders the statistics page against a canned report: the tiles, the legend,
// the task table and the failure list must all carry the report's numbers in
// text, so nothing on the page is readable from colour alone.

import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import '../i18n';
import type { StatisticsReport } from '../api/statisticsApi';

const report: StatisticsReport = {
  from: '2026-10-01',
  to: '2026-10-02',
  zone: 'UTC',
  truncated: false,
  totals: { started: 5, completed: 2, inProgress: 1, failed: 1, cancelled: 1 },
  services: [{ key: 'vehicleRegistration', name: 'Vehicle Registration' }],
  tasks: [
    { id: 'vehicleRegistration:Task_Review', serviceKey: 'vehicleRegistration', name: 'Review' },
  ],
  perService: [
    {
      key: 'vehicleRegistration',
      name: 'Vehicle Registration',
      counts: { started: 5, completed: 2, inProgress: 1, failed: 1, cancelled: 1 },
    },
  ],
  perDay: [
    {
      date: '2026-10-01',
      counts: { started: 2, completed: 2, inProgress: 0, failed: 0, cancelled: 0 },
    },
    {
      date: '2026-10-02',
      counts: { started: 3, completed: 0, inProgress: 1, failed: 1, cancelled: 1 },
    },
  ],
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

const fetchStatistics = vi.fn(async () => report);
vi.mock('../api/statisticsApi', () => ({
  fetchStatistics: (...args: unknown[]) => fetchStatistics(...(args as [])),
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

  it('renders the report numbers as text', async () => {
    await act(async () => {
      root.render(
        <MemoryRouter>
          <StatisticsPage />
        </MemoryRouter>,
      );
    });

    expect(fetchStatistics).toHaveBeenCalledWith(
      expect.objectContaining({ services: [], tasks: [] }),
    );
    const tiles = [...container.querySelectorAll('.stats-tile')].map((t) => t.textContent);
    expect(tiles).toEqual([
      'Started5',
      'Completed240%',
      'In progress120%',
      'Failed120%',
      'Cancelled120%',
    ]);
    expect(container.querySelectorAll('.stats-donut-segment')).toHaveLength(4);
    expect(container.querySelectorAll('.stats-days-plot li')).toHaveLength(2);
    expect(container.textContent).toContain('2 min');

    const failure = container.querySelector('.stats-table a');
    expect(failure?.getAttribute('href')).toBe('/processes/pi-12345678');
    expect(failure?.textContent).toBe('BK-7');
    expect(container.textContent).toContain('Connection refused');
  });
});
