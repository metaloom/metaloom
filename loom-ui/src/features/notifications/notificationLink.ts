import { NotificationResponse } from "../../api/notifications";

/**
 * Where clicking a notification should take you, or null when it is not clickable.
 *
 * Ordering matters: a notification can carry more than one subject reference (a reply on
 * a task-scoped comment has both a comment and a task), so the most specific destination
 * wins. Asset first, then task, then run — a comment has no screen of its own, so it
 * resolves through whichever subject accompanies it.
 *
 * A notification with no subject at all is deliberately inert rather than routed
 * somewhere generic: a click that navigates nowhere useful reads as a broken link.
 */
export function notificationLink(notification: NotificationResponse): string | null {
  if (notification.assetUuid) {
    return `/assets/${encodeURIComponent(notification.assetUuid)}`;
  }
  if (notification.taskUuid) {
    return `/tasks?task=${encodeURIComponent(notification.taskUuid)}`;
  }
  if (notification.pipelineRunUuid) {
    return `/monitoring?run=${encodeURIComponent(notification.pipelineRunUuid)}`;
  }
  return null;
}

/** Severity used for the transient toast raised when a notification arrives live. */
export function notificationSeverity(notification: NotificationResponse): "info" | "warning" {
  return notification.type === "PIPELINE_RUN_FAILED" ? "warning" : "info";
}
