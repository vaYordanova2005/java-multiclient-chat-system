import { createContext, useCallback, useContext, useMemo, useRef, useState, type ReactNode } from 'react';
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
  // `remember` picks where the session is persisted: localStorage (default,
  // survives closing the browser) vs sessionStorage (cleared when the tab
  // closes) — the "Remember me" checkbox on LoginPage. Omitting it (e.g. the
  // internal re-login that follows a username change) reuses whichever
  // storage the current session already lives in, instead of silently
  // re-defaulting to "remembered".
  login: (session: AuthSession, remember?: boolean) => void;
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

function readFrom(storage: Storage): AuthSession | null {
  try {
    const raw = storage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const stored = JSON.parse(raw) as StoredSession;
    if (stored.expiresAt !== null && Date.now() >= stored.expiresAt) {
      storage.removeItem(STORAGE_KEY);
      return null;
    }
    return { token: stored.token, username: stored.username };
  } catch {
    return null;
  }
}

// localStorage (remembered) takes precedence over sessionStorage
// (this-tab-only) in the unlikely case both somehow hold a session.
function readInitialAuth(): { session: AuthSession | null; remember: boolean } {
  const remembered = readFrom(localStorage);
  if (remembered) return { session: remembered, remember: true };
  const tabOnly = readFrom(sessionStorage);
  if (tabOnly) return { session: tabOnly, remember: false };
  return { session: null, remember: true };
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [initialAuth] = useState(readInitialAuth);
  const [session, setSession] = useState<AuthSession | null>(initialAuth.session);
  const [logoutNotice, setLogoutNotice] = useState<LogoutNotice | null>(null);
  // Not state — it doesn't drive rendering, just which Storage the next
  // login() without an explicit `remember` should reuse.
  const rememberRef = useRef(initialAuth.remember);

  const login = useCallback((next: AuthSession, remember?: boolean) => {
    const effectiveRemember = remember ?? rememberRef.current;
    rememberRef.current = effectiveRemember;
    const stored: StoredSession = { ...next, expiresAt: decodeExpiry(next.token) };
    const storage = effectiveRemember ? localStorage : sessionStorage;
    const other = effectiveRemember ? sessionStorage : localStorage;
    other.removeItem(STORAGE_KEY);
    storage.setItem(STORAGE_KEY, JSON.stringify(stored));
    setSession(next);
    setLogoutNotice(null);
  }, []);

  const logout = useCallback((notice?: LogoutNotice) => {
    localStorage.removeItem(STORAGE_KEY);
    sessionStorage.removeItem(STORAGE_KEY);
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
