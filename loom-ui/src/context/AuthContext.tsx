import React, { createContext, useContext, useState, useCallback } from "react";
import { login as apiLogin, getMe, decodeJwt } from "../api/auth";

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

  return (
    <AuthContext.Provider value={{ isAuthenticated, username, userUuid, token, login, logout }}>
      {children}
    </AuthContext.Provider>
  );
}
