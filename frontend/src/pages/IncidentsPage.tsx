import { useCallback, useEffect, useState } from 'react';
import { Trans, useTranslation } from 'react-i18next';
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

function shortId(id: string): string {
  return id.length > 8 ? `…${id.slice(-8)}` : id;
}

/**
 * Lists every open incident with its process, activity, error message, and
 * — for failedJob incidents — a button that resets the job retry counter so
 * the engine picks the job up again.
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

  async function retry(inc: Incident) {
    if (!inc.configuration) return;
    setBusyId(inc.id);
    setError(null);
    try {
      // The engine increments the retry attempt count; 1 is enough to make the
      // job executor pick up a job currently stuck at retries=0.
      await setJobRetries(inc.configuration, 1);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusyId(null);
    }
  }

  return (
    <div className="card card-wide">
      <div className="card-head">
        <h1 className="card-title">{t('title')}</h1>
        <button className="btn" onClick={load} disabled={loading}>
          {t('common:actions.refresh')}
        </button>
      </div>
      <p className="muted">
        <Trans t={t} i18nKey="intro" components={{ strong: <strong /> }} />
      </p>

      {loading && <p className="muted">{t('common:feedback.loading')}</p>}
      {error && <p className="form-error">{error}</p>}

      {!loading && !error && incidents.length === 0 && <p className="empty">{t('empty')}</p>}

      {!loading && !error && incidents.length > 0 && (
        <ul className="row-list">
          {incidents.map((inc) => {
            const info = defs.get(inc.processDefinitionId);
            const serviceName = translateBackendName(
              t,
              info?.def.name ?? info?.def.key ?? inc.processDefinitionId,
            );
            const activityName = inc.activityId
              ? translateBackendName(t, info?.activityNames.get(inc.activityId) ?? inc.activityId)
              : t('incident.processScope');
            const canRetry = inc.incidentType === 'failedJob' && inc.configuration;

            return (
              <li key={inc.id} className="incident">
                <div className="incident-head">
                  <span className="row-title">
                    {serviceName} <span className="muted">·</span> {activityName}
                  </span>
                  <span className="row-sub">
                    {t(`types.${inc.incidentType}`, { defaultValue: inc.incidentType })}{' '}
                    <span className="muted">·</span>{' '}
                    {t('incident.processLabel', { id: shortId(inc.processInstanceId) })}{' '}
                    <span className="muted">·</span> {formatDateTime(inc.incidentTimestamp)}
                  </span>
                </div>
                {inc.incidentMessage && <p className="incident-message">{inc.incidentMessage}</p>}
                <div className="incident-actions">
                  {canRetry ? (
                    <button
                      className="btn btn-primary"
                      onClick={() => retry(inc)}
                      disabled={busyId === inc.id}
                    >
                      {busyId === inc.id ? t('incident.retrying') : t('common:actions.retry')}
                    </button>
                  ) : (
                    <span className="muted">{t('incident.noRetry')}</span>
                  )}
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
