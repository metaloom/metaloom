import { API_BASE_URL } from "./config";
import { pagingQuery, type PagingInfo, type PagingParams } from "./paging";
import { authHeaders, handleResponse } from "./http";

export interface LibraryResponse {
  uuid: string;
  name: string;
  /** Storage pool binaries uploaded into this library land in. Absent means the server's local upload directory. */
  poolUuid?: string;
  /** Backend kind behind {@link poolUuid} — "filesystem" or "s3". The server never returns null. */
  storageType?: string;
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
  _metainfo?: PagingInfo;
}

export interface LibraryCreateRequest {
  name: string;
  poolUuid?: string;
  meta?: Record<string, unknown>;
}

export interface LibraryUpdateRequest {
  name?: string;
  poolUuid?: string;
  meta?: Record<string, unknown>;
}

export async function listLibraries(token: string, paging?: PagingParams): Promise<LibraryListResponse> {
  const res = await fetch(`${API_BASE_URL}/libraries${pagingQuery(paging)}`, {
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
