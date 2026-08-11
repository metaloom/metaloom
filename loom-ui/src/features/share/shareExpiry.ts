/**
 * Pure helpers behind the share dialog.
 *
 * Extracted rather than inlined because this repo has no jsdom and no React Testing Library — pure
 * logic is unit-tested here with vitest, anything rendered is a mocked Playwright spec.
 */

/** The expiry choices the dialog offers, in the order it offers them. */
export type ExpiryChoice = "1d" | "7d" | "30d" | "1y" | "never";

export const EXPIRY_CHOICES: ExpiryChoice[] = ["1d", "7d", "30d", "1y", "never"];

/**
 * Seven days, because a review link that outlives the review is the common mistake and a week is
 * long enough for one round of notes.
 */
export const DEFAULT_EXPIRY: ExpiryChoice = "7d";

const DAY_MS = 24 * 60 * 60 * 1000;

const DAYS: Record<Exclude<ExpiryChoice, "never">, number> = {
  "1d": 1,
  "7d": 7,
  "30d": 30,
  "1y": 365,
};

/**
 * Turn a choice into the ISO instant the server wants, or `undefined` for a link that never expires.
 *
 * @param choice the dialog selection
 * @param now injectable clock, so the test does not have to reason about the real one
 */
export function expiryToIso(choice: ExpiryChoice, now: Date = new Date()): string | undefined {
  if (choice === "never") return undefined;
  return new Date(now.getTime() + DAYS[choice] * DAY_MS).toISOString();
}

/**
 * The choice that best describes an expiry already stored on a link, for re-opening the dialog.
 *
 * Rounds up to the next offered choice rather than down: a link with six days left is a "7 days"
 * link that has been sitting for one, and showing "1 day" would invite somebody to shorten it by
 * accident just by opening the dialog and saving.
 */
export function isoToExpiry(iso: string | undefined, now: Date = new Date()): ExpiryChoice {
  if (!iso) return "never";
  const remainingDays = (new Date(iso).getTime() - now.getTime()) / DAY_MS;
  if (remainingDays <= 1) return "1d";
  if (remainingDays <= 7) return "7d";
  if (remainingDays <= 30) return "30d";
  return "1y";
}

/**
 * Two words and a number.
 *
 * Not a random character string: this password is read aloud on a call, typed on a phone, and
 * pasted into an email by somebody who did not choose it. One that cannot be transcribed gets
 * replaced by "password1" the first time a client complains.
 *
 * The word list is deliberately short and unremarkable; entropy here is a convenience default for
 * a capability URL that is already unguessable, not the security boundary.
 *
 * @param random injectable for the test — `Math.random` by default
 */
export function generatePassword(random: () => number = Math.random): string {
  const words = [
    "amber", "beacon", "cedar", "delta", "ember", "fable", "grove", "harbor",
    "indigo", "jetty", "kernel", "lantern", "meadow", "nimbus", "orchid", "pebble",
    "quarry", "ripple", "summit", "thicket", "umbra", "velvet", "willow", "zephyr",
  ];
  const pick = () => words[Math.floor(random() * words.length)];
  const number = 10 + Math.floor(random() * 90);
  return `${pick()}-${pick()}-${number}`;
}

/**
 * Whether the viewer should show a media player, an image, or neither.
 *
 * Driven by the mime type rather than by the file extension, because the extension is whatever the
 * uploader's operating system decided and the mime type is what the browser will actually try to
 * decode.
 */
export function mediaKindOf(mimeType: string | undefined): "video" | "audio" | "image" | "pdf" | "other" {
  if (!mimeType) return "other";
  if (mimeType.startsWith("video/")) return "video";
  if (mimeType.startsWith("audio/")) return "audio";
  if (mimeType.startsWith("image/")) return "image";
  if (mimeType === "application/pdf") return "pdf";
  return "other";
}

/**
 * Seconds as `m:ss`, or `h:mm:ss` past an hour.
 *
 * Used for both the player's clock and an annotation's timecode, so a mark always reads the same
 * as the position it names.
 */
export function formatTimecode(seconds: number | undefined): string {
  if (seconds === undefined || Number.isNaN(seconds) || seconds < 0) return "0:00";
  const total = Math.floor(seconds);
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const secs = total % 60;
  const pad = (n: number) => String(n).padStart(2, "0");
  return hours > 0 ? `${hours}:${pad(minutes)}:${pad(secs)}` : `${minutes}:${pad(secs)}`;
}

/**
 * Human-readable file size, decimal units.
 *
 * Decimal rather than binary on purpose, matching the upload and asset screens: this number is
 * shown to somebody about to download a file, and it is the figure their file manager will show
 * them afterwards.
 */
export function formatFileSize(bytes: number | undefined): string {
  if (bytes === undefined || bytes < 0) return "";
  if (bytes < 1000) return `${bytes} B`;
  const units = ["kB", "MB", "GB", "TB"];
  let value = bytes / 1000;
  let unit = 0;
  while (value >= 1000 && unit < units.length - 1) {
    value /= 1000;
    unit++;
  }
  return `${value.toFixed(value < 10 ? 1 : 0)} ${units[unit]}`;
}

/**
 * Group a flat comment list into roots and their replies.
 *
 * One level deep, matching what the server enforces. An orphan — a reply whose parent was deleted
 * out from under it — is promoted to a root rather than dropped, because losing a customer's words
 * silently is worse than showing them slightly out of place.
 */
export function groupComments<T extends { uuid: string; parentUuid?: string }>(
  comments: T[],
): Array<{ root: T; replies: T[] }> {
  const byUuid = new Map(comments.map((c) => [c.uuid, c]));
  const threads = new Map<string, { root: T; replies: T[] }>();

  for (const comment of comments) {
    if (!comment.parentUuid || !byUuid.has(comment.parentUuid)) {
      threads.set(comment.uuid, { root: comment, replies: [] });
    }
  }
  for (const comment of comments) {
    if (comment.parentUuid && byUuid.has(comment.parentUuid)) {
      threads.get(comment.parentUuid)?.replies.push(comment);
    }
  }
  return [...threads.values()];
}

/**
 * Count reactions by type, so the bar can render "APPROVE 2" without a second request.
 */
export function countReactions<T extends { type: string }>(reactions: T[]): Record<string, number> {
  const counts: Record<string, number> = {};
  for (const reaction of reactions) {
    counts[reaction.type] = (counts[reaction.type] ?? 0) + 1;
  }
  return counts;
}
