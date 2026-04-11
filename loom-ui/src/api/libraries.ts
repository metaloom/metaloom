import { API_BASE_URL } from "./config";

export interface LibraryResponse {
  uuid: string;
  name: string;
  meta?: Record<string, unknown>;
  status?: {
    creator?: { uuid: string; name?: string };
    created?: string;
    editor?: { uuid: string; name?: string };
    edited?: string;
  };
}

export interface LibraryListResponse {
  data: LibraryResponse[];
  _metainfo?: {
    totalCount?: number;
    currentPage?: number;
    pageCount?: number;
    perPage?: number;
  };
}

export interface LibraryCreateRequest {
  name: string;
  meta?: Record<string, unknown>;
}

export interface LibraryUpdateRequest {
  name?: string;
  meta?: Record<string, unknown>;
}

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

export async function listLibraries(token: string): Promise<LibraryListResponse> {
  const res = await fetch(`${API_BASE_URL}/libraries`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<LibraryListResponse>(res);
}

export async function loadLibrary(token: string, uuid: string): Promise<LibraryResponse> {
  const res = await fetch(`${API_BASE_URL}/libraries/${encodeURIComponent(uuid)}`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<LibraryResponse>(res);
}

export async function createLibrary(token: string, request: LibraryCreateRequest): Promise<LibraryResponse> {
  const res = await fetch(`${API_BASE_URL}/libraries`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<LibraryResponse>(res);
}

export async function updateLibrary(token: string, uuid: string, request: LibraryUpdateRequest): Promise<LibraryResponse> {
  const res = await fetch(`${API_BASE_URL}/libraries/${encodeURIComponent(uuid)}`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<LibraryResponse>(res);
}

export async function deleteLibrary(token: string, uuid: string): Promise<void> {
  const res = await fetch(`${API_BASE_URL}/libraries/${encodeURIComponent(uuid)}`, {
    method: "DELETE",
    headers: authHeaders(token),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`API error ${res.status}: ${text}`);
  }
}
