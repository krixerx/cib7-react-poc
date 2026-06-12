import { useCallback, useEffect, useState } from 'react';
import { Trans, useTranslation } from 'react-i18next';
import {
  getHistoricProcessInstance,
  getProcessDefinitionXml,
  listHistoricTasks,
  listHistoricVariables,
  type CamundaTask,
  type HistoricProcessInstance,
  type HistoricTask,
  type HistoricVariableInstance,
} from '../api/camundaClient';
import { parseActivityNames, parseProcessName, parseUserTasks } from '../api/bpmn';
import { formRegistry, parseFormId } from '../forms/registry';
import DocumentsCard from '../components/DocumentsCard';
import ProcessTimeline from '../components/ProcessTimeline';
import { translateBackendName } from '../i18n/backendNames';
import { formatDateTime } from '../i18n/format';

/**
 * Read-only view of a process instance. Loads the historic variables and
 * renders the LAST completed user task's React form with `readOnly` set, so
 * the viewer sees the same fields the case actually carried but can't edit
 * them. Works for both ended cases and in-flight cases (e.g. an applicant
 * watching a case that's parked with the back office).
 *
 * Used as the route body of CompletedProcessPage (deep-link `/processes/:id`)
 * AND inlined in the civil-servant worklist's right pane when a selected
 * case has no active user task and no incidents.
 */
export interface ProcessHistoryViewProps {
  processInstanceId: string;
  /** Optional element rendered on the right of the card head (e.g. a Close/Back button). */
  topSlot?: React.ReactNode;
  /** When true, parent is rendering the Documents card itself (e.g. in a sidebar). */
  hideDocuments?: boolean;
  /** When true, skip the form card's own header — parent owns the page title. */
  hideOwnHeader?: boolean;
  /** Fires once the historic data has loaded so the parent can surface service name / outcome. */
  onLoaded?: (info: {
    serviceName: string;
    outcome: string;
    isInFlight: boolean;
    processDefinitionKey: string;
  }) => void;
}

interface LoadedState {
  pi: HistoricProcessInstance;
  /** The last completed user task — its form drives the read-only render. */
  lastTask: HistoricTask;
  /** Historic variables collapsed to plain values keyed by name. */
  data: Record<string, unknown>;
  /** Form key resolved from the BPMN model for `lastTask.taskDefinitionKey`. */
  formKey: string | null;
  /** End-event label for ended cases, or "Currently with <step>" for in-flight. */
  outcome: string;
  /** Human-readable name of the process definition (service name). */
  serviceName: string;
}

function unwrap(vars: HistoricVariableInstance[]): Record<string, unknown> {
  return Object.fromEntries(vars.map((v) => [v.name, v.value]));
}

/** Builds a CamundaTask shape from a historic task so the form contract holds. */
function synthesizeTask(ht: HistoricTask, formKey: string | null): CamundaTask {
  return {
    id: ht.id,
    name: ht.name,
    created: ht.startTime,
    processInstanceId: ht.processInstanceId,
    processDefinitionId: ht.processDefinitionId,
    taskDefinitionKey: ht.taskDefinitionKey,
    formKey,
    assignee: ht.assignee,
  };
}

export default function ProcessHistoryView({
  processInstanceId,
  topSlot,
  hideDocuments = false,
  hideOwnHeader = false,
  onLoaded,
}: ProcessHistoryViewProps) {
  const { t } = useTranslation('process-detail');
  const [state, setState] = useState<LoadedState | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    setState(null);
    try {
      const pi = await getHistoricProcessInstance(processInstanceId);
      const [tasks, vars, xml] = await Promise.all([
        listHistoricTasks(processInstanceId),
        listHistoricVariables(processInstanceId),
        getProcessDefinitionXml(pi.processDefinitionKey),
      ]);
      const completedTasks = tasks.filter((t) => t.endTime);
      if (completedTasks.length === 0) {
        throw new Error(t('errors.noCompletedTask'));
      }
      const lastTask = completedTasks[0]; // sorted desc by endTime
      const userTasks = parseUserTasks(xml.bpmn20Xml);
      const formKey = userTasks.find((ut) => ut.id === lastTask.taskDefinitionKey)?.formKey ?? null;
      const activityNames = parseActivityNames(xml.bpmn20Xml);
      const activeTask = tasks.find((t) => !t.endTime);
      const outcome = pi.endTime
        ? pi.endActivityId
          ? translateBackendName(t, activityNames.get(pi.endActivityId) ?? pi.endActivityId)
          : pi.state
        : activeTask
          ? t('outcome.currentlyWith', { step: translateBackendName(t, activeTask.name) })
          : t('common:status.inProgress');
      const serviceName = translateBackendName(
        t,
        parseProcessName(xml.bpmn20Xml) ?? pi.processDefinitionKey,
      );
      setState({ pi, lastTask, data: unwrap(vars), formKey, outcome, serviceName });
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, [processInstanceId, t]);

  useEffect(() => {
    load();
  }, [load]);

  useEffect(() => {
    if (state && onLoaded) {
      onLoaded({
        serviceName: state.serviceName,
        outcome: state.outcome,
        isInFlight: state.pi.endTime === null,
        processDefinitionKey: state.pi.processDefinitionKey,
      });
    }
  }, [state, onLoaded]);

  if (loading) {
    return (
      <div className="card">
        {topSlot && <div className="card-head">{topSlot}</div>}
        <p className="muted">{t('feedback.loadingProcess')}</p>
      </div>
    );
  }

  if (!state) {
    return (
      <div className="card">
        {topSlot && <div className="card-head">{topSlot}</div>}
        <p className="form-error">{error ?? t('errors.processNotFound')}</p>
      </div>
    );
  }

  const isInFlight = state.pi.endTime === null;
  const formId = parseFormId(state.formKey);
  const Form = formId ? formRegistry[formId] : undefined;
  const stubTask = synthesizeTask(state.lastTask, state.formKey);
  const stampDate = isInFlight ? (state.lastTask.endTime ?? state.pi.startTime) : state.pi.endTime!;

  return (
    <>
      {/* Progress first: "where is my case and how did it get here" is the
          question this read-only view usually answers — the submitted form
          below is the supporting detail. */}
      <ProcessTimeline processInstanceId={state.pi.id} />
      <div className="card">
        {!hideOwnHeader && (
          <>
            <div className="card-head">
              <h1 className="card-title">{translateBackendName(t, state.lastTask.name)}</h1>
              {topSlot}
            </div>
            <p className="muted">
              {isInFlight ? t('stamp.submitted') : t('stamp.completed')} {formatDateTime(stampDate)}{' '}
              · <strong>{state.outcome}</strong>
            </p>
          </>
        )}

        {Form ? (
          <Form
            task={stubTask}
            data={state.data}
            onComplete={() => Promise.resolve()}
            submitting={false}
            readOnly
          />
        ) : (
          <p className="form-error">
            <Trans
              t={t}
              i18nKey="errors.noFormRegistered"
              values={{ formKey: state.formKey ?? t('errors.formKeyNone') }}
              components={{ code: <code /> }}
            />
          </p>
        )}
      </div>
      {!hideDocuments && <DocumentsCard processInstanceId={state.pi.id} />}
    </>
  );
}
