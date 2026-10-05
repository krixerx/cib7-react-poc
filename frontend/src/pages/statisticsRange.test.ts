import { describe, expect, it } from 'vitest';
import { formatDuration, isoDate, presetRange, previousRange } from './statisticsRange';

describe('presetRange', () => {
  // 2 Oct 2026, 00:30 local time: late enough in UTC terms to be 1 Oct in some zones.
  const now = new Date(2026, 9, 2, 0, 30);

  it('uses local calendar days', () => {
    expect(isoDate(now)).toBe('2026-10-02');
    expect(presetRange('today', now)).toEqual({ from: '2026-10-02', to: '2026-10-02' });
  });

  it('computes the relative presets inclusively', () => {
    expect(presetRange('yesterday', now)).toEqual({ from: '2026-10-01', to: '2026-10-01' });
    expect(presetRange('last7', now)).toEqual({ from: '2026-09-26', to: '2026-10-02' });
    expect(presetRange('last30', now)).toEqual({ from: '2026-09-03', to: '2026-10-02' });
    expect(presetRange('thisMonth', now)).toEqual({ from: '2026-10-01', to: '2026-10-02' });
  });
});

describe('previousRange', () => {
  it('is the same number of days, ending the day before', () => {
    expect(previousRange({ from: '2026-10-02', to: '2026-10-02' })).toEqual({
      from: '2026-10-01',
      to: '2026-10-01',
    });
    expect(previousRange({ from: '2026-09-26', to: '2026-10-02' })).toEqual({
      from: '2026-09-19',
      to: '2026-09-25',
    });
  });

  it('crosses the daylight-saving change without losing a day', () => {
    // Most of Europe leaves summer time on 25 Oct 2026.
    expect(previousRange({ from: '2026-10-26', to: '2026-11-01' })).toEqual({
      from: '2026-10-19',
      to: '2026-10-25',
    });
  });
});

describe('formatDuration', () => {
  it('picks a readable unit', () => {
    expect(formatDuration(45_000)).toBe('45 s');
    expect(formatDuration(12 * 60_000)).toBe('12 min');
    expect(formatDuration((3 * 60 + 20) * 60_000)).toBe('3 h 20 min');
    expect(formatDuration((2 * 24 + 4) * 3_600_000)).toBe('2 d 4 h');
  });
});
