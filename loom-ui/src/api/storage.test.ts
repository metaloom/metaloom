import { describe, expect, it } from "vitest";

import { formatBytes, formatBytesOrUnknown } from "./format";
import {
  dedupeSavings,
  savingsPercent,
  sortBackends,
  sortCategories,
  storageTotals,
  usedFraction,
  watermarkTone,
  type StorageBackend,
  type StorageCategory,
  type StorageReport,
} from "./storage";

function category(overrides: Partial<StorageCategory> = {}): StorageCategory {
  return {
    category: "FACE_CROP",
    elements: 10,
    logicalBytes: 1000,
    distinctObjects: 8,
    distinctBytes: 800,
    ...overrides,
  };
}

function backend(overrides: Partial<StorageBackend> = {}): StorageBackend {
  return {
    poolUuid: null,
    poolName: "Default storage",
    kind: "filesystem",
    description: "filesystem:/uploads",
    freeBytes: 500,
    totalBytes: 1000,
    watermark: "OK",
    objects: 3,
    bytes: 500,
    error: null,
    ...overrides,
  };
}

describe("dedupeSavings", () => {
  it("is the gap between what the catalogue claims and what the disk holds", () => {
    expect(dedupeSavings(category())).toBe(200);
  });

  it("clamps a momentarily negative gap to zero", () => {
    // Two queries against a live database: a write landing between them can invert the
    // difference, and "-4 KiB saved" is a worse answer than "none".
    expect(dedupeSavings(category({ logicalBytes: 800, distinctBytes: 1000 }))).toBe(0);
  });
});

describe("savingsPercent", () => {
  it("reports the saving as a share of the claimed total", () => {
    expect(savingsPercent(category())).toBe(20);
  });

  it("is zero rather than NaN for an empty category", () => {
    expect(savingsPercent(category({ logicalBytes: 0, distinctBytes: 0 }))).toBe(0);
  });
});

describe("usedFraction", () => {
  it("is the used share of a measurable volume", () => {
    expect(usedFraction(backend())).toBe(0.5);
  });

  it("is null, not zero, when the backend cannot say", () => {
    // Zero renders as an empty bar, which reads as "plenty of room" for a bucket whose
    // capacity is not a thing that exists.
    expect(usedFraction(backend({ freeBytes: null, totalBytes: null }))).toBeNull();
  });

  it("treats an omitted capacity the same as a null one", () => {
    // The wire shape, not a hypothetical: the server omits a null field rather than sending
    // it, so an object store's capacity arrives as undefined. Checking only for null lets it
    // through and the bar renders at NaN%.
    const bucket = backend();
    delete (bucket as Partial<StorageBackend>).freeBytes;
    delete (bucket as Partial<StorageBackend>).totalBytes;
    expect(usedFraction(bucket)).toBeNull();
  });

  it("is null when the total is zero rather than dividing by it", () => {
    expect(usedFraction(backend({ totalBytes: 0 }))).toBeNull();
  });
});

describe("watermarkTone", () => {
  it("maps the three measurable states", () => {
    expect(watermarkTone("OK")).toBe("green");
    expect(watermarkTone("WARN")).toBe("amber");
    expect(watermarkTone("CRITICAL")).toBe("red");
  });

  it("never paints an unmeasurable backend green", () => {
    expect(watermarkTone("UNKNOWN")).toBe("neutral");
  });
});

describe("sortBackends", () => {
  it("puts whatever needs attention first and unmeasurable last", () => {
    const sorted = sortBackends([
      backend({ poolUuid: "b", poolName: "Bucket", watermark: "UNKNOWN" }),
      backend({ poolUuid: "a", poolName: "Archive", watermark: "OK" }),
      backend({ poolUuid: "c", poolName: "Scratch", watermark: "CRITICAL" }),
      backend({ poolUuid: "d", poolName: "Warm", watermark: "WARN" }),
    ]);
    expect(sorted.map((b) => b.poolName)).toEqual(["Scratch", "Warm", "Archive", "Bucket"]);
  });

  it("puts the default storage first among equally healthy backends", () => {
    const sorted = sortBackends([
      backend({ poolUuid: "a", poolName: "Archive" }),
      backend({ poolUuid: null, poolName: "Default storage" }),
    ]);
    expect(sorted[0].poolName).toBe("Default storage");
  });

  it("recognises the default storage when its pool uuid is omitted rather than null", () => {
    const local = backend({ poolName: "Default storage" });
    delete (local as Partial<StorageBackend>).poolUuid;
    expect(sortBackends([backend({ poolUuid: "a", poolName: "Archive" }), local])[0].poolName)
      .toBe("Default storage");
  });
});

