/**
 * The customer-facing share area: `/shares/{slug}`.
 *
 * **This is the one client module that never sends a bearer token.** The caller has no Loom
 * account; they hold a link. Everything past `openShare` carries the opaque session token that
 * call returns, in `X-Loom-Share-Session`.
 *
 * `credentials: "include"` is set on every request so the `loom_share_session` cookie the server
 * also sets rides along. That cookie is what authenticates `<video src>` and `<img src>`, which
 * cannot carry a header — the header is for these `fetch` calls, the cookie is for the media
 * elements, and both must stay in step.
 */
import { API_BASE_URL } from "./config";
import { pagingQuery, type PagingInfo, type PagingParams } from "./paging";
import type { ShareAnnotationResponse, ShareCommentResponse, ShareReactionResponse, ShareTargetType } from "./shareLinks";

export type { ShareAnnotationResponse, ShareCommentResponse, ShareReactionResponse };

/** What the front door is told before anything has been proved. */
export interface ShareChallengeResponse {
  targetType: ShareTargetType;
  passwordRequired: boolean;
  /** True once somebody has named the link; the viewer then greets them instead of asking again. */
  visitorNameKnown: boolean;
  visitorName?: string;
}

export interface ShareSessionRequest {
  password?: string;
  visitorName?: string;
}

export interface ShareSessionResponse {
  sessionToken: string;
  sessionExpiresAt?: string;
  visitorName: string;
  targetType: ShareTargetType;
  targetName?: string;
  targetDescription?: string;
  allowDownload: boolean;
  showMetadata: boolean;
  allowComments: boolean;
  allowReactions: boolean;
  allowAnnotations: boolean;
}

/** One asset, in the narrow projection a visitor is allowed to see. */
export interface SharedAssetResponse {
  uuid: string;
  filename: string;
  mimeType: string;
  size?: number;
  duration?: number;
  width?: number;
  height?: number;
  title?: string;
  description?: string;
  created?: string;
}

export interface SharedAssetListResponse {
  data: SharedAssetResponse[];
  _metainfo?: PagingInfo;
}

export interface ShareCommentListResponse {
  data: ShareCommentResponse[];
  _metainfo?: PagingInfo;
}

export interface ShareAnnotationListResponse {
  data: ShareAnnotationResponse[];
  _metainfo?: PagingInfo;
}

export interface ShareReactionListResponse {
  data: ShareReactionResponse[];
  _metainfo?: PagingInfo;
}

export interface ShareCommentRequest {
  assetUuid?: string;
  parentUuid?: string;
  annotationUuid?: string;
  text: string;
}

export interface ShareAnnotationRequest {
  assetUuid: string;
  kind: "TEMPORAL" | "SPATIAL" | "SPATIOTEMPORAL";
  timeFrom?: number;
  timeTo?: number;
  areaX?: number;
  areaY?: number;
  areaWidth?: number;
  areaHeight?: number;
  text?: string;
}

export interface ShareReactionRequest {
  type: string;
  assetUuid?: string;
  commentUuid?: string;
  annotationUuid?: string;
}

/**
 * A failed share call, carrying the status so the viewer can tell the three cases apart.
 *
 * 404 means the link is gone or was never real, 401 means the session lapsed and the gate should
 * come back, 403 means the link does not allow what was attempted. Collapsing them into one error
 * string would leave the viewer unable to explain any of them.
 */
export class ShareApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = "ShareApiError";
    this.status = status;
  }
}

function shareHeaders(sessionToken?: string): Record<string, string> {
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  if (sessionToken) headers["X-Loom-Share-Session"] = sessionToken;
  return headers;
}

async function handleResponse<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new ShareApiError(res.status, text || `API error ${res.status}`);
  }
  return res.json() as Promise<T>;
}

function shareUrl(slug: string, suffix = ""): string {
  return `${API_BASE_URL}/shares/${encodeURIComponent(slug)}${suffix}`;
}

/** What the link asks for before it will open. Rejects with 404 for unknown, revoked and lapsed alike. */
export async function loadShareChallenge(slug: string): Promise<ShareChallengeResponse> {
  const res = await fetch(shareUrl(slug), { method: "GET", credentials: "include" });
  return handleResponse<ShareChallengeResponse>(res);
}

/** Open the link. The returned token must be passed to every call below. */
export async function openShare(slug: string, request: ShareSessionRequest): Promise<ShareSessionResponse> {
  const res = await fetch(shareUrl(slug, "/sessions"), {
    method: "POST",
    headers: shareHeaders(),
    credentials: "include",
    body: JSON.stringify(request),
  });
  return handleResponse<ShareSessionResponse>(res);
}

