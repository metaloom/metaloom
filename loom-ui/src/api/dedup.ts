import { API_BASE_URL } from "./config";
import { withPaging, type PagingInfo, type PagingParams } from "./paging";

// ── Types matching the Loom REST API dedup models ─────────────────────────
// Mirrors io.metaloom.loom.rest.model.dedup.*

/** Review status of a duplicate group. Only CONFIRMED groups are ever acted on by the apply node. */
export const STATUS_PENDING = "PENDING";
export const STATUS_CONFIRMED = "CONFIRMED";
export const STATUS_REJECTED = "REJECTED";

export type DedupStatus = "PENDING" | "CONFIRMED" | "REJECTED";

/** The asset the group proposes keeping. */
export const ROLE_KEEP = "KEEP";
/** A candidate duplicate the apply node may move once the group is confirmed. */
export const ROLE_DUP = "DUP";

export interface DedupGroupMemberModel {
  assetUuid: string;
  role: string;
  score?: number;
  /** Size in bytes, snapshotted at discovery time. */
  size?: number;
  /**
   * Number of all-zero chunks found at discovery time. Anything above 0 means a truncated or
   * still-downloading file — which is exactly what a plausible-looking duplicate can turn out to be.
   */
  zeroChunkCount?: number;
}

export interface DedupGroupResponse {
  uuid: string;
  algorithm: string;
  status: string;
  /**
   * The asset the group keeps. Denormalised, and NOT mirrored back into the member roles when a
   * reviewer reassigns it — prefer this over `role === "KEEP"` when both are present.
   */
  keepAssetUuid?: string;
  /** The minimum member score: how close a call the whole proposal is. */
  score?: number;
  members?: DedupGroupMemberModel[];
}

export interface DedupGroupListResponse {
  data: DedupGroupResponse[];
  _metainfo?: PagingInfo;
}

export interface DedupGroupUpdateRequest {
  /**
   * Required by the server even when only the keep is being reassigned — send the group's current
   * status in that case.
   */
  status: string;
  keepAssetUuid?: string;
}

// ── Helpers ───────────────────────────────────────────────────────────────

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

// ── Review queue API ──────────────────────────────────────────────────────

/**
 * List review groups, newest first.
 *
 * The review queue is `status: "PENDING"`. The route is keyset paged and caps at 25 rows by
 * default, so an unpaged call returns a page rather than the collection.
 */
export async function listDedupGroups(
  token: string,
  opts: { status?: string } & PagingParams = {}
): Promise<DedupGroupListResponse> {
  const { status, ...paging } = opts;
  const base = status
    ? `${API_BASE_URL}/dedup-groups?status=${encodeURIComponent(status)}`
    : `${API_BASE_URL}/dedup-groups`;
  const res = await fetch(withPaging(base, paging), {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<DedupGroupListResponse>(res);
}

export async function loadDedupGroup(token: string, uuid: string): Promise<DedupGroupResponse> {
  const res = await fetch(`${API_BASE_URL}/dedup-groups/${encodeURIComponent(uuid)}`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<DedupGroupResponse>(res);
}

/**
 * Record the reviewer's decision.
 *
 * PATCH, not POST — this route predates and deliberately deviates from the POST-to-update
 * convention the rest of the API follows.
 */
export async function updateDedupGroup(
  token: string,
  uuid: string,
  request: DedupGroupUpdateRequest
): Promise<DedupGroupResponse> {
  const res = await fetch(`${API_BASE_URL}/dedup-groups/${encodeURIComponent(uuid)}`, {
    method: "PATCH",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<DedupGroupResponse>(res);
}

/** Discard a proposal outright, without deciding it. Its members are removed with it. */
export async function deleteDedupGroup(token: string, uuid: string): Promise<void> {
  const res = await fetch(`${API_BASE_URL}/dedup-groups/${encodeURIComponent(uuid)}`, {
    method: "DELETE",
    headers: authHeaders(token),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`API error ${res.status}: ${text}`);
  }
}

/** The groups one asset takes part in, as keep or as duplicate. */
export async function listAssetDedupGroups(
  token: string,
  assetUuid: string
): Promise<DedupGroupListResponse> {
  const res = await fetch(`${API_BASE_URL}/assets/${encodeURIComponent(assetUuid)}/dedup-groups`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<DedupGroupListResponse>(res);
}
