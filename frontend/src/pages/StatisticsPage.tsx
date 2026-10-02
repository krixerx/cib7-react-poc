import { useCallback, useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { ChevronDown, RefreshCw } from 'lucide-react';
import {
  fetchStatistics,
  type StatisticsReport,
  type StatusCounts,
  type TaskStats,
} from '../api/statisticsApi';
import { formatDate, formatDateTime, formatNumber } from '../i18n/format';
import { translateBackendName } from '../i18n/backendNames';
import { RANGE_PRESETS, formatDuration, presetRange, type RangePreset } from './statisticsRange';

/** The four statuses every started case falls into exactly one of, in legend order. */
const STATUSES = ['completed', 'inProgress', 'failed', 'cancelled'] as const;
type Status = (typeof STATUSES)[number];

const REFRESH_MS = 5 * 60_000;

function localZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
}

/** YYYY-MM-DD as a local date, so the label never shifts a day in western zones. */
function parseDay(day: string): Date {
  const [y, m, d] = day.split('-').map(Number);
  return new Date(y, m - 1, d);
}

function percent(part: number, whole: number): string {
  return whole === 0 ? '0%' : `${Math.round((part / whole) * 100)}%`;
}

/**
 * Process statistics for the back office: how many cases started in a date
 * range, and how many of them completed, are in progress, failed or were
 * cancelled, broken down by service, by day and by task, plus the open
 * failures. Shown to users with the `statistics-viewer` realm role.
 *
 * Charts are plain HTML and SVG on the status tokens; every chart has its
 * numbers in text next to it (tiles, legend, table), so no value is
 * conveyed by colour alone.
 */
