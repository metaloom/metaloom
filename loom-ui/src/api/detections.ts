import { API_BASE_URL } from "./config";
import type { PagingInfo } from "./paging";

/** The three review verdicts, mirroring the `review_status` Postgres enum. */
export type ReviewStatus = "PENDING" | "CONFIRMED" | "REJECTED";

export interface DetectionResponse {
  uuid: string;
  type: string;
  /**
   * The detected class, e.g. "dog". Null for a face — `facedetect` has no classes to report.
   *
   * This is what the model said. A reviewer's correction goes to `correctedLabel` instead, so the
   * model's own answer survives as the training signal.
   */
  label?: string;
  frameNumber: number;
  bboxX: number;
  bboxY: number;
  bboxWidth: number;
  bboxHeight: number;
  confidence: number;
  assetUuid: string;
  meta?: Record<string, unknown>;
  /**
   * Review verdict.
   *
   * Named `reviewStatus` rather than `status` because `status` below is already taken by the
   * creator/editor audit block every response carries.
   */
  reviewStatus?: ReviewStatus;
  reviewedAt?: string;
  correctedLabel?: string;
  status?: {
    creator?: { uuid: string; name?: string };
    created?: string;
    editor?: { uuid: string; name?: string };
    edited?: string;
  };
}

export interface DetectionConfirmRequest {
  correctedLabel?: string;
}

export interface DetectionReviewItem {
  uuid: string;
  status: ReviewStatus;
  correctedLabel?: string;
}

export interface DetectionBulkReviewRequest {
  reviews: DetectionReviewItem[];
}

export interface DetectionListResponse {
  data: DetectionResponse[];
  _metainfo?: PagingInfo;
}

export interface DetectionCreateRequest {
  type: string;
  frameNumber?: number;
  bboxX?: number;
  bboxY?: number;
  bboxWidth?: number;
  bboxHeight?: number;
  confidence?: number;
  meta?: Record<string, unknown>;
}

export interface DetectionUpdateRequest {
  type?: string;
  frameNumber?: number;
  bboxX?: number;
  bboxY?: number;
  bboxWidth?: number;
  bboxHeight?: number;
  confidence?: number;
  meta?: Record<string, unknown>;
}

export interface DetectionBulkCreateRequest {
  detections: DetectionCreateRequest[];
}

