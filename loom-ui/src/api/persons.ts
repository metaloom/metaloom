import { API_BASE_URL } from "./config";
import type { ClusterListResponse } from "./clusters";
import { pagingQuery, type PagingInfo, type PagingParams } from "./paging";

export interface PersonResponse {
  uuid: string;
  alias: string;
  firstname?: string;
  lastname?: string;
  /**
   * Where the person's avatar is served from, or absent when they have none.
   *
   * A URL rather than a uuid: how a person's picture is addressed is the server's
   * business. It used to be `primaryImageUuid`, which pointed at an *asset* - so for
   * somebody discovered in a video it resolved to the whole video file.
   */
  avatarUrl?: string;
  meta?: Record<string, unknown>;
  status?: {
    creator?: { uuid: string; name?: string };
    created?: string;
    editor?: { uuid: string; name?: string };
    edited?: string;
  };
}

/** One picture belonging to a person. */
export interface PersonImageResponse {
  uuid: string;
  filename: string;
  mimeType: string;
  size: number;
  url: string;
  avatar: boolean;
  status?: {
    creator?: { uuid: string; name?: string };
    created?: string;
    editor?: { uuid: string; name?: string };
    edited?: string;
  };
}

export interface PersonImageListResponse {
  data: PersonImageResponse[];
  _metainfo?: PagingInfo;
}

export interface PersonListResponse {
  data: PersonResponse[];
  _metainfo?: PagingInfo;
}

export interface PersonCreateRequest {
  alias: string;
  firstname?: string;
  lastname?: string;
  meta?: Record<string, unknown>;
}

export interface PersonUpdateRequest {
  alias?: string;
  firstname?: string;
  lastname?: string;
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

export async function listPersons(token: string, paging?: PagingParams): Promise<PersonListResponse> {
  const res = await fetch(`${API_BASE_URL}/persons${pagingQuery(paging)}`, {
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

/**
 * The face clusters confirmed to be this person, across every asset they appear in.
 *
 * The inverse of confirming a cluster.
 */
export async function listPersonClusters(token: string, uuid: string): Promise<ClusterListResponse> {
  const res = await fetch(`${API_BASE_URL}/persons/${encodeURIComponent(uuid)}/clusters`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<ClusterListResponse>(res);
}

/**
 * The person's own pictures, newest first.
 *
 * These belong to the person rather than to any asset, so they survive deleting the
 * material somebody was found in.
 */
export async function listPersonImages(token: string, uuid: string): Promise<PersonImageListResponse> {
  const res = await fetch(`${API_BASE_URL}/persons/${encodeURIComponent(uuid)}/images`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<PersonImageListResponse>(res);
}

/**
 * Upload a picture of this person.
 *
 * No `Content-Type` header: the browser sets it, boundary included.
 */
export async function uploadPersonImage(token: string, uuid: string, file: File): Promise<PersonImageResponse> {
  const form = new FormData();
  form.append("file", file, file.name);
  const res = await fetch(`${API_BASE_URL}/persons/${encodeURIComponent(uuid)}/images`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}` },
    body: form,
  });
  return handleResponse<PersonImageResponse>(res);
}

/**
 * Take a copy of a detection's face crop into the person's own images.
 *
 * The one-click path from "discovered in a video" to a real avatar. It is a copy, so the
 * result outlives the asset the face was found in.
 */
export async function importPersonImage(token: string, uuid: string, detectionUuid: string): Promise<PersonImageResponse> {
  const res = await fetch(`${API_BASE_URL}/persons/${encodeURIComponent(uuid)}/images/from-detection`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify({ detectionUuid }),
  });
  return handleResponse<PersonImageResponse>(res);
}

export async function deletePersonImage(token: string, uuid: string, imageUuid: string): Promise<void> {
  const res = await fetch(`${API_BASE_URL}/persons/${encodeURIComponent(uuid)}/images/${encodeURIComponent(imageUuid)}`, {
    method: "DELETE",
    headers: authHeaders(token),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`API error ${res.status}: ${text}`);
  }
}

/**
 * Designate one of the person's images as their avatar. Pass null to clear it.
 */
export async function setPersonAvatar(token: string, uuid: string, imageUuid: string | null): Promise<PersonResponse> {
  const res = await fetch(`${API_BASE_URL}/persons/${encodeURIComponent(uuid)}/avatar`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify({ imageUuid: imageUuid ?? "" }),
  });
  return handleResponse<PersonResponse>(res);
}