export default function StatisticsPage() {
  const { t } = useTranslation('statistics');
  const { t: tc } = useTranslation();
  const zone = useMemo(localZone, []);
  const [preset, setPreset] = useState<RangePreset>('today');
  const [range, setRange] = useState(() => presetRange('today', new Date()));
  const [services, setServices] = useState<string[]>([]);
  const [tasks, setTasks] = useState<string[]>([]);
  const [report, setReport] = useState<StatisticsReport | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setReport(await fetchStatistics({ ...range, zone, services, tasks }));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, [range, zone, services, tasks]);

  useEffect(() => {
    void load();
    const timer = window.setInterval(() => void load(), REFRESH_MS);
    return () => window.clearInterval(timer);
  }, [load]);

  const choosePreset = (next: RangePreset) => {
    setPreset(next);
    if (next !== 'custom') setRange(presetRange(next, new Date()));
  };

  const chooseServices = (next: string[]) => {
    setServices(next);
    // The task list depends on the services, so a task picked for another service would linger.
    setTasks([]);
  };

  const name = (raw: string) => translateBackendName(tc, raw);

  return (
    <div className="card card-wide stats">
      <div className="card-head">
        <h1 className="card-title">{t('title')}</h1>
        <button type="button" className="btn" onClick={() => void load()} disabled={loading}>
          <RefreshCw aria-hidden="true" />
          {t('refresh')}
        </button>
      </div>
      <p className="muted">{t('intro')}</p>

      <div className="stats-filters" role="group" aria-label={t('filters.label')}>
        <label className="field">
          <span className="field-label">{t('filters.date')}</span>
          <select
            className="field-input"
            value={preset}
            onChange={(e) => choosePreset(e.target.value as RangePreset)}
          >
            {RANGE_PRESETS.map((p) => (
              <option key={p} value={p}>
                {t(`presets.${p}`)}
              </option>
            ))}
          </select>
        </label>
        {preset === 'custom' && (
          <>
            <label className="field">
              <span className="field-label">{t('filters.from')}</span>
              <input
                type="date"
                className="field-input"
                value={range.from}
                max={range.to}
                onChange={(e) => e.target.value && setRange({ ...range, from: e.target.value })}
              />
            </label>
            <label className="field">
              <span className="field-label">{t('filters.to')}</span>
              <input
                type="date"
                className="field-input"
                value={range.to}
                min={range.from}
                onChange={(e) => e.target.value && setRange({ ...range, to: e.target.value })}
              />
            </label>
          </>
        )}
        <MultiSelect
          label={t('filters.service')}
          allLabel={t('filters.all')}
          countLabel={(n) => t('filters.selected', { count: n })}
          options={(report?.services ?? []).map((s) => ({ value: s.key, label: name(s.name) }))}
          selected={services}
          onChange={chooseServices}
        />
        <MultiSelect
          label={t('filters.task')}
          allLabel={t('filters.all')}
          countLabel={(n) => t('filters.selected', { count: n })}
          options={(report?.tasks ?? []).map((task) => ({
            value: task.id,
            label: name(task.name),
            hint: name(report?.services.find((s) => s.key === task.serviceKey)?.name ?? ''),
          }))}
          selected={tasks}
          onChange={setTasks}
        />
      </div>

      {error && <p className="form-error">{error}</p>}
      {!report && loading && <p className="muted">{tc('feedback.loading')}</p>}
      {report?.truncated && <p className="form-banner form-banner-warn">{t('truncated')}</p>}

      {report && (
        <div className={`stats-body${loading ? ' is-loading' : ''}`} aria-busy={loading}>
          <Tiles totals={report.totals} />
          {report.totals.started === 0 ? (
            <p className="empty">{t('empty')}</p>
          ) : (
            <>
              <div className="stats-grid">
                <section className="stats-panel" aria-labelledby="stats-status">
                  <h2 id="stats-status" className="stats-panel-title">
                    {t('panels.status')}
                  </h2>
                  <Donut totals={report.totals} />
                </section>
                <section className="stats-panel" aria-labelledby="stats-service">
                  <h2 id="stats-service" className="stats-panel-title">
                    {t('panels.perService')}
                  </h2>
                  <ul className="stats-bars">
                    {report.perService.map((s) => (
                      <li key={s.key}>
                        <span className="stats-bar-label">{name(s.name)}</span>
                        <StackedBar counts={s.counts} scale={maxStarted(report.perService)} />
                        <span className="stats-bar-value">{formatNumber(s.counts.started)}</span>
                      </li>
                    ))}
                  </ul>
                </section>
              </div>
              <section className="stats-panel" aria-labelledby="stats-day">
                <h2 id="stats-day" className="stats-panel-title">
                  {t('panels.perDay')}
                </h2>
                <DayColumns days={report.perDay} />
              </section>
            </>
          )}
          <section className="stats-panel" aria-labelledby="stats-tasks">
            <h2 id="stats-tasks" className="stats-panel-title">
              {t('panels.tasks')}
            </h2>
            <TaskTable rows={report.taskStats} report={report} name={name} />
          </section>
          <section className="stats-panel" aria-labelledby="stats-failures">
            <h2 id="stats-failures" className="stats-panel-title">
              {t('panels.failures')}
            </h2>
            {report.failures.length === 0 ? (
              <p className="empty">{t('failures.none')}</p>
            ) : (
              <div className="stats-table-wrap">
                <table className="stats-table">
                  <thead>
                    <tr>
                      <th scope="col">{t('failures.time')}</th>
                      <th scope="col">{t('failures.service')}</th>
                      <th scope="col">{t('failures.case')}</th>
                      <th scope="col">{t('failures.task')}</th>
                      <th scope="col">{t('failures.error')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {report.failures.map((f) => (
                      <tr key={`${f.processInstanceId}-${f.time}-${f.task}`}>
                        <td className="nowrap">{f.time ? formatDateTime(f.time) : ''}</td>
                        <td>{name(f.serviceName)}</td>
                        <td>
                          <Link to={`/processes/${encodeURIComponent(f.processInstanceId)}`}>
                            {f.businessKey || `…${f.processInstanceId.slice(-8)}`}
                          </Link>
                        </td>
                        <td>{name(f.task ?? '')}</td>
                        <td className="stats-error">{f.message}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>
        </div>
      )}
    </div>
  );
}

function maxStarted(rows: { counts: StatusCounts }[]): number {
  return Math.max(1, ...rows.map((r) => r.counts.started));
}

function Tiles({ totals }: { totals: StatusCounts }) {
  const { t } = useTranslation('statistics');
  const tiles: { key: 'started' | Status; value: number }[] = [
    { key: 'started', value: totals.started },
    ...STATUSES.map((s) => ({ key: s, value: totals[s] })),
  ];
  return (
    <ul className="stats-tiles">
      {tiles.map((tile) => (
        <li key={tile.key} className={`stats-tile stat-${tile.key}`}>
          <span className="stats-tile-label">{t(`status.${tile.key}`)}</span>
          <span className="stats-tile-value">{formatNumber(tile.value)}</span>
          {tile.key !== 'started' && (
            <span className="stats-tile-share">{percent(tile.value, totals.started)}</span>
          )}
        </li>
      ))}
    </ul>
  );
}

function Donut({ totals }: { totals: StatusCounts }) {
  const { t } = useTranslation('statistics');
  const radius = 60;
  const circumference = 2 * Math.PI * radius;
  const visible = STATUSES.filter((s) => totals[s] > 0);
  const gap = visible.length > 1 ? 2 : 0;
  let offset = 0;
  return (
    <div className="stats-donut">
      <svg viewBox="0 0 160 160" role="img" aria-label={t('panels.status')}>
        <g className="stats-donut-ring">
          {visible.map((s) => {
            const length = (totals[s] / totals.started) * circumference;
            const dash = Math.max(length - gap, 0.5);
            const segment = (
              <circle
                key={s}
                className={`stats-donut-segment stat-${s}`}
                cx="80"
                cy="80"
                r={radius}
                strokeDasharray={`${dash} ${circumference - dash}`}
                strokeDashoffset={-offset}
              >
                <title>
                  {t(`status.${s}`)}: {formatNumber(totals[s])} (
                  {percent(totals[s], totals.started)})
                </title>
              </circle>
            );
            offset += length;
            return segment;
          })}
        </g>
        <text x="80" y="78" className="stats-donut-total">
          {formatNumber(totals.started)}
        </text>
        <text x="80" y="98" className="stats-donut-caption">
          {t('status.started')}
        </text>
      </svg>
      <ul className="stats-legend">
        {STATUSES.map((s) => (
          <li key={s}>
            <span className={`stats-swatch stat-${s}`} aria-hidden="true" />
            <span>{t(`status.${s}`)}</span>
            <span className="stats-legend-value">
              {formatNumber(totals[s])} · {percent(totals[s], totals.started)}
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}

function StackedBar({ counts, scale }: { counts: StatusCounts; scale: number }) {
  const { t } = useTranslation('statistics');
  return (
    <span className="stats-bar-track">
      <span className="stats-bar" style={{ width: `${(counts.started / scale) * 100}%` }}>
        {STATUSES.filter((s) => counts[s] > 0).map((s) => (
          <span
            key={s}
            className={`stats-bar-segment stat-${s}`}
            style={{ flexGrow: counts[s] }}
            title={`${t(`status.${s}`)}: ${formatNumber(counts[s])}`}
          />
        ))}
      </span>
    </span>
  );
}

function DayColumns({ days }: { days: StatisticsReport['perDay'] }) {
  const { t } = useTranslation('statistics');
  const scale = maxStarted(days);
  // Label at most ~12 days so the axis never collides; every column keeps its tooltip. On a
  // phone only the first and last day are labelled (backoffice.css).
  const labelEvery = Math.ceil(days.length / 12);
  return (
    <div className="stats-days">
      <span className="stats-days-max" aria-hidden="true">
        {formatNumber(scale)}
      </span>
      <ol className="stats-days-plot">
        {days.map((d, i) => {
          const date = parseDay(d.date);
          const label = formatDate(date, { day: 'numeric', month: 'short' });
          const tooltip = [
            `${formatDate(date)}: ${formatNumber(d.counts.started)} ${t('status.started')}`,
            ...STATUSES.filter((s) => d.counts[s] > 0).map(
              (s) => `${t(`status.${s}`)}: ${formatNumber(d.counts[s])}`,
            ),
          ].join('\n');
          return (
            <li key={d.date} title={tooltip}>
              <span
                className="stats-day-column"
                style={{ height: `${(d.counts.started / scale) * 100}%` }}
              >
                {STATUSES.filter((s) => d.counts[s] > 0)
                  .slice()
                  .reverse()
                  .map((s) => (
                    <span
                      key={s}
                      className={`stats-day-segment stat-${s}`}
                      style={{ flexGrow: d.counts[s] }}
                    />
                  ))}
              </span>
              {(i % labelEvery === 0 || i === days.length - 1) && (
                <span
                  className={[
                    'stats-day-label',
                    i === 0 || i === days.length - 1 ? 'is-edge' : '',
                    i % labelEvery !== 0 ? 'is-tail' : '',
                  ].join(' ')}
                >
                  {label}
                </span>
              )}
            </li>
          );
        })}
      </ol>
    </div>
  );
}

function TaskTable({
  rows,
  report,
  name,
}: {
  rows: TaskStats[];
  report: StatisticsReport;
  name: (raw: string) => string;
}) {
  const { t } = useTranslation('statistics');
  if (rows.length === 0) return <p className="empty">{t('tasks.none')}</p>;
  const serviceName = (key: string) => report.services.find((s) => s.key === key)?.name ?? key;
  return (
    <div className="stats-table-wrap">
      <table className="stats-table">
        <thead>
          <tr>
            <th scope="col">{t('tasks.service')}</th>
            <th scope="col">{t('tasks.task')}</th>
            <th scope="col" className="num">
              {t('tasks.completed')}
            </th>
            <th scope="col" className="num">
              {t('tasks.waiting')}
            </th>
            <th scope="col" className="num">
              {t('tasks.avgTime')}
            </th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.id}>
              <td>{name(serviceName(r.serviceKey))}</td>
              <td>{name(r.name)}</td>
              <td className="num">{formatNumber(r.completed)}</td>
              <td className="num">{formatNumber(r.waiting)}</td>
              <td className="num nowrap">
                {r.avgDurationMs === null ? '–' : formatDuration(r.avgDurationMs)}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

interface MultiSelectOption {
  value: string;
  label: string;
  hint?: string;
}

/**
 * A checkbox list in a disclosure, so a filter with many values stays one
 * row high. Nothing selected means "all".
 */
function MultiSelect({
  label,
  allLabel,
  countLabel,
  options,
  selected,
  onChange,
}: {
  label: string;
  allLabel: string;
  countLabel: (n: number) => string;
  options: MultiSelectOption[];
  selected: string[];
  onChange: (next: string[]) => void;
}) {
  const summary =
    selected.length === 0
      ? allLabel
      : selected.length === 1
        ? (options.find((o) => o.value === selected[0])?.label ?? countLabel(1))
        : countLabel(selected.length);
  const toggle = (value: string) =>
    onChange(selected.includes(value) ? selected.filter((v) => v !== value) : [...selected, value]);
  return (
    <div className="field">
      <span className="field-label">{label}</span>
      <details className="stats-multi">
        <summary className="field-input">
          <span>{summary}</span>
          <ChevronDown aria-hidden="true" />
        </summary>
        <div className="stats-multi-menu">
          <label className="stats-multi-option">
            <input type="checkbox" checked={selected.length === 0} onChange={() => onChange([])} />
            <span>{allLabel}</span>
          </label>
          {options.map((o) => (
            <label key={o.value} className="stats-multi-option">
              <input
                type="checkbox"
                checked={selected.includes(o.value)}
                onChange={() => toggle(o.value)}
              />
              <span>
                {o.label}
                {o.hint && <span className="stats-multi-hint">{o.hint}</span>}
              </span>
            </label>
          ))}
        </div>
      </details>
    </div>
  );
}
