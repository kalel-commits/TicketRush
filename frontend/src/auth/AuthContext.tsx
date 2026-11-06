import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { api, setToken, UNAUTHORIZED_EVENT } from '../api/client';

/**
 * JWT in memory + sessionStorage (demo scope, spec §23.1): survives a reload in the same tab, gone when the tab
 * closes. The README notes the trade-off against an httpOnly cookie.
 */
const KEY = 'ticketrush.token';
const EMAIL_KEY = 'ticketrush.email';   // display only (the nav's account chip); never used for auth

interface AuthState {
  token: string | null;
  email: string | null;
  login: (email: string, password: string) => Promise<void>;
  register: (email: string, password: string) => Promise<void>;
  logout: () => void;
}

const AuthContext = createContext<AuthState | null>(null);

function initialToken(): string | null {
  try {
    return sessionStorage.getItem(KEY);
  } catch {
    return null;
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setTokenState] = useState<string | null>(() => {
    const t = initialToken();
    setToken(t);
    return t;
  });

  const [email, setEmail] = useState<string | null>(() => {
    try { return sessionStorage.getItem(EMAIL_KEY); } catch { return null; }
  });

  const store = useCallback((t: string | null) => {
    setToken(t);
    setTokenState(t);
    try {
      if (t) sessionStorage.setItem(KEY, t);
      else sessionStorage.removeItem(KEY);
    } catch {
      // storage unavailable: in-memory only
    }
  }, []);

  const login = useCallback(
    async (email: string, password: string) => {
      const r = await api<{ accessToken: string }>('/api/users/login', { method: 'POST', body: { email, password } });
      store(r.accessToken);
      setEmail(email);
      try { sessionStorage.setItem(EMAIL_KEY, email); } catch { /* in-memory only */ }
    },
    [store],
  );

  const register = useCallback(
    async (email: string, password: string) => {
      await api('/api/users/register', { method: 'POST', body: { email, password } });
      await login(email, password);
    },
    [login],
  );

  const logout = useCallback(() => {
    store(null);
    setEmail(null);
    try { sessionStorage.removeItem(EMAIL_KEY); } catch { /* in-memory only */ }
  }, [store]);
  useEffect(() => {
    window.addEventListener(UNAUTHORIZED_EVENT, logout);
    return () => window.removeEventListener(UNAUTHORIZED_EVENT, logout);
  }, [logout]);
  const value = useMemo(() => ({ token, email: token ? email : null, login, register, logout }), [token, email, login, register, logout]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth outside AuthProvider');
  return ctx;
}
