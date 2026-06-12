import { useCallback, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../auth/AuthProvider';
import ProcessHistoryView from './ProcessHistoryView';
import CaseDetailLayout from '../components/CaseDetailLayout';
import { categoryOf } from '../services/categories';

/**
 * Route wrapper around ProcessHistoryView for the deep-link
 * `/processes/:processInstanceId`. Wraps the view in CaseDetailLayout so
 * the page shows a category-tinted top rail (back · service eyebrow ·
 * status pill) above a two-column grid with a sticky Documents sidebar.
 * The civil-servant worklist still embeds the same ProcessHistoryView in
 * its right pane — but inline, with no extra chrome, since the worklist
 * already wraps it.
 *
 * Back target adapts to who's viewing: civil servants land on the
 * worklist root; applicants land on My processes.
 */
export default function CompletedProcessPage() {
  const { t } = useTranslation('process-detail');
  const { processInstanceId } = useParams<{ processInstanceId: string }>();
  const navigate = useNavigate();
  const { isCivilServant } = useAuth();
  const backPath = isCivilServant ? '/' : '/my-processes';

  const [info, setInfo] = useState<{
    serviceName: string;
    outcome: string;
    isInFlight: boolean;
    processDefinitionKey: string;
  } | null>(null);

  const onLoaded = useCallback(
    (i: {
      serviceName: string;
      outcome: string;
      isInFlight: boolean;
      processDefinitionKey: string;
    }) => setInfo(i),
    [],
  );

  if (!processInstanceId) {
    return (
      <div className="card">
        <p className="form-error">{t('errors.noProcessId')}</p>
      </div>
    );
  }

  return (
    <CaseDetailLayout
      category={info ? categoryOf(info.processDefinitionKey) : null}
      serviceName={info?.serviceName ?? null}
      title={t('header.title')}
      outcome={info?.outcome ?? null}
      isInFlight={info?.isInFlight ?? false}
      onBack={() => navigate(backPath)}
      processInstanceId={processInstanceId}
    >
      <ProcessHistoryView
        processInstanceId={processInstanceId}
        hideDocuments
        hideOwnHeader
        onLoaded={onLoaded}
      />
    </CaseDetailLayout>
  );
}
