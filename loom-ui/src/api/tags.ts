import { API_BASE_URL } from "./config";
import { pagingQuery, type PagingInfo, type PagingParams } from "./paging";
import type { AreaInfo } from "./annotations";

export type { AreaInfo };

// ── Types matching the Loom REST API tag models ───────────────────────

export interface TagResponse {
  uuid: string;
  name: string;
  collection: string;
  color?: string;
  /** Spatial/temporal region of the asset this tag references (region tags only). */
  area?: AreaInfo;
  status?: {
    creator?: { uuid: string; name: string };
    created?: string;
    editor?: { uuid: string; name: string };
    edited?: string;
  };
  meta?: Record<string, unknown>;
  /** The current user's rating for this tag (populated client-side via loadTagRating). */
  userRating?: number;
}

export interface TagRatingResponse {
  rating?: number;
}

export interface TagListResponse {
  data: TagResponse[];
  _metainfo?: PagingInfo;
}

export interface TagCreateRequest {
  name: string;
  collection: string;
  /** Optional spatial/temporal region of the asset to tag. Omit for a plain tag. */
  area?: AreaInfo;
  meta?: Record<string, unknown>;
}

export interface TagUpdateRequest {
  name: string;
  collection: string;
  meta?: Record<string, unknown>;
}

/**
 * Namespace a screen writes a coined tag into.
 *
 * `tag` is `UNIQUE (name, collection)`, so `collection` is a free-text namespace on the tag — it
 * is *not* an asset collection, despite the name. Two screens that pick different namespaces for
 * the same word produce two tags a reviewer cannot tell apart, so every screen that lets a person
 * coin a tag writes into this one.
 */
export const DEFAULT_TAG_COLLECTION = "default";

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

export async function listTags(token: string, paging?: PagingParams): Promise<TagListResponse> {
  const res = await fetch(`${API_BASE_URL}/tags${pagingQuery(paging)}`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<TagListResponse>(res);
}

export async function loadTag(token: string, uuid: string): Promise<TagResponse> {
  const res = await fetch(`${API_BASE_URL}/tags/${encodeURIComponent(uuid)}`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<TagResponse>(res);
}

export async function createTag(token: string, request: TagCreateRequest): Promise<TagResponse> {
  const res = await fetch(`${API_BASE_URL}/tags`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<TagResponse>(res);
}

export async function updateTag(token: string, uuid: string, request: TagUpdateRequest): Promise<TagResponse> {
  const res = await fetch(`${API_BASE_URL}/tags/${encodeURIComponent(uuid)}`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<TagResponse>(res);
}

export async function deleteTag(token: string, uuid: string): Promise<void> {
  const res = await fetch(`${API_BASE_URL}/tags/${encodeURIComponent(uuid)}`, {
    method: "DELETE",
    headers: authHeaders(token),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`API error ${res.status}: ${text}`);
  }
}

// ── Per-user tag rating (/tags/:uuid/rating) ──────────────────────────

/** Set the current user's rating for a tag. */
export async function rateTag(token: string, uuid: string, rating: number): Promise<TagRatingResponse> {
  const res = await fetch(`${API_BASE_URL}/tags/${encodeURIComponent(uuid)}/rating`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify({ rating }),
  });
  return handleResponse<TagRatingResponse>(res);
}

/** Load the current user's rating for a tag. `rating` is undefined/null when the user has not rated it. */
export async function loadTagRating(token: string, uuid: string): Promise<TagRatingResponse> {
  const res = await fetch(`${API_BASE_URL}/tags/${encodeURIComponent(uuid)}/rating`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<TagRatingResponse>(res);
}

/** Remove the current user's rating for a tag (204). */
export async function deleteTagRating(token: string, uuid: string): Promise<void> {
  const res = await fetch(`${API_BASE_URL}/tags/${encodeURIComponent(uuid)}/rating`, {
    method: "DELETE",
    headers: authHeaders(token),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`API error ${res.status}: ${text}`);
  }
}

// ── Asset tagging (/assets/:uuid/tags) ────────────────────────────────

/** Tag an asset. Pass `area` to create a region (time + area) tag. Returns the created tag (201). */
export async function tagAsset(
  token: string,
  assetUuid: string,
  request: TagCreateRequest,
): Promise<TagResponse> {
  const res = await fetch(`${API_BASE_URL}/assets/${encodeURIComponent(assetUuid)}/tags`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<TagResponse>(res);
}

/** Remove a tag from an asset (204). */
export async function untagAsset(token: string, assetUuid: string, tagUuid: string): Promise<void> {
  const res = await fetch(
    `${API_BASE_URL}/assets/${encodeURIComponent(assetUuid)}/tags/${encodeURIComponent(tagUuid)}`,
    {
      method: "DELETE",
      headers: authHeaders(token),
    },
  );
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`API error ${res.status}: ${text}`);
  }
}
