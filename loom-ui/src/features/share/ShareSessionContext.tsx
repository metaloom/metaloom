import React, { createContext, useCallback, useContext, useMemo, useState } from "react";
import type { ShareSessionResponse } from "../../api/shares";

/**
 * The redeemed share session, for the duration of one customer visit.
 *
 * Deliberately narrower than `AuthContext`: this is not a user. It holds the opaque token that
 * proves a link's password was satisfied, the name the visitor is known by, and what the link
 * lets them do — nothing that could be mistaken for an account.
 */
export interface ShareSession {
  slug: string;
  token: string;
  visitorName: string;
  targetType: "ASSET" | "COLLECTION";
  targetName?: string;
  targetDescription?: string;
  allowDownload: boolean;
  showMetadata: boolean;
  allowComments: boolean;
  allowReactions: boolean;
  allowAnnotations: boolean;
}

interface ShareSessionContextValue {
  session: ShareSession | null;
  setSession: (session: ShareSession) => void;
  clearSession: () => void;
}

const ShareSessionContext = createContext<ShareSessionContextValue>({
  session: null,
  setSession: () => {},
  clearSession: () => {},
});

/**
 * `sessionStorage`, not `localStorage`, and keyed per slug.
 *
 * Per slug because one browser may hold sessions for several links at once and they must not
 * overwrite each other. `sessionStorage` because a review link often arrives on a shared or
 * borrowed machine, and a credential that survives closing the tab is a credential the next person
 * inherits. The cost is re-entering the password after a browser restart, which is the right trade
 * for something that was emailed around.
 */
function storageKey(slug: string): string {
  return `loom.share.${slug}`;
}

export function readStoredSession(slug: string): ShareSession | null {
  try {
    const raw = window.sessionStorage.getItem(storageKey(slug));
    return raw ? (JSON.parse(raw) as ShareSession) : null;
  } catch {
    // A private-browsing mode that refuses storage, or a stored value from an older shape. Either
    // way the visitor simply opens the link again; failing here would blank the page instead.
    return null;
  }
}

function writeStoredSession(session: ShareSession): void {
  try {
    window.sessionStorage.setItem(storageKey(session.slug), JSON.stringify(session));
  } catch {
    // Not being able to remember the session is survivable; not being able to render is not.
  }
}

function clearStoredSession(slug: string): void {
  try {
    window.sessionStorage.removeItem(storageKey(slug));
  } catch {
    /* see above */
  }
}

/** Fold a session response and its slug into the shape the viewer consumes. */
export function toShareSession(slug: string, response: ShareSessionResponse): ShareSession {
  return {
    slug,
    token: response.sessionToken,
    visitorName: response.visitorName,
    targetType: response.targetType,
    targetName: response.targetName,
    targetDescription: response.targetDescription,
    allowDownload: response.allowDownload,
    showMetadata: response.showMetadata,
    allowComments: response.allowComments,
    allowReactions: response.allowReactions,
    allowAnnotations: response.allowAnnotations,
  };
}

export function ShareSessionProvider({ slug, children }: { slug: string; children: React.ReactNode }) {
  const [session, setSessionState] = useState<ShareSession | null>(() => readStoredSession(slug));

  const setSession = useCallback((next: ShareSession) => {
    writeStoredSession(next);
    setSessionState(next);
  }, []);

  const clearSession = useCallback(() => {
    clearStoredSession(slug);
    setSessionState(null);
  }, [slug]);

  const value = useMemo(() => ({ session, setSession, clearSession }), [session, setSession, clearSession]);
  return <ShareSessionContext.Provider value={value}>{children}</ShareSessionContext.Provider>;
}

export function useShareSession(): ShareSessionContextValue {
  return useContext(ShareSessionContext);
}
