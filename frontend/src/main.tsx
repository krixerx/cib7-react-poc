import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter, Routes, Route } from 'react-router-dom';
import App from './App';
import { AuthProvider } from './auth/AuthProvider';
import ConfirmOwnerPage from './pages/ConfirmOwnerPage';
import SignFounderPage from './pages/SignFounderPage';
import './styles.css';

/**
 * The `/confirm-owner/:token` and `/sign-founder/:token` routes bypass
 * AuthProvider so the pages are reachable from email links without a
 * Keycloak session — the per-participant UUID token in the URL is the
 * credential. Every other route falls through to the catch-all, which
 * mounts the authenticated SPA.
 */
ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <BrowserRouter>
      <Routes>
        <Route path="/confirm-owner/:token" element={<ConfirmOwnerPage />} />
        <Route path="/sign-founder/:token" element={<SignFounderPage />} />
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
