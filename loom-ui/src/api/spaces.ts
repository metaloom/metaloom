import { API_BASE_URL } from "./config";
import { pagingQuery, type PagingInfo, type PagingParams } from "./paging";
import { authHeaders, handleResponse } from "./http";

// ── Types matching the Loom REST API space models ─────────────────────

export interface SpaceResponse {
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

export interface SpaceListResponse {
  data: SpaceResponse[];
  _metainfo?: PagingInfo;
}

export interface SpaceCreateRequest {
  name: string;
  meta?: Record<string, unknown>;
}

export interface SpaceUpdateRequest {
  name?: string;
  meta?: Record<string, unknown>;
}

// ── Helpers ───────────────────────────────────────────────────────────

// ── CRUD API ──────────────────────────────────────────────────────────

export async function listSpaces(token: string, paging?: PagingParams): Promise<SpaceListResponse> {
  const res = await fetch(`${API_BASE_URL}/spaces${pagingQuery(paging)}`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<SpaceListResponse>(res);
}

export async function loadSpace(token: string, uuid: string): Promise<SpaceResponse> {
  const res = await fetch(`${API_BASE_URL}/spaces/${encodeURIComponent(uuid)}`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<SpaceResponse>(res);
}

export async function createSpace(token: string, request: SpaceCreateRequest): Promise<SpaceResponse> {
  const res = await fetch(`${API_BASE_URL}/spaces`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<SpaceResponse>(res);
}

export async function updateSpace(token: string, uuid: string, request: SpaceUpdateRequest): Promise<SpaceResponse> {
  const res = await fetch(`${API_BASE_URL}/spaces/${encodeURIComponent(uuid)}`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<SpaceResponse>(res);
}

export async function deleteSpace(token: string, uuid: string): Promise<void> {
  const res = await fetch(`${API_BASE_URL}/spaces/${encodeURIComponent(uuid)}`, {
    method: "DELETE",
    headers: authHeaders(token),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`API error ${res.status}: ${text}`);
  }
}
