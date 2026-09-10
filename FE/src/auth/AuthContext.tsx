import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';
import { decodeExpiry } from './token';

interface AuthSession {
  token: string;
  username: string;
}

interface StoredSession extends AuthSession {
  // epoch millis, decoded from the token's own payload — not a security
  // boundary (BE re-verifies the signature on every request/handshake),
  // just lets the FE treat an expired token as "logged out" instead of
  // walking straight into a socket that will refuse to open.
  expiresAt: number | null;
}

export interface LogoutNotice {
  text: string;
  variant: 'success' | 'error';
}

interface AuthContextValue {
  session: AuthSession | null;
  login: (session: AuthSession) => void;
  // `notice` is surfaced by RequireAuth's own redirect (see RequireAuth.tsx)
  // instead of the caller separately calling useNavigate() itself — a
  // logout() that flips `session` to null and a same-tick explicit
  // navigate('/login', {state}) race: whichever's <Navigate> commits last
  // wins, and RequireAuth's route-driven one (no state) can silently
  // overwrite the caller's notice. Routing it all through one place removes
  // the race instead of trying to win it.
  logout: (notice?: LogoutNotice) => void;
  logoutNotice: LogoutNotice | null;
}

const STORAGE_KEY = 'messenger.auth';

const AuthContext = createContext<AuthContextValue | null>(null);

function readStoredSession(): AuthSession | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const stored = JSON.parse(raw) as StoredSession;
    if (stored.expiresAt !== null && Date.now() >= stored.expiresAt) {
      localStorage.removeItem(STORAGE_KEY);
      return null;
    }
    return { token: stored.token, username: stored.username };
  } catch {
    return null;
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<AuthSession | null>(readStoredSession);
  const [logoutNotice, setLogoutNotice] = useState<LogoutNotice | null>(null);

  const login = useCallback((next: AuthSession) => {
    const stored: StoredSession = { ...next, expiresAt: decodeExpiry(next.token) };
    localStorage.setItem(STORAGE_KEY, JSON.stringify(stored));
    setSession(next);
  }, []);

  const logout = useCallback((notice?: LogoutNotice) => {
    localStorage.removeItem(STORAGE_KEY);
    setSession(null);
    setLogoutNotice(notice ?? null);
  }, []);

  const value = useMemo(
    () => ({ session, login, logout, logoutNotice }),
    [session, login, logout, logoutNotice],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}
