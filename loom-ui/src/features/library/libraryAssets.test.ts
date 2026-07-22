import { describe, expect, it } from "vitest";
import { AssetResponse } from "../../api/assets";
import { assetInLibrary, assetsInLibrary } from "./libraryAssets";

function asset(uuid: string, libraryUuids: string[] | undefined): AssetResponse {
  return {
    uuid,
    locations: libraryUuids?.map((libraryUuid, i) => ({ uuid: `${uuid}-loc-${i}`, libraryUuid })),
  };
}

const inA = asset("asset-a", ["lib-a"]);
const inB = asset("asset-b", ["lib-b"]);
const inBoth = asset("asset-ab", ["lib-a", "lib-b"]);
const noLocations = asset("asset-none", undefined);
const emptyLocations = asset("asset-empty", []);

const all = [inA, inB, inBoth, noLocations, emptyLocations];

describe("assetInLibrary", () => {
  it("matches an asset with a location in the library", () => {
    expect(assetInLibrary(inA, "lib-a")).toBe(true);
    expect(assetInLibrary(inA, "lib-b")).toBe(false);
  });

  it("excludes assets with missing or empty locations", () => {
    expect(assetInLibrary(noLocations, "lib-a")).toBe(false);
    expect(assetInLibrary(emptyLocations, "lib-a")).toBe(false);
  });

  it("matches an asset in multiple libraries for each of them", () => {
    expect(assetInLibrary(inBoth, "lib-a")).toBe(true);
    expect(assetInLibrary(inBoth, "lib-b")).toBe(true);
    expect(assetInLibrary(inBoth, "lib-c")).toBe(false);
  });
});

describe("assetsInLibrary", () => {
  it("returns only the assets belonging to the selected library", () => {
    expect(assetsInLibrary(all, "lib-a").map(a => a.uuid)).toEqual(["asset-a", "asset-ab"]);
    expect(assetsInLibrary(all, "lib-b").map(a => a.uuid)).toEqual(["asset-b", "asset-ab"]);
    expect(assetsInLibrary(all, "lib-c")).toEqual([]);
  });

  it("derives per-library counts that include multi-library assets in each library", () => {
    const counts = new Map(["lib-a", "lib-b"].map(id => [id, assetsInLibrary(all, id).length]));
    expect(counts.get("lib-a")).toBe(2);
    expect(counts.get("lib-b")).toBe(2);
  });
});
