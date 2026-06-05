import { Routes, Route, Link, NavLink, Navigate } from 'react-router-dom';
import ServicesPage from './pages/ServicesPage';
import TasksPage from './pages/TasksPage';
import TaskDetailPage from './pages/TaskDetailPage';
import CompletedProcessPage from './pages/CompletedProcessPage';
import IncidentsPage from './pages/IncidentsPage';
import MyProcessesPage from './pages/MyProcessesPage';
import { useAuth } from './auth/AuthProvider';

/**
 * Role-based shell. Anonymous visitors see Services only (catalogue browsing).
 * Applicants (PartA) see Services + My processes. Civil servants (PartB) see
 * Tasks + Incidents. The task-detail and completed-process pages are shared —
 * both roles open the same form pages, just for tasks they're allowed to touch.
 */
export default function App() {
  const { authenticated, username, isCivilServant, login, register, logout } = useAuth();
  const part = isCivilServant ? 'B' : 'A';
  const partLabel = isCivilServant ? 'Back office' : 'Applicant';

  return (
    <div className="app">
      <header className="app-header">
        <Link to="/" className="app-title">
          CIB&nbsp;seven <span className="app-title-sep">·</span> React POC
          {authenticated && (
            <>
              <span className="app-title-sep">·</span>{' '}
              <span className={`part-badge part-${part.toLowerCase()}`}>Part {part}</span>{' '}
              <span className="muted">{partLabel}</span>
            </>
          )}
        </Link>
        <nav className="app-nav">
          {!authenticated ? (
            <NavLink to="/" end>
              Services
            </NavLink>
          ) : isCivilServant ? (
            <>
              <NavLink to="/" end>
                Tasks
              </NavLink>
              <NavLink to="/incidents">Incidents</NavLink>
            </>
          ) : (
            <>
              <NavLink to="/" end>
                Services
              </NavLink>
              <NavLink to="/my-processes">My processes</NavLink>
            </>
          )}
          <span className="app-user">
            {authenticated ? (
              <>
                <span className="muted">{username}</span>
                <button className="btn btn-link" onClick={logout}>
                  Log out
                </button>
              </>
            ) : (
              <>
                <button className="btn btn-link" onClick={register}>
                  Register
                </button>
                <button className="btn btn-link" onClick={login}>
                  Log in
                </button>
              </>
            )}
          </span>
        </nav>
      </header>

      <main className="app-main">
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
            </>
          )}
          <Route path="/tasks/:taskId" element={<TaskDetailPage />} />
          <Route path="/processes/:processInstanceId" element={<CompletedProcessPage />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>
    </div>
  );
}
