import { API_BASE_URL } from "./config";

// ── Types matching the Loom REST API group models ─────────────────────

export interface GroupResponse {
  uuid: string;
  name: string;
  meta?: Record<string, unknown>;
  status?: {
    creator?: { uuid: string; name: string };
    created?: string;
    editor?: { uuid: string; name: string };
    edited?: string;
  };
}

export interface GroupListResponse {
  data: GroupResponse[];
  _metainfo?: {
    totalCount?: number;
    currentPage?: number;
    pageCount?: number;
    perPage?: number;
  };
}

export interface GroupCreateRequest {
  name: string;
  meta?: Record<string, unknown>;
}

export interface GroupUpdateRequest {
  name?: string;
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

export async function listGroups(token: string): Promise<GroupListResponse> {
  const res = await fetch(`${API_BASE_URL}/groups`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<GroupListResponse>(res);
}

export async function loadGroup(token: string, uuid: string): Promise<GroupResponse> {
  const res = await fetch(`${API_BASE_URL}/groups/${encodeURIComponent(uuid)}`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<GroupResponse>(res);
}

export async function createGroup(token: string, request: GroupCreateRequest): Promise<GroupResponse> {
  const res = await fetch(`${API_BASE_URL}/groups`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<GroupResponse>(res);
}

export async function updateGroup(token: string, uuid: string, request: GroupUpdateRequest): Promise<GroupResponse> {
  const res = await fetch(`${API_BASE_URL}/groups/${encodeURIComponent(uuid)}`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<GroupResponse>(res);
}

export async function deleteGroup(token: string, uuid: string): Promise<void> {
  const res = await fetch(`${API_BASE_URL}/groups/${encodeURIComponent(uuid)}`, {
    method: "DELETE",
    headers: authHeaders(token),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`API error ${res.status}: ${text}`);
  }
}
