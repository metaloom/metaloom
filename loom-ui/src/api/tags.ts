import { API_BASE_URL } from "./config";

// ── Types matching the Loom REST API tag models ───────────────────────

export interface TagResponse {
  uuid: string;
  name: string;
  collection: string;
  color?: string;
  status?: {
    creator?: { uuid: string; name: string };
    created?: string;
    editor?: { uuid: string; name: string };
    edited?: string;
  };
  meta?: Record<string, unknown>;
}

export interface TagListResponse {
  data: TagResponse[];
  _metainfo?: {
    totalCount?: number;
    currentPage?: number;
    pageCount?: number;
    perPage?: number;
  };
}

export interface TagCreateRequest {
  name: string;
  collection: string;
  meta?: Record<string, unknown>;
}

export interface TagUpdateRequest {
  name: string;
  collection: string;
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

export async function listTags(token: string): Promise<TagListResponse> {
  const res = await fetch(`${API_BASE_URL}/tags`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<TagListResponse>(res);
}

export async function loadTag(token: string, uuid: string): Promise<TagResponse> {
  const res = await fetch(`${API_BASE_URL}/tags/${encodeURIComponent(uuid)}`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<TagResponse>(res);
}

export async function createTag(token: string, request: TagCreateRequest): Promise<TagResponse> {
  const res = await fetch(`${API_BASE_URL}/tags`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<TagResponse>(res);
}

export async function updateTag(token: string, uuid: string, request: TagUpdateRequest): Promise<TagResponse> {
  const res = await fetch(`${API_BASE_URL}/tags/${encodeURIComponent(uuid)}`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<TagResponse>(res);
}

export async function deleteTag(token: string, uuid: string): Promise<void> {
  const res = await fetch(`${API_BASE_URL}/tags/${encodeURIComponent(uuid)}`, {
    method: "DELETE",
    headers: authHeaders(token),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`API error ${res.status}: ${text}`);
  }
}
