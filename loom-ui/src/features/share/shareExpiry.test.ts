import { describe, expect, it } from "vitest";
import {
  countReactions,
  DEFAULT_EXPIRY,
  EXPIRY_CHOICES,
  expiryToIso,
  formatFileSize,
  formatTimecode,
  generatePassword,
  groupComments,
  isoToExpiry,
  mediaKindOf,
} from "./shareExpiry";

const NOW = new Date("2026-08-11T12:00:00.000Z");

describe("expiryToIso", () => {
  it("turns each offered choice into an instant that far ahead", () => {
    expect(expiryToIso("1d", NOW)).toBe("2026-08-12T12:00:00.000Z");
    expect(expiryToIso("7d", NOW)).toBe("2026-08-18T12:00:00.000Z");
    expect(expiryToIso("30d", NOW)).toBe("2026-09-10T12:00:00.000Z");
    expect(expiryToIso("1y", NOW)).toBe("2027-08-11T12:00:00.000Z");
  });

  it("returns undefined for never, so the field is omitted rather than sent empty", () => {
    expect(expiryToIso("never", NOW)).toBeUndefined();
  });

  it("defaults to a week - long enough for one round of notes, short enough to lapse", () => {
    expect(DEFAULT_EXPIRY).toBe("7d");
    expect(EXPIRY_CHOICES).toEqual(["1d", "7d", "30d", "1y", "never"]);
  });
});

describe("isoToExpiry", () => {
  it("maps an absent expiry to never", () => {
    expect(isoToExpiry(undefined, NOW)).toBe("never");
  });

  it("rounds up to the next offered choice", () => {
    // A link with six days left is a 7-day link that has been sitting for one. Rounding down would
    // show "1 day" and invite somebody to shorten it just by opening the dialog and saving.
    expect(isoToExpiry("2026-08-17T12:00:00.000Z", NOW)).toBe("7d");
    expect(isoToExpiry("2026-08-12T00:00:00.000Z", NOW)).toBe("1d");
    expect(isoToExpiry("2026-09-01T12:00:00.000Z", NOW)).toBe("30d");
    expect(isoToExpiry("2027-01-01T12:00:00.000Z", NOW)).toBe("1y");
  });

  it("reports an already-lapsed expiry as the shortest choice rather than never", () => {
    expect(isoToExpiry("2026-08-01T12:00:00.000Z", NOW)).toBe("1d");
  });
});

describe("generatePassword", () => {
  it("is two words and a two-digit number, so it can be read aloud and typed", () => {
    expect(generatePassword(() => 0)).toBe("amber-amber-10");
    expect(generatePassword(() => 0.999999)).toMatch(/^zephyr-zephyr-99$/);
  });

  it("produces something transcribable rather than random characters", () => {
    for (let i = 0; i < 20; i++) {
      expect(generatePassword()).toMatch(/^[a-z]+-[a-z]+-\d{2}$/);
    }
  });
});

describe("mediaKindOf", () => {
  it("routes by mime type, not by file extension", () => {
    expect(mediaKindOf("video/mp4")).toBe("video");
    expect(mediaKindOf("audio/mpeg")).toBe("audio");
    expect(mediaKindOf("image/jpeg")).toBe("image");
    expect(mediaKindOf("application/pdf")).toBe("pdf");
    expect(mediaKindOf("application/zip")).toBe("other");
    expect(mediaKindOf(undefined)).toBe("other");
  });
});

describe("formatTimecode", () => {
  it("shows m:ss below an hour and h:mm:ss above it", () => {
    expect(formatTimecode(0)).toBe("0:00");
    expect(formatTimecode(9)).toBe("0:09");
    expect(formatTimecode(75)).toBe("1:15");
    expect(formatTimecode(3661)).toBe("1:01:01");
  });

  it("does not render NaN or a negative position into the player clock", () => {
    expect(formatTimecode(undefined)).toBe("0:00");
    expect(formatTimecode(NaN)).toBe("0:00");
    expect(formatTimecode(-4)).toBe("0:00");
  });
});

describe("formatFileSize", () => {
  it("uses decimal units, matching what a file manager shows for the same download", () => {
    expect(formatFileSize(512)).toBe("512 B");
    expect(formatFileSize(1500)).toBe("1.5 kB");
    expect(formatFileSize(184_320_512)).toBe("184 MB");
    expect(formatFileSize(2_500_000_000)).toBe("2.5 GB");
  });

  it("renders nothing for an absent size rather than a misleading zero", () => {
    expect(formatFileSize(undefined)).toBe("");
  });
});

describe("groupComments", () => {
  const root = { uuid: "r1" };
  const reply = { uuid: "c2", parentUuid: "r1" };
  const second = { uuid: "r2" };

  it("groups replies under their root, in one level", () => {
    const threads = groupComments([root, reply, second]);
    expect(threads).toHaveLength(2);
    expect(threads[0].root.uuid).toBe("r1");
    expect(threads[0].replies.map((r) => r.uuid)).toEqual(["c2"]);
    expect(threads[1].replies).toEqual([]);
  });

  it("promotes an orphan rather than dropping it", () => {
    // The parent was deleted out from under it. Losing a customer's words silently is worse than
    // showing them slightly out of place.
    const orphan = { uuid: "c9", parentUuid: "gone" };
    const threads = groupComments([root, orphan]);
    expect(threads.map((t) => t.root.uuid)).toEqual(["r1", "c9"]);
  });

  it("handles an empty list", () => {
    expect(groupComments([])).toEqual([]);
  });
});

describe("countReactions", () => {
  it("counts by type so the bar can render a total without a second request", () => {
    expect(countReactions([{ type: "APPROVE" }, { type: "APPROVE" }, { type: "QUESTION" }])).toEqual({
      APPROVE: 2,
      QUESTION: 1,
    });
    expect(countReactions([])).toEqual({});
  });
});
