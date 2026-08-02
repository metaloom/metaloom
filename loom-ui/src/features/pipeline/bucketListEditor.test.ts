import { describe, expect, it } from "vitest";

import { bucketIdError, slugifyBucketId, type Bucket } from "./BucketListEditor";

/**
 * The pure half of the bucket editor. The rendering is covered by
 * `e2e/pipeline-ports-mocked.spec.ts` — this suite runs in the node environment, which is where
 * every non-DOM test in this repo lives.
 *
 * What these two functions decide is not cosmetic: a bucket id becomes an **output port id**, so
 * getting it wrong means either a port the author cannot wire or two ports with the same handle id.
 */

describe("slugifyBucketId", () => {
  it("passes a well-formed id through untouched", () => {
    expect(slugifyBucketId("de")).toBe("de");
    expect(slugifyBucketId("pt_br")).toBe("pt_br");
    expect(slugifyBucketId("x1")).toBe("x1");
  });

  it("repairs what someone would naturally type rather than rejecting it", () => {
    // "Brazilian Portuguese" becoming a usable id beats an error explaining the id grammar.
    expect(slugifyBucketId("Brazilian Portuguese")).toBe("brazilian_portuguese");
    expect(slugifyBucketId("Deutsch")).toBe("deutsch");
    expect(slugifyBucketId("zh-Hant")).toBe("zh_hant");
  });

  it("drops leading separators, which a port id may not start with", () => {
    expect(slugifyBucketId("  German")).toBe("german");
    expect(slugifyBucketId("-de")).toBe("de");
    expect(slugifyBucketId("___")).toBe("");
  });

  it("truncates to the length a port id allows", () => {
    expect(slugifyBucketId("a".repeat(200))).toHaveLength(63);
  });
});

describe("bucketIdError", () => {
  const rows = (...ids: string[]): Bucket[] => ids.map(id => ({ id }));

  it("accepts a distinct, well-formed id", () => {
    expect(bucketIdError("de", 0, rows("de", "en"))).toBeNull();
  });

  it("flags a row that has not been filled in", () => {
    expect(bucketIdError("", 0, rows("", "en"))).toBe("required");
  });

  /**
   * A bucket named after one of the node's fixed ports would produce two handles with the same id,
   * and an edge would attach to whichever was rendered last.
   */
  it("flags an id that collides with one of the node's fixed ports", () => {
    for (const reserved of ["other", "passed", "bucket", "media", "text"]) {
      expect(bucketIdError(reserved, 0, rows(reserved))).toBe("reserved");
    }
  });

  it("flags an id that is not a legal port id", () => {
    expect(bucketIdError("Not A Port", 0, rows("Not A Port"))).toBe("invalid");
    expect(bucketIdError("_leading", 0, rows("_leading"))).toBe("invalid");
  });

  it("flags a duplicate, and blames the row being edited rather than its twin", () => {
    expect(bucketIdError("de", 1, rows("de", "de"))).toBe("duplicate");
    // The same id at its own index is not a duplicate of itself.
    expect(bucketIdError("de", 0, rows("de", "en"))).toBeNull();
  });
});
