import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Routes, Route, Link, NavLink, Navigate, useLocation } from 'react-router-dom';
import { Landmark, LogOut } from 'lucide-react';
import OfficialBanner from './components/OfficialBanner';
import LanguageSwitcher from './components/LanguageSwitcher';
import ThemeToggle from './components/ThemeToggle';
import SiteFooter from './components/SiteFooter';
import ServicesPage from './pages/ServicesPage';
import TasksPage from './pages/TasksPage';
import TaskDetailPage from './pages/TaskDetailPage';
import CompletedProcessPage from './pages/CompletedProcessPage';
import IncidentsPage from './pages/IncidentsPage';
import MyProcessesPage from './pages/MyProcessesPage';
import MyFilesPage from './pages/MyFilesPage';
import StatisticsPage from './pages/StatisticsPage';
import { useAuth } from './auth/AuthProvider';
import { countHistoricProcessInstancesByStarter } from './api/camundaClient';

/**
 * Role-based shell. Anonymous visitors see Services only (catalogue browsing).
 * Applicants (PartA) see Services, My cases and My files. Civil servants (PartB) see
 * Tasks + Incidents. Statistics follow the `statistics-viewer` role rather
 * than the PartB role, so Keycloak alone decides who sees them. The task-detail and completed-process pages are shared —
 * both roles open the same form pages, just for tasks they're allowed to touch.
 */
export default function App() {
  const { t } = useTranslation();
  const { authenticated, username, isCivilServant, canViewStatistics, login, register, logout } =
    useAuth();
  const location = useLocation();

  // "My processes" count badge. Refetched whenever the path changes so the
  // number stays in sync with the page: starting a new case from /, opening
  // a task, or coming back to /my-processes all retrigger it. Silently
  // falls back to null on error (e.g. transient 401 during token refresh)
  // rather than blowing up the nav.
  const [myProcessCount, setMyProcessCount] = useState<number | null>(null);
  useEffect(() => {
    if (!authenticated || isCivilServant || !username) {
      setMyProcessCount(null);
      return;
    }
    let cancelled = false;
    countHistoricProcessInstancesByStarter(username)
      .then((r) => {
        if (!cancelled) setMyProcessCount(r.count);
      })
      .catch(() => {
        if (!cancelled) setMyProcessCount(null);
      });
    return () => {
      cancelled = true;
    };
  }, [authenticated, isCivilServant, username, location.pathname]);

  // Scrolled state gives the floating header bar its shadow.
  const [scrolled, setScrolled] = useState(false);
  useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 8);
    onScroll();
    window.addEventListener('scroll', onScroll, { passive: true });
    return () => window.removeEventListener('scroll', onScroll);
  }, []);

  const statisticsLink = canViewStatistics && (
    <NavLink to="/statistics">{t('app.nav.statistics')}</NavLink>
  );

  const navLinks = !authenticated ? (
    <NavLink to="/" end>
      {t('app.nav.services')}
    </NavLink>
  ) : isCivilServant ? (
    <>
      <NavLink to="/" end>
        {t('app.nav.tasks')}
      </NavLink>
      <NavLink to="/incidents">{t('app.nav.incidents')}</NavLink>
      {statisticsLink}
    </>
  ) : (
    <>
      <NavLink to="/" end>
        {t('app.nav.services')}
      </NavLink>
      <NavLink to="/my-processes">
        {t('app.nav.myProcesses')}
        {myProcessCount !== null && <span className="nav-badge">{myProcessCount}</span>}
      </NavLink>
      <NavLink to="/my-files">{t('app.nav.myFiles')}</NavLink>
      {statisticsLink}
    </>
  );

  return (
    <div className={`app ${isCivilServant ? 'app-backoffice' : 'app-public'}`}>
      <a className="skip-link" href="#main">
        {t('app.skipToContent')}
      </a>
      <OfficialBanner />
      <div className="app-mesh" aria-hidden="true">
        <i />
        <i />
        <i />
        <i />
      </div>
      <header className={`app-header${scrolled ? ' is-scrolled' : ''}`}>
        <div className="shell-wrap">
          <div className="app-bar glass">
            <Link to="/" className="brand">
              <span className="brand-mark" aria-hidden="true">
                <Landmark />
              </span>
              <span className="brand-text">
                <span className="brand-name">{t('app.brandName')}</span>
                <span className="brand-sub">
                  {authenticated && isCivilServant ? t('app.roleBackOffice') : t('app.brandSub')}
                </span>
              </span>
            </Link>
            <nav className="app-nav" aria-label={t('app.nav.label')}>
              {navLinks}
            </nav>
            <div className="app-bar-end">
              <LanguageSwitcher />
              <ThemeToggle />
              {authenticated ? (
                <span className="app-user">
                  <span className="app-user-avatar" aria-hidden="true">
                    {(username ?? '?').slice(0, 1).toUpperCase()}
                  </span>
                  <span className="app-user-name">{username}</span>
                  <button
                    type="button"
                    className="icon-btn"
                    onClick={logout}
                    aria-label={t('app.auth.logOut')}
                    title={t('app.auth.logOut')}
                  >
                    <LogOut aria-hidden="true" />
                  </button>
                </span>
              ) : (
                <span className="app-user">
                  <button type="button" className="btn btn-ghost app-register" onClick={register}>
                    {t('app.auth.register')}
                  </button>
                  <button type="button" className="btn btn-primary" onClick={login}>
                    {t('app.auth.logIn')}
                  </button>
                </span>
              )}
            </div>
          </div>
          <nav className="app-nav app-nav-mobile" aria-label={t('app.nav.label')}>
            {navLinks}
          </nav>
        </div>
      </header>

      <main className="app-main" id="main">
        <Routes>
          {!authenticated ? (
            <Route path="/" element={<ServicesPage />} />
          ) : isCivilServant ? (
            <>
              <Route path="/" element={<TasksPage />} />
              <Route path="/incidents" element={<IncidentsPage />} />
            </>
          ) : (
            <>
              <Route path="/" element={<ServicesPage />} />
              <Route path="/my-processes" element={<MyProcessesPage />} />
              <Route path="/my-files" element={<MyFilesPage />} />
            </>
          )}
          {canViewStatistics && <Route path="/statistics" element={<StatisticsPage />} />}
          <Route path="/tasks/:taskId" element={<TaskDetailPage />} />
          <Route path="/processes/:processInstanceId" element={<CompletedProcessPage />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>
      <SiteFooter />
    </div>
  );
}
