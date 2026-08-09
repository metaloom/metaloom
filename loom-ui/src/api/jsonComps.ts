import { API_BASE_URL } from "./config";
import { pagingQuery, type PagingInfo, type PagingParams } from "./paging";

/**
 * Client for the per-asset JSON components — `/assets/:uuid/json-comps`.
 *
 * A JSON component is where a node parks a structured result that has no column of its own: the
 * `vlm` node writes one per prompt, keyed by `schemaType: "vlm"` with the prompt id as `variant`.
 * The row is the record; the ledger entry in `asset_node_result` only points at it.
 */

export interface JsonCompResponse {
  uuid: string;
  assetUuid?: string;
  /** The node that produced it, e.g. `vlm`. */
  nodeKind?: string;
  /** What the payload is, e.g. `vlm`. Not the same as nodeKind — one node can write several shapes. */
  schemaType?: string;
  /** Discriminator within the schema. For `vlm` this is the prompt id. */
  variant?: string;
  /** The model or version that produced it. */
  producerVersion?: string;
  data?: Record<string, unknown>;
  status?: {
    creator?: { uuid: string; name?: string };
    created?: string;
    editor?: { uuid: string; name?: string };
    edited?: string;
  };
}

export interface JsonCompListResponse {
  data: JsonCompResponse[];
  _metainfo?: PagingInfo;
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

/** List every JSON component of an asset. */
export async function listAssetJsonComps(
  token: string, assetUuid: string, paging?: PagingParams,
): Promise<JsonCompListResponse> {
  const res = await fetch(
    `${API_BASE_URL}/assets/${encodeURIComponent(assetUuid)}/json-comps${pagingQuery(paging)}`,
    { method: "GET", headers: authHeaders(token) },
  );
  return handleResponse<JsonCompListResponse>(res);
}

/**
 * The human-readable text of a component payload.
 *
 * `VlmNode` parses the model's reply as JSON when it can and otherwise wraps it as `{"text": …}`, so
 * a payload is either a document with a text-ish field or an object worth showing verbatim. The
 * field names tried below are the ones the node's own prompts ask for; anything else is rendered as
 * pretty JSON rather than silently reported as empty — a result that exists but has an unfamiliar
 * shape must never look like a result that is missing.
 */
export function compText(comp: JsonCompResponse): string {
  const data = comp.data;
  if (!data) return "";
  for (const key of ["text", "caption", "description", "answer", "summary"]) {
    const value = data[key];
    if (typeof value === "string" && value.trim()) return value.trim();
  }
  return JSON.stringify(data, null, 2);
}
