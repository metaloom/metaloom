import { describe, it, expect } from "vitest";
import {
  FAMILY_COLORS, contentTypeColor, contentTypeLabel, family, findContentType,
  isAssignable, isProvisional, isWildcard, wildcardOf,
} from "./contentTypes";
import type { ContentType } from "../../types/nodeDescriptors";

/**
 * Contract test for the TypeScript mirror of Java's `ContentTypeLattice`.
 *
 * The cases below are the same ones `ContentTypeLatticeTest` asserts on the Java side; the two
 * implementations exist twice on purpose (a round trip over HTTP per drag would be worse) and this
 * fixture is what keeps them from drifting. When an arm of the rule changes in Java, this file is
 * the failure that says so.
 */

/** The eight families of `ContentTypeRegistry.FAMILIES`, in palette order. */
const FAMILIES = ["media", "text", "detection", "hash", "scalar", "artifact", "struct", "control"];

/**
 * (actual, declared, assignable) triples. Mirrors the cases in `ContentTypeLatticeTest` one for one.
 */
const FIXTURE: [string, string, boolean][] = [
  // Exact match.
  ["detection/face", "detection/face", true],
  ["media/*", "media/*", true],
  ["hash/md5", "hash/md5", true],

  // Second arm — a consumer declaring the family wildcard accepts any subtype of it.
  ["detection/face", "detection/*", true],
  ["detection/object", "detection/*", true],
  ["hash/md5", "hash/*", true],
  ["text/transcript", "text/*", true],
  ["media/video", "media/*", true],

  // Third arm — a source emits media/* because the concrete mime is unknown when the graph is drawn.
  ["media/*", "media/image", true],
  ["media/*", "media/video", true],
  ["text/*", "text/plain", true],

  // Assignability never crosses families.
  ["hash/md5", "scalar/string", false],
  ["text/plain", "media/*", false],
  ["media/image", "artifact/image", false],
  ["artifact/image", "media/image", false],
  ["detection/face", "scalar/string", false],

  // Two concrete subtypes of one family are unrelated — only the wildcard bridges them.
  ["detection/face", "detection/object", false],
  ["hash/md5", "hash/sha256", false],
  ["media/image", "media/video", false],
  ["text/transcript", "text/plain", false],
];

describe("isAssignable", () => {
  it.each(FIXTURE)("%s -> %s is %s", (actual, declared, expected) => {
    expect(isAssignable(actual, declared)).toBe(expected);
  });

  it("rejects a null or undefined type on either side", () => {
    expect(isAssignable(null, "media/*")).toBe(false);
    expect(isAssignable("media/*", null)).toBe(false);
    expect(isAssignable(null, null)).toBe(false);
    expect(isAssignable(undefined, "media/image")).toBe(false);
    expect(isAssignable("media/image", undefined)).toBe(false);
    expect(isAssignable(undefined, undefined)).toBe(false);
  });

  it("rejects malformed ids — an id without a slash has no family", () => {
    expect(isAssignable("media", "media/*")).toBe(false);
    expect(isAssignable("media/*", "media")).toBe(false);
    expect(isAssignable("", "media/*")).toBe(false);
  });
});

describe("isProvisional", () => {
  it("flags only wildcard-producer into concrete-consumer", () => {
    expect(isProvisional("media/*", "media/image")).toBe(true);
    expect(isProvisional("text/*", "text/plain")).toBe(true);
    // Decided now, not at runtime.
    expect(isProvisional("detection/face", "detection/*")).toBe(false);
    expect(isProvisional("media/image", "media/image")).toBe(false);
    // Not assignable at all, so not provisional either.
    expect(isProvisional("hash/md5", "scalar/string")).toBe(false);
    expect(isProvisional(null, "media/image")).toBe(false);
  });
});

describe("family / isWildcard / wildcardOf", () => {
  it("splits on the first slash", () => {
    expect(family("detection/face")).toBe("detection");
    expect(family("media/*")).toBe("media");
    expect(family("struct/scene-layout")).toBe("struct");
    expect(family("nofamily")).toBeNull();
    expect(family(null)).toBeNull();
    expect(family(undefined)).toBeNull();
  });

  it("recognises family wildcards", () => {
    expect(isWildcard("media/*")).toBe(true);
    expect(isWildcard("hash/*")).toBe(true);
    expect(isWildcard("media/image")).toBe(false);
    expect(isWildcard(null)).toBe(false);
    expect(isWildcard(undefined)).toBe(false);
  });

  it("derives the family wildcard", () => {
    expect(wildcardOf("detection/face")).toBe("detection/*");
    expect(wildcardOf("media/*")).toBe("media/*");
    expect(wildcardOf("nofamily")).toBeNull();
    expect(wildcardOf(null)).toBeNull();
  });
});

describe("FAMILY_COLORS", () => {
  it("has exactly one colour per family", () => {
    expect(Object.keys(FAMILY_COLORS).sort()).toEqual([...FAMILIES].sort());
  });

  it("gives every family a distinct colour", () => {
    expect(new Set(Object.values(FAMILY_COLORS)).size).toBe(FAMILIES.length);
  });

  it("colours by family and falls back for unknown ids", () => {
    expect(contentTypeColor("detection/face", "#fallback")).toBe(FAMILY_COLORS.detection);
    expect(contentTypeColor("detection/*", "#fallback")).toBe(FAMILY_COLORS.detection);
    expect(contentTypeColor("quantum/entangled", "#fallback")).toBe("#fallback");
    expect(contentTypeColor(null, "#fallback")).toBe("#fallback");
  });
});

describe("served vocabulary lookup", () => {
  // Only the shape matters here: labels and descriptions come from the server, never from TS.
  const served: ContentType[] = [
    { id: "media/*", label: "Any Media", family: "media", wildcard: true },
    { id: "detection/face", label: "Face Detections", family: "detection", wildcard: false, description: "One element per detected face" },
  ];

  it("resolves labels and descriptions from the served list", () => {
    expect(contentTypeLabel("detection/face", served)).toBe("Face Detections");
    expect(findContentType("detection/face", served)?.description).toBe("One element per detected face");
  });

  it("falls back to the raw id for a type this build has never heard of", () => {
    expect(contentTypeLabel("struct/holograph", served)).toBe("struct/holograph");
    expect(findContentType("struct/holograph", served)).toBeUndefined();
    expect(contentTypeLabel(null, [])).toBe("");
  });
});
