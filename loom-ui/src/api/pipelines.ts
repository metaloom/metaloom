import { API_BASE_URL } from "./config";

// ── Types matching the Loom REST API pipeline models ──────────────────

/**
 * Flattened pipeline model. `uuid` identifies the pipeline; `versionUuid` and
 * `versionNumber` identify the version the remaining fields were rendered from.
 */
export interface PipelineResponse {
  uuid: string;
  versionUuid: string;
  versionNumber: number;
  name: string;
  description?: string;
  definition?: Record<string, unknown>;
  enabled: boolean;
  priority: number;
  dryRun: boolean;
  status?: {
    creator?: { uuid: string; name: string };
    created?: string;
    editor?: { uuid: string; name: string };
    edited?: string;
  };
}

export interface PipelineListResponse {
  data: PipelineResponse[];
  _metainfo?: {
    totalCount?: number;
    currentPage?: number;
    pageCount?: number;
    perPage?: number;
  };
}

/** Payload for creating a new pipeline. */
export interface PipelineCreateRequest {
  name: string;
  description?: string;
  definition?: Record<string, unknown>;
  enabled?: boolean;
  priority?: number;
  dryRun?: boolean;
}

/** Payload for updating an existing pipeline. */
export interface PipelineUpdateRequest {
  name?: string;
  description?: string;
  definition?: Record<string, unknown>;
  enabled?: boolean;
  priority?: number;
  dryRun?: boolean;
}

/** Payload for triggering a pipeline run. */
export interface PipelineRunRequest {
  /** Optional list of asset UUIDs to run the pipeline against. */
  mediaUuids?: string[];
  /** Optional list of path glob patterns to select media. */
  pathGlobs?: string[];
  /** Override the pipeline's dry-run flag for this run. */
  dryRun?: boolean;
}

export interface PipelineRunResponse {
  workOrderId: string;
  processorNodeId?: string;
  dispatched: boolean;
  message?: string;
}

// ── Helpers ───────────────────────────────────────────────────────────

function authHeaders(token: string): Record<string, string> {
  return {
    "Content-Type": "application/json",
    Authorization: `Bearer ${token}`,
  };
}

async function handleResponse<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`API error ${res.status}: ${text}`);
  }
  return res.json() as Promise<T>;
}

// ── CRUD API ──────────────────────────────────────────────────────────

