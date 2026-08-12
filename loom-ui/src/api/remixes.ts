import { API_BASE_URL } from "./config";
import { pagingQuery, type PagingInfo, type PagingParams } from "./paging";
import { searchResults, type SearchResultResponse } from "./search";

// ── Types matching the Loom REST API remix models ─────────────────────
// Hand-written to mirror RemixResponse / RemixMemberResponse. There is no OpenAPI
// codegen in this project; the Java models are the source of truth.

/** The part an asset plays inside a remix. Mirrors RemixRole. */
export type RemixRole = "SOURCE" | "DERIVED";

export interface RemixResponse {
  uuid: string;
  name: string;
  description?: string | null;
  /** The original the remix is built around, or null when it is not in the catalogue. */
  sourceAssetUuid?: string | null;
  /**
   * Number of assets in the remix. Always present on a REST response; absent when the object was
   * built from a search hit, which carries a title and a uuid and no count.
   */
  memberCount?: number;
  meta?: Record<string, unknown>;
  status?: {
    creator?: { uuid: string; name: string };
    created?: string;
    editor?: { uuid: string; name: string };
    edited?: string;
  };
}

export interface RemixListResponse {
  data: RemixResponse[];
  _metainfo?: PagingInfo;
}

/**
 * One membership. `uuid` is the membership's own identity, not the asset's — removing a
 * member is keyed on `assetUuid`.
 */
export interface RemixMemberResponse {
  uuid: string;
  assetUuid: string;
  role: RemixRole;
  ordinal?: number | null;
  filename?: string;
  mimeType?: string;
  sha512sum?: string;
  size?: number;
  added?: string;
  addedBy?: string;
}

export interface RemixMemberListResponse {
  data: RemixMemberResponse[];
  _metainfo?: PagingInfo;
}

export interface RemixCreateRequest {
  name: string;
  description?: string;
  /** May be empty. Creating with members is one call so a failed second call cannot leave an empty remix. */
  assetUuids?: string[];
  /** Must be one of assetUuids. */
  sourceAssetUuid?: string;
}

export interface RemixUpdateRequest {
  name?: string;
  description?: string;
  sourceAssetUuid?: string;
}

export interface RemixMemberRequest {
  assetUuids: string[];
  sourceAssetUuid?: string;
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

async function expectNoContent(res: Response): Promise<void> {
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`API error ${res.status}: ${text}`);
  }
}

// ── CRUD API ──────────────────────────────────────────────────────────

export async function listRemixes(token: string, paging?: PagingParams): Promise<RemixListResponse> {
  const res = await fetch(`${API_BASE_URL}/remixes${pagingQuery(paging)}`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<RemixListResponse>(res);
}

export async function loadRemix(token: string, uuid: string): Promise<RemixResponse> {
  const res = await fetch(`${API_BASE_URL}/remixes/${encodeURIComponent(uuid)}`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<RemixResponse>(res);
}

export async function createRemix(token: string, request: RemixCreateRequest): Promise<RemixResponse> {
  const res = await fetch(`${API_BASE_URL}/remixes`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<RemixResponse>(res);
}

/** Update is POST, not PUT — the server's convention throughout. */
export async function updateRemix(token: string, uuid: string, request: RemixUpdateRequest): Promise<RemixResponse> {
  const res = await fetch(`${API_BASE_URL}/remixes/${encodeURIComponent(uuid)}`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<RemixResponse>(res);
}

export async function deleteRemix(token: string, uuid: string): Promise<void> {
  const res = await fetch(`${API_BASE_URL}/remixes/${encodeURIComponent(uuid)}`, {
    method: "DELETE",
    headers: authHeaders(token),
  });
  return expectNoContent(res);
}

// ── Membership ────────────────────────────────────────────────────────

export async function listRemixMembers(
  token: string,
  uuid: string,
  paging?: PagingParams,
): Promise<RemixMemberListResponse> {
  const res = await fetch(`${API_BASE_URL}/remixes/${encodeURIComponent(uuid)}/assets${pagingQuery(paging)}`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<RemixMemberListResponse>(res);
}

/**
 * Add assets to a remix. One request for the whole selection, and adding something that
 * is already a member is not an error.
 */
export async function addRemixAssets(token: string, uuid: string, assetUuids: string[]): Promise<RemixResponse> {
  const res = await fetch(`${API_BASE_URL}/remixes/${encodeURIComponent(uuid)}/assets`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify({ assetUuids } satisfies RemixMemberRequest),
  });
  return handleResponse<RemixResponse>(res);
}

export async function removeRemixAsset(token: string, uuid: string, assetUuid: string): Promise<void> {
  const res = await fetch(
    `${API_BASE_URL}/remixes/${encodeURIComponent(uuid)}/assets/${encodeURIComponent(assetUuid)}`,
    { method: "DELETE", headers: authHeaders(token) },
  );
  return expectNoContent(res);
}

/** Promote a member to source. The previous source is demoted server-side, in one transaction. */
export async function setRemixSource(token: string, uuid: string, assetUuid: string): Promise<RemixResponse> {
  const res = await fetch(`${API_BASE_URL}/remixes/${encodeURIComponent(uuid)}/source`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify({ assetUuids: [], sourceAssetUuid: assetUuid } satisfies RemixMemberRequest),
  });
  return handleResponse<RemixResponse>(res);
}

export async function listAssetRemixes(token: string, assetUuid: string, paging?: PagingParams): Promise<RemixListResponse> {
  const res = await fetch(`${API_BASE_URL}/assets/${encodeURIComponent(assetUuid)}/remixes${pagingQuery(paging)}`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<RemixListResponse>(res);
}

/**
 * Create a remix out of a selection, in one call.
 *
 * <p>This is what the asset browser's "combine into remix" action uses. Sending the
 * members with the create rather than as a follow-up means a failure cannot leave an
 * empty remix behind.</p>
 */
export async function combineIntoRemix(
  token: string,
  name: string,
  assetUuids: string[],
  description?: string,
): Promise<RemixResponse> {
  return createRemix(token, { name, description, assetUuids });
}

/**
 * Search remixes by name, description or the filenames of their members.
 *
 * <p>Goes to `/search/results` with `types=remix` rather than `/search/assets`, which forces
 * `types={asset}` server-side and can therefore never return a remix. Returns search hits, not
 * `RemixResponse` objects — a hit carries the uuid and title, which is all a card needs.</p>
 */
export async function searchRemixes(
  token: string,
  query: string,
  opts: { limit?: number; signal?: AbortSignal } = {},
): Promise<SearchResultResponse> {
  return searchResults(
    token,
    { q: query, types: ["remix"], limit: opts.limit ?? 25 },
    { signal: opts.signal },
  );
}
