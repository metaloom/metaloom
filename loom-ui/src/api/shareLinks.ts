/**
 * Share links, from the owner's side: `/share-links`.
 *
 * Ordinary authenticated calls. The customer's side of the same feature is `api/shares.ts`, which
 * carries a different credential entirely — see the note at the top of that file.
 */
import { API_BASE_URL } from "./config";
import { pagingQuery, type PagingInfo, type PagingParams } from "./paging";

export type ShareTargetType = "ASSET" | "COLLECTION";

export interface ShareResponse {
  uuid: string;
  /** The public half of the link. */
  slug: string;
  /** Absolute URL of the customer view, assembled server-side from the request host. Paste it as-is. */
  url: string;
  targetType: ShareTargetType;
  targetUuid: string;
  /** Name of the shared asset or collection, so a list of links reads without a second request. */
  targetName?: string;
  /**
   * The password in clear.
   *
   * Present **only** on the response to the request that set it — the server stores a bcrypt hash
   * and cannot return it again. The dialog therefore has to show it immediately rather than behind
   * a "reveal" the user may never press.
   */
  password?: string;
  passwordProtected: boolean;
  expired: boolean;
  expiresAt?: string;
  allowDownload: boolean;
  showMetadata: boolean;
  allowComments: boolean;
  allowReactions: boolean;
  allowAnnotations: boolean;
  /** The name the first visitor gave. Absent until somebody opens the link. */
  visitorName?: string;
  firstVisitedAt?: string;
  lastViewedAt?: string;
  viewCount: number;
  feedbackCount: number;
  status?: {
    creator?: { uuid: string; name: string };
    created?: string;
    editor?: { uuid: string; name: string };
    edited?: string;
  };
}

export interface ShareListResponse {
  data: ShareResponse[];
  _metainfo?: PagingInfo;
}

export interface ShareCreateRequest {
  targetType: ShareTargetType;
  targetUuid: string;
  /** Omit for an open link. */
  password?: string;
  /** ISO instant. Omit for a link that never expires. */
  expiresAt?: string;
  allowDownload?: boolean;
  showMetadata?: boolean;
  allowComments?: boolean;
  allowReactions?: boolean;
  allowAnnotations?: boolean;
}

export interface ShareUpdateRequest {
  password?: string;
  /** Wins over `password` when both are sent. */
  removePassword?: boolean;
  expiresAt?: string;
  clearExpiry?: boolean;
  allowDownload?: boolean;
  showMetadata?: boolean;
  allowComments?: boolean;
  allowReactions?: boolean;
  allowAnnotations?: boolean;
}

export interface ShareCommentResponse {
  uuid: string;
  assetUuid?: string;
  parentUuid?: string;
  annotationUuid?: string;
  text: string;
  authorName: string;
  created: string;
  edited?: string;
}

export interface ShareAnnotationResponse {
  uuid: string;
  assetUuid: string;
  kind: "TEMPORAL" | "SPATIAL" | "SPATIOTEMPORAL";
  timeFrom?: number;
  timeTo?: number;
  areaX?: number;
  areaY?: number;
  areaWidth?: number;
  areaHeight?: number;
  text?: string;
  authorName: string;
  created: string;
  edited?: string;
}

export interface ShareReactionResponse {
  uuid: string;
  type: string;
  assetUuid?: string;
  commentUuid?: string;
  annotationUuid?: string;
  authorName: string;
  created: string;
}

export interface ShareFeedbackResponse {
  uuid: string;
  visitorName?: string;
  comments: ShareCommentResponse[];
  annotations: ShareAnnotationResponse[];
  reactions: ShareReactionResponse[];
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

export async function listShareLinks(token: string, paging?: PagingParams): Promise<ShareListResponse> {
  const res = await fetch(`${API_BASE_URL}/share-links${pagingQuery(paging)}`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<ShareListResponse>(res);
}

export async function createShareLink(token: string, request: ShareCreateRequest): Promise<ShareResponse> {
  const res = await fetch(`${API_BASE_URL}/share-links`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<ShareResponse>(res);
}

export async function updateShareLink(token: string, uuid: string, request: ShareUpdateRequest): Promise<ShareResponse> {
  const res = await fetch(`${API_BASE_URL}/share-links/${encodeURIComponent(uuid)}`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<ShareResponse>(res);
}

export async function deleteShareLink(token: string, uuid: string): Promise<void> {
  const res = await fetch(`${API_BASE_URL}/share-links/${encodeURIComponent(uuid)}`, {
    method: "DELETE",
    headers: authHeaders(token),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`API error ${res.status}: ${text}`);
  }
}

/** The links pointing at one asset. */
export async function listAssetShareLinks(token: string, assetUuid: string, paging?: PagingParams): Promise<ShareListResponse> {
  const res = await fetch(`${API_BASE_URL}/assets/${encodeURIComponent(assetUuid)}/share-links${pagingQuery(paging)}`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<ShareListResponse>(res);
}

/** The links pointing at one collection. */
export async function listCollectionShareLinks(
  token: string,
  collectionUuid: string,
  paging?: PagingParams,
): Promise<ShareListResponse> {
  const res = await fetch(`${API_BASE_URL}/collections/${encodeURIComponent(collectionUuid)}/share-links${pagingQuery(paging)}`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<ShareListResponse>(res);
}

/** Everything the visitor said through one link. */
export async function loadShareFeedback(token: string, uuid: string): Promise<ShareFeedbackResponse> {
  const res = await fetch(`${API_BASE_URL}/share-links/${encodeURIComponent(uuid)}/feedback`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<ShareFeedbackResponse>(res);
}
