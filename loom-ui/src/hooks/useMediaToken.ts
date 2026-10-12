import { useEffect, useState } from "react";

import { mintMediaToken } from "../api/assets";
import { useAuth } from "../context/AuthContext";

/**
 * A short-lived media token for one asset, refreshed before it expires.
 *
 * <p>Why a token at all: an `<img>` or `<video>` element cannot send an `Authorization` header, and
 * the session cookie is `Secure`/`__Host-` prefixed, so a browser silently drops it on any
 * plain-HTTP deployment. Media URLs then 401 and the UI shows a placeholder with nothing to say
 * why. The poster and stream routes accept `?mt=<token>` instead, scoped to a single asset.</p>
 *
 * <p>Tokens are cached per asset for the lifetime of the page, because a grid of tiles would
 * otherwise mint one per tile on every render. The cache is module-level rather than per-hook for
 * the same reason: two components showing the same asset share one token and one request.</p>
 *
 * @param assetUuid the asset to mint for, or null/undefined to mint nothing
 * @returns the token, or null while it is being fetched or if minting failed
 */
export function useMediaToken(assetUuid?: string | null): string | null {
  const { token } = useAuth();
  // The token is stored *with the asset it was minted for*. A bare string here was the bug this
  // shape exists to prevent: when a caller handed the same instance the next asset (the workflow
  // queue does, unkeyed), the first render for the new asset still returned the previous asset's
  // token, and the first poster/stream URL built from it went out with the wrong `?mt=` - a 401 at
  // best, a broken image that never retried at worst. Comparing the owner during render means no
  // render ever sees a token that belongs to a different asset.
  const [minted, setMinted] = useState<{ assetUuid: string; token: string } | null>(null);

  useEffect(() => {
    if (!token || !assetUuid) {
      setMinted(null);
      return;
    }
    const cached = cache.get(assetUuid);
    if (cached && cached.expiresAt > Date.now()) {
      setMinted({ assetUuid, token: cached.token });
      return;
    }

    let cancelled = false;
    mintMediaToken(token, assetUuid)
      .then(resp => {
        // Expire our copy early, so a URL built from it is still valid when it arrives at the
        // server. A token that expires mid-flight shows up as a broken image, which is exactly the
        // failure this hook exists to remove.
        const expiresAt = Date.now() + Math.max(5, resp.expiresIn - 30) * 1000;
        cache.set(assetUuid, { token: resp.token, expiresAt });
        if (!cancelled) {
          setMinted({ assetUuid, token: resp.token });
        }
      })
      .catch(() => {
        // Not an error banner: the caller degrades to its placeholder, exactly as it does for an
        // asset that has no preview at all.
        if (!cancelled) {
          setMinted(null);
        }
      });
    return () => { cancelled = true; };
  }, [token, assetUuid]);

  if (!token || !assetUuid) {
    return null;
  }
  if (minted && minted.assetUuid === assetUuid) {
    return minted.token;
  }
  // Switched to an asset whose token is already cached: use it straight away instead of rendering
  // one frame of placeholder while the effect catches up.
  const cached = cache.get(assetUuid);
  return cached && cached.expiresAt > Date.now() ? cached.token : null;
}

interface CachedToken {
  token: string;
  /** Wall-clock ms after which this copy is considered stale. */
  expiresAt: number;
}

/** Shared across every component on the page; see the note about grids above. */
const cache = new Map<string, CachedToken>();

/** Visible for tests: forget every cached token. */
export function clearMediaTokenCache(): void {
  cache.clear();
}
