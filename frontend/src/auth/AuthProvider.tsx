import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { keycloak } from './keycloak';

/**
 * Auth context. The session is loaded eagerly via Keycloak's
 * `init({ onLoad: 'login-required' })`; until that resolves the provider
 * renders a loading screen and Keycloak owns the page (it will redirect to
 * the hosted login form).
 *
 * `realmRoles` is the `realm_access.roles` array from the access token. The
 * SPA uses it to decide whether to show the PartA (applicant) or PartB
 * (civil-servant / back office) UI — `applicant` vs `civil-servant` realm
 * roles map directly to those parts. A user with both (e.g. an admin) sees
 * PartB by default.
 */
interface AuthContextValue {
  username: string;
  realmRoles: string[];
  isApplicant: boolean;
  isCivilServant: boolean;
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
        onLoad: 'login-required',
        pkceMethod: 'S256',
        checkLoginIframe: false,
      })
      .then((authenticated) => {
        if (!authenticated) {
          // Should not happen with onLoad:'login-required' — Keycloak redirects.
          setError('Authentication failed — Keycloak did not return a session.');
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
        <h1 className="card-title">Login failed</h1>
        <p className="form-error">{error}</p>
      </div>
    );
  }

  if (!ready) {
    return (
      <div className="card">
        <p className="muted">Signing you in…</p>
      </div>
    );
  }

  const token = keycloak.tokenParsed as
    | { preferred_username?: string; sub?: string; realm_access?: { roles?: string[] } }
    | undefined;

  const username = token?.preferred_username ?? token?.sub ?? 'unknown';
  const realmRoles = token?.realm_access?.roles ?? [];
  const isCivilServant = realmRoles.includes('civil-servant');
  // A user is treated as a pure applicant only when they're not also a
  // civil servant — admins (Homer) carry both roles in dev seeds.
  const isApplicant = realmRoles.includes('applicant') && !isCivilServant;

  const logout = () => {
    keycloak.logout({ redirectUri: window.location.origin });
  };

  return (
    <AuthContext.Provider value={{ username, realmRoles, isApplicant, isCivilServant, logout }}>
      {children}
    </AuthContext.Provider>
  );
}
