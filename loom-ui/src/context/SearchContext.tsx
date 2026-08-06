import React, { createContext, useCallback, useContext, useEffect, useState } from "react";
import { SearchApiError, searchStatus } from "../api/search";
import type { SearchCapability } from "../types";
import { useAuth } from "./AuthContext";

interface SearchContextValue {
  /**
   * Whether a search box should be drawn at all.
   *
   * False until `/search/status` says otherwise, and false again after any failure. This is the
   * opposite polarity to `NodeRegistryContext`, which treats unknown as available: an unknown
   * node must stay authorable, but a search field that answers 503 on every keystroke is worse
   * than no search field.
   */
  available: boolean;
  /** Which backend is bound: `postgres`, `elasticsearch`, `none`, or `unknown` before we know. */
  provider: string;
  capabilities: SearchCapability[];
  /** Why search is unavailable, when the server said. Shown in the explanatory panel. */
  reason?: string;
  /** Whether the initial status call is still in flight. */
  loading: boolean;
  /** Whether the bound backend advertises a capability. Unknown reads as false. */
  has: (capability: SearchCapability) => boolean;
  /**
   * Flip to unavailable after a query answered 503 mid-session.
   *
   * The sidebar field and the results panel are separate components, so only a shared flag can
   * retract both at once.
   */
  markUnavailable: (reason?: string) => void;
  /** Re-ask the server. */
  refresh: () => void;
}

const SearchContext = createContext<SearchContextValue>({
  available: false,
  provider: "unknown",
  capabilities: [],
  reason: undefined,
  loading: true,
  has: () => false,
  markUnavailable: () => {},
  refresh: () => {},
});

export function useSearch() {
  return useContext(SearchContext);
}

/**
 * Holds the answer to "should this deployment show a search box?".
 *
 * One call per login. `/search/status` answers 200 even when search is down, so the question
 * itself does not fail — but it is gated on READ_SEARCH and can still answer 403, which is a
 * legitimate permission state rather than an error worth toasting.
 *
 * Not to be confused with the backend's `SearchProvider` SPI, which is the thing this reports on.
 */
export function SearchProvider({ children }: { children: React.ReactNode }) {
  const [available, setAvailable] = useState(false);
  const [provider, setProvider] = useState("unknown");
  const [capabilities, setCapabilities] = useState<SearchCapability[]>([]);
  const [reason, setReason] = useState<string | undefined>(undefined);
  const [loading, setLoading] = useState(true);
  const [reloadCount, setReloadCount] = useState(0);
  const { token } = useAuth();

  useEffect(() => {
    if (!token) return;
    let cancelled = false;
    setLoading(true);
    searchStatus(token)
      .then((status) => {
        if (cancelled) return;
        setAvailable(status.available);
        setProvider(status.provider);
        setCapabilities(status.capabilities ?? []);
        setReason(status.reason);
      })
      .catch((err) => {
        if (cancelled) return;
        // A 403 (no READ_SEARCH), a network failure and a malformed answer are the same thing
        // from here: we cannot promise a working search box, so we do not draw one. This must
        // never propagate — a broken search backend has never been allowed to break the app.
        console.debug("Search status could not be read; hiding search", err);
        setAvailable(false);
        setCapabilities([]);
        setProvider("unknown");
        setReason(err instanceof SearchApiError ? err.body : undefined);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [token, reloadCount]);

  const has = useCallback(
    (capability: SearchCapability) => capabilities.includes(capability),
    [capabilities],
  );

  const markUnavailable = useCallback((nextReason?: string) => {
    setAvailable(false);
    setReason(nextReason);
  }, []);

  const refresh = useCallback(() => setReloadCount((count) => count + 1), []);

  return (
    <SearchContext.Provider
      value={{ available, provider, capabilities, reason, loading, has, markUnavailable, refresh }}
    >
      {children}
    </SearchContext.Provider>
  );
}
