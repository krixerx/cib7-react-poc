import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter, Routes, Route } from 'react-router-dom';
import App from './App';
import { AuthProvider } from './auth/AuthProvider';
import LanguageSwitcher from './components/LanguageSwitcher';
import ThemeToggle from './components/ThemeToggle';
import PublicFrame from './components/PublicFrame';
import ConsentPage from './pages/ConsentPage';
import PayPage from './pages/PayPage';
import MockBankPage from './pages/MockBankPage';
import './theme/colorScheme';
import i18n, { SUPPORTED_LANGS } from './i18n';
import { loadBrand } from './pack/brand';
import { loadPack } from './pack/catalog';
import './styles/tokens.css';
import './styles/base.css';
import './styles/shell.css';
import './styles/landing.css';
import './styles/cases.css';
import './styles/forms.css';
import './styles/backoffice.css';
import './styles/public.css';

/**
 * The mock bank stands in for the payment provider's own site, so it gets no
 * portal chrome, only the language and theme switches pinned to a corner.
 */
function Standalone({ children }: { children: React.ReactNode }) {
  return (
    <>
      <div className="standalone-lang glass">
        <LanguageSwitcher />
        <ThemeToggle />
      </div>
      {children}
    </>
  );
}

/**
 * The `/consent/:purpose/:token` (co-signing, one page for every purpose the
 * pack declares) and `/pay/:token` routes bypass AuthProvider so the pages are reachable from email links
 * without a Keycloak session: the engine-signed capability token in the URL
 * is the credential (docs/security.md rule 3). `/mock-bank/:sessionId` is
 * the demo payment provider's page, which stands in for an external site.
 * Every other route falls through to the catch-all, which mounts the
 * authenticated SPA.
 */
const root = ReactDOM.createRoot(document.getElementById('root')!);

/**
 * The service pack (catalog, form texts, display names, branding) is read
 * before the first render, so no screen shows a pack text key or the core
 * colours first. Neither loader throws: without a pack the SPA still starts,
 * on core texts and the core look.
 */
void Promise.all([loadPack(i18n, SUPPORTED_LANGS), loadBrand(i18n, SUPPORTED_LANGS)]).then(() =>
  root.render(
    <React.StrictMode>
      <BrowserRouter>
        <Routes>
          <Route
            path="/consent/:purpose/:token"
            element={
              <PublicFrame>
                <ConsentPage />
              </PublicFrame>
            }
          />
          <Route
            path="/pay/:token"
            element={
              <PublicFrame>
                <PayPage />
              </PublicFrame>
            }
          />
          <Route
            path="/mock-bank/:sessionId"
            element={
              <Standalone>
                <MockBankPage />
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
  ),
);
