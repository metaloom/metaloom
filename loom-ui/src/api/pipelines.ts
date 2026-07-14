import { API_BASE_URL } from "./config";

// ── Types matching the Loom REST API pipeline models ──────────────────

export interface PipelineResponse {
  uuid: string;
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
