import { API_BASE_URL } from "./config";

// ── Types matching the Loom REST API search index models ──────────────────
// Mirrors io.metaloom.loom.rest.model.searchindex.*

/** What kind of index this is; decides how it is counted, rebuilt and dropped. */
export type SearchIndexKind = "LEXICAL" | "VECTOR" | "FINGERPRINT";

export type IndexJobAction = "REINDEX" | "DELTA_SYNC" | "DROP";

export type IndexJobState = "PENDING" | "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED";

/** States a job will not leave. A poll loop stops when it sees one of these. */
export const TERMINAL_JOB_STATES: IndexJobState[] = ["SUCCEEDED", "FAILED", "CANCELLED"];

export interface IndexJobResponse {
  uuid: string;
  indexId: string;
  action: IndexJobAction;
  state: IndexJobState;
  processed: number;
  /**
   * Absent when the server cannot know it — the lexical rebuild is one SQL call whose
   * length is not predictable from any count. Render an indeterminate bar in that case
   * rather than inventing a denominator.
   */
  total?: number | null;
  removed: number;
  startedAt?: string;
  finishedAt?: string;
  error?: string;
}

export interface SearchIndexResponse {
  id: string;
  kind: SearchIndexKind;
  backendId: string;
  label?: string;
  /** The embedding.type this space holds, e.g. "face". Absent for non-vector indices. */
  type?: string | null;
  /** The producing model. Empty string when none was recorded; absent for non-vector indices. */
  model?: string | null;
  dimensions?: number | null;
  algorithm?: string | null;
  enabled: boolean;
  available: boolean;
  reason?: string | null;
  /** What the system of record holds. */
  documentCount: number;
  /** What the index holds. Below documentCount means a backlog; above it means orphans. */
  indexedCount: number;
  pendingCount: number;
  lastSyncedAt?: string;
  supportedActions: IndexJobAction[];
  activeJob?: IndexJobResponse | null;
}

export interface SearchIndexBackendResponse {
  id: string;
  provider: string;
  enabled: boolean;
  available: boolean;
  reason?: string | null;
  documentCount: number;
  /** Entries deleted but not yet merged away — why a drop does not shrink sizeBytes at once. */
  deletedCount: number;
  sizeBytes: number;
}

export interface SearchIndexListResponse {
  data: SearchIndexResponse[];
  backends: SearchIndexBackendResponse[];
}

export interface IndexJobListResponse {
  data: IndexJobResponse[];
}

// ── Helpers ───────────────────────────────────────────────────────────────

function authHeaders(token: string): Record<string, string> {
  return {
    "Content-Type": "application/json",
    Authorization: `Bearer ${token}`,
  };
}

/**
 * A typed error so a caller can tell a 403 (no permission — hide the screen) from a
 * network failure (keep the last good snapshot and warn), which a bare Error cannot.
 */
export class SearchIndexApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = "SearchIndexApiError";
    this.status = status;
  }
}

async function handleResponse<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new SearchIndexApiError(res.status, `API error ${res.status}: ${text}`);
  }
  return res.json() as Promise<T>;
}

// ── API ───────────────────────────────────────────────────────────────────

/** Every index plus the storage backends they live in. */
export async function listSearchIndices(token: string): Promise<SearchIndexListResponse> {
  const res = await fetch(`${API_BASE_URL}/search-indices`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<SearchIndexListResponse>(res);
}

/** Recent jobs for one index, newest first. Includes the running one. */
export async function listSearchIndexJobs(token: string, indexId: string): Promise<IndexJobListResponse> {
  const res = await fetch(`${API_BASE_URL}/search-indices/${encodeURIComponent(indexId)}/jobs`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<IndexJobListResponse>(res);
}

/**
 * Start a maintenance job. Resolves with the accepted job (HTTP 202), not with a result —
 * poll {@link loadSearchIndexJob} for progress.
 */
export async function createSearchIndexJob(
  token: string,
  indexId: string,
  action: IndexJobAction,
): Promise<IndexJobResponse> {
  const res = await fetch(`${API_BASE_URL}/search-indices/${encodeURIComponent(indexId)}/jobs`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify({ action }),
  });
  return handleResponse<IndexJobResponse>(res);
}

export async function loadSearchIndexJob(
  token: string,
  indexId: string,
  jobUuid: string,
): Promise<IndexJobResponse> {
  const res = await fetch(
    `${API_BASE_URL}/search-indices/${encodeURIComponent(indexId)}/jobs/${encodeURIComponent(jobUuid)}`,
    { method: "GET", headers: authHeaders(token) },
  );
  return handleResponse<IndexJobResponse>(res);
}

/** Ask a running job to stop. Cooperative, so the returned state may still be RUNNING. */
export async function cancelSearchIndexJob(
  token: string,
  indexId: string,
  jobUuid: string,
): Promise<IndexJobResponse> {
  const res = await fetch(
    `${API_BASE_URL}/search-indices/${encodeURIComponent(indexId)}/jobs/${encodeURIComponent(jobUuid)}`,
    { method: "DELETE", headers: authHeaders(token) },
  );
  return handleResponse<IndexJobResponse>(res);
}

// ── Presentation helpers (pure, unit-tested) ──────────────────────────────

/**
 * Human-readable byte size.
 *
 * Binary units because that is what a filesystem reports, and one decimal place above
 * KiB because "1.4 GiB" and "1 GiB" are meaningfully different numbers to an operator
 * watching an index grow.
 */
export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return "0 B";
  const units = ["B", "KiB", "MiB", "GiB", "TiB", "PiB"];
  const exponent = Math.min(units.length - 1, Math.floor(Math.log(bytes) / Math.log(1024)));
  const value = bytes / Math.pow(1024, exponent);
  return `${exponent === 0 ? value : value.toFixed(1)} ${units[exponent]}`;
}

/**
 * Fraction of a job that is done, or null when the total is unknown.
 *
 * Null rather than 0 so the caller renders an indeterminate bar: a determinate bar
 * pinned at 0% for a running job reads as "stuck", which is the opposite of the truth.
 */
export function jobProgress(job: IndexJobResponse): number | null {
  if (job.total === null || job.total === undefined || job.total <= 0) return null;
  return Math.min(100, Math.round((job.processed / job.total) * 100));
}

export function isTerminal(job: IndexJobResponse): boolean {
  return TERMINAL_JOB_STATES.includes(job.state);
}

/** Overall health of one index, as the four tones the status chip understands. */
export function indexTone(index: SearchIndexResponse): "green" | "amber" | "red" | "neutral" {
  // Never switched on is a decision, not a fault — neutral, not red. An enabled backend
  // that failed to open is the fault, and it is the one an operator must not miss.
  if (!index.enabled) return "neutral";
  if (!index.available) return "red";
  if (index.pendingCount > 0) return "amber";
  // More entries indexed than the database holds means orphans: the index is answering
  // with things that no longer exist, which is wrong in a quieter way than a backlog.
  if (index.indexedCount > index.documentCount) return "amber";
  return "green";
}

/** The label that goes beside the tone. Kept with it so the two cannot drift apart. */
export function indexStateLabel(index: SearchIndexResponse): string {
  if (!index.enabled) return "Disabled";
  if (!index.available) return "Unavailable";
  if (index.activeJob && !isTerminal(index.activeJob)) return "Working";
  if (index.pendingCount > 0) return "Behind";
  if (index.indexedCount > index.documentCount) return "Drifted";
  return "Healthy";
}
