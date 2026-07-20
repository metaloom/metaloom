import { API_BASE_URL } from "./config";

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
}

export interface ProcessorListResponse {
  data: Processor[];
  _metainfo?: {
    totalCount?: number;
    currentPage?: number;
    pageCount?: number;
    perPage?: number;
  };
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
