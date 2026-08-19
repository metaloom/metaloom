import { API_BASE_URL } from "./config";
import { authHeaders, handleResponse } from "./http";

// ── Types matching the Loom REST notification models ──────────────────

export type NotificationType =
  | "TASK_ASSIGNED"
  | "TASK_UNASSIGNED"
  | "TASK_STATUS_CHANGED"
  | "TASK_COMMENT"
  | "COMMENT_REPLY"
  | "PIPELINE_RUN_FAILED";

export interface NotificationResponse {
  uuid: string;
  type: NotificationType;
  read: boolean;
  readAt?: string;
  title: string;
  body?: string;
  // At most one subject reference is set; it drives the deep link.
  taskUuid?: string;
  commentUuid?: string;
  pipelineRunUuid?: string;
  assetUuid?: string;
  viaGroupUuid?: string;
  meta?: Record<string, unknown>;
  // status.creator is the ACTOR — whoever did the thing — not the recipient. It is absent
  // for a machine-generated entry such as a failed pipeline run.
  status?: {
    creator?: { uuid: string; name?: string };
    created?: string;
    editor?: { uuid: string; name?: string };
    edited?: string;
  };
}

export interface NotificationListResponse {
  data: NotificationResponse[];
  // The caller's total unread count, not the page's. The badge needs the former.
  unreadCount: number;
  _metainfo?: {
    totalCount?: number;
    perPage?: number;
  };
}

// ── Helpers ───────────────────────────────────────────────────────────

async function expectNoContent(res: Response): Promise<void> {
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`API error ${res.status}: ${text}`);
  }
}

// ── API ───────────────────────────────────────────────────────────────

export async function listNotifications(
  token: string,
  opts: { unreadOnly?: boolean } = {},
): Promise<NotificationListResponse> {
  const query = opts.unreadOnly ? "?unread=true" : "";
  const res = await fetch(`${API_BASE_URL}/notifications${query}`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<NotificationListResponse>(res);
}

export async function markNotificationRead(
  token: string,
  uuid: string,
  read = true,
): Promise<NotificationResponse> {
  const res = await fetch(`${API_BASE_URL}/notifications/${encodeURIComponent(uuid)}`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify({ read }),
  });
  return handleResponse<NotificationResponse>(res);
}

export async function markAllNotificationsRead(token: string): Promise<void> {
  const res = await fetch(`${API_BASE_URL}/notifications/read-all`, {
    method: "POST",
    headers: authHeaders(token),
  });
  await expectNoContent(res);
}

export async function deleteNotification(token: string, uuid: string): Promise<void> {
  const res = await fetch(`${API_BASE_URL}/notifications/${encodeURIComponent(uuid)}`, {
    method: "DELETE",
    headers: authHeaders(token),
  });
  await expectNoContent(res);
}

export async function clearNotifications(token: string): Promise<void> {
  const res = await fetch(`${API_BASE_URL}/notifications`, {
    method: "DELETE",
    headers: authHeaders(token),
  });
  await expectNoContent(res);
}