describe("storageTotals", () => {
  /** The shape the demo install actually produces: sharing that spans two categories. */
  function report(): StorageReport {
    return {
      timestamp: "2026-08-10T09:14:22Z",
      thresholds: { minFreeSpaceBytes: 0, warnFreeSpaceBytes: 0, maxUploadSizeBytes: -1 },
      categories: [
        // Media, counted out of asset_location - a different table from the attachments below.
        category({ category: "ASSET_BINARY", elements: 8, logicalBytes: 800, distinctObjects: 8, distinctBytes: 800 }),
        category({ category: "PERSON_AVATAR", elements: 3, logicalBytes: 300, distinctObjects: 3, distinctBytes: 300 }),
        // Shares one object with PERSON_AVATAR: no saving is visible inside either row.
        category({ category: "USER_AVATAR", elements: 2, logicalBytes: 200, distinctObjects: 2, distinctBytes: 200 }),
      ],
      backends: [],
      objects: 4,
      distinctBytes: 400,
      orphanObjects: 0,
      orphanBytes: 0,
    };
  }

  it("adds the two disjoint sets rather than summing the categories", () => {
    // Attachments as the report counted them (4 objects, 400 B) plus the media row (8, 800 B).
    // Summing every category's distinctBytes would give 1300 and double-count the shared object.
    const totals = storageTotals(report());
    expect(totals.objects).toBe(12);
    expect(totals.onDiskBytes).toBe(1200);
  });

  it("sees a saving that spans two categories, which per-category arithmetic cannot", () => {
    // Every category here reports logicalBytes === distinctBytes, so summing dedupeSavings gives 0 -
    // beside two byte columns that visibly disagree. The claimed total is 1300 and the disk holds
    // 1200, so 100 bytes were saved by storing the shared object once.
    const totals = storageTotals(report());
    expect(totals.claimedBytes).toBe(1300);
    expect(totals.savedBytes).toBe(100);
    expect(report().categories.reduce((sum, c) => sum + dedupeSavings(c), 0)).toBe(0);
  });

  it("never reports a negative saving", () => {
    const skewed = report();
    skewed.distinctBytes = 9999;
    expect(storageTotals(skewed).savedBytes).toBe(0);
  });
});

describe("sortCategories", () => {
  it("orders by what is actually on disk, not by what the catalogue claims", () => {
    // The screen answers "what is filling my disk". A category whose elements all
    // deduplicate onto one object is not the answer however large its logical total.
    const sorted = sortCategories([
      category({ category: "FACE_CROP", logicalBytes: 9_000, distinctBytes: 100 }),
      category({ category: "ASSET_BINARY", logicalBytes: 5_000, distinctBytes: 5_000 }),
    ]);
    expect(sorted.map((c) => c.category)).toEqual(["ASSET_BINARY", "FACE_CROP"]);
  });
});

describe("formatBytes", () => {
  it("uses binary units, which is what a filesystem reports", () => {
    expect(formatBytes(0)).toBe("0 B");
    expect(formatBytes(512)).toBe("512 B");
    expect(formatBytes(1024)).toBe("1.0 KiB");
    expect(formatBytes(1_476_395_008)).toBe("1.4 GiB");
  });

  it("does not render nonsense for a missing number", () => {
    expect(formatBytes(Number.NaN)).toBe("0 B");
    expect(formatBytes(-1)).toBe("0 B");
  });
});

describe("formatBytesOrUnknown", () => {
  it("keeps 'cannot say' distinct from 'nothing left'", () => {
    expect(formatBytesOrUnknown(null, "Unknown")).toBe("Unknown");
    expect(formatBytesOrUnknown(0, "Unknown")).toBe("0 B");
  });
});
