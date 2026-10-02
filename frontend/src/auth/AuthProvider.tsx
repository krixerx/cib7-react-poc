import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { keycloak } from './keycloak';

/**
 * Auth context. Keycloak is initialised in `check-sso` mode so the SPA
 * renders even when the user is anonymous — the applicant lands on a
 * services-list page first ("what can I do here?") and only signs in when
 * they decide to start a service. The {@link PublicEngineRestSecurityConfig}
 * carve-out on the backend serves that list without a Bearer.
 *
 * `realmRoles` is the `realm_access.roles` array from the access token. The
 * SPA uses it to decide whether to show the PartA (applicant) or PartB
 * (civil-servant / back office) UI — `applicant` vs `civil-servant` realm
 * roles map directly to those parts. A user with both (e.g. an admin) sees
 * PartB by default. Anonymous users see PartA with auth-required actions
 * gated behind a login redirect.
 */
interface AuthContextValue {
  authenticated: boolean;
  username: string;
  realmRoles: string[];
  isApplicant: boolean;
  isCivilServant: boolean;
  /** `statistics-viewer` realm role; all of PartB get it through the civil-servant group. */
  canViewStatistics: boolean;
  login: () => void;
  register: () => void;
  logout: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error('useAuth must be used inside <AuthProvider>');
  }
  return ctx;
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const { t, i18n } = useTranslation('components');
  const [ready, setReady] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    // React 18 StrictMode double-invokes effects in dev. Keycloak's init
    // is not idempotent — calling it twice throws "A 'Keycloak' instance
    // can only be initialized once". The guard keeps the second call a no-op.
    if (keycloak.didInitialize) {
      setReady(true);
      return;
    }
    keycloak
      .init({
        onLoad: 'check-sso',
        pkceMethod: 'S256',
        checkLoginIframe: false,
      })
      .then(() => {
        // `/?auth=register` is the sign-up link handed out by the MCP sidecar
        // (get_signup_url). It cannot point at Keycloak's registration
        // endpoint itself, because cib7-frontend requires PKCE and only
        // keycloak-js holds the verifier, so the SPA starts the flow here.
        const wantsRegister =
          new URLSearchParams(window.location.search).get('auth') === 'register';
        if (wantsRegister && !keycloak.authenticated) {
          void keycloak.register({ redirectUri: window.location.origin + '/' });
          return;
        }
        setReady(true);
      })
      .catch((e) => {
        setError(e instanceof Error ? e.message : String(e));
      });
  }, []);

  if (error) {
    return (
      <div className="card">
        <h1 className="card-title">{t('auth.loginFailed')}</h1>
        <p className="form-error">{error}</p>
      </div>
    );
  }

  if (!ready) {
    return (
      <div className="card">
        <p className="muted">{t('common:feedback.loading')}</p>
      </div>
    );
  }

  const authenticated = keycloak.authenticated === true;
  const token = keycloak.tokenParsed as
    { preferred_username?: string; sub?: string; realm_access?: { roles?: string[] } } | undefined;

  const username = authenticated ? (token?.preferred_username ?? token?.sub ?? 'unknown') : '';
  const realmRoles = authenticated ? (token?.realm_access?.roles ?? []) : [];
  const isCivilServant = realmRoles.includes('civil-servant');
  // A user is treated as a pure applicant only when they're not also a
  // civil servant — admins (Homer) carry both roles in dev seeds.
  const isApplicant = realmRoles.includes('applicant') && !isCivilServant;
  const canViewStatistics = realmRoles.includes('statistics-viewer');

  // Carry the SPA's chosen language into Keycloak. keycloak-js maps `locale`
  // to the OIDC `ui_locales` auth param, so the login/register pages render in
  // the same language the user picked here (EN/AR) — provided the realm lists
  // it in supportedLocales. Without this the login window always falls back to
  // the realm default (English).
  const uiLocale = i18n.resolvedLanguage ?? i18n.language;
  const login = () => {
    keycloak.login({ redirectUri: window.location.href, locale: uiLocale });
  };
  const register = () => {
    keycloak.register({ redirectUri: window.location.href, locale: uiLocale });
  };
  const logout = () => {
    keycloak.logout({ redirectUri: window.location.origin });
  };

  return (
    <AuthContext.Provider
      value={{
        authenticated,
        username,
        realmRoles,
        isApplicant,
        isCivilServant,
        canViewStatistics,
        login,
        register,
        logout,
      }}
    >
      {children}
    </AuthContext.Provider>
  );
}
