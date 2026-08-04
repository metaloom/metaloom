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
  /**
   * Ask workers to attach debugging previews of what each node emits.
   *
   * Off by default and per *run*, not per pipeline: encoding and storing a thumbnail per image
   * per item is a cost no production run should pay.
   */
  debug?: boolean;
  /**
   * Node ids to halt at, so a run can start already armed.
   *
   * Run state, never definition state: these belong to this run and are not written back
   * into the stored pipeline.
   */
  breakpoints?: string[];
}

export interface PipelineRunResponse {
  runUuid: string;
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
 * Resolve a preview's server-absolute path against the configured API base.
 *
 * The server returns `/api/v1/…` rather than a full URL because it does not know what host the
 * browser reached it through. `API_BASE_URL` already ends in `/api/v1`, so the shared prefix is
 * dropped rather than concatenated — otherwise a dev setup pointing at another origin would
 * request `/api/v1/api/v1/…`.
 */
export function previewSrc(url: string): string {
  const base = API_BASE_URL.replace(/\/api\/v1$/, "");
  return url.startsWith("/api/v1") ? base + url : url;
}

/**
 * Trigger execution of a pipeline. Loom hands the pipeline's source node to a
 * registered processor as a SOURCE_TASK; the run engine then dispatches
 * per-node tasks as media items stream back.
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

// ── Node executions (per run item) ────────────────────────────────────

/**
 * One element of a port payload.
 *
 * `value` is a JSON-native tree — a string for a hash or a path, an object for a detection,
 * an array for an embedding. It is deliberately untyped here: the renderer switches on the
 * payload's `contentType`, never on the shape of this field.
 */
export interface PortDataElement {
  origin?: { itemId?: string; seq?: number; total?: number };
  value: unknown;
}

/**
 * What one output port carried, as persisted in `pipeline_node_task.outputs`.
 *
 * Cardinality lives on the port, never in the content type: a `ONE` payload has a single
 * element and a `MANY` payload has the whole sequence its consumers gather back.
 */
export interface PortPayload {
  contentType: string;
  cardinality: "ONE" | "MANY";
  elements: PortDataElement[];
}

/**
 * One node execution within a run item — the finest granularity the engine records.
 *
 * There is one per graph node, and one **per element** for a node downstream of a `MANY`
 * output (distinguished by `elementSeq`).
 */
export interface PipelineNodeTaskRecord {
  uuid: string;
  itemUuid: string;
  runUuid: string;
  nodeId: string;
  nodeKind: string;
  elementSeq: number;
  /** PENDING | LEASED | DONE | FAILED | DEAD_LETTER */
  state: string;
  attempt: number;
  maxAttempts: number;
  leasedBy?: string;
  started?: number;
  finished?: number;
  durationMs?: number;
  errorMessage?: string;
  /** Keyed by **output port id**. Retained on SKIPPED and FAILED results too. */
  outputs?: Record<string, PortPayload>;
  /**
   * Debugging previews keyed by output port id; present only for a run started with `debug`.
   *
   * This is the only way to look at media a node *produced*: an `artifact/image` port carries a
   * path on the worker that made it, which nothing else can resolve.
   */
  previews?: Record<string, NodePreviewMeta>;
}

export interface PipelineNodeTaskListResponse {
  data: PipelineNodeTaskRecord[];
}

/**
 * Metadata for one debugging preview. The bytes live behind `url`, never inline.
 *
 * A preview with no `url` was not produced — `skippedReason` says why. That is deliberately
 * distinct from a port having no preview entry at all, which means it was never previewable.
 */
export interface NodePreviewMeta {
  mimeType?: string;
  width?: number;
  height?: number;
  /** Server-absolute path (`/api/v1/…`), not a full URL — see `previewSrc`. */
  url?: string;
  /**
   * The node's own description of what this port carried, as GFM Markdown.
   *
   * Set by a node calling `ctx.preview(port, markdown)`. Beats every family default, because the
   * node knows things the content type does not — that these four numbers are a bounding box.
   */
  markdown?: string;
  skippedReason?: string;
}

/**
 * Load the node executions of a single run item, including the outputs each node emitted.
 *
 * This is the only route that exposes what a node actually produced; the run and item
 * records carry a coarse state and nothing else. Unpaged by design — the set is bounded by
 * the graph and the caller always wants all of it.
 *
 * Endpoint: `GET /api/v1/pipelines/:uuid/runs/:runUuid/items/:itemUuid/tasks`.
 * Degrades to an empty array rather than throwing, matching `listPipelineRunItems`.
 */
export async function listPipelineRunItemTasks(
  token: string,
  pipelineUuid: string,
  runUuid: string,
  itemUuid: string,
): Promise<PipelineNodeTaskRecord[]> {
  const res = await fetch(
    `${API_BASE_URL}/pipelines/${encodeURIComponent(pipelineUuid)}/runs/${encodeURIComponent(runUuid)}`
    + `/items/${encodeURIComponent(itemUuid)}/tasks`,
    { method: "GET", headers: authHeaders(token) },
  );
  if (!res.ok) {
    return [];
  }
  const body = await res.json() as PipelineNodeTaskListResponse | PipelineNodeTaskRecord[];
  if (Array.isArray(body)) return body;
  return body.data ?? [];
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

/**
 * Suspend an in-flight pipeline run.
 *
 * Unlike a cancel this is reversible: the run keeps everything it has done, no further
 * work is handed to workers, and the source scan stops too. The run's status becomes
 * `PAUSED`, which is non-terminal.
 * Endpoint: `POST /api/v1/pipelines/:uuid/runs/:runUuid/pause`.
 */
export async function pausePipelineRun(token: string, pipelineUuid: string, runUuid: string): Promise<void> {
  const res = await fetch(`${API_BASE_URL}/pipelines/${encodeURIComponent(pipelineUuid)}/runs/${encodeURIComponent(runUuid)}/pause`, {
    method: "POST",
    headers: authHeaders(token),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`API error ${res.status}: ${text}`);
  }
}

/**
 * Resume a suspended pipeline run.
 *
 * The server requires the run to still be live: a run whose engine was lost to a restart
 * is refused with 409 rather than flipped back to `RUNNING` with nothing to advance it.
 * Endpoint: `POST /api/v1/pipelines/:uuid/runs/:runUuid/resume`.
 */
export async function resumePipelineRun(token: string, pipelineUuid: string, runUuid: string): Promise<void> {
  const res = await fetch(`${API_BASE_URL}/pipelines/${encodeURIComponent(pipelineUuid)}/runs/${encodeURIComponent(runUuid)}/resume`, {
    method: "POST",
    headers: authHeaders(token),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`API error ${res.status}: ${text}`);
  }
}

/** One execution a breakpoint is currently withholding from its downstream nodes. */
export interface HeldExecution {
  nodeId: string;
  itemUuid: string;
  elementSeq: number;
}

/**
 * What a run halts at, and what it is currently holding.
 *
 * The two answer different questions: `nodeIds` is what was armed, `held` is what has actually
 * stopped. A breakpoint no item has reached yet is armed and holding nothing.
 */
export interface PipelineBreakpointResponse {
  nodeIds: string[];
  held: HeldExecution[];
}

const EMPTY_BREAKPOINTS: PipelineBreakpointResponse = { nodeIds: [], held: [] };

function breakpointsUrl(pipelineUuid: string, runUuid: string): string {
  return `${API_BASE_URL}/pipelines/${encodeURIComponent(pipelineUuid)}/runs/${encodeURIComponent(runUuid)}/breakpoints`;
}

/**
 * Load the nodes a run halts at and the executions it is holding.
 *
 * Degrades to an empty result rather than throwing: a run whose engine is gone is genuinely
 * holding nothing, and the debug view should render that rather than an error.
 * Endpoint: `GET /api/v1/pipelines/:uuid/runs/:runUuid/breakpoints`.
 */
export async function loadPipelineRunBreakpoints(
  token: string,
  pipelineUuid: string,
  runUuid: string,
): Promise<PipelineBreakpointResponse> {
  const res = await fetch(breakpointsUrl(pipelineUuid, runUuid), {
    method: "GET",
    headers: authHeaders(token),
  });
  if (!res.ok) {
    return EMPTY_BREAKPOINTS;
  }
  const body = await res.json() as Partial<PipelineBreakpointResponse>;
  return { nodeIds: body.nodeIds ?? [], held: body.held ?? [] };
}

/**
 * Replace the set of nodes a run halts at.
 *
 * A whole-set replacement, so the editor sends what the armed set should become rather than a
 * delta the two sides could disagree about. An empty list disarms everything.
 * Endpoint: `PUT /api/v1/pipelines/:uuid/runs/:runUuid/breakpoints`.
 */
export async function setPipelineRunBreakpoints(
  token: string,
  pipelineUuid: string,
  runUuid: string,
  nodeIds: string[],
): Promise<PipelineBreakpointResponse> {
  const res = await fetch(breakpointsUrl(pipelineUuid, runUuid), {
    method: "PUT",
    headers: { ...authHeaders(token), "Content-Type": "application/json" },
    body: JSON.stringify({ nodeIds }),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`API error ${res.status}: ${text}`);
  }
  const body = await res.json() as Partial<PipelineBreakpointResponse>;
  return { nodeIds: body.nodeIds ?? [], held: body.held ?? [] };
}

/**
 * Let one node's held executions through, leaving its breakpoint armed.
 * Endpoint: `POST /api/v1/pipelines/:uuid/runs/:runUuid/breakpoints/:nodeId/continue`.
 */
export async function continuePipelineRunBreakpoint(
  token: string,
  pipelineUuid: string,
  runUuid: string,
  nodeId: string,
): Promise<void> {
  const res = await fetch(`${breakpointsUrl(pipelineUuid, runUuid)}/${encodeURIComponent(nodeId)}/continue`, {
    method: "POST",
    headers: authHeaders(token),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`API error ${res.status}: ${text}`);
  }
}

/**
 * Release exactly one held execution.
 *
 * Fails with 409 when the run is not holding anything, rather than succeeding silently — a step
 * that did nothing must not look like a step that advanced the run.
 * Endpoint: `POST /api/v1/pipelines/:uuid/runs/:runUuid/steps`.
 */
export async function stepPipelineRun(
  token: string,
  pipelineUuid: string,
  runUuid: string,
): Promise<PipelineBreakpointResponse> {
  const res = await fetch(
    `${API_BASE_URL}/pipelines/${encodeURIComponent(pipelineUuid)}/runs/${encodeURIComponent(runUuid)}/steps`,
    { method: "POST", headers: authHeaders(token) },
  );
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`API error ${res.status}: ${text}`);
  }
  const body = await res.json() as Partial<PipelineBreakpointResponse>;
  return { nodeIds: body.nodeIds ?? [], held: body.held ?? [] };
}
