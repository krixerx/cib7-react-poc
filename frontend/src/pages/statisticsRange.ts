/** Date-range presets of the statistics page, in the browser's own timezone. */

export type RangePreset = 'today' | 'yesterday' | 'last7' | 'last30' | 'thisMonth' | 'custom';

export const RANGE_PRESETS: RangePreset[] = [
  'today',
  'yesterday',
  'last7',
  'last30',
  'thisMonth',
  'custom',
];

/** The local calendar date of `d` as YYYY-MM-DD (not `toISOString`, which is UTC). */
export function isoDate(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

function addDays(d: Date, days: number): Date {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate() + days);
}

/**
 * Inclusive `from`/`to` dates for a preset, relative to `now`. `custom` has
 * no range of its own and returns today, which the page then lets the user edit.
 */
export function presetRange(preset: RangePreset, now: Date): { from: string; to: string } {
  switch (preset) {
    case 'yesterday': {
      const y = isoDate(addDays(now, -1));
      return { from: y, to: y };
    }
    case 'last7':
      return { from: isoDate(addDays(now, -6)), to: isoDate(now) };
    case 'last30':
      return { from: isoDate(addDays(now, -29)), to: isoDate(now) };
    case 'thisMonth':
      return { from: isoDate(new Date(now.getFullYear(), now.getMonth(), 1)), to: isoDate(now) };
    default:
      return { from: isoDate(now), to: isoDate(now) };
  }
}

/**
 * The period of equal length that ends the day before `range` starts, so the
 * page can say how a number changed. Today compares with all of yesterday.
 */
export function previousRange(range: { from: string; to: string }): { from: string; to: string } {
  const [fy, fm, fd] = range.from.split('-').map(Number);
  const [ty, tm, td] = range.to.split('-').map(Number);
  const from = new Date(fy, fm - 1, fd);
  const days = Math.round((Date.UTC(ty, tm - 1, td) - Date.UTC(fy, fm - 1, fd)) / 86_400_000) + 1;
  return { from: isoDate(addDays(from, -days)), to: isoDate(addDays(from, -1)) };
}

/** Compact human duration: "45 s", "12 min", "3 h 20 min", "2 d 4 h". */
export function formatDuration(ms: number): string {
  const s = Math.round(ms / 1000);
  if (s < 60) return `${s} s`;
  const min = Math.round(s / 60);
  if (min < 60) return `${min} min`;
  const h = Math.floor(min / 60);
  if (h < 24) return min % 60 ? `${h} h ${min % 60} min` : `${h} h`;
  const d = Math.floor(h / 24);
  return h % 24 ? `${d} d ${h % 24} h` : `${d} d`;
}
