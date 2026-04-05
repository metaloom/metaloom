import React, { createContext, useContext, useState, useCallback } from "react";

interface AuthContextValue {
  isAuthenticated: boolean;
  username: string | null;
  login: (username: string, password: string) => boolean;
  logout: () => void;
}

const AuthContext = createContext<AuthContextValue>({
  isAuthenticated: false,
  username: null,
  login: () => false,
  logout: () => {},
});

export function useAuth() {
  return useContext(AuthContext);
}

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [isAuthenticated, setIsAuthenticated] = useState(false);
  const [username, setUsername] = useState<string | null>(null);

  const login = useCallback((user: string, pass: string) => {
    if (user === "admin@jotschi.de" && pass === "admin") {
      setIsAuthenticated(true);
      setUsername(user);
      return true;
    }
    return false;
  }, []);

  const logout = useCallback(() => {
    setIsAuthenticated(false);
    setUsername(null);
  }, []);

  return (
    <AuthContext.Provider value={{ isAuthenticated, username, login, logout }}>
      {children}
    </AuthContext.Provider>
  );
}
