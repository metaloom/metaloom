import { API_BASE_URL } from "./config";

export interface SkillResponse {
  uuid: string;
  name: string;
  description: string;
  content: string;
  enabled: boolean;
  published: boolean;
  originSkillUuid?: string | null;
  /** Set on installed copies whose origin has been edited since the install. */
  updateAvailable?: boolean | null;
  meta?: Record<string, unknown>;
  status?: {
    creator?: { uuid: string; name?: string };
    created?: string;
    editor?: { uuid: string; name?: string };
    edited?: string;
  };
}

export interface SkillListResponse {
  data: SkillResponse[];
  _metainfo?: {
    totalCount?: number;
    currentPage?: number;
    pageCount?: number;
    perPage?: number;
  };
}

export interface SkillCreateRequest {
  name: string;
  description: string;
  content: string;
  enabled?: boolean;
  published?: boolean;
  meta?: Record<string, unknown>;
}

export interface SkillUpdateRequest {
  name?: string;
  description?: string;
  content?: string;
  enabled?: boolean;
  published?: boolean;
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

/** List the callers own skills. */
export async function listSkills(token: string): Promise<SkillListResponse> {
  const res = await fetch(`${API_BASE_URL}/skills`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<SkillListResponse>(res);
}

export async function loadSkill(token: string, uuid: string): Promise<SkillResponse> {
  const res = await fetch(`${API_BASE_URL}/skills/${encodeURIComponent(uuid)}`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<SkillResponse>(res);
}

export async function createSkill(token: string, request: SkillCreateRequest): Promise<SkillResponse> {
  const res = await fetch(`${API_BASE_URL}/skills`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<SkillResponse>(res);
}

// Updates use POST (not PUT) — loom convention
export async function updateSkill(token: string, uuid: string, request: SkillUpdateRequest): Promise<SkillResponse> {
  const res = await fetch(`${API_BASE_URL}/skills/${encodeURIComponent(uuid)}`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<SkillResponse>(res);
}

export async function deleteSkill(token: string, uuid: string): Promise<void> {
  const res = await fetch(`${API_BASE_URL}/skills/${encodeURIComponent(uuid)}`, {
    method: "DELETE",
    headers: authHeaders(token),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`API error ${res.status}: ${text}`);
  }
}

/** List the published skills of all users (the shared skill library). */
export async function listSkillLibrary(token: string): Promise<SkillListResponse> {
  const res = await fetch(`${API_BASE_URL}/skills/library`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<SkillListResponse>(res);
}

/** Install a published skill by copying it into the callers own skill set. */
export async function installSkill(token: string, uuid: string): Promise<SkillResponse> {
  const res = await fetch(`${API_BASE_URL}/skills/${encodeURIComponent(uuid)}/install`, {
    method: "POST",
    headers: authHeaders(token),
  });
  return handleResponse<SkillResponse>(res);
}
