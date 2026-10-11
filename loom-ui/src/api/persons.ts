import { API_BASE_URL } from "./config";
import type { ClusterListResponse } from "./clusters";
import { pagingQuery, type PagingInfo, type PagingParams } from "./paging";
import { authHeaders, ensureOk, handleResponse } from "./http";

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
  /**
   * Where the person's hero voice sample is served from, or absent when none is chosen.
   *
   * Needs the Authorization header like every other voice sample, so it is fetched as a blob
   * rather than pointed at by an `<audio src>`.
   */
  heroVoiceUrl?: string | null;
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

/**
 * One voice model's embedding of a voice sample.
 *
 * A sample is embedded once per model the voiceprint node runs with; `usable` is false when the
 * model found nothing to embed, and `error` says why.
 */
export interface PersonPrintResponse {
  uuid: string;
  sampleUuid: string;
  /** The cluster type the print compares with, e.g. "speaker". */
  type: string;
  model: string;
  dimensions?: number | null;
  usable: boolean;
  error?: string | null;
  /** When the print was last computed (ISO-8601). */
  edited?: string | null;
}

/** One recording of a person's voice. Like their pictures, it belongs to the person, not to an asset. */
export interface PersonVoiceSampleResponse {
  uuid: string;
  filename: string;
  mimeType: string;
  size: number;
  /** Absolute path of the bytes; needs the Authorization header. */
  url: string;
  hero: boolean;
  durationMs?: number | null;
  /** The asset the sample was cut from, or absent for an upload. Provenance only. */
  sourceAssetUuid?: string | null;
  sourceClusterUuid?: string | null;
  sourceTimeFrom?: number | null;
  sourceTimeTo?: number | null;
  prints: PersonPrintResponse[];
  status?: {
    creator?: { uuid: string; name?: string };
    created?: string;
    editor?: { uuid: string; name?: string };
    edited?: string;
  };
}

export interface PersonVoiceSampleListResponse {
  data: PersonVoiceSampleResponse[];
}

/**
 * Cut a voice sample out of a speaker cluster's asset.
 *
 * Both times or neither: without them the server takes the speaker's longest turn, at most 30
 * seconds of it. With them, at most 120 seconds apart.
 */
export interface PersonVoiceSampleImportRequest {
  clusterUuid: string;
  timeFrom?: number;
  timeTo?: number;
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
  await ensureOk(res);
}

/**
 * The clusters confirmed to be this person, across every asset they appear in - faces and
 * speakers alike, told apart by `type`.
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
  await ensureOk(res);
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

/** The person's voice samples, each with the prints computed from it. */
export async function listPersonVoiceSamples(token: string, uuid: string): Promise<PersonVoiceSampleListResponse> {
  const res = await fetch(`${API_BASE_URL}/persons/${encodeURIComponent(uuid)}/voice-samples`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<PersonVoiceSampleListResponse>(res);
}

/**
 * Upload a recording of this person. Audio or video; anything else is a 415.
 *
 * No `Content-Type` header: the browser sets it, boundary included.
 */
export async function uploadPersonVoiceSample(token: string, uuid: string, file: File): Promise<PersonVoiceSampleResponse> {
  const form = new FormData();
  form.append("file", file, file.name);
  const res = await fetch(`${API_BASE_URL}/persons/${encodeURIComponent(uuid)}/voice-samples`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}` },
    body: form,
  });
  return handleResponse<PersonVoiceSampleResponse>(res);
}

/**
 * Take a copy of a speaker's speech into the person's voice samples.
 *
 * The voice counterpart of {@link importPersonImage}: a copy, so it outlives the asset it was cut
 * from. Needs READ_CLUSTER, READ_ASSET_BINARY and UPDATE_PERSON.
 */
export async function importPersonVoiceSample(
  token: string,
  uuid: string,
  request: PersonVoiceSampleImportRequest,
): Promise<PersonVoiceSampleResponse> {
  const res = await fetch(`${API_BASE_URL}/persons/${encodeURIComponent(uuid)}/voice-samples/from-cluster`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<PersonVoiceSampleResponse>(res);
}

export async function deletePersonVoiceSample(token: string, uuid: string, sampleUuid: string): Promise<void> {
  const res = await fetch(`${API_BASE_URL}/persons/${encodeURIComponent(uuid)}/voice-samples/${encodeURIComponent(sampleUuid)}`, {
    method: "DELETE",
    headers: authHeaders(token),
  });
  await ensureOk(res);
}

/** Designate one of the person's voice samples as their hero voice. Pass null to clear it. */
export async function setPersonHeroVoice(token: string, uuid: string, sampleUuid: string | null): Promise<PersonResponse> {
  const res = await fetch(`${API_BASE_URL}/persons/${encodeURIComponent(uuid)}/hero-voice`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify({ sampleUuid: sampleUuid ?? "" }),
  });
  return handleResponse<PersonResponse>(res);
}
