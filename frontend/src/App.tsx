import { Routes, Route, Link, NavLink } from 'react-router-dom';
import ServicesPage from './pages/ServicesPage';
import TasksPage from './pages/TasksPage';
import TaskDetailPage from './pages/TaskDetailPage';

export default function App() {
  return (
    <div className="app">
      <header className="app-header">
        <Link to="/" className="app-title">
          CIB&nbsp;seven <span className="app-title-sep">·</span> React POC
        </Link>
        <nav className="app-nav">
          <NavLink to="/" end>
            Services
          </NavLink>
          <NavLink to="/tasks">Tasks</NavLink>
        </nav>
      </header>

      <main className="app-main">
        <Routes>
          <Route path="/" element={<ServicesPage />} />
          <Route path="/tasks" element={<TasksPage />} />
          <Route path="/tasks/:taskId" element={<TaskDetailPage />} />
        </Routes>
      </main>
    </div>
  );
}
