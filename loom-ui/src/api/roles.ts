import { API_BASE_URL } from "./config";
import { pagingQuery, type PagingInfo, type PagingParams } from "./paging";
import { authHeaders, handleResponse } from "./http";

// ── Types matching the Loom REST API role models ──────────────────────

export interface RoleResponse {
  uuid: string;
  name: string;
  permissions?: string[];
  meta?: Record<string, unknown>;
  status?: {
    creator?: { uuid: string; name: string };
    created?: string;
    editor?: { uuid: string; name: string };
    edited?: string;
  };
}

export interface RoleListResponse {
  data: RoleResponse[];
  _metainfo?: PagingInfo;
}

export interface RoleCreateRequest {
  name: string;
  permissions?: string[];
  meta?: Record<string, unknown>;
}

export interface RoleUpdateRequest {
  name?: string;
  permissions?: string[];
  meta?: Record<string, unknown>;
}

// ── Helpers ───────────────────────────────────────────────────────────

// ── CRUD API ──────────────────────────────────────────────────────────

export async function listRoles(token: string, paging?: PagingParams): Promise<RoleListResponse> {
  const res = await fetch(`${API_BASE_URL}/roles${pagingQuery(paging)}`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<RoleListResponse>(res);
}

export async function loadRole(token: string, uuid: string): Promise<RoleResponse> {
  const res = await fetch(`${API_BASE_URL}/roles/${encodeURIComponent(uuid)}`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<RoleResponse>(res);
}

export async function createRole(token: string, request: RoleCreateRequest): Promise<RoleResponse> {
  const res = await fetch(`${API_BASE_URL}/roles`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<RoleResponse>(res);
}

export async function updateRole(token: string, uuid: string, request: RoleUpdateRequest): Promise<RoleResponse> {
  const res = await fetch(`${API_BASE_URL}/roles/${encodeURIComponent(uuid)}`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<RoleResponse>(res);
}

export async function deleteRole(token: string, uuid: string): Promise<void> {
  const res = await fetch(`${API_BASE_URL}/roles/${encodeURIComponent(uuid)}`, {
    method: "DELETE",
    headers: authHeaders(token),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`API error ${res.status}: ${text}`);
  }
}
