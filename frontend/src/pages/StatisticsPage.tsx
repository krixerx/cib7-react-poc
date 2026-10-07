import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { ChevronDown, RefreshCw } from 'lucide-react';
import {
  fetchStatistics,
  type Outcomes,
  type PathCounts,
  type StatisticsReport,
  type StatusCounts,
  type TaskStats,
} from '../api/statisticsApi';
import { formatDate, formatDateTime, formatNumber } from '../i18n/format';
import { translateBackendName } from '../i18n/backendNames';
import {
  RANGE_PRESETS,
  formatDuration,
  presetRange,
  previousRange,
  type RangePreset,
} from './statisticsRange';

const REFRESH_MS = 5 * 60_000;

/** Below this many cases in either period a change is noise, so it never raises attention. */
const MIN_CASES_FOR_CHANGE = 5;

function localZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
}

/** YYYY-MM-DD as a local date, so the label never shifts a day in western zones. */
function parseDay(day: string): Date {
  const [y, m, d] = day.split('-').map(Number);
  return new Date(y, m - 1, d);
}

function ratio(part: number, whole: number): number | null {
  return whole === 0 ? null : part / whole;
}

/** The numbers one metric is computed from: the whole report, one service or one day. */
interface Scope {
  counts: StatusCounts;
  outcomes: Outcomes;
}

type MetricKey =
  'started' | 'completed' | 'leadTime' | 'approval' | 'returned' | 'touchless' | 'failed';

interface Metric {
  key: MetricKey;
  kind: 'count' | 'rate' | 'duration';
  /** +1 when a higher value is better, -1 when lower is better, 0 when neither. */
  better: 1 | -1 | 0;
  value: (s: Scope) => number | null;
}

const METRICS: Metric[] = [
  { key: 'started', kind: 'count', better: 0, value: (s) => s.counts.started },
  { key: 'completed', kind: 'count', better: 1, value: (s) => s.counts.completed },
  { key: 'leadTime', kind: 'duration', better: -1, value: (s) => s.outcomes.avgLeadTimeMs },
  {
    key: 'approval',
    kind: 'rate',
    better: 1,
    value: (s) => ratio(s.outcomes.approved, s.outcomes.approved + s.outcomes.rejected),
  },
  {
    key: 'returned',
    kind: 'rate',
    better: -1,
    value: (s) => ratio(s.outcomes.returned, s.counts.started),
  },
  {
    key: 'touchless',
    kind: 'rate',
    better: 1,
    value: (s) => ratio(s.outcomes.touchless, s.counts.completed),
  },
  { key: 'failed', kind: 'count', better: -1, value: (s) => s.counts.failed },
];

function formatMetric(metric: Metric, v: number | null): string {
  if (v === null) return '–';
  if (metric.kind === 'rate') return `${formatNumber(Math.round(v * 100))}%`;
  if (metric.kind === 'duration') return formatDuration(v);
  return formatNumber(v);
}

interface Change {
  /** Signed size: percentage points for rates, relative percent otherwise. */
  amount: number;
  tone: 'good' | 'bad' | 'neutral';
  /** True when the change is large enough to call out in the attention strip. */
  notable: boolean;
}

/**
 * How a metric moved against the previous period. Rates compare in
 * percentage points, counts and times relative to the earlier value.
 */
function changeOf(metric: Metric, cur: number | null, prev: number | null): Change | null {
  if (cur === null || prev === null) return null;
  let amount: number;
  let notable: boolean;
  if (metric.kind === 'rate') {
    amount = Math.round((cur - prev) * 100);
    notable = Math.abs(amount) >= 5;
  } else {
    if (prev === 0) return null;
    amount = Math.round(((cur - prev) / prev) * 100);
    notable = Math.abs(amount) >= 25;
  }
  const direction = Math.sign(amount) * metric.better;
  return {
    amount,
    tone: direction > 0 ? 'good' : direction < 0 ? 'bad' : 'neutral',
    notable,
  };
}

