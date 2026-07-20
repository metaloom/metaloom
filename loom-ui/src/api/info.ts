import { API_BASE_URL } from "./config";

// ── Types matching the Loom REST RESTInfoResponse model ───────────────
//
// The `GET /api/v1/` endpoint returns authoritative instance/system info
// sourced from the `loom` singleton row plus the running server version.
// The `loom` row is optional (its table is created without a seeded row),
// so `dbRevision` and `lastUsed` are nullable; only `version` is guaranteed.

export interface InfoResponse {
  version: string;
  dbRevision?: string;  // applied DB schema revision
  lastUsed?: string;    // ISO-8601 local date-time
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

// The info route is mounted at the API base path itself (`/api/v1`), so the
// request targets API_BASE_URL directly rather than a sub-path.
export async function getInfo(token: string): Promise<InfoResponse> {
  const res = await fetch(`${API_BASE_URL}`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<InfoResponse>(res);
}
