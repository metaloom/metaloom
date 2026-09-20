/**
 * Loom serves the UI and the REST API from the same origin (one container, one port), so
 * with no explicit override the API base is derived from wherever the page was actually
 * loaded - never baked in at build time. A build-time constant here would only work when a
 * browser happens to reach the app via the exact host/port used at build time (e.g.
 * "localhost:8092"); any other hostname, IP or port - which is the normal case for a real
 * deployment - would have every request silently target the browser's own machine instead of
 * the server. Outside a browser (SSR/tests, no `window`) fall back to the historical dev default.
 */
function defaultApiBaseUrl(): string {
  if (typeof window !== "undefined" && window.location) {
    return `${window.location.origin}/api/v1`;
  }
  return "http://localhost:8092/api/v1";
}

/** Base URL for the Loom REST API, configurable via VITE_API_BASE_URL env var. */
export const API_BASE_URL =
  import.meta.env.VITE_API_BASE_URL?.replace(/\/+$/, "") ?? defaultApiBaseUrl();
