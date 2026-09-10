import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';

interface AuthSession {
  token: string;
  username: string;
}

interface StoredSession extends AuthSession {
  // epoch millis, decoded from the token's own payload (see TokenService.java —
  // format is base64url(JSON payload incl. "exp").base64url(signature)). Not a
  // security boundary (BE re-verifies the signature on every request/handshake),
  // just lets the FE treat an expired token as "logged out" instead of walking
  // straight into a socket that will refuse to open.
  expiresAt: number | null;
}

interface AuthContextValue {
  session: AuthSession | null;
  login: (session: AuthSession) => void;
  logout: () => void;
}

const STORAGE_KEY = 'messenger.auth';

const AuthContext = createContext<AuthContextValue | null>(null);

function decodeExpiry(token: string): number | null {
  try {
    const payloadB64 = token.split('.')[0];
    if (!payloadB64) return null;
    const base64 = payloadB64.replace(/-/g, '+').replace(/_/g, '/');
    const padded = base64.padEnd(Math.ceil(base64.length / 4) * 4, '=');
    const payload = JSON.parse(atob(padded)) as { exp?: unknown };
    return typeof payload.exp === 'number' ? payload.exp : null;
  } catch {
    return null;
  }
}

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

  const login = useCallback((next: AuthSession) => {
    const stored: StoredSession = { ...next, expiresAt: decodeExpiry(next.token) };
    localStorage.setItem(STORAGE_KEY, JSON.stringify(stored));
    setSession(next);
  }, []);

  const logout = useCallback(() => {
    localStorage.removeItem(STORAGE_KEY);
    setSession(null);
  }, []);

  const value = useMemo(() => ({ session, login, logout }), [session, login, logout]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}
