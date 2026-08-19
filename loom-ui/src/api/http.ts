/**
 * The one place an HTTP response becomes either a value or a failure.
 *
 * Before this module there were 36 private `handleResponse` copies across `src/api/`, each
 * throwing `new Error("API error 500: ...")`. Three things were lost every time:
 *
 * - **the trace id**, which only exists on the response and is the sole value that lets an
 *   operator find the matching stack trace in the server log;
 * - **the status**, as a number rather than a substring of a message; and
 * - **the fact that it was a 401 at all**, so an expired session surfaced as a page of
 *   independently-failing widgets rather than one "your session expired".
 *
 * Everything here exists to keep those three. `ApiError` carries them, `handleResponse`
 * populates them, and the failure surface (`FailureContext`) renders them.
 */

import { API_BASE_URL } from "./config";

/** The response header carrying the server's per-request id. Mirrors `TraceIdHandler.TRACE_ID_HEADER`. */
export const TRACE_ID_HEADER = "X-Trace-Id";

/**
 * Fired on the `window` when any request answers 401.
 *
 * A DOM event rather than a callback registry because the emitter is a plain module with no
 * React context above it, and the listener (`AuthProvider`) is a component. Ten parallel 401s
 * produce ten events; suppressing the duplicates is the listener's job, since only it knows
 * whether it has already logged out.
 */
export const SESSION_EXPIRED_EVENT = "loom:session-expired";

/** What was being attempted, for the report. See `FailureReport#getAction` on the server. */
export interface RequestContext {
  /** The client's own name for the operation - "createPerson", "deleteTag". */
  action?: string;
  method?: string;
  path?: string;
}

/**
 * A failed HTTP call, with everything a failure report needs.
 *
 * Thrown instead of a bare `Error` so that `catch (e)` sites can render something specific and
 * offer to report it. `instanceof` is the discriminator; `isApiError` exists for the cases where
 * the value crossed a module boundary that a bundler could have duplicated.
 */
export class ApiError extends Error {
  readonly status: number;
  /** The `X-Trace-Id` of the failing response, or null when the server sent none. */
  readonly traceId: string | null;
  /** The server's own message, when the body was the usual `GenericMessageResponse`. */
  readonly serverMessage: string | null;
  readonly action: string | null;
  readonly method: string | null;
  readonly path: string | null;

  constructor(
    message: string,
    init: {
      status: number;
      traceId?: string | null;
      serverMessage?: string | null;
      context?: RequestContext;
    },
  ) {
    super(message);
    this.name = "ApiError";
    this.status = init.status;
    this.traceId = init.traceId ?? null;
    this.serverMessage = init.serverMessage ?? null;
    this.action = init.context?.action ?? null;
    this.method = init.context?.method ?? null;
    this.path = init.context?.path ?? null;
  }
}

/** True for an `ApiError` from this module, tolerant of a duplicated class identity. */
export function isApiError(value: unknown): value is ApiError {
  return value instanceof ApiError || (value instanceof Error && value.name === "ApiError");
}

/** The standard JSON headers for an authenticated call. Lifted verbatim from the 36 copies. */
export function authHeaders(token: string): Record<string, string> {
  return {
    "Content-Type": "application/json",
    Authorization: `Bearer ${token}`,
  };
}

/**
 * Turn a response into its parsed body, or throw an {@link ApiError}.
 *
 * Behaviour on success is unchanged from the copies this replaces: 2xx returns `res.json()`.
 * A 204 returns `undefined` rather than throwing on an empty body, which several of the copies
 * got wrong in their own different ways.
 */
export async function handleResponse<T>(res: Response, context?: RequestContext): Promise<T> {
  const traceId = traceIdOf(res);

  if (!res.ok) {
    const text = await res.text().catch(() => "");
    const serverMessage = extractMessage(text);
    // The body's own traceId wins over the header only when the header is absent: a
    // cross-origin deployment without `Access-Control-Expose-Headers` hides the header from
    // JS entirely, and the body copy is what keeps the id reachable there.
    const bodyTraceId = extractTraceId(text);

    if (res.status === 401) {
      notifySessionExpired();
    }

    throw new ApiError(serverMessage ?? `API error ${res.status}${text ? `: ${text}` : ""}`, {
      status: res.status,
      traceId: traceId ?? bodyTraceId,
      serverMessage,
      context: { ...context, path: context?.path ?? pathOf(res.url) },
    });
  }

  if (res.status === 204) {
    return undefined as T;
  }
  return res.json() as Promise<T>;
}

/**
 * Pull the human-readable message out of a `GenericMessageResponse` body.
 *
 * Returns null rather than the raw text when the body is not that shape: a stack trace or an
 * HTML error page rendered into a toast is worse than the generic "API error 500".
 */
function extractMessage(body: string): string | null {
  if (!body) return null;
  try {
    const parsed = JSON.parse(body) as { message?: unknown };
    return typeof parsed.message === "string" && parsed.message.trim() ? parsed.message : null;
  } catch {
    return null;
  }
}

function extractTraceId(body: string): string | null {
  if (!body) return null;
  try {
    const parsed = JSON.parse(body) as { traceId?: unknown };
    return typeof parsed.traceId === "string" && parsed.traceId ? parsed.traceId : null;
  } catch {
    return null;
  }
}

/** The path part of a URL, for the report. Falls back to the whole string if it will not parse. */
function pathOf(url: string): string | undefined {
  if (!url) return undefined;
  try {
    return new URL(url).pathname;
  } catch {
    return url;
  }
}

function notifySessionExpired(): void {
  if (typeof window === "undefined") return;
  window.dispatchEvent(new CustomEvent(SESSION_EXPIRED_EVENT));
}

/**
 * The trace id of a response, header first and body second.
 *
 * Exported for the handful of modules that keep their own typed error class - `UserApiError`,
 * `SearchApiError` and the rest. Those cannot simply call {@link handleResponse}, because their
 * callers switch on the concrete type; they use this so their errors carry the id too, rather
 * than being the six places where it is silently dropped.
 */
export function traceIdOf(res: Response): string | null {
  // Defensive about `headers` existing at all. A real `Response` always has it, but this runs
  // against hand-rolled response stubs in the unit tests and against whatever a fetch polyfill
  // hands back - and a missing trace id must never be the reason a request fails.
  try {
    return res.headers?.get(TRACE_ID_HEADER) ?? null;
  } catch {
    return null;
  }
}

/**
 * Fire the session-expired event when a response is a 401.
 *
 * The counterpart of {@link traceIdOf} for the same six modules: without this, an expired token
 * reaching one of them would fail silently and the global logout would never happen.
 */
export function noteUnauthorized(status: number): void {
  if (status === 401) {
    notifySessionExpired();
  }
}

/** Absolute URL for a path relative to the API root. */
export function apiUrl(path: string): string {
  return `${API_BASE_URL}${path.startsWith("/") ? path : `/${path}`}`;
}
