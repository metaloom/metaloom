import { API_BASE_URL } from "./config";
import { pagingQuery, type PagingInfo, type PagingParams } from "./paging";

// ── Types matching the Loom REST API user models ──────────────────────

export interface UserResponse {
  uuid: string;
  username: string;
  firstname?: string;
  lastname?: string;
  email?: string;
  enabled: boolean;
  /**
   * Where the account's picture is served from, or null/absent when it has none.
   *
   * Always the /users/:uuid form, even when read through /me: it goes into an <img src>
   * that other people's browsers load too, where a self-relative URL would show each of
   * them their own face.
   */
  avatarUrl?: string | null;
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
  _metainfo?: PagingInfo;
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

/**
 * A typed error, so a caller can tell a 403 from a network failure without matching on
 * the message text.
 */
export class UserApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = "UserApiError";
    this.status = status;
  }
}

async function handleResponse<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new UserApiError(res.status, `API error ${res.status}: ${text}`);
  }
  return res.json() as Promise<T>;
}

// ── CRUD API ──────────────────────────────────────────────────────────

export async function listUsers(token: string, paging?: PagingParams): Promise<UserListResponse> {
  const res = await fetch(`${API_BASE_URL}/users${pagingQuery(paging)}`, {
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

// ── The account picture ───────────────────────────────────────────────
//
// Against /me rather than /users/:uuid on purpose. UPDATE_USER is the permission to edit
// anybody's account and no ordinary user holds it, so a profile screen that used the
// administrative route would work for administrators only.

export interface UserAvatarResponse {
  uuid: string;
  filename: string;
  mimeType: string;
  size: number;
  url: string;
}

/**
 * Upload the signed-in user's picture, replacing any previous one.
 *
 * Deliberately sets no Content-Type: the browser has to supply the multipart boundary,
 * and setting the header by hand produces a body the server cannot parse.
 */
export async function uploadMyAvatar(token: string, file: File): Promise<UserAvatarResponse> {
  const form = new FormData();
  form.append("file", file);
  const res = await fetch(`${API_BASE_URL}/me/avatar`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}` },
    body: form,
  });
  return handleResponse<UserAvatarResponse>(res);
}

/** Remove the signed-in user's picture. */
export async function deleteMyAvatar(token: string): Promise<void> {
  const res = await fetch(`${API_BASE_URL}/me/avatar`, {
    method: "DELETE",
    headers: authHeaders(token),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new UserApiError(res.status, `API error ${res.status}: ${text}`);
  }
}
