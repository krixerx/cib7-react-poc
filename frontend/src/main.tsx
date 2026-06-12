import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter, Routes, Route } from 'react-router-dom';
import App from './App';
import { AuthProvider } from './auth/AuthProvider';
import LanguageSwitcher from './components/LanguageSwitcher';
import ConfirmOwnerPage from './pages/ConfirmOwnerPage';
import SignFounderPage from './pages/SignFounderPage';
import PayPage from './pages/PayPage';
import './i18n';
import './styles.css';

/**
 * The standalone email-link pages render without the App header, so they get
 * their own language switcher pinned to the top corner — an Arabic-speaking
 * recipient must be able to switch before reading the page.
 */
function Standalone({ children }: { children: React.ReactNode }) {
  return (
    <>
      <div className="standalone-lang">
        <LanguageSwitcher />
      </div>
      {children}
    </>
  );
}

/**
 * The `/confirm-owner/:token`, `/sign-founder/:token`, and
 * `/pay/:processInstanceId` routes bypass AuthProvider so the pages
 * are reachable from email links without a Keycloak session — the
 * per-participant UUID token (or process instance id, for the
 * payment page) in the URL is the credential. Every other route
 * falls through to the catch-all, which mounts the authenticated SPA.
 */
ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <BrowserRouter>
      <Routes>
        <Route
          path="/confirm-owner/:token"
          element={
            <Standalone>
              <ConfirmOwnerPage />
            </Standalone>
          }
        />
        <Route
          path="/sign-founder/:token"
          element={
            <Standalone>
              <SignFounderPage />
            </Standalone>
          }
        />
        <Route
          path="/pay/:processInstanceId"
          element={
            <Standalone>
              <PayPage />
            </Standalone>
          }
        />
        <Route
          path="*"
          element={
            <AuthProvider>
              <App />
            </AuthProvider>
          }
        />
      </Routes>
    </BrowserRouter>
  </React.StrictMode>,
);
