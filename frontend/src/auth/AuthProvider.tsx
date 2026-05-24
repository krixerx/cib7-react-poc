import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { keycloak } from './keycloak';

/**
 * Minimal auth context. `authenticated` flips to true once Keycloak's
 * `init({ onLoad: 'login-required' })` resolves with a session; until then
 * the provider renders a loading screen and Keycloak owns the page (it will
 * redirect to its hosted login form).
 */
interface AuthContextValue {
  username: string;
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

  const username =
    (keycloak.tokenParsed?.preferred_username as string | undefined) ??
    (keycloak.tokenParsed?.sub as string | undefined) ??
    'unknown';

  const logout = () => {
    keycloak.logout({ redirectUri: window.location.origin });
  };

  return <AuthContext.Provider value={{ username, logout }}>{children}</AuthContext.Provider>;
}

