import { API_BASE_URL } from "./config";
import { pagingQuery, type PagingInfo, type PagingParams } from "./paging";
import { authHeaders, handleResponse } from "./http";

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
  /** UUID of the skill_version this response was rendered from (active version on a skill, historic version on a version-list entry). */
  versionUuid?: string | null;
  /** Version number of the rendered skill_version (active version on a skill, historic version on a version-list entry). */
  versionNumber?: number | null;
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
  _metainfo?: PagingInfo;
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

/** List the callers own skills. */
export async function listSkills(token: string, paging?: PagingParams): Promise<SkillListResponse> {
  const res = await fetch(`${API_BASE_URL}/skills${pagingQuery(paging)}`, {
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
export async function listSkillLibrary(token: string, paging?: PagingParams): Promise<SkillListResponse> {
  const res = await fetch(`${API_BASE_URL}/skills/library${pagingQuery(paging)}`, {
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

/** Paged list of a skill's versions. Each entry is a SkillResponse carrying that version's body + versionNumber. */
export interface SkillVersionListResponse {
  data: SkillResponse[];
  _metainfo?: PagingInfo;
}

/** List the versions of a skill. Degrades to an empty list on a non-ok response. */
export async function listSkillVersions(token: string, uuid: string): Promise<SkillResponse[]> {
  const res = await fetch(`${API_BASE_URL}/skills/${encodeURIComponent(uuid)}/versions`, {
    method: "GET",
    headers: authHeaders(token),
  });
  if (!res.ok) {
    return [];
  }
  const body = (await res.json()) as SkillVersionListResponse | SkillResponse[];
  return Array.isArray(body) ? body : (body.data ?? []);
}

/** Load a specific version of a skill. */
export async function loadSkillVersion(token: string, uuid: string, versionNumber: number): Promise<SkillResponse> {
  const res = await fetch(`${API_BASE_URL}/skills/${encodeURIComponent(uuid)}/versions/${versionNumber}`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<SkillResponse>(res);
}

/**
 * Revert a skill to the given version. This deletes all newer versions and re-points the skill's active version at the target.
 * Returns the updated skill (carrying the reverted body).
 */
export async function restoreSkillVersion(token: string, uuid: string, versionNumber: number): Promise<SkillResponse> {
  const res = await fetch(`${API_BASE_URL}/skills/${encodeURIComponent(uuid)}/versions/${versionNumber}/restore`, {
    method: "POST",
    headers: authHeaders(token),
  });
  return handleResponse<SkillResponse>(res);
}
