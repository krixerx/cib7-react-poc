import { useCallback, useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { ArrowRight, Award, Download, FileText, FolderOpen, RotateCw, Search } from 'lucide-react';
import {
  documentLabel,
  isGenerated,
  getDownloadUrl,
  listMyDocuments,
  type CaseDocumentEntry,
} from '../api/documentsApi';
import {
  listHistoricProcessInstancesByStarter,
  listProcessDefinitions,
} from '../api/camundaClient';
import { useAuth } from '../auth/AuthProvider';
import { translateBackendName } from '../i18n/backendNames';
import { formatDate } from '../i18n/format';

/**
 * PartA — the applicant's "My files": every document of every case they
 * started, in one list. Certificates and other issued documents are the
 * reason to come here, so they are the default view; the applicant's own
 * uploads are one click away. Each row links back to its case.
 */

type Filter = 'issued' | 'uploaded' | 'all';

const FILTERS: Filter[] = ['issued', 'uploaded', 'all'];

/** Engine-generated documents use the `generated-*` categories. */
function isIssued(doc: CaseDocumentEntry): boolean {
  return isGenerated(doc.category);
}

function matchesFilter(doc: CaseDocumentEntry, filter: Filter): boolean {
  if (filter === 'all') return true;
  return filter === 'issued' ? isIssued(doc) : !isIssued(doc);
}

interface FileRow {
  doc: CaseDocumentEntry;
  categoryText: string;
  /** Service the case belongs to; null when the case is no longer in history. */
  serviceName: string | null;
}

export default function MyFilesPage() {
  const { t } = useTranslation('my-files');
  const { username } = useAuth();
  const [rows, setRows] = useState<FileRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [downloadError, setDownloadError] = useState<string | null>(null);
  const [filter, setFilter] = useState<Filter>('issued');
  const [query, setQuery] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [docs, instances, defs] = await Promise.all([
        listMyDocuments(),
        // Service names are a label only; the list works without them.
        listHistoricProcessInstancesByStarter(username).catch(() => []),
        listProcessDefinitions().catch(() => []),
      ]);
      const defById = new Map(defs.map((d) => [d.id, d]));
      const serviceByCase = new Map(
        instances.map((pi) => {
          const def = defById.get(pi.processDefinitionId);
          return [pi.id, def?.name ?? def?.key ?? pi.processDefinitionKey];
        }),
      );
      setRows(
        docs.map((doc) => {
          const service = serviceByCase.get(doc.processInstanceId);
          return {
            doc,
            categoryText: documentLabel(t, doc.category),
            serviceName: service ? translateBackendName(t, service) : null,
          };
        }),
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, [username, t]);

  useEffect(() => {
    load();
  }, [load]);

  const counts = useMemo(() => {
    const issued = rows.filter((r) => isIssued(r.doc)).length;
    return { issued, uploaded: rows.length - issued, all: rows.length };
  }, [rows]);

  const visible = useMemo(() => {
    const q = query.trim().toLocaleLowerCase();
    return rows.filter(
      (r) =>
        matchesFilter(r.doc, filter) &&
        (!q ||
          [r.doc.filename, r.categoryText, r.serviceName ?? ''].some((s) =>
            s.toLocaleLowerCase().includes(q),
          )),
    );
  }, [rows, filter, query]);

  async function handleDownload(id: string) {
    setDownloadError(null);
    try {
      const d = await getDownloadUrl(id);
      window.open(d.url, '_blank', 'noopener,noreferrer');
    } catch (e) {
      setDownloadError(
        t('errors.downloadFailed', { message: e instanceof Error ? e.message : '' }),
      );
    }
  }

  const showContent = !loading && !error;
  const caseCount = new Set(rows.map((r) => r.doc.processInstanceId)).size;

  return (
    <div className="mp mf">
      <div className="mp-head">
        <div>
          <h1 className="mp-title">{t('head.title')}</h1>
          {showContent && rows.length > 0 && (
            <p className="mp-kpi">
              <span className="mp-kpi-strong">{t('head.files', { count: rows.length })}</span>
              {' · '}
              {t('head.cases', { count: caseCount })}
            </p>
          )}
        </div>
        <button type="button" className="btn" onClick={load} disabled={loading}>
          <RotateCw aria-hidden="true" />
          {t('common:actions.refresh')}
        </button>
      </div>

      {loading && <p className="muted">{t('common:feedback.loading')}</p>}
      {error && <p className="form-error">{t('errors.loadFailed', { message: error })}</p>}

      {showContent && rows.length === 0 && (
        <div className="empty-state">
          <span className="empty-state-icon" aria-hidden="true">
            <FolderOpen size={26} />
          </span>
          <h2>{t('empty.title')}</h2>
          <p>{t('empty.body')}</p>
          <Link to="/" className="btn btn-primary">
            {t('empty.cta')}
            <ArrowRight aria-hidden="true" />
          </Link>
        </div>
      )}

      {showContent && rows.length > 0 && (
        <>
          <div className="mf-toolbar">
            <div className="mf-filters" role="group" aria-label={t('filters.label')}>
              {FILTERS.map((f) => (
                <button
                  key={f}
                  type="button"
                  className={`chip${filter === f ? ' active' : ''}`}
                  aria-pressed={filter === f}
                  onClick={() => setFilter(f)}
                >
                  {t(`filters.${f}`)} <span className="mf-count">{counts[f]}</span>
                </button>
              ))}
            </div>
            <label className="mf-search">
              <Search size={16} aria-hidden="true" />
              <input
                type="search"
                className="field-input"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder={t('search.placeholder')}
                aria-label={t('search.label')}
              />
            </label>
          </div>

          {downloadError && <p className="form-error">{downloadError}</p>}

          {visible.length === 0 ? (
            <p className="muted">{t('empty.filtered')}</p>
          ) : (
            <ul className="document-list mf-list">
              {visible.map((r) => (
                <li key={r.doc.id} className="document-row mf-row">
                  <span className="document-row-icon" aria-hidden="true">
                    {isIssued(r.doc) ? <Award size={18} /> : <FileText size={18} />}
                  </span>
                  <div className="document-row-meta">
                    <span className="document-row-title">{r.doc.filename}</span>
                    <span className="document-row-sub">
                      {[r.categoryText, r.serviceName, formatDate(r.doc.createdAt)]
                        .filter(Boolean)
                        .join(' · ')}
                    </span>
                  </div>
                  <span className="mf-row-actions">
                    <Link to={`/processes/${r.doc.processInstanceId}`} className="mf-case-link">
                      {t('row.openCase')}
                    </Link>
                    <button
                      type="button"
                      className="icon-btn"
                      onClick={() => handleDownload(r.doc.id)}
                      aria-label={`${t('common:actions.download')}: ${r.doc.filename}`}
                      title={t('common:actions.download')}
                    >
                      <Download aria-hidden="true" />
                    </button>
                  </span>
                </li>
              ))}
            </ul>
          )}
        </>
      )}
    </div>
  );
}
