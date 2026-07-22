import { useCallback, useEffect, useMemo, useState } from 'react';
import { Trans, useTranslation } from 'react-i18next';
import { DataGrid, type GridColDef, type GridRenderCellParams } from '@mui/x-data-grid';
import { Button } from '@tedi-design-system/react/tedi';
import {
  listIncidents,
  listProcessDefinitions,
  getProcessDefinitionXml,
  setJobRetries,
  type Incident,
  type ProcessDefinition,
} from '../api/camundaClient';
import { parseActivityNames } from '../api/bpmn';
import { formatDateTime } from '../i18n/format';
import { translateBackendName } from '../i18n/backendNames';

interface DefinitionInfo {
  def: ProcessDefinition;
  /** BPMN element id → display name (e.g. "Task_GetPrice" → "Look up vehicle in registry"). */
  activityNames: Map<string, string>;
}

/** One flat DataGrid row per open incident, display strings pre-resolved. */
interface IncidentRow {
  id: string;
  service: string;
  activity: string;
  type: string;
  processId: string;
  timestamp: string;
  message: string;
  /** Job id when the incident is a retryable failedJob, else null. */
  jobId: string | null;
}

function shortId(id: string): string {
  return id.length > 8 ? `…${id.slice(-8)}` : id;
}

/**
 * Lists every open incident with its process, activity, error message, and
 * — for failedJob incidents — a button that resets the job retry counter so
 * the engine picks the job up again.
 *
 * The list renders as an MUI DataGrid (sortable columns, virtualised rows) —
 * the portal's reference for "complex data table" screens where TEDI has no
 * component; the surrounding chrome (card, refresh) stays TEDI/portal styled.
 */
export default function IncidentsPage() {
  const { t } = useTranslation('incidents');
  const [defs, setDefs] = useState<Map<string, DefinitionInfo>>(new Map());
  const [incidents, setIncidents] = useState<Incident[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [defList, open] = await Promise.all([listProcessDefinitions(), listIncidents()]);
      const infos = await Promise.all(
        defList.map(async (def) => {
          const xml = (await getProcessDefinitionXml(def.key)).bpmn20Xml;
          return [def.id, { def, activityNames: parseActivityNames(xml) }] as const;
        }),
      );
      setDefs(new Map(infos));
      setIncidents(open);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const retry = useCallback(
    async (row: IncidentRow) => {
      if (!row.jobId) return;
      setBusyId(row.id);
      setError(null);
      try {
        // The engine increments the retry attempt count; 1 is enough to make the
        // job executor pick up a job currently stuck at retries=0.
        await setJobRetries(row.jobId, 1);
        await load();
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
      } finally {
        setBusyId(null);
      }
    },
    [load],
  );

  const rows: IncidentRow[] = useMemo(
    () =>
      incidents.map((inc) => {
        const info = defs.get(inc.processDefinitionId);
        return {
          id: inc.id,
          service: translateBackendName(
            t,
            info?.def.name ?? info?.def.key ?? inc.processDefinitionId,
          ),
          activity: inc.activityId
            ? translateBackendName(t, info?.activityNames.get(inc.activityId) ?? inc.activityId)
            : t('incident.processScope'),
          type: t(`types.${inc.incidentType}`, { defaultValue: inc.incidentType }),
          processId: shortId(inc.processInstanceId),
          timestamp: inc.incidentTimestamp,
          message: inc.incidentMessage ?? '',
          jobId: inc.incidentType === 'failedJob' && inc.configuration ? inc.configuration : null,
        };
      }),
    [incidents, defs, t],
  );

  const columns: GridColDef[] = useMemo(
    () => [
      { field: 'service', headerName: t('table.service'), flex: 1.2, minWidth: 160 },
      { field: 'activity', headerName: t('table.activity'), flex: 1.2, minWidth: 160 },
      { field: 'type', headerName: t('table.type'), flex: 0.8, minWidth: 120 },
      { field: 'processId', headerName: t('table.process'), width: 110, sortable: false },
      {
        field: 'timestamp',
        headerName: t('table.time'),
        width: 160,
        valueFormatter: ({ value }) => formatDateTime(value as string),
      },
      { field: 'message', headerName: t('table.message'), flex: 2, minWidth: 220, sortable: false },
      {
        field: 'actions',
        headerName: t('table.actions'),
        width: 130,
        sortable: false,
        filterable: false,
        renderCell: (params: GridRenderCellParams<unknown, IncidentRow>) =>
          params.row.jobId ? (
            <Button
              size="small"
              onClick={() => retry(params.row)}
              disabled={busyId === params.row.id}
            >
              {busyId === params.row.id ? t('incident.retrying') : t('common:actions.retry')}
            </Button>
          ) : (
            <span className="muted">{t('incident.noRetry')}</span>
          ),
      },
    ],
    [t, retry, busyId],
  );

  return (
    <div className="card card-wide">
      <div className="card-head">
        <h1 className="card-title">{t('title')}</h1>
        <Button visualType="secondary" onClick={load} disabled={loading}>
          {t('common:actions.refresh')}
        </Button>
      </div>
      <p className="muted">
        <Trans t={t} i18nKey="intro" components={{ strong: <strong /> }} />
      </p>

      {loading && <p className="muted">{t('common:feedback.loading')}</p>}
      {error && <p className="form-error">{error}</p>}

      {!loading && !error && incidents.length === 0 && <p className="empty">{t('empty')}</p>}

      {!loading && !error && incidents.length > 0 && (
        <DataGrid
          rows={rows}
          columns={columns}
          autoHeight
          disableSelectionOnClick
          pageSize={25}
          rowsPerPageOptions={[25]}
          getRowHeight={() => 'auto'}
          initialState={{ sorting: { sortModel: [{ field: 'timestamp', sort: 'desc' }] } }}
        />
      )}
    </div>
  );
}
