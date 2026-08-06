import { API_BASE_URL } from "./config";
import { pagingQuery, type PagingInfo, type PagingParams } from "./paging";

// ── Types matching the Loom REST API token response ───────────────────

export interface TokenResponse {
  uuid: string;
  name: string;
  token?: string; // Only returned on create
  status?: {
    creator?: { uuid: string; username?: string };
    editor?: { uuid: string; username?: string };
    created?: string;
    edited?: string;
  };
  meta?: Record<string, unknown>;
}

export interface TokenListResponse {
  data: TokenResponse[];
  _metainfo?: PagingInfo;
}

export interface TokenCreateRequest {
  name: string;
  meta?: Record<string, unknown>;
}

export interface TokenUpdateRequest {
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

// ── CRUD ──────────────────────────────────────────────────────────────

export async function listTokens(token: string, paging?: PagingParams): Promise<TokenListResponse> {
  const res = await fetch(`${API_BASE_URL}/tokens${pagingQuery(paging)}`, { headers: authHeaders(token) });
  return handleResponse<TokenListResponse>(res);
}

export async function loadToken(token: string, uuid: string): Promise<TokenResponse> {
  const res = await fetch(`${API_BASE_URL}/tokens/${encodeURIComponent(uuid)}`, { headers: authHeaders(token) });
  return handleResponse<TokenResponse>(res);
}

export async function createToken(token: string, request: TokenCreateRequest): Promise<TokenResponse> {
  const res = await fetch(`${API_BASE_URL}/tokens`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<TokenResponse>(res);
}

export async function updateToken(token: string, uuid: string, request: TokenUpdateRequest): Promise<TokenResponse> {
  const res = await fetch(`${API_BASE_URL}/tokens/${encodeURIComponent(uuid)}`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<TokenResponse>(res);
}

export async function deleteToken(token: string, uuid: string): Promise<void> {
  const res = await fetch(`${API_BASE_URL}/tokens/${encodeURIComponent(uuid)}`, {
    method: "DELETE",
    headers: authHeaders(token),
  });
  if (!res.ok && res.status !== 204) {
    const text = await res.text().catch(() => "");
    throw new Error(`API error ${res.status}: ${text}`);
  }
}
