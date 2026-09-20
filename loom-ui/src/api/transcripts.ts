import { API_BASE_URL } from "./config";
import type { PagingInfo } from "./paging";
import { authHeaders, handleResponse } from "./http";

export interface TranscriptWordResponse {
  word: string;
  startTime: number;
  endTime: number;
  confidence: number;
}

export interface TranscriptSectionResponse {
  id: string;
  title: string;
  startTime: number;
  endTime: number;
  words: TranscriptWordResponse[];
}

export interface TranscriptResponse {
  uuid: string;
  assetUuid?: string;
  source?: string; // producing node kind (e.g. "whisper")
  lang?: string;
  transcriptText?: string;
  duration?: number; // milliseconds
  model?: string;
  transcriptJson?: {
    sections?: TranscriptSectionResponse[];
    /**
     * What the whisper node writes: one entry per utterance, with absolute millisecond bounds.
     *
     * A second shape rather than the node being made to emit `sections`, because the two are not
     * the same thing. A section is an editable chapter with per-word timing, which is what a human
     * or a forced aligner produces; whisper produces sentences with a start and an end and no word
     * boundaries at all. The UI folds these into sections for display — see AssetDetail.
     */
    segments?: TranscriptSegmentResponse[];
  };
  status?: {
    creator?: { uuid: string; name?: string };
    created?: string;
    editor?: { uuid: string; name?: string };
    edited?: string;
  };
}

/** One utterance from an ASR node. `from`/`to` are milliseconds into the asset. */
export interface TranscriptSegmentResponse {
  text?: string;
  from?: number;
  to?: number;
}

export interface TranscriptListResponse {
  data: TranscriptResponse[];
  _metainfo?: PagingInfo;
}

export interface TranscriptCreateRequest {
  source?: string;
  lang?: string;
  transcriptText?: string;
  duration?: number; // milliseconds — send integers
  model?: string;
  transcriptJson?: { sections?: TranscriptSectionResponse[] };
  meta?: Record<string, unknown>;
}

export type TranscriptUpdateRequest = TranscriptCreateRequest;

export async function listAssetTranscripts(
  token: string,
  assetUuid: string
): Promise<TranscriptListResponse> {
  const res = await fetch(`${API_BASE_URL}/assets/${encodeURIComponent(assetUuid)}/transcripts`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<TranscriptListResponse>(res);
}

export async function createTranscript(
  token: string,
  assetUuid: string,
  request: TranscriptCreateRequest
): Promise<TranscriptResponse> {
  const res = await fetch(`${API_BASE_URL}/assets/${encodeURIComponent(assetUuid)}/transcripts`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<TranscriptResponse>(res);
}

export async function loadTranscript(
  token: string,
  assetUuid: string,
  transcriptUuid: string
): Promise<TranscriptResponse> {
  const res = await fetch(
    `${API_BASE_URL}/assets/${encodeURIComponent(assetUuid)}/transcripts/${encodeURIComponent(transcriptUuid)}`,
    {
      method: "GET",
      headers: authHeaders(token),
    }
  );
  return handleResponse<TranscriptResponse>(res);
}

export async function updateTranscript(
  token: string,
  assetUuid: string,
  transcriptUuid: string,
  request: TranscriptUpdateRequest
): Promise<TranscriptResponse> {
  const res = await fetch(
    `${API_BASE_URL}/assets/${encodeURIComponent(assetUuid)}/transcripts/${encodeURIComponent(transcriptUuid)}`,
    {
      method: "POST",
      headers: authHeaders(token),
      body: JSON.stringify(request),
    }
  );
  return handleResponse<TranscriptResponse>(res);
}

export async function deleteTranscript(
  token: string,
  assetUuid: string,
  transcriptUuid: string
): Promise<void> {
  const res = await fetch(
    `${API_BASE_URL}/assets/${encodeURIComponent(assetUuid)}/transcripts/${encodeURIComponent(transcriptUuid)}`,
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
