import { API_BASE_URL } from "./config";

// ── Types matching the Loom REST API pool models ──────────────────────

export interface PoolResponse {
  uuid: string;
  name: string;
  fsPath?: string;
  s3Bucket?: string;
  s3Region?: string;
  s3Endpoint?: string;
  freeSpace?: number;
  usedSpace?: number;
  meta?: Record<string, unknown>;
  status?: {
    creator?: { uuid: string; name: string };
    created?: string;
    editor?: { uuid: string; name: string };
    edited?: string;
  };
}

export interface PoolListResponse {
  data: PoolResponse[];
  _metainfo?: {
    totalCount?: number;
    currentPage?: number;
    pageCount?: number;
    perPage?: number;
  };
}

export interface PoolCreateRequest {
  name: string;
  fsPath?: string;
  s3Bucket?: string;
  s3Region?: string;
  s3Endpoint?: string;
}

export interface PoolUpdateRequest {
  name?: string;
  fsPath?: string;
  s3Bucket?: string;
  s3Region?: string;
  s3Endpoint?: string;
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

export async function listPools(token: string): Promise<PoolListResponse> {
  const res = await fetch(`${API_BASE_URL}/pools`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<PoolListResponse>(res);
}

export async function loadPool(token: string, uuid: string): Promise<PoolResponse> {
  const res = await fetch(`${API_BASE_URL}/pools/${encodeURIComponent(uuid)}`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<PoolResponse>(res);
}

export async function createPool(token: string, request: PoolCreateRequest): Promise<PoolResponse> {
  const res = await fetch(`${API_BASE_URL}/pools`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<PoolResponse>(res);
}

export async function updatePool(token: string, uuid: string, request: PoolUpdateRequest): Promise<PoolResponse> {
  const res = await fetch(`${API_BASE_URL}/pools/${encodeURIComponent(uuid)}`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<PoolResponse>(res);
}

export async function deletePool(token: string, uuid: string): Promise<void> {
  const res = await fetch(`${API_BASE_URL}/pools/${encodeURIComponent(uuid)}`, {
    method: "DELETE",
    headers: authHeaders(token),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`API error ${res.status}: ${text}`);
  }
}
