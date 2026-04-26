import { API_BASE_URL } from "./config";

export interface ClusterResponse {
  uuid: string;
  name: string;
  type?: string;
  meta?: Record<string, unknown>;
  status?: {
    creator?: { uuid: string; name?: string };
    created?: string;
    editor?: { uuid: string; name?: string };
    edited?: string;
  };
}

export interface ClusterListResponse {
  data: ClusterResponse[];
  _metainfo?: {
    totalCount?: number;
    currentPage?: number;
    pageCount?: number;
    perPage?: number;
  };
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

export async function listClusters(token: string): Promise<ClusterListResponse> {
  const res = await fetch(`${API_BASE_URL}/clusters`, {
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
