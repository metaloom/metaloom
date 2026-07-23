import { API_BASE_URL } from "./config";

// ── Types matching the Loom REST API annotation models ────────────────

export interface AreaInfo {
  from?: number;
  to?: number;
  width?: number;
  height?: number;
  startX?: number;
  startY?: number;
}

export interface AnnotationResponseItem {
  uuid: string;
  type?: string;
  title?: string;
  description?: string;
  area?: AreaInfo;
  assetUuid?: string;
  meta?: Record<string, unknown>;
  status?: {
    creator?: { uuid: string; username?: string };
    created?: string;
    editor?: { uuid: string; username?: string };
    edited?: string;
  };
}

export interface AnnotationListResponse {
  data: AnnotationResponseItem[];
  _metainfo?: {
    totalCount?: number;
    currentPage?: number;
    pageCount?: number;
    perPage?: number;
  };
}

export interface AnnotationCreateRequest {
  type?: string;
  title?: string;
  description?: string;
  area?: AreaInfo;
  assetUuid?: string;
  meta?: Record<string, unknown>;
}

export interface AnnotationUpdateRequest {
  type?: string;
  title?: string;
  description?: string;
  area?: AreaInfo;
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

export async function listAnnotations(token: string): Promise<AnnotationListResponse> {
  const res = await fetch(`${API_BASE_URL}/annotations`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<AnnotationListResponse>(res);
}

export async function loadAnnotation(token: string, uuid: string): Promise<AnnotationResponseItem> {
  const res = await fetch(`${API_BASE_URL}/annotations/${encodeURIComponent(uuid)}`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<AnnotationResponseItem>(res);
}

export async function createAnnotation(
  token: string,
  request: AnnotationCreateRequest
): Promise<AnnotationResponseItem> {
  const res = await fetch(`${API_BASE_URL}/annotations`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<AnnotationResponseItem>(res);
}

export async function updateAnnotation(
  token: string,
  uuid: string,
  request: AnnotationUpdateRequest
): Promise<AnnotationResponseItem> {
  const res = await fetch(`${API_BASE_URL}/annotations/${encodeURIComponent(uuid)}`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<AnnotationResponseItem>(res);
}

export async function deleteAnnotation(token: string, uuid: string): Promise<void> {
  const res = await fetch(`${API_BASE_URL}/annotations/${encodeURIComponent(uuid)}`, {
    method: "DELETE",
    headers: authHeaders(token),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`API error ${res.status}: ${text}`);
  }
}
