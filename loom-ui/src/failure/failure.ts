/**
 * The normalised shape of "something went wrong", and how any thrown value becomes one.
 *
 * Kept apart from React so it can be unit-tested in the node environment - `src/failure/failure.test.ts`.
 * The context that renders these lives in `src/context/FailureContext.tsx`.
 */

import { isApiError } from "../api/http";

/** One failure, as the toast renders it and as the report dialog submits it. */
export interface Failure {
  /**
   * What the user was doing, in the client's own vocabulary - `"createPerson"`, `"deleteTag"`.
   *
   * Always supplied by the call site, never derived. The path answers "which route"; this has to
   * answer "which button", and one route serves several buttons.
   */
  action: string;
  /** A short, human sentence for the toast. Never a stack trace. */
  message: string;
  /** The `X-Trace-Id` of the failing response. The one value that resolves in the server log. */
  traceId?: string;
  status?: number;
  method?: string;
  path?: string;
  /** The client-side route the user was on, stamped when the failure is raised. */
  route?: string;
}

/**
 * Turn anything a `catch` block can receive into a {@link Failure}.
 *
 * The interesting case is an `ApiError`, which carries the status and the trace id. Everything
 * else - a `TypeError` from a dropped connection, a string somebody threw, `undefined` - still
 * has to produce a reportable failure, because a report about an unclassifiable error is more
 * useful than no report.
 */
export function toFailure(action: string, error: unknown, route?: string): Failure {
  if (isApiError(error)) {
    return {
      action,
      message: humanMessage(error.serverMessage ?? error.message, error.status),
      traceId: error.traceId ?? undefined,
      status: error.status,
      method: error.method ?? undefined,
      path: error.path ?? undefined,
      route,
    };
  }

  // The typed per-module error classes (`UserApiError`, `SearchApiError`, ...) are structurally
  // like an ApiError without being one. Read them duck-typed rather than importing six classes.
  const loose = error as { status?: unknown; traceId?: unknown; message?: unknown } | null;
  const status = typeof loose?.status === "number" ? loose.status : undefined;
  const traceId = typeof loose?.traceId === "string" ? loose.traceId : undefined;

  return {
    action,
    message: humanMessage(messageOf(error), status),
    traceId,
    status,
    route,
  };
}

function messageOf(error: unknown): string {
  if (error instanceof Error && error.message) return error.message;
  if (typeof error === "string" && error.trim()) return error;
  return "";
}

/**
 * A sentence a person can read.
 *
 * A network failure surfaces as `TypeError: Failed to fetch`, which tells a user nothing; a 500
 * surfaces as the server's deliberately opaque "Internal Server Error", which at least says whose
 * fault it is. Anything that still reads like machine output gets replaced by the status wording.
 */
function humanMessage(raw: string, status?: number): string {
  const trimmed = raw.trim();
  if (!trimmed || /^(TypeError|Failed to fetch|NetworkError|Load failed)/i.test(trimmed)) {
    return status ? statusWording(status) : "The server could not be reached.";
  }
  if (/^API error \d+/.test(trimmed) && status) {
    return statusWording(status);
  }
  return trimmed;
}

function statusWording(status: number): string {
  switch (status) {
    case 400:
      return "The request was rejected as invalid.";
    case 401:
      return "Your session has expired.";
    case 403:
      return "You do not have permission to do that.";
    case 404:
      return "That no longer exists.";
    case 409:
      return "That already exists.";
    case 413:
      return "That was too large to accept.";
    case 503:
      return "The server is temporarily unavailable.";
    default:
      return status >= 500 ? "The server failed to handle the request." : `The request failed (${status}).`;
  }
}

/**
 * A one-line technical summary, shown under the message in the report dialog.
 *
 * Deliberately not shown in the toast: a user reading a toast wants to know whether their work
 * was saved, not which verb hit which path.
 */
export function failureSummary(failure: Failure): string {
  const parts: string[] = [];
  if (failure.method || failure.path) {
    parts.push([failure.method, failure.path].filter(Boolean).join(" "));
  }
  if (failure.status) parts.push(`HTTP ${failure.status}`);
  return parts.join(" · ");
}
