import { API_BASE_URL } from "./config";

export interface DetectionResponse {
  uuid: string;
  type: string;
  frameNumber: number;
  bboxX: number;
  bboxY: number;
  bboxWidth: number;
  bboxHeight: number;
  confidence: number;
  assetUuid: string;
  meta?: Record<string, unknown>;
  status?: {
    creator?: { uuid: string; name?: string };
    created?: string;
    editor?: { uuid: string; name?: string };
    edited?: string;
  };
}

export interface DetectionListResponse {
  data: DetectionResponse[];
  _metainfo?: {
    totalCount?: number;
    currentPage?: number;
    pageCount?: number;
    perPage?: number;
  };
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
