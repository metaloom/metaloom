import { API_BASE_URL } from "./config";
import type { PagingInfo } from "./paging";

// ── Types matching the Loom REST API processor models ─────────────────

export type ProcessorState =
  | "STARTING"
  | "ONLINE"
  | "OFFLINE"
  | "PAUSED"
  | "TERMINATING";

export type ProcessorCapability = "GPU" | "CPU" | "IO";

/** System metrics reported by a processor node. Loads are 0-100; sizes are bytes. */
export interface SystemStatusInfo {
  cpuLoad?: number;
  gpuLoad?: number;
  ioLoad?: number;
  memoryUsed?: number;
  memoryTotal?: number;
  diskUsed?: number;
  diskTotal?: number;
}

/** A registered processor (cortex) node. */
export interface Processor {
  uuid: string;
  /** Stable node id; the natural key for correlating snapshots with live events. */
  nodeId: string;
  name: string;
  host?: string;
  priority?: number;
  state?: ProcessorState;
  capabilities?: ProcessorCapability[];
  systemStatus?: SystemStatusInfo;
  lastSeen?: string;
  /** Node kinds this worker is permitted to run; empty/absent means unrestricted. */
  nodeWhitelist?: string[];
  /** Node kinds this worker is forbidden to run; takes precedence over the whitelist. */
  nodeBlacklist?: string[];
  /**
   * Whether Loom durably remembers this worker in the cortex_instance table. Persisted
   * workers stay listed (and their restrictions editable) even while offline.
   */
  persisted?: boolean;
}

/** Body for {@link updateProcessorRestrictions}. Both lists replace the persisted values. */
export interface ProcessorRestrictionUpdateRequest {
  nodeWhitelist: string[];
  nodeBlacklist: string[];
}

export interface ProcessorListResponse {
  data: Processor[];
  _metainfo?: PagingInfo;
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

// ── API ───────────────────────────────────────────────────────────────

/** Fetch the current point-in-time snapshot of all registered processors. */
export async function listProcessors(token: string): Promise<ProcessorListResponse> {
  const res = await fetch(`${API_BASE_URL}/processors`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<ProcessorListResponse>(res);
}

/** Load a single processor (live or persisted-but-offline) by its stable node id. */
export async function getProcessor(token: string, nodeId: string): Promise<Processor> {
  const res = await fetch(`${API_BASE_URL}/processors/${encodeURIComponent(nodeId)}`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<Processor>(res);
}

/**
 * Persist the node-kind whitelist/blacklist for a worker. Applies to the live worker
 * immediately when connected, and survives reconnects/restarts. Requires MANAGE_CORTEX_INSTANCE.
 */
export async function updateProcessorRestrictions(
  token: string,
  nodeId: string,
  req: ProcessorRestrictionUpdateRequest,
): Promise<Processor> {
  const res = await fetch(`${API_BASE_URL}/processors/${encodeURIComponent(nodeId)}/restrictions`, {
    method: "PUT",
    headers: authHeaders(token),
    body: JSON.stringify(req),
  });
  return handleResponse<Processor>(res);
}

/**
 * Forget a persisted (offline) worker. Only permitted while the worker is disconnected;
 * an online worker returns 409. Requires MANAGE_CORTEX_INSTANCE.
 */
export async function forgetProcessor(token: string, nodeId: string): Promise<void> {
  const res = await fetch(`${API_BASE_URL}/processors/${encodeURIComponent(nodeId)}`, {
    method: "DELETE",
    headers: authHeaders(token),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`API error ${res.status}: ${text}`);
  }
}
