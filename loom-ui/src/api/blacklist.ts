import { API_BASE_URL } from "./config";

// ── Types matching the Loom REST API blacklist models ─────────────────

export interface BlacklistResponse {
  uuid: string;
  name?: string;
  assetUuid?: string;
  meta?: Record<string, unknown>;
  status?: {
    creator?: { uuid: string; name?: string };
    created?: string;
    editor?: { uuid: string; name?: string };
    edited?: string;
  };
}

export interface BlacklistListResponse {
  data: BlacklistResponse[];
  _metainfo?: {
    totalCount?: number;
    currentPage?: number;
    pageCount?: number;
    perPage?: number;
  };
}

export interface BlacklistCreateRequest {
  name?: string;
  assetUuid?: string;
  meta?: Record<string, unknown>;
}

export interface BlacklistUpdateRequest {
  name?: string;
  assetUuid?: string;
  meta?: Record<string, unknown>;
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

export async function listBlacklists(token: string): Promise<BlacklistListResponse> {
  const res = await fetch(`${API_BASE_URL}/blacklists`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<BlacklistListResponse>(res);
}

export async function loadBlacklist(
  token: string,
  uuid: string
): Promise<BlacklistResponse> {
  const res = await fetch(`${API_BASE_URL}/blacklists/${encodeURIComponent(uuid)}`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<BlacklistResponse>(res);
}

export async function createBlacklist(
  token: string,
  request: BlacklistCreateRequest
): Promise<BlacklistResponse> {
  const res = await fetch(`${API_BASE_URL}/blacklists`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<BlacklistResponse>(res);
}

export async function updateBlacklist(
  token: string,
  uuid: string,
  request: BlacklistUpdateRequest
): Promise<BlacklistResponse> {
  const res = await fetch(`${API_BASE_URL}/blacklists/${encodeURIComponent(uuid)}`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<BlacklistResponse>(res);
}

export async function deleteBlacklist(
  token: string,
  uuid: string
): Promise<void> {
  const res = await fetch(`${API_BASE_URL}/blacklists/${encodeURIComponent(uuid)}`, {
    method: "DELETE",
    headers: authHeaders(token),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`API error ${res.status}: ${text}`);
  }
}
