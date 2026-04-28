import { API_BASE_URL } from "./config";

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
  source?: string;
  lang?: string;
  transcriptText?: string;
  duration?: number;
  model?: string;
  transcriptJson?: {
    sections?: TranscriptSectionResponse[];
  };
  status?: {
    creator?: { uuid: string; name?: string };
    created?: string;
    editor?: { uuid: string; name?: string };
    edited?: string;
  };
}

export interface TranscriptListResponse {
  data: TranscriptResponse[];
  _metainfo?: {
    totalCount?: number;
    currentPage?: number;
    pageCount?: number;
    perPage?: number;
  };
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
