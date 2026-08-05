import { describe, expect, it } from "vitest";
import { notificationLink, notificationSeverity } from "./notificationLink";
import { NotificationResponse } from "../../api/notifications";

const base = (over: Partial<NotificationResponse>): NotificationResponse => ({
  uuid: "n1",
  type: "TASK_ASSIGNED",
  read: false,
  title: "something happened",
  ...over,
});

describe("notificationLink", () => {
  it("routes an asset notification to the asset detail view", () => {
    expect(notificationLink(base({ assetUuid: "a1" }))).toBe("/assets/a1");
  });

  it("routes a task notification to the tasks view with a deep-link parameter", () => {
    expect(notificationLink(base({ taskUuid: "t1" }))).toBe("/tasks?task=t1");
  });

  it("routes a run notification to monitoring", () => {
    expect(notificationLink(base({ type: "PIPELINE_RUN_FAILED", pipelineRunUuid: "r1" }))).toBe("/monitoring?run=r1");
  });

  it("prefers the most specific subject when several are set", () => {
    // A reply on a task-scoped comment carries both. The asset screen is the more specific
    // destination, so it wins.
    expect(notificationLink(base({ assetUuid: "a1", taskUuid: "t1" }))).toBe("/assets/a1");
    expect(notificationLink(base({ taskUuid: "t1", pipelineRunUuid: "r1" }))).toBe("/tasks?task=t1");
  });

  it("returns null when there is no subject at all", () => {
    // Deliberately inert rather than routed somewhere generic — a click that goes nowhere
    // useful reads as a broken link.
    expect(notificationLink(base({}))).toBeNull();
    expect(notificationLink(base({ commentUuid: "c1" }))).toBeNull();
  });

  it("encodes uuids into the routes", () => {
    expect(notificationLink(base({ assetUuid: "a b/c" }))).toBe("/assets/a%20b%2Fc");
    expect(notificationLink(base({ taskUuid: "x/y" }))).toBe("/tasks?task=x%2Fy");
  });
});

describe("notificationSeverity", () => {
  it("flags a failed run as a warning and everything else as info", () => {
    expect(notificationSeverity(base({ type: "PIPELINE_RUN_FAILED" }))).toBe("warning");
    expect(notificationSeverity(base({ type: "TASK_ASSIGNED" }))).toBe("info");
    expect(notificationSeverity(base({ type: "COMMENT_REPLY" }))).toBe("info");
  });
});
