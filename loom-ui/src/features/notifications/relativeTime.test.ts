import { describe, expect, it } from "vitest";
import { relativeTime } from "./relativeTime";

const NOW = Date.parse("2026-10-10T12:00:00Z");

describe("relativeTime", () => {
  it("says nothing without a timestamp", () => {
    expect(relativeTime(undefined, NOW)).toBeNull();
    expect(relativeTime("", NOW)).toBeNull();
  });

  it("says nothing for an unparseable timestamp rather than NaN", () => {
    expect(relativeTime("not-a-date", NOW)).toBeNull();
  });

  it("treats the last minute and a clock-skewed future as just now", () => {
    expect(relativeTime("2026-10-10T11:59:30Z", NOW)).toEqual({ key: "notifications.time.justNow", count: 0 });
    expect(relativeTime("2026-10-10T12:05:00Z", NOW)).toEqual({ key: "notifications.time.justNow", count: 0 });
  });

  it("buckets into minutes, hours and days", () => {
    expect(relativeTime("2026-10-10T11:15:00Z", NOW)).toEqual({ key: "notifications.time.minutes", count: 45 });
    expect(relativeTime("2026-10-10T09:00:00Z", NOW)).toEqual({ key: "notifications.time.hours", count: 3 });
    expect(relativeTime("2026-10-07T12:00:00Z", NOW)).toEqual({ key: "notifications.time.days", count: 3 });
  });
});
