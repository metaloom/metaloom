import React, { createContext, useContext, useState, useCallback, useEffect, useRef } from "react";
import { login as apiLogin, getMe, decodeJwt, isJwtExpired } from "../api/auth";
import { SESSION_EXPIRED_EVENT } from "../api/http";
import { useToast } from "./ToastContext";

interface AuthContextValue {
  isAuthenticated: boolean;
  username: string | null;
  userUuid: string | null;
  token: string | null;
  login: (username: string, password: string) => Promise<boolean>;
  logout: () => void;
}

const AuthContext = createContext<AuthContextValue>({
  isAuthenticated: false,
  username: null,
  userUuid: null,
  token: null,
  login: async () => false,
  logout: () => {},
});

export function useAuth() {
  return useContext(AuthContext);
}

/**
 * Where the session survives a reload.
 *
 * <p>Not a cookie, though the server does set one. `AuthenticationEndpointService` issues
 * `__Host-loom_token`, and `__Host-` implies `Secure`, so a browser talking to a deployment over
 * plain HTTP drops it on arrival — and even over TLS it is `HttpOnly`, which is the point of it:
 * script cannot read it, so it can authenticate an `<img>` but it can never tell this provider
 * who is signed in. Before this, the JWT lived in React state alone and F5 was indistinguishable
 * from signing out.</p>
 *
 * <p>`sessionStorage` rather than `localStorage`: per tab, and gone when the tab closes, which is
 * the same lifetime the user already believes a session has. A shared machine does not keep
 * somebody signed in overnight because they reloaded a page once.</p>
 */
const TOKEN_KEY = "loom.auth.token";

function readStoredToken(): string | null {
  try {
    const stored = window.sessionStorage.getItem(TOKEN_KEY);
    // An expired token is worse than none: it would render the app shell and then 401 on every
    // request in it, which is exactly the confusing state the focus check below exists to avoid.
    if (!stored || isJwtExpired(stored)) return null;
    return stored;
  } catch {
    // Private browsing, or storage disabled. Falls back to the old in-memory behaviour.
    return null;
  }
}

function writeStoredToken(token: string | null) {
  try {
    if (token) window.sessionStorage.setItem(TOKEN_KEY, token);
    else window.sessionStorage.removeItem(TOKEN_KEY);
  } catch {
    /* see readStoredToken */
  }
}

export function AuthProvider({ children }: { children: React.ReactNode }) {
  // Read synchronously in the initialiser, not in an effect: an effect runs after the first
  // render, and AuthGate would have already answered that first render with the login page.
  const [token, setToken] = useState<string | null>(readStoredToken);
  const [isAuthenticated, setIsAuthenticated] = useState(() => token !== null);
  const [username, setUsername] = useState<string | null>(null);
  const [userUuid, setUserUuid] = useState<string | null>(() => (token ? decodeJwt(token)?.uuid ?? null : null));
  const { showToast } = useToast();

  // The 401 listener and the focus check are registered once and must read the CURRENT token, not
  // the one captured when they were registered. A ref is the only thing both can see.
  const tokenRef = useRef<string | null>(null);
  tokenRef.current = token;

  const login = useCallback(async (user: string, pass: string) => {
    try {
      const response = await apiLogin(user, pass);
      setToken(response.token);
      writeStoredToken(response.token);
      setIsAuthenticated(true);
      setUsername(user);
      // Immediately derive the uuid from the JWT so the UI can gate authored
      // content (e.g. comment/reaction edit/delete) without waiting on a round-trip.
      setUserUuid(decodeJwt(response.token)?.uuid ?? null);
      // Then confirm authoritatively via /me. A failure here must not fail the login.
      try {
        const me = await getMe(response.token);
        setUserUuid(me.uuid);
      } catch {
        // Keep the JWT-derived uuid (if any) as a best-effort fallback.
      }
      return true;
    } catch {
      return false;
    }
  }, []);

  const logout = useCallback(() => {
    setIsAuthenticated(false);
    setUsername(null);
    setUserUuid(null);
    setToken(null);
    writeStoredToken(null);
  }, []);

  /**
   * Put a name and an authoritative uuid back on a session restored from storage.
   *
   * The JWT carries the uuid but not the username, so a reloaded tab would otherwise show an
   * empty account menu. A failure here is a dead token, and saying so once is better than letting
   * the next thing the user clicks answer 401.
   */
  const restored = useRef(false);
  useEffect(() => {
    if (restored.current || !token || username) return;
    restored.current = true;
    let cancelled = false;
    getMe(token)
      .then(me => {
        if (cancelled) return;
        setUserUuid(me.uuid);
        setUsername(me.username ?? null);
      })
      .catch(() => {
        if (!cancelled) logout();
      });
    return () => { cancelled = true; };
  }, [token, username, logout]);

  // --- The global 401 path ---
  //
  // Before this, `src/api/` had 36 independent response handlers and no shared notion of "the
  // session is gone", so an expired token produced a page of separately-failing widgets, each
  // with its own message, and none of them saying the one thing that was true.
  //
  // `expiring` guards against the pile-up in the other direction: ten parallel requests all
  // answer 401 and all dispatch the event, and the user must see one message, not ten. A ref
  // rather than state because the guard has to hold within a single tick, before any re-render.
  const expiring = useRef(false);
  const expireSession = useCallback(() => {
    if (expiring.current || !tokenRef.current) return;
    expiring.current = true;
    logout();
    showToast("Your session has expired. Please sign in again.", "warning");
    // Cleared on the next tick, not never: a user who signs back in and is expired again later
    // must get the message a second time.
    window.setTimeout(() => {
      expiring.current = false;
    }, 0);
  }, [logout, showToast]);

  useEffect(() => {
    window.addEventListener(SESSION_EXPIRED_EVENT, expireSession);
    return () => window.removeEventListener(SESSION_EXPIRED_EVENT, expireSession);
  }, [expireSession]);

  // Expire proactively rather than waiting for the next 401 to prove it.
  //
  // This is what finally calls `isJwtExpired`, which was written and then wired to nothing. The
  // focus listener is the case that matters: a laptop closed over a weekend comes back to a UI
  // that looks signed in and answers 401 to everything the user touches. Checking on focus turns
  // that into one honest message before they touch anything.
  useEffect(() => {
    if (!token) return undefined;
    const check = () => {
      if (tokenRef.current && isJwtExpired(tokenRef.current)) {
        expireSession();
      }
    };
    check();
    window.addEventListener("focus", check);
    return () => window.removeEventListener("focus", check);
  }, [token, expireSession]);

  return (
    <AuthContext.Provider value={{ isAuthenticated, username, userUuid, token, login, logout }}>
      {children}
    </AuthContext.Provider>
  );
}
