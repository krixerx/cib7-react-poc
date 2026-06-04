import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter, Routes, Route } from 'react-router-dom';
import App from './App';
import { AuthProvider } from './auth/AuthProvider';
import ConfirmOwnerPage from './pages/ConfirmOwnerPage';
import './styles.css';

/**
 * The `/confirm-owner/:token` route bypasses AuthProvider so the page is
 * reachable from email links without a Keycloak session — the per-owner
 * UUID token in the URL is the credential. Every other route falls through
 * to the catch-all, which mounts the authenticated SPA.
 */
ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <BrowserRouter>
      <Routes>
        <Route path="/confirm-owner/:token" element={<ConfirmOwnerPage />} />
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
