import { API_BASE_URL } from "./config";
import { handleResponse } from "./http";

/**
 * Media bytes that need an `Authorization` header to load.
 *
 * An `<audio src>` cannot send one, so every recording this UI plays is fetched here and wrapped
 * in an object URL by the caller (see `AuthedAudioPlayer`, which also revokes it).
 */

/** The longest stretch `GET /assets/:uuid/audio-clip` will cut, in milliseconds. */
export const AUDIO_CLIP_MAX_MS = 120_000;

/**
 * Resolve a URL the server handed out against the API origin.
 *
 * The server answers with absolute paths - `/api/v1/persons/…/data` - so a relative resolve is
 * what keeps them working when the UI talks to an API on another origin (`VITE_API_BASE_URL`).
 * A URL that is already absolute is returned unchanged.
 */
export function resolveApiUrl(url: string): string {
  try {
    return new URL(url, API_BASE_URL).toString();
  } catch {
    return url;
  }
}

/** Fetch any authenticated media URL - a voice sample, a hero voice - as a blob. */
export async function fetchAuthedBlob(token: string, url: string): Promise<Blob> {
  const res = await fetch(resolveApiUrl(url), {
    method: "GET",
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!res.ok) {
    // Through the shared handler so a 401 still ends the session and the trace id survives.
    await handleResponse<never>(res);
  }
  return res.blob();
}

/** The audio-clip route for one stretch of an asset. Whole milliseconds, as the server parses them. */
export function audioClipUrl(assetUuid: string, fromMs: number, toMs: number): string {
  const from = Math.max(0, Math.floor(fromMs));
  const to = Math.max(from, Math.ceil(toMs));
  return `${API_BASE_URL}/assets/${encodeURIComponent(assetUuid)}/audio-clip?from=${from}&to=${to}`;
}

/**
 * One stretch of an asset's audio as 16 kHz mono WAV, cut on demand.
 *
 * How a reviewer listens to a speaker's turn. The server refuses more than
 * {@link AUDIO_CLIP_MAX_MS} and answers 422 for an asset without audio.
 */
export async function fetchAudioClip(token: string, assetUuid: string, fromMs: number, toMs: number): Promise<Blob> {
  return fetchAuthedBlob(token, audioClipUrl(assetUuid, fromMs, toMs));
}
