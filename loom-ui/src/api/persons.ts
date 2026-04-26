import { API_BASE_URL } from "./config";

export interface PersonResponse {
  uuid: string;
  alias: string;
  firstname?: string;
  lastname?: string;
  primaryImageUuid?: string;
  meta?: Record<string, unknown>;
  status?: {
    creator?: { uuid: string; name?: string };
    created?: string;
    editor?: { uuid: string; name?: string };
    edited?: string;
  };
}

export interface PersonListResponse {
  data: PersonResponse[];
  _metainfo?: {
    totalCount?: number;
    currentPage?: number;
    pageCount?: number;
    perPage?: number;
  };
}

export interface PersonCreateRequest {
  alias: string;
  firstname?: string;
  lastname?: string;
  primaryImageUuid?: string;
  meta?: Record<string, unknown>;
}

export interface PersonUpdateRequest {
  alias?: string;
  firstname?: string;
  lastname?: string;
  primaryImageUuid?: string;
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

export async function listPersons(token: string): Promise<PersonListResponse> {
  const res = await fetch(`${API_BASE_URL}/persons`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<PersonListResponse>(res);
}

export async function loadPerson(token: string, uuid: string): Promise<PersonResponse> {
  const res = await fetch(`${API_BASE_URL}/persons/${encodeURIComponent(uuid)}`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<PersonResponse>(res);
}

export async function createPerson(token: string, request: PersonCreateRequest): Promise<PersonResponse> {
  const res = await fetch(`${API_BASE_URL}/persons`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<PersonResponse>(res);
}

export async function updatePerson(token: string, uuid: string, request: PersonUpdateRequest): Promise<PersonResponse> {
  const res = await fetch(`${API_BASE_URL}/persons/${encodeURIComponent(uuid)}`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<PersonResponse>(res);
}

export async function deletePerson(token: string, uuid: string): Promise<void> {
  const res = await fetch(`${API_BASE_URL}/persons/${encodeURIComponent(uuid)}`, {
    method: "DELETE",
    headers: authHeaders(token),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`API error ${res.status}: ${text}`);
  }
}