/**
 * Process statistics for the back office: what happened to the cases started
 * in a period, how that compares with the period before, where cases flow and
 * how the back office is keeping up. Shown to users with the
 * `statistics-viewer` realm role.
 *
 * Every number is computed from engine history (docs/statistics.md); the
 * page deliberately has no forecasts or baselines, since history is wiped on
 * every engine restart. Charts are plain HTML and SVG on the tokens, and each
 * one carries its numbers in text, so nothing is conveyed by colour alone.
 */
export default function StatisticsPage() {
  const { t } = useTranslation('statistics');
  const { t: tc } = useTranslation();
  const zone = useMemo(localZone, []);
  const [preset, setPreset] = useState<RangePreset>('last7');
  const [range, setRange] = useState(() => presetRange('last7', new Date()));
  const [services, setServices] = useState<string[]>([]);
  const [tasks, setTasks] = useState<string[]>([]);
  const [metricKey, setMetricKey] = useState<MetricKey>('started');
  const [report, setReport] = useState<StatisticsReport | null>(null);
  const [previous, setPrevious] = useState<StatisticsReport | null>(null);
  const [updatedAt, setUpdatedAt] = useState<Date | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const filter = { zone, services, tasks };
      const [current, before] = await Promise.all([
        fetchStatistics({ ...range, ...filter }),
        fetchStatistics({ ...previousRange(range), ...filter }),
      ]);
      setReport(current);
      setPrevious(before);
      setUpdatedAt(new Date());
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

  /** Clicking a service focuses the page on it; clicking it again shows all services. */
  const focusService = (key: string) =>
    chooseServices(services.length === 1 && services[0] === key ? [] : [key]);

  const toggleService = (key: string) =>
    chooseServices(services.includes(key) ? services.filter((s) => s !== key) : [...services, key]);

  const name = (raw: string) => translateBackendName(tc, raw);
  const metric = METRICS.find((m) => m.key === metricKey) ?? METRICS[0];

  return (
    <div className="stats">
      <header className="stats-hero">
        <div className="stats-hero-top">
          <div>
            <h1 className="stats-hero-title">{t('title')}</h1>
            <p className="stats-hero-intro">{t('intro')}</p>
          </div>
          <div className="stats-hero-actions">
            {updatedAt && (
              <span className="stats-updated">
                {t('updated', {
                  time: formatDateTime(updatedAt, { hour: '2-digit', minute: '2-digit' }),
                })}
              </span>
            )}
            <button
              type="button"
              className="stats-hero-btn"
              onClick={() => void load()}
              disabled={loading}
            >
              <RefreshCw aria-hidden="true" />
              {t('refresh')}
            </button>
          </div>
        </div>

        <div className="stats-filters" role="group" aria-label={t('filters.label')}>
          <div className="stats-seg" role="group" aria-label={t('filters.date')}>
            {RANGE_PRESETS.map((p) => (
              <button
                key={p}
                type="button"
                aria-pressed={preset === p}
                onClick={() => choosePreset(p)}
              >
                {t(`presets.${p}`)}
              </button>
            ))}
          </div>
          {preset === 'custom' && (
            <div className="stats-dates">
              <label>
                <span>{t('filters.from')}</span>
                <input
                  type="date"
                  value={range.from}
                  max={range.to}
                  onChange={(e) => e.target.value && setRange({ ...range, from: e.target.value })}
                />
              </label>
              <label>
                <span>{t('filters.to')}</span>
                <input
                  type="date"
                  value={range.to}
                  min={range.from}
                  onChange={(e) => e.target.value && setRange({ ...range, to: e.target.value })}
                />
              </label>
            </div>
          )}
        </div>
        <div className="stats-filters">
          <div className="stats-chips" role="group" aria-label={t('filters.service')}>
            <button
              type="button"
              className="stats-chip"
              aria-pressed={services.length === 0}
              onClick={() => chooseServices([])}
            >
              {t('filters.allServices')}
            </button>
            {(report?.services ?? []).map((s) => (
              <button
                key={s.key}
                type="button"
                className="stats-chip"
                aria-pressed={services.includes(s.key)}
                onClick={() => toggleService(s.key)}
              >
                {name(s.name)}
              </button>
            ))}
          </div>
          <MultiSelect
            label={t('filters.task')}
            allLabel={t('filters.allTasks')}
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

        {report && (
          <Attention report={report} previous={previous} onPickMetric={(k) => setMetricKey(k)} />
        )}
      </header>

      {error && <p className="form-error">{error}</p>}
      {!report && loading && <p className="muted">{tc('feedback.loading')}</p>}
      {report?.truncated && <p className="form-banner form-banner-warn">{t('truncated')}</p>}

      {report && (
        <div className={`stats-body${loading ? ' is-loading' : ''}`} aria-busy={loading}>
          <section aria-labelledby="stats-kpis">
            <div className="stats-sec-head">
              <h2 id="stats-kpis">{t('kpi.title')}</h2>
              <p>{t('kpi.subtitle')}</p>
            </div>
            <Kpis
              report={report}
              previous={previous}
              selected={metric.key}
              onSelect={setMetricKey}
            />
          </section>

          {report.totals.started === 0 ? (
            <p className="empty stats-card">{t('empty')}</p>
          ) : (
            <>
              <div className="stats-grid">
                <section className="stats-card" aria-labelledby="stats-trend">
                  <h2 id="stats-trend" className="stats-card-title">
                    {t(`metrics.${metric.key}`)}
                  </h2>
                  <p className="stats-card-sub">{t('trend.subtitle')}</p>
                  <Trend metric={metric} days={report.perDay} previousDays={previous?.perDay} />
                </section>
                <section className="stats-card" aria-labelledby="stats-mix">
                  <h2 id="stats-mix" className="stats-card-title">
                    {t('mix.title')}
                  </h2>
                  <p className="stats-card-sub">{t('mix.subtitle')}</p>
                  <ul className="stats-bars">
                    {report.perService.map((s) => (
                      <li key={s.key}>
                        <button
                          type="button"
                          className="stats-bar-label"
                          aria-pressed={services.length === 1 && services[0] === s.key}
                          onClick={() => focusService(s.key)}
                        >
                          {name(s.name)}
                        </button>
                        <StackedBar counts={s.counts} scale={maxStarted(report.perService)} />
                        <span className="stats-bar-value">{formatNumber(s.counts.started)}</span>
                      </li>
                    ))}
                  </ul>
                  <StatusLegend />
                </section>
              </div>

              <section className="stats-card" aria-labelledby="stats-flow">
                <h2 id="stats-flow" className="stats-card-title">
                  {t('flow.title')}
                </h2>
                <p className="stats-card-sub">{t('flow.subtitle')}</p>
                <FlowChart flow={report.flow} started={report.totals.started} />
                <p className="stats-note">
                  {t('flow.returned', { count: report.outcomes.returned })}
                </p>
              </section>

              <section className="stats-card" aria-labelledby="stats-services">
                <h2 id="stats-services" className="stats-card-title">
                  {t('services.title')}
                </h2>
                <p className="stats-card-sub">{t('services.subtitle')}</p>
                <ServiceTable
                  report={report}
                  focused={services.length === 1 ? services[0] : null}
                  onFocus={focusService}
                  name={name}
                />
              </section>
            </>
          )}

          <section className="stats-card" aria-labelledby="stats-backoffice">
            <h2 id="stats-backoffice" className="stats-card-title">
              {t('backOffice.title')}
            </h2>
            <p className="stats-card-sub">{t('backOffice.subtitle')}</p>
            <BackOfficeStats report={report} />
            <TaskTable rows={report.taskStats} report={report} name={name} />
          </section>

          <section className="stats-card" aria-labelledby="stats-failures">
            <h2 id="stats-failures" className="stats-card-title">
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

function scopeOf(report: StatisticsReport): Scope {
  return { counts: report.totals, outcomes: report.outcomes };
}

/**
 * The strip in the hero band: open failures, the back-office queue and any
 * metric that moved notably the wrong way against the previous period.
 */
function Attention({
  report,
  previous,
  onPickMetric,
}: {
  report: StatisticsReport;
  previous: StatisticsReport | null;
  onPickMetric: (key: MetricKey) => void;
}) {
  const { t } = useTranslation('statistics');
  const items: ReactNode[] = [];

  if (report.totals.failed > 0) {
    items.push(
      <a key="failed" className="stats-alert sev-critical" href="#stats-failures">
        <span className="stats-alert-sev">{t('attention.critical')}</span>
        <strong className="stats-alert-value">{formatNumber(report.totals.failed)}</strong>
        <span className="stats-alert-label">
          {t('attention.failed', { count: report.totals.failed })}
        </span>
      </a>,
    );
  }

  const bo = report.backOffice;
  if (bo.waiting > 0) {
    const ageMs = bo.oldestWaitingSince
      ? Date.now() - new Date(bo.oldestWaitingSince).getTime()
      : 0;
    items.push(
      <a
        key="queue"
        className={`stats-alert ${ageMs > 86_400_000 ? 'sev-warn' : 'sev-info'}`}
        href="#stats-backoffice"
      >
        <span className="stats-alert-sev">
          {ageMs > 86_400_000 ? t('attention.warn') : t('attention.info')}
        </span>
        <strong className="stats-alert-value">{formatNumber(bo.waiting)}</strong>
        <span className="stats-alert-label">{t('attention.queue', { count: bo.waiting })}</span>
        {ageMs > 0 && (
          <span className="stats-alert-hint">
            {t('attention.oldest', { age: formatDuration(ageMs) })}
          </span>
        )}
      </a>,
    );
  }

  if (
    previous &&
    report.totals.started >= MIN_CASES_FOR_CHANGE &&
    previous.totals.started >= MIN_CASES_FOR_CHANGE
  ) {
    for (const m of METRICS) {
      const cur = m.value(scopeOf(report));
      const change = changeOf(m, cur, m.value(scopeOf(previous)));
      if (!change || !change.notable || change.tone !== 'bad') continue;
      items.push(
        <button
          key={m.key}
          type="button"
          className="stats-alert sev-warn"
          onClick={() => onPickMetric(m.key)}
        >
          <span className="stats-alert-sev">{t('attention.warn')}</span>
          <strong className="stats-alert-value">{formatMetric(m, cur)}</strong>
          <span className="stats-alert-label">{t(`metrics.${m.key}`)}</span>
          <span className="stats-alert-hint">
            {t(m.kind === 'rate' ? 'change.pp' : 'change.pct', { value: signed(change.amount) })}{' '}
            {t('change.vsPrevious')}
          </span>
        </button>,
      );
    }
  }

  return (
    <section className="stats-attention" aria-labelledby="stats-attention">
      <h2 id="stats-attention" className="stats-attention-title">
        {t('attention.title')}
      </h2>
      {items.length === 0 ? (
        <p className="stats-attention-none">{t('attention.none')}</p>
      ) : (
        <div className="stats-alerts">{items}</div>
      )}
    </section>
  );
}

function signed(n: number): string {
  return `${n > 0 ? '+' : n < 0 ? '−' : '±'}${formatNumber(Math.abs(n))}`;
}

function Kpis({
  report,
  previous,
  selected,
  onSelect,
}: {
  report: StatisticsReport;
  previous: StatisticsReport | null;
  selected: MetricKey;
  onSelect: (key: MetricKey) => void;
}) {
  const { t } = useTranslation('statistics');
  return (
    <ul className="stats-kpis">
      {METRICS.map((m) => {
        const cur = m.value(scopeOf(report));
        const change = previous ? changeOf(m, cur, m.value(scopeOf(previous))) : null;
        return (
          <li key={m.key}>
            <button
              type="button"
              className="stats-kpi"
              aria-pressed={selected === m.key}
              onClick={() => onSelect(m.key)}
            >
              <span className="stats-kpi-label">{t(`metrics.${m.key}`)}</span>
              <span className="stats-kpi-value">{formatMetric(m, cur)}</span>
              <span className={`stats-kpi-change tone-${change?.tone ?? 'neutral'}`}>
                {change
                  ? `${t(m.kind === 'rate' ? 'change.pp' : 'change.pct', { value: signed(change.amount) })} ${t('change.vsPrevious')}`
                  : t('change.none')}
              </span>
              <Sparkline values={report.perDay.map((d) => m.value(d))} />
            </button>
          </li>
        );
      })}
    </ul>
  );
}

/** A line over the days of the period; gaps where a rate has no cases that day. */
function Sparkline({ values }: { values: (number | null)[] }) {
  const known = values.filter((v): v is number => v !== null);
  if (values.length < 2 || known.length < 2) return <span className="stats-spark" />;
  const min = Math.min(...known);
  const span = Math.max(...known) - min || 1;
  let d = '';
  let pen = false;
  values.forEach((v, i) => {
    if (v === null) {
      pen = false;
      return;
    }
    const x = (i / (values.length - 1)) * 100;
    const y = 30 - ((v - min) / span) * 28;
    d += `${pen ? 'L' : 'M'}${x.toFixed(1)},${y.toFixed(1)}`;
    pen = true;
  });
  return (
    <svg className="stats-spark" viewBox="0 0 100 32" preserveAspectRatio="none" aria-hidden="true">
      <path d={d} vectorEffect="non-scaling-stroke" />
    </svg>
  );
}

/** One column per day for the selected metric, with a tick for the same day of the previous period. */
function Trend({
  metric,
  days,
  previousDays,
}: {
  metric: Metric;
  days: StatisticsReport['perDay'];
  previousDays: StatisticsReport['perDay'] | undefined;
}) {
  const { t } = useTranslation('statistics');
  const values = days.map((d) => metric.value(d));
  const before = days.map((_, i) => {
    const p = previousDays?.[i];
    return p ? metric.value(p) : null;
  });
  const scale = Math.max(
    metric.kind === 'rate' ? 1 : 0,
    ...values.map((v) => v ?? 0),
    ...before.map((v) => v ?? 0),
  );
  const top = scale || 1;
  // Label at most ~12 days so the axis never collides; every column keeps its tooltip. On a
  // phone only the first and last day are labelled (backoffice.css).
  const labelEvery = Math.ceil(days.length / 12);
  return (
    <div className="stats-days">
      <span className="stats-days-max" aria-hidden="true">
        {formatMetric(metric, top)}
      </span>
      <ol className="stats-days-plot">
        {days.map((d, i) => {
          const date = parseDay(d.date);
          const v = values[i];
          const p = before[i];
          const tooltip = [
            `${formatDate(date)}: ${formatMetric(metric, v)}`,
            ...(previousDays?.[i]
              ? [
                  `${t('trend.previous')} (${formatDate(parseDay(previousDays[i].date))}): ${formatMetric(metric, p)}`,
                ]
              : []),
          ].join('\n');
          return (
            <li key={d.date} title={tooltip}>
              {v !== null && (
                <span className="stats-day-column" style={{ height: `${(v / top) * 100}%` }} />
              )}
              {p !== null && (
                <span
                  className="stats-day-previous"
                  style={{ bottom: `${(p / top) * 100}%` }}
                  aria-hidden="true"
                />
              )}
              {(i % labelEvery === 0 || i === days.length - 1) && (
                <span
                  className={[
                    'stats-day-label',
                    i === 0 || i === days.length - 1 ? 'is-edge' : '',
                    i % labelEvery !== 0 ? 'is-tail' : '',
                  ].join(' ')}
                >
                  {formatDate(date, { day: 'numeric', month: 'short' })}
                </span>
              )}
            </li>
          );
        })}
      </ol>
      <ul className="stats-legend stats-legend-inline">
        <li>
          <span className="stats-swatch stats-swatch-current" aria-hidden="true" />
          {t('trend.current')}
        </li>
        <li>
          <span className="stats-swatch stats-swatch-previous" aria-hidden="true" />
          {t('trend.previous')}
        </li>
      </ul>
    </div>
  );
}

/** The four statuses every started case falls into exactly one of, in legend order. */
const STATUSES = ['completed', 'inProgress', 'failed', 'cancelled'] as const;

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

function StatusLegend() {
  const { t } = useTranslation('statistics');
  return (
    <ul className="stats-legend stats-legend-inline">
      {STATUSES.map((s) => (
        <li key={s}>
          <span className={`stats-swatch stat-${s}`} aria-hidden="true" />
          {t(`status.${s}`)}
        </li>
      ))}
    </ul>
  );
}

type FlowNodeKey =
  | 'started'
  | 'backOffice'
  | 'direct'
  | 'approved'
  | 'rejected'
  | 'inProgress'
  | 'failed'
  | 'cancelled';

const OUTCOME_KEYS: (keyof PathCounts)[] = [
  'approved',
  'rejected',
  'inProgress',
  'failed',
  'cancelled',
];

const FLOW_W = 760;
const FLOW_H = 240;
const NODE_W = 12;
const NODE_GAP = 12;
const COLUMN_X = [150, 390, 610];

/**
 * A three-column Sankey: started cases, whether they reached the back office,
 * and where they stand now. Band width is the number of cases; every node is
 * labelled with its count, and each band has a tooltip.
 */
function FlowChart({ flow, started }: { flow: StatisticsReport['flow']; started: number }) {
  const { t } = useTranslation('statistics');
  const sum = (p: PathCounts) => OUTCOME_KEYS.reduce((n, k) => n + p[k], 0);
  type FlowNode = { key: FlowNodeKey; value: number };
  type FlowLink = { from: FlowNodeKey; to: FlowNodeKey; value: number };
  const columns = (
    [
      [{ key: 'started', value: started }],
      [
        { key: 'backOffice', value: sum(flow.backOffice) },
        { key: 'direct', value: sum(flow.direct) },
      ],
      OUTCOME_KEYS.map((k) => ({ key: k, value: flow.backOffice[k] + flow.direct[k] })),
    ] as FlowNode[][]
  ).map((col) => col.filter((n) => n.value > 0));

  const longest = Math.max(...columns.map((c) => c.length));
  const scale = (FLOW_H - NODE_GAP * (longest - 1)) / Math.max(1, started);
  const nodes = new Map<
    FlowNodeKey,
    { x: number; y: number; h: number; value: number; out: number; in: number }
  >();
  columns.forEach((col, ci) => {
    const height = col.reduce((n, node) => n + node.value * scale, 0) + NODE_GAP * (col.length - 1);
    let y = (FLOW_H - height) / 2;
    col.forEach((node) => {
      const h = node.value * scale;
      nodes.set(node.key, { x: COLUMN_X[ci], y, h, value: node.value, out: y, in: y });
      y += h + NODE_GAP;
    });
  });

  const links = (
    [
      { from: 'started', to: 'backOffice', value: sum(flow.backOffice) },
      { from: 'started', to: 'direct', value: sum(flow.direct) },
      ...(['backOffice', 'direct'] as const).flatMap((path) =>
        OUTCOME_KEYS.map((k) => ({ from: path, to: k, value: flow[path][k] })),
      ),
    ] as FlowLink[]
  ).filter((l) => l.value > 0);

  const label = (key: FlowNodeKey) => t(`flow.nodes.${key}`);

  return (
    <div className="stats-flow">
      <svg viewBox={`0 0 ${FLOW_W} ${FLOW_H + 20}`} role="img" aria-label={t('flow.title')}>
        <g transform="translate(0 10)">
          {links.map((l) => {
            const a = nodes.get(l.from)!;
            const b = nodes.get(l.to)!;
            const h = l.value * scale;
            const x0 = a.x + NODE_W;
            const x1 = b.x;
            const xm = (x0 + x1) / 2;
            const s0 = a.out;
            const t0 = b.in;
            a.out += h;
            b.in += h;
            const d =
              `M${x0},${s0} C${xm},${s0} ${xm},${t0} ${x1},${t0} ` +
              `L${x1},${t0 + h} C${xm},${t0 + h} ${xm},${s0 + h} ${x0},${s0 + h} Z`;
            return (
              <path key={`${l.from}-${l.to}`} className={`stats-flow-link flow-${l.to}`} d={d}>
                <title>{`${label(l.from)} → ${label(l.to)}: ${formatNumber(l.value)}`}</title>
              </path>
            );
          })}
          {[...nodes.entries()].map(([key, n]) => {
            const first = n.x === COLUMN_X[0];
            return (
              <g key={key} className={`flow-${key}`}>
                <rect
                  className="stats-flow-node"
                  x={n.x}
                  y={n.y}
                  width={NODE_W}
                  height={Math.max(n.h, 2)}
                  rx="3"
                />
                <text
                  className="stats-flow-label"
                  x={first ? n.x - 8 : n.x + NODE_W + 8}
                  y={n.y + Math.max(n.h, 2) / 2}
                  textAnchor={first ? 'end' : 'start'}
                >
                  <tspan className="stats-flow-name">{label(key)}</tspan>
                  <tspan className="stats-flow-value"> {formatNumber(n.value)}</tspan>
                </text>
              </g>
            );
          })}
        </g>
      </svg>
    </div>
  );
}

function ServiceTable({
  report,
  focused,
  onFocus,
  name,
}: {
  report: StatisticsReport;
  focused: string | null;
  onFocus: (key: string) => void;
  name: (raw: string) => string;
}) {
  const { t } = useTranslation('statistics');
  const shown = METRICS.filter((m) => m.key !== 'started');
  return (
    <div className="stats-table-wrap">
      <table className="stats-table stats-services">
        <thead>
          <tr>
            <th scope="col">{t('tasks.service')}</th>
            <th scope="col" className="num">
              {t('metrics.started')}
            </th>
            {shown.map((m) => (
              <th key={m.key} scope="col" className="num">
                {t(`metrics.${m.key}`)}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {report.perService.map((s) => (
            <tr key={s.key} className={focused === s.key ? 'is-focused' : undefined}>
              <th scope="row">
                <button
                  type="button"
                  className="stats-link-btn"
                  aria-pressed={focused === s.key}
                  onClick={() => onFocus(s.key)}
                >
                  {name(s.name)}
                </button>
              </th>
              <td className="num">{formatNumber(s.counts.started)}</td>
              {shown.map((m) => (
                <td key={m.key} className="num nowrap">
                  {formatMetric(m, m.value(s))}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function BackOfficeStats({ report }: { report: StatisticsReport }) {
  const { t } = useTranslation('statistics');
  const bo = report.backOffice;
  const oldest = bo.oldestWaitingSince
    ? formatDuration(Date.now() - new Date(bo.oldestWaitingSince).getTime())
    : '–';
  const stats = [
    { key: 'done', value: formatNumber(bo.done) },
    { key: 'waiting', value: formatNumber(bo.waiting) },
    { key: 'avgTask', value: bo.avgTaskMs === null ? '–' : formatDuration(bo.avgTaskMs) },
    { key: 'oldest', value: oldest },
  ];
  return (
    <dl className="stats-team">
      {stats.map((s) => (
        <div key={s.key} className="stats-team-item">
          <dt>{t(`backOffice.${s.key}`)}</dt>
          <dd>{s.value}</dd>
        </div>
      ))}
    </dl>
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
    <details className="stats-multi">
      <summary aria-label={`${label}: ${summary}`}>
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
  );
}
