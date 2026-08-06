import { describe, expect, it } from "vitest";
import { API_BASE_URL } from "../../api/config";
import { assetTypeFromMime, hitToCard, mimeFilterFor, toAsset } from "./assetMapping";

describe("assetTypeFromMime", () => {
  it("classifies the media prefixes", () => {
    expect(assetTypeFromMime("image/jpeg")).toBe("image");
    expect(assetTypeFromMime("video/mp4")).toBe("video");
    expect(assetTypeFromMime("audio/ogg")).toBe("audio");
  });

  it("treats application/* and text/* as documents", () => {
    expect(assetTypeFromMime("application/pdf")).toBe("document");
    expect(assetTypeFromMime("text/plain")).toBe("document");
  });

  it("falls back to unknown for a missing or unrecognised mime", () => {
    expect(assetTypeFromMime(undefined)).toBe("unknown");
    expect(assetTypeFromMime("")).toBe("unknown");
    expect(assetTypeFromMime("model/gltf+json")).toBe("unknown");
  });
});

describe("mimeFilterFor", () => {
  it("maps a type filter onto the ?mime= prefix", () => {
    expect(mimeFilterFor("image")).toBe("image/");
    expect(mimeFilterFor("video")).toBe("video/");
    expect(mimeFilterFor("audio")).toBe("audio/");
  });

  it("sends nothing for 'all'", () => {
    expect(mimeFilterFor("all")).toBeUndefined();
  });

  it("sends nothing for document — it spans two prefixes and half of it would be a lie", () => {
    expect(mimeFilterFor("document")).toBeUndefined();
  });
});

describe("toAsset", () => {
  it("maps a full asset response", () => {
    const asset = toAsset({
      uuid: "a1",
      file: { mimeType: "image/png", filename: "drone.png", size: 2048, origin: "upload", firstSeen: "" },
      hashes: { sha512: "deadbeef" },
      tags: [{ uuid: "t1", name: "sunset", collection: "default" }],
      imageComponents: [{ width: 1920, height: 1080 }],
      collections: [{ uuid: "c1", name: "trips" }],
      status: { creator: { uuid: "u1" }, created: "2026-01-01T00:00:00Z" },
    });

    expect(asset.id).toBe("a1");
    expect(asset.name).toBe("drone.png");
    expect(asset.type).toBe("image");
    expect(asset.tags).toEqual(["sunset"]);
    expect(asset.width).toBe(1920);
    expect(asset.thumbnailUrl).toBe(`${API_BASE_URL}/assets/a1/binary/data`);
    expect(asset.collectionIds).toEqual(["c1"]);
  });

  it("falls back to the uuid when the file carries no filename", () => {
    expect(toAsset({ uuid: "a2" }).name).toBe("a2");
  });

  it("gives non-images no thumbnail url — an <img> cannot render them", () => {
    const asset = toAsset({
      uuid: "a3",
      file: { mimeType: "video/mp4", filename: "clip.mp4", size: 1, origin: "", firstSeen: "" },
    });
    expect(asset.thumbnailUrl).toBe("");
  });
});

describe("hitToCard", () => {
  const hit = {
    type: "asset" as const,
    uuid: "h1",
    score: 0.9,
    title: "drone-footage.jpg",
    subtitle: "shot over the bay",
    mimeType: "image/jpeg",
    size: 4096,
    // Epoch seconds with a fractional part — the server serializes Instant as a number.
    sortDate: 1767225600.5,
  };

  it("maps the fields the index actually carries", () => {
    const card = hitToCard(hit);

    expect(card.id).toBe("h1");
    expect(card.name).toBe("drone-footage.jpg");
    expect(card.description).toBe("shot over the bay");
    expect(card.type).toBe("image");
    expect(card.fileSize).toBe(4096);
    expect(card.thumbnailUrl).toBe(`${API_BASE_URL}/assets/h1/binary/data`);
  });

  it("reads sortDate as seconds, not milliseconds", () => {
    expect(hitToCard(hit).createdAt).toBe(new Date(1767225600500).toISOString());
    expect(hitToCard(hit).createdAt.startsWith("2026-")).toBe(true);
  });

  it("leaves createdAt empty when the hit has no sortDate", () => {
    expect(hitToCard({ ...hit, sortDate: undefined }).createdAt).toBe("");
  });

  it("returns empty rather than invented values for what the index does not hold", () => {
    const card = hitToCard(hit);
    expect(card.tags).toEqual([]);
    expect(card.collectionIds).toEqual([]);
    expect(card.libraryId).toBe("");
    expect(card.duration).toBeUndefined();
    expect(card.width).toBeUndefined();
  });

  it("derives the type from the hit mime, and copes when there is none", () => {
    expect(hitToCard({ ...hit, mimeType: "video/mp4" }).type).toBe("video");
    expect(hitToCard({ ...hit, mimeType: undefined }).type).toBe("unknown");
    expect(hitToCard({ ...hit, mimeType: undefined }).thumbnailUrl).toBe("");
  });
});
