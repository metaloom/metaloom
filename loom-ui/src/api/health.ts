import { API_BASE_URL } from "./config";
import { authHeaders, handleResponse } from "./http";

// ── Types matching the Loom REST HealthCheckResponse model ────────────
//
// The `GET /api/v1/health` endpoint is public (no auth guard) and always
// returns HTTP 200 — even when the database check fails, in which case
// `status` is "DEGRADED" and `database` is "DOWN". Only `status` is
// guaranteed non-null; the remaining fields are nullable in the model.

export interface HealthCheckResponse {
  status: string;      // "UP" | "DEGRADED"
  version?: string;
  database?: string;   // "UP" | "DOWN"
  timestamp?: string;  // ISO-8601 instant
}

// ── Helpers ───────────────────────────────────────────────────────────

// ── API ───────────────────────────────────────────────────────────────

export async function getHealth(token: string): Promise<HealthCheckResponse> {
  const res = await fetch(`${API_BASE_URL}/health`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<HealthCheckResponse>(res);
}