export interface DetectionBulkResponse {
  detections: DetectionResponse[];
  total: number;
  created: number;
  failed: number;
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

export async function listAssetDetections(
  token: string,
  assetUuid: string
): Promise<DetectionListResponse> {
  const res = await fetch(`${API_BASE_URL}/assets/${assetUuid}/detections`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<DetectionListResponse>(res);
}

export async function loadDetection(
  token: string,
  assetUuid: string,
  detectionUuid: string
): Promise<DetectionResponse> {
  const res = await fetch(
    `${API_BASE_URL}/assets/${assetUuid}/detections/${detectionUuid}`,
    {
      method: "GET",
      headers: authHeaders(token),
    }
  );
  return handleResponse<DetectionResponse>(res);
}

export async function createDetection(
  token: string,
  assetUuid: string,
  request: DetectionCreateRequest
): Promise<DetectionResponse> {
  const res = await fetch(`${API_BASE_URL}/assets/${assetUuid}/detections`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<DetectionResponse>(res);
}

export async function updateDetection(
  token: string,
  assetUuid: string,
  detectionUuid: string,
  request: DetectionUpdateRequest
): Promise<DetectionResponse> {
  const res = await fetch(
    `${API_BASE_URL}/assets/${assetUuid}/detections/${detectionUuid}`,
    {
      method: "POST",
      headers: authHeaders(token),
      body: JSON.stringify(request),
    }
  );
  return handleResponse<DetectionResponse>(res);
}

export async function deleteDetection(
  token: string,
  assetUuid: string,
  detectionUuid: string
): Promise<void> {
  const res = await fetch(
    `${API_BASE_URL}/assets/${assetUuid}/detections/${detectionUuid}`,
    {
      method: "DELETE",
      headers: authHeaders(token),
    }
  );
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`API error ${res.status}: ${text}`);
  }
}

export async function bulkCreateDetections(
  token: string,
  assetUuid: string,
  request: DetectionBulkCreateRequest
): Promise<DetectionBulkResponse> {
  const res = await fetch(
    `${API_BASE_URL}/assets/${assetUuid}/detections/bulk`,
    {
      method: "POST",
      headers: authHeaders(token),
      body: JSON.stringify(request),
    }
  );
  return handleResponse<DetectionBulkResponse>(res);
}

/**
 * Confirm a detection: the producer found something real here.
 *
 * Pass `correctedLabel` when the box was right but the class was wrong. That is still a
 * confirmation — the producer's own `label` is kept alongside the correction.
 */
export async function confirmDetection(
  token: string,
  assetUuid: string,
  detectionUuid: string,
  request?: DetectionConfirmRequest
): Promise<DetectionResponse> {
  const res = await fetch(
    `${API_BASE_URL}/assets/${assetUuid}/detections/${detectionUuid}/confirm`,
    {
      method: "POST",
      headers: authHeaders(token),
      body: JSON.stringify(request ?? {}),
    }
  );
  return handleResponse<DetectionResponse>(res);
}

/**
 * Reject a detection as a false positive.
 *
 * The row is kept, not deleted: it is the record that the producer was wrong here, which is what
 * stops the next run presenting the same box again as new.
 */
export async function rejectDetection(
  token: string,
  assetUuid: string,
  detectionUuid: string
): Promise<DetectionResponse> {
  const res = await fetch(
    `${API_BASE_URL}/assets/${assetUuid}/detections/${detectionUuid}/reject`,
    {
      method: "POST",
      headers: authHeaders(token),
    }
  );
  return handleResponse<DetectionResponse>(res);
}

/**
 * Record many verdicts on one asset in a single request.
 *
 * Keyboard review outruns per-item HTTP badly enough that one request per keystroke either lags
 * behind the reviewer or drops decisions, so this is the shape the review screen uses.
 *
 * A bad item is counted in `failed` and skipped rather than failing the batch.
 */
export async function bulkReviewDetections(
  token: string,
  assetUuid: string,
  request: DetectionBulkReviewRequest
): Promise<DetectionBulkResponse> {
  const res = await fetch(
    `${API_BASE_URL}/assets/${assetUuid}/detections/review-bulk`,
    {
      method: "POST",
      headers: authHeaders(token),
      body: JSON.stringify(request),
    }
  );
  return handleResponse<DetectionBulkResponse>(res);
}

/**
 * The cross-asset review queue.
 *
 * Unlike {@link listAssetDetections} this spans every asset, which is the one question that cannot
 * be asked of a single one: what is still waiting to be reviewed.
 */
export async function listDetections(
  token: string,
  status?: ReviewStatus,
  type?: string
): Promise<DetectionListResponse> {
  const params = new URLSearchParams();
  if (status) {
    params.set("status", status);
  }
  if (type) {
    params.set("type", type);
  }
  const query = params.toString();
  const res = await fetch(`${API_BASE_URL}/detections${query ? `?${query}` : ""}`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<DetectionListResponse>(res);
}

/**
 * Fetch the cropped face image for a detection.
 *
 * Served from this deployment's own storage. The review UI used to stand these in with portraits from
 * a third-party avatar service, which both leaked detection uuids to that service and showed the
 * reviewer a stranger's face — face crops are biometric data and do not leave the deployment.
 *
 * A crop only exists once the face-detection node has run, so a 404 is a normal state rather than an
 * error to surface.
 */
export async function fetchDetectionCrop(
  token: string,
  assetUuid: string,
  detectionUuid: string
): Promise<Blob | null> {
  const res = await fetch(
    `${API_BASE_URL}/assets/${encodeURIComponent(assetUuid)}/detections/${encodeURIComponent(detectionUuid)}/crop`,
    {
      method: "GET",
      headers: { Authorization: `Bearer ${token}` },
    }
  );
  if (res.status === 404) {
    return null;
  }
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`API error ${res.status}: ${text}`);
  }
  return res.blob();
}
