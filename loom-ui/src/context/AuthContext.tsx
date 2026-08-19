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

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [isAuthenticated, setIsAuthenticated] = useState(false);
  const [username, setUsername] = useState<string | null>(null);
  const [userUuid, setUserUuid] = useState<string | null>(null);
  const [token, setToken] = useState<string | null>(null);
  const { showToast } = useToast();

  // The 401 listener and the focus check are registered once and must read the CURRENT token, not
  // the one captured when they were registered. A ref is the only thing both can see.
  const tokenRef = useRef<string | null>(null);
  tokenRef.current = token;

  const login = useCallback(async (user: string, pass: string) => {
    try {
      const response = await apiLogin(user, pass);
      setToken(response.token);
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
  }, []);

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
