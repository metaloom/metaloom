import { API_BASE_URL } from "./config";
import { pagingQuery, type PagingInfo, type PagingParams } from "./paging";

/** Review verdict on a machine-proposed cluster. */
export const CLUSTER_STATUS_PENDING = "PENDING";
export const CLUSTER_STATUS_CONFIRMED = "CONFIRMED";
export const CLUSTER_STATUS_REJECTED = "REJECTED";

/** The cluster type the face workflow proposes and reviews. */
export const CLUSTER_TYPE_FACE = "face";

export interface ClusterResponse {
  uuid: string;
  name: string;
  type?: string;
  meta?: Record<string, unknown>;
  /**
   * Review verdict: PENDING, CONFIRMED or REJECTED.
   *
   * Named reviewStatus rather than status because `status` is already taken on this response by the
   * creator/editor audit block below. The two are unrelated.
   */
  reviewStatus?: string;
  /** The person this cluster was confirmed to be, or absent while unreviewed or rejected. */
  personUuid?: string;
  /** The asset the cluster was computed within, or absent for a human-authored cluster. */
  assetUuid?: string;
  clusterIndex?: number;
  /** Cohesion, or absent for a single-member cluster. */
  score?: number;
  /** How many embeddings belong to this cluster, so a card can say "N faces" without a members request. */
  memberCount?: number;
  nodeKind?: string;
  status?: {
    creator?: { uuid: string; name?: string };
    created?: string;
    editor?: { uuid: string; name?: string };
    edited?: string;
  };
}

/**
 * One face in a cluster, with the geometry of the detection it came from.
 *
 * `detectionUuid` is what addresses the face crop; the bounding box is a 0-1 factor of the frame.
 */
export interface ClusterMemberModel {
  embeddingUuid?: string;
  detectionUuid?: string;
  assetUuid?: string;
  frameNumber?: number;
  bboxX?: number;
  bboxY?: number;
  bboxWidth?: number;
  bboxHeight?: number;
  confidence?: number;
  origin?: string;
}

export interface ClusterMemberListResponse {
  members: ClusterMemberModel[];
  total: number;
}

export interface ClusterConfirmRequest {
  /** Link an existing person... */
  personUuid?: string;
  /** ...or leave personUuid unset and give a name to create one. Needs CREATE_PERSON. */
  alias?: string;
  firstname?: string;
  lastname?: string;
  /** Optional label for the grouping itself, distinct from the person's name. */
  name?: string;
}

export interface ClusterListResponse {
  data: ClusterResponse[];
  _metainfo?: PagingInfo;
}

export interface ClusterCreateRequest {
  name: string;
  type?: string;
  meta?: Record<string, unknown>;
}

export interface ClusterUpdateRequest {
  name?: string;
  type?: string;
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

export interface ClusterFilter {
  /** PENDING, CONFIRMED or REJECTED. */
  status?: string;
  /** Cluster type, e.g. "face". */
  type?: string;
}

export async function listClusters(
  token: string,
  paging?: PagingParams,
  filter?: ClusterFilter,
): Promise<ClusterListResponse> {
  const query = pagingQuery(paging);
  const extra: string[] = [];
  if (filter?.status) extra.push(`status=${encodeURIComponent(filter.status)}`);
  if (filter?.type) extra.push(`type=${encodeURIComponent(filter.type)}`);
  const suffix = extra.length === 0 ? "" : (query ? "&" : "?") + extra.join("&");

  const res = await fetch(`${API_BASE_URL}/clusters${query}${suffix}`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<ClusterListResponse>(res);
}

/** The faces in a cluster, each carrying the detection uuid its crop is addressed by. */
export async function listClusterMembers(token: string, uuid: string): Promise<ClusterMemberListResponse> {
  const res = await fetch(`${API_BASE_URL}/clusters/${encodeURIComponent(uuid)}/members`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<ClusterMemberListResponse>(res);
}

/** Confirm that a cluster is a person, linking an existing one or creating a new one. */
export async function confirmCluster(
  token: string,
  uuid: string,
  request: ClusterConfirmRequest,
): Promise<ClusterResponse> {
  const res = await fetch(`${API_BASE_URL}/clusters/${encodeURIComponent(uuid)}/confirm`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<ClusterResponse>(res);
}

/** Reject a cluster: it is not a subject worth keeping. */
export async function rejectCluster(token: string, uuid: string): Promise<ClusterResponse> {
  const res = await fetch(`${API_BASE_URL}/clusters/${encodeURIComponent(uuid)}/reject`, {
    method: "POST",
    headers: authHeaders(token),
  });
  return handleResponse<ClusterResponse>(res);
}

/** The clusters computed within one asset. */
export async function listAssetClusters(token: string, assetUuid: string): Promise<ClusterListResponse> {
  const res = await fetch(`${API_BASE_URL}/assets/${encodeURIComponent(assetUuid)}/clusters`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<ClusterListResponse>(res);
}

export async function loadCluster(token: string, uuid: string): Promise<ClusterResponse> {
  const res = await fetch(`${API_BASE_URL}/clusters/${encodeURIComponent(uuid)}`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<ClusterResponse>(res);
}

export async function createCluster(token: string, request: ClusterCreateRequest): Promise<ClusterResponse> {
  const res = await fetch(`${API_BASE_URL}/clusters`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<ClusterResponse>(res);
}

export async function updateCluster(token: string, uuid: string, request: ClusterUpdateRequest): Promise<ClusterResponse> {
  const res = await fetch(`${API_BASE_URL}/clusters/${encodeURIComponent(uuid)}`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<ClusterResponse>(res);
}

export async function deleteCluster(token: string, uuid: string): Promise<void> {
  const res = await fetch(`${API_BASE_URL}/clusters/${encodeURIComponent(uuid)}`, {
    method: "DELETE",
    headers: authHeaders(token),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`API error ${res.status}: ${text}`);
  }
}
