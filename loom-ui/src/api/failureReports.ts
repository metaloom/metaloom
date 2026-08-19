import { API_BASE_URL } from "./config";
import { authHeaders, handleResponse } from "./http";
import { pagingQuery, type PagingInfo, type PagingParams } from "./paging";

// ── Types matching the Loom REST failure report models ────────────────

export type FailureReportTriageStatus = "NEW" | "ACKNOWLEDGED" | "RESOLVED";

export interface FailureReportResponse {
  uuid: string;
  action: string;
  traceId?: string;
  httpMethod?: string;
  path?: string;
  statusCode?: number;
  errorMessage?: string;
  route?: string;
  userAgent?: string;
  text?: string;
  /** Not `status`: that property carries the creator/editor audit block. */
  triageStatus: FailureReportTriageStatus;
  hasScreenshot?: boolean;
  /** Absolute URL of the screenshot, or absent when there is none. Never inlined. */
  screenshotUrl?: string;
  status?: {
    creator?: { uuid: string; name: string };
    created?: string;
    editor?: { uuid: string; name: string };
    edited?: string;
  };
}

export interface FailureReportListResponse {
  data: FailureReportResponse[];
  _metainfo?: PagingInfo;
}

/**
 * What the report dialog sends.
 *
 * Only `action` is required. Everything describing the failing request is optional, because the
 * failures worth reporting include the ones that produced no response at all - a render throw
 * caught by the error boundary, a screen that simply stayed empty.
 *
 * There is no `userAgent` field: the server stamps it from the request headers, because observed
 * provenance is worth more than declared provenance.
 */
export interface FailureReportCreateRequest {
  action: string;
  traceId?: string;
  httpMethod?: string;
  path?: string;
  statusCode?: number;
  errorMessage?: string;
  route?: string;
  text?: string;
  /** A data URL or bare base64. PNG, JPEG and WebP only - the server sniffs the bytes. */
  screenshot?: string;
  screenshotWidth?: number;
  screenshotHeight?: number;
}

export interface FailureReportUpdateRequest {
  triageStatus: FailureReportTriageStatus;
}

// ── API ───────────────────────────────────────────────────────────────

/**
 * Submit a problem report.
 *
 * Needs authentication and no permission - see `FailureReportEndpointService`. That is what makes
 * this callable from the failure path of any screen, including screens the user was denied.
 */
export async function createFailureReport(
  token: string,
  request: FailureReportCreateRequest,
): Promise<FailureReportResponse> {
  const res = await fetch(`${API_BASE_URL}/failure-reports`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<FailureReportResponse>(res, { action: "submitFailureReport", method: "POST", path: "/failure-reports" });
}

export async function listFailureReports(
  token: string,
  paging?: PagingParams,
): Promise<FailureReportListResponse> {
  const res = await fetch(`${API_BASE_URL}/failure-reports${pagingQuery(paging)}`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<FailureReportListResponse>(res, { action: "listFailureReports", method: "GET", path: "/failure-reports" });
}

export async function loadFailureReport(token: string, uuid: string): Promise<FailureReportResponse> {
  const res = await fetch(`${API_BASE_URL}/failure-reports/${encodeURIComponent(uuid)}`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<FailureReportResponse>(res, { action: "loadFailureReport", method: "GET", path: "/failure-reports" });
}

export async function updateFailureReport(
  token: string,
  uuid: string,
  request: FailureReportUpdateRequest,
): Promise<FailureReportResponse> {
  const res = await fetch(`${API_BASE_URL}/failure-reports/${encodeURIComponent(uuid)}`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(request),
  });
  return handleResponse<FailureReportResponse>(res, { action: "triageFailureReport", method: "POST", path: "/failure-reports" });
}

export async function deleteFailureReport(token: string, uuid: string): Promise<void> {
  const res = await fetch(`${API_BASE_URL}/failure-reports/${encodeURIComponent(uuid)}`, {
    method: "DELETE",
    headers: authHeaders(token),
  });
  await handleResponse<void>(res, { action: "deleteFailureReport", method: "DELETE", path: "/failure-reports" });
}