export async function listSharedAssets(
  slug: string,
  sessionToken: string,
  paging?: PagingParams,
): Promise<SharedAssetListResponse> {
  const res = await fetch(`${shareUrl(slug, "/assets")}${pagingQuery(paging)}`, {
    method: "GET",
    headers: shareHeaders(sessionToken),
    credentials: "include",
  });
  return handleResponse<SharedAssetListResponse>(res);
}

export async function loadSharedAsset(slug: string, sessionToken: string, assetUuid: string): Promise<SharedAssetResponse> {
  const res = await fetch(shareUrl(slug, `/assets/${encodeURIComponent(assetUuid)}`), {
    method: "GET",
    headers: shareHeaders(sessionToken),
    credentials: "include",
  });
  return handleResponse<SharedAssetResponse>(res);
}

/**
 * Where a shared asset's bytes live.
 *
 * Used directly as `<video src>` and `<img src>`, which is why it takes no token: those elements
 * cannot set a header and authenticate with the `loom_share_session` cookie instead. Same-origin
 * only, like every other media URL in this app.
 */
export function sharedBinaryUrl(slug: string, assetUuid: string): string {
  return shareUrl(slug, `/assets/${encodeURIComponent(assetUuid)}/binary/data`);
}

/** As above, but asks for the file as a download. Refused with 403 when the link forbids it. */
export function sharedDownloadUrl(slug: string, assetUuid: string): string {
  return `${sharedBinaryUrl(slug, assetUuid)}?download=1`;
}

// ── Feedback ──────────────────────────────────────────────────────────

export async function listSharedComments(slug: string, sessionToken: string): Promise<ShareCommentListResponse> {
  const res = await fetch(shareUrl(slug, "/comments"), {
    method: "GET",
    headers: shareHeaders(sessionToken),
    credentials: "include",
  });
  return handleResponse<ShareCommentListResponse>(res);
}

export async function createSharedComment(
  slug: string,
  sessionToken: string,
  request: ShareCommentRequest,
): Promise<ShareCommentResponse> {
  const res = await fetch(shareUrl(slug, "/comments"), {
    method: "POST",
    headers: shareHeaders(sessionToken),
    credentials: "include",
    body: JSON.stringify(request),
  });
  return handleResponse<ShareCommentResponse>(res);
}

export async function deleteSharedComment(slug: string, sessionToken: string, commentUuid: string): Promise<void> {
  const res = await fetch(shareUrl(slug, `/comments/${encodeURIComponent(commentUuid)}`), {
    method: "DELETE",
    headers: shareHeaders(sessionToken),
    credentials: "include",
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new ShareApiError(res.status, text || `API error ${res.status}`);
  }
}

export async function listSharedAnnotations(slug: string, sessionToken: string): Promise<ShareAnnotationListResponse> {
  const res = await fetch(shareUrl(slug, "/annotations"), {
    method: "GET",
    headers: shareHeaders(sessionToken),
    credentials: "include",
  });
  return handleResponse<ShareAnnotationListResponse>(res);
}

export async function createSharedAnnotation(
  slug: string,
  sessionToken: string,
  request: ShareAnnotationRequest,
): Promise<ShareAnnotationResponse> {
  const res = await fetch(shareUrl(slug, "/annotations"), {
    method: "POST",
    headers: shareHeaders(sessionToken),
    credentials: "include",
    body: JSON.stringify(request),
  });
  return handleResponse<ShareAnnotationResponse>(res);
}

export async function deleteSharedAnnotation(slug: string, sessionToken: string, annotationUuid: string): Promise<void> {
  const res = await fetch(shareUrl(slug, `/annotations/${encodeURIComponent(annotationUuid)}`), {
    method: "DELETE",
    headers: shareHeaders(sessionToken),
    credentials: "include",
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new ShareApiError(res.status, text || `API error ${res.status}`);
  }
}

export async function listSharedReactions(slug: string, sessionToken: string): Promise<ShareReactionListResponse> {
  const res = await fetch(shareUrl(slug, "/reactions"), {
    method: "GET",
    headers: shareHeaders(sessionToken),
    credentials: "include",
  });
  return handleResponse<ShareReactionListResponse>(res);
}

export async function createSharedReaction(
  slug: string,
  sessionToken: string,
  request: ShareReactionRequest,
): Promise<ShareReactionResponse> {
  const res = await fetch(shareUrl(slug, "/reactions"), {
    method: "POST",
    headers: shareHeaders(sessionToken),
    credentials: "include",
    body: JSON.stringify(request),
  });
  return handleResponse<ShareReactionResponse>(res);
}

export async function deleteSharedReaction(slug: string, sessionToken: string, reactionUuid: string): Promise<void> {
  const res = await fetch(shareUrl(slug, `/reactions/${encodeURIComponent(reactionUuid)}`), {
    method: "DELETE",
    headers: shareHeaders(sessionToken),
    credentials: "include",
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new ShareApiError(res.status, text || `API error ${res.status}`);
  }
}
