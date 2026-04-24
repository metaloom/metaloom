import { API_BASE_URL } from "./config";

// ── Types matching the Loom REST API collection models ────────────────

export interface CollectionResponse {
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

export interface CollectionListResponse {
  data: CollectionResponse[];
  _metainfo?: {
    totalCount?: number;
    currentPage?: number;
    pageCount?: number;
    perPage?: number;
  };
}

export interface CollectionCreateRequest {
  name: string;
}

export interface CollectionUpdateRequest {
  name?: string;
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

export async function listCollections(token: string): Promise<CollectionListResponse> {
  const res = await fetch(`${API_BASE_URL}/collections`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<CollectionListResponse>(res);
}

export async function loadCollection(token: string, uuid: string): Promise<CollectionResponse> {
  const res = await fetch(`${API_BASE_URL}/collections/${encodeURIComponent(uuid)}`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<CollectionResponse>(res);
}

export async function createCollection(token: string, request: CollectionCreateRequest): Promise<CollectionResponse> {
  const res = await fetch(`${API_BASE_URL}/collections`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<CollectionResponse>(res);
}

export async function updateCollection(token: string, uuid: string, request: CollectionUpdateRequest): Promise<CollectionResponse> {
  const res = await fetch(`${API_BASE_URL}/collections/${encodeURIComponent(uuid)}`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<CollectionResponse>(res);
}

export async function deleteCollection(token: string, uuid: string): Promise<void> {
  const res = await fetch(`${API_BASE_URL}/collections/${encodeURIComponent(uuid)}`, {
    method: "DELETE",
    headers: authHeaders(token),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`API error ${res.status}: ${text}`);
  }
}
