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
