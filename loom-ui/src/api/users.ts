import { API_BASE_URL } from "./config";

// ── Types matching the Loom REST API user models ──────────────────────

export interface UserResponse {
  uuid: string;
  username: string;
  firstname?: string;
  lastname?: string;
  email?: string;
  enabled: boolean;
  meta?: Record<string, unknown>;
  status?: {
    creator?: { uuid: string; name: string };
    created?: string;
    editor?: { uuid: string; name: string };
    edited?: string;
  };
}

export interface UserListResponse {
  data: UserResponse[];
  _metainfo?: {
    totalCount?: number;
    currentPage?: number;
    pageCount?: number;
    perPage?: number;
  };
}

export interface UserCreateRequest {
  username: string;
  firstname?: string;
  lastname?: string;
  email?: string;
  meta?: Record<string, unknown>;
}

export interface UserUpdateRequest {
  username?: string;
  firstname?: string;
  lastname?: string;
  email?: string;
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

export async function listUsers(token: string): Promise<UserListResponse> {
  const res = await fetch(`${API_BASE_URL}/users`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<UserListResponse>(res);
}

export async function loadUser(token: string, uuid: string): Promise<UserResponse> {
  const res = await fetch(`${API_BASE_URL}/users/${encodeURIComponent(uuid)}`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<UserResponse>(res);
}

export async function createUser(token: string, request: UserCreateRequest): Promise<UserResponse> {
  const res = await fetch(`${API_BASE_URL}/users`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<UserResponse>(res);
}

export async function updateUser(token: string, uuid: string, request: UserUpdateRequest): Promise<UserResponse> {
  const res = await fetch(`${API_BASE_URL}/users/${encodeURIComponent(uuid)}`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<UserResponse>(res);
}

export async function deleteUser(token: string, uuid: string): Promise<void> {
  const res = await fetch(`${API_BASE_URL}/users/${encodeURIComponent(uuid)}`, {
    method: "DELETE",
    headers: authHeaders(token),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`API error ${res.status}: ${text}`);
  }
}
