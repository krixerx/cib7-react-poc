import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter, Routes, Route } from 'react-router-dom';
import { LabelProvider, StyleProvider } from '@tedi-design-system/react/tedi';
import { ThemeProvider } from '@mui/material/styles';
import App from './App';
import { AuthProvider } from './auth/AuthProvider';
import LanguageSwitcher from './components/LanguageSwitcher';
import ConfirmOwnerPage from './pages/ConfirmOwnerPage';
import SignFounderPage from './pages/SignFounderPage';
import PayPage from './pages/PayPage';
import { muiTheme } from './theme/mui';
import './i18n';
// TEDI base styles load before styles.css so portal overrides keep winning.
import '@tedi-design-system/react/index.css';
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
/**
 * TEDI providers wrap the whole tree: StyleProvider wires what-input focus
 * handling, LabelProvider supplies TEDI-internal labels. TEDI ships only
 * et/en/ru, so the locale is pinned to 'en' — under Arabic the app's own
 * i18n still switches while TEDI-internal microcopy stays English.
 * The MUI ThemeProvider brands the MUI components TEDI doesn't cover.
 */
ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <StyleProvider>
      <LabelProvider locale="en">
        <ThemeProvider theme={muiTheme}>
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
        </ThemeProvider>
      </LabelProvider>
    </StyleProvider>
  </React.StrictMode>,
);