export async function listPipelines(token: string): Promise<PipelineListResponse> {
  const res = await fetch(`${API_BASE_URL}/pipelines`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<PipelineListResponse>(res);
}

export async function loadPipeline(token: string, uuid: string): Promise<PipelineResponse> {
  const res = await fetch(`${API_BASE_URL}/pipelines/${encodeURIComponent(uuid)}`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<PipelineResponse>(res);
}

export async function createPipeline(token: string, request: PipelineCreateRequest): Promise<PipelineResponse> {
  const res = await fetch(`${API_BASE_URL}/pipelines`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<PipelineResponse>(res);
}

export async function updatePipeline(token: string, uuid: string, request: PipelineUpdateRequest): Promise<PipelineResponse> {
  const res = await fetch(`${API_BASE_URL}/pipelines/${encodeURIComponent(uuid)}`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<PipelineResponse>(res);
}

export async function deletePipeline(token: string, uuid: string): Promise<void> {
  const res = await fetch(`${API_BASE_URL}/pipelines/${encodeURIComponent(uuid)}`, {
    method: "DELETE",
    headers: authHeaders(token),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`API error ${res.status}: ${text}`);
  }
}

/**
 * Trigger execution of a pipeline. Loom dispatches a WORK_ORDER to a
 * registered processor which then runs the pipeline against the
 * requested media selection.
 */
export async function runPipeline(token: string, uuid: string, request: PipelineRunRequest = {}): Promise<PipelineRunResponse> {
  const res = await fetch(`${API_BASE_URL}/pipelines/${encodeURIComponent(uuid)}/run`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<PipelineRunResponse>(res);
}

// ── Pipeline Versions ─────────────────────────────────────────────────

/**
 * A pipeline version is rendered as a regular `PipelineResponse`: `uuid` is
 * always the pipeline UUID, while `versionUuid` / `versionNumber` identify the
 * specific version. In a version listing `status.creator` / `status.created`
 * describe the author of that version rather than the pipeline itself.
 */
export interface PipelineVersionListResponse {
  data: PipelineResponse[];
  _metainfo?: {
    totalCount?: number;
    perPage?: number;
    lastUuid?: string;
  };
}

/** Optional body for a version restore — both fields default to the restored version's values. */
export interface PipelineVersionRestoreRequest {
  name?: string;
  description?: string;
}

/**
 * List the version history of a pipeline (newest first as returned by the server).
 * Endpoint: `GET /api/v1/pipelines/:uuid/versions`.
 * Degrades gracefully to an empty array when the endpoint is not deployed yet.
 */
export async function listPipelineVersions(token: string, uuid: string): Promise<PipelineResponse[]> {
  const res = await fetch(`${API_BASE_URL}/pipelines/${encodeURIComponent(uuid)}/versions`, {
    method: "GET",
    headers: authHeaders(token),
  });
  if (!res.ok) return [];
  const body = await res.json() as PipelineVersionListResponse | PipelineResponse[];
  if (Array.isArray(body)) return body;
  return body.data ?? [];
}

/**
 * Load a single version of a pipeline.
 * Endpoint: `GET /api/v1/pipelines/:uuid/versions/:versionNumber`.
 */
export async function loadPipelineVersion(token: string, uuid: string, versionNumber: number): Promise<PipelineResponse> {
  const res = await fetch(`${API_BASE_URL}/pipelines/${encodeURIComponent(uuid)}/versions/${versionNumber}`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<PipelineResponse>(res);
}

/**
 * Restore a previous version of a pipeline.
 *
 * Restore is a *copy-forward*: the server creates a **new** version whose
 * content is copied from `versionNumber` and repoints the pipeline at it. The
 * returned `PipelineResponse` therefore carries the newly created
 * `versionNumber` (e.g. restoring v1 while v4 is latest yields v5), not the
 * one that was requested. Responds with HTTP 201.
 */
export async function restorePipelineVersion(
  token: string,
  uuid: string,
  versionNumber: number,
  request: PipelineVersionRestoreRequest = {},
): Promise<PipelineResponse> {
  const res = await fetch(`${API_BASE_URL}/pipelines/${encodeURIComponent(uuid)}/versions/${versionNumber}/restore`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<PipelineResponse>(res);
}

// ── Pipeline Run History ──────────────────────────────────────────────

export interface PipelineRunRecord {
  uuid: string;
  pipelineUuid: string;
  started: string;
  finished?: string;
  status: string;
  mediaCount: number;
  successCount: number;
  failureCount: number;
  dryRun: boolean;
  errorMessage?: string;
}

export interface PipelineRunListResponse {
  data: PipelineRunRecord[];
  metainfo?: {
    totalCount?: number;
    currentPage?: number;
    pageCount?: number;
    perPage?: number;
  };
}

/**
 * Fetch the run history for a pipeline.
 * The endpoint is `GET /api/v1/pipelines/:uuid/runs` (paged).
 * If the server has not yet deployed the runs endpoint, this will
 * return an empty array gracefully.
 */
export async function listPipelineRuns(token: string, uuid: string): Promise<PipelineRunRecord[]> {
  const res = await fetch(`${API_BASE_URL}/pipelines/${encodeURIComponent(uuid)}/runs`, {
    method: "GET",
    headers: authHeaders(token),
  });
  if (!res.ok) {
    // If the endpoint does not exist yet (404) or the server returns
    // an error, return an empty array so the UI shows "no runs".
    return [];
  }
  const body = await res.json() as PipelineRunListResponse | PipelineRunRecord[];
  if (Array.isArray(body)) return body;
  return body.data ?? [];
}

// ── Pipeline Run Stats ────────────────────────────────────────────────

export interface PipelineRunDayStats {
  /** Calendar day of the bucket (ISO 8601 date, e.g. "2026-07-22"). */
  date: string;
  /** Number of pipeline runs started on this day (across all pipelines). */
  runCount: number;
  /** Sum of successfully processed media items of runs started on this day. */
  successCount: number;
  /** Sum of failed media items of runs started on this day. */
  failureCount: number;
  /** Sum of skipped media items of runs started on this day. */
  skippedCount: number;
}

export interface PipelineRunStatsResponse {
  /** Daily buckets, oldest first, zero-filled for days without runs. */
  daily: PipelineRunDayStats[];
}

/**
 * Load aggregated daily run statistics across all pipelines.
 * Endpoint: `GET /api/v1/pipelines/runs/stats`.
 */
export async function loadPipelineRunStats(token: string): Promise<PipelineRunStatsResponse> {
  const res = await fetch(`${API_BASE_URL}/pipelines/runs/stats`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<PipelineRunStatsResponse>(res);
}

// ── Pipeline Run Items ────────────────────────────────────────────────

export interface PipelineRunItemRecord {
  uuid: string;
  runUuid: string;
  itemSeq: number;
  mediaPath?: string;
  sha512?: string;
  sizeBytes?: number;
  state: string;
  errorMessage?: string;
}

export interface PipelineRunItemListResponse {
  data: PipelineRunItemRecord[];
  metainfo?: {
    totalCount?: number;
    currentPage?: number;
    pageCount?: number;
    perPage?: number;
  };
}

/**
 * Load a single pipeline run (detail view).
 * Endpoint: `GET /api/v1/pipelines/:uuid/runs/:runUuid`.
 */
export async function loadPipelineRun(token: string, pipelineUuid: string, runUuid: string): Promise<PipelineRunRecord> {
  const res = await fetch(`${API_BASE_URL}/pipelines/${encodeURIComponent(pipelineUuid)}/runs/${encodeURIComponent(runUuid)}`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<PipelineRunRecord>(res);
}

/**
 * Fetch the items discovered/processed by a single pipeline run (paged).
 * Endpoint: `GET /api/v1/pipelines/:uuid/runs/:runUuid/items`.
 * Degrades gracefully to an empty array when the endpoint is not deployed
 * yet or the run has no items.
 */
export async function listPipelineRunItems(token: string, pipelineUuid: string, runUuid: string): Promise<PipelineRunItemRecord[]> {
  const res = await fetch(`${API_BASE_URL}/pipelines/${encodeURIComponent(pipelineUuid)}/runs/${encodeURIComponent(runUuid)}/items`, {
    method: "GET",
    headers: authHeaders(token),
  });
  if (!res.ok) {
    return [];
  }
  const body = await res.json() as PipelineRunItemListResponse | PipelineRunItemRecord[];
  if (Array.isArray(body)) return body;
  return body.data ?? [];
}

/**
 * Cancel an in-flight pipeline run.
 *
 * Stops the run's engine dispatching further node tasks and marks the run
 * `CANCELLED`. In-flight worker tasks are left to settle naturally.
 * Endpoint: `POST /api/v1/pipelines/:uuid/runs/:runUuid/cancel`.
 */
export async function cancelPipelineRun(token: string, pipelineUuid: string, runUuid: string): Promise<void> {
  const res = await fetch(`${API_BASE_URL}/pipelines/${encodeURIComponent(pipelineUuid)}/runs/${encodeURIComponent(runUuid)}/cancel`, {
    method: "POST",
    headers: authHeaders(token),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`API error ${res.status}: ${text}`);
  }
}
