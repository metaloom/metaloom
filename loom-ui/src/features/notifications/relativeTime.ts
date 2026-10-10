/**
 * How long ago a notification was raised, as an i18n key plus count.
 *
 * Pure so it can be unit-tested; the popover hands the result to `t`. Returns null when there is
 * nothing honest to say — no timestamp, or one that does not parse (which used to render "NaNd").
 */
export interface RelativeTime {
  key: "notifications.time.justNow" | "notifications.time.minutes" | "notifications.time.hours" | "notifications.time.days";
  count: number;
}

export function relativeTime(iso: string | undefined, now: number = Date.now()): RelativeTime | null {
  if (!iso) return null;
  const then = new Date(iso).getTime();
  if (!Number.isFinite(then)) return null;
  // A server clock slightly ahead of the browser gives a negative age; that is "just now", too.
  const mins = Math.floor((now - then) / 60000);
  if (mins < 1) return { key: "notifications.time.justNow", count: 0 };
  if (mins < 60) return { key: "notifications.time.minutes", count: mins };
  const hours = Math.floor(mins / 60);
  if (hours < 24) return { key: "notifications.time.hours", count: hours };
  return { key: "notifications.time.days", count: Math.floor(hours / 24) };
}
