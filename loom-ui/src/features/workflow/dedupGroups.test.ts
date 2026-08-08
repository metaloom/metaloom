import { afterEach, describe, expect, it, vi } from "vitest";
import {
  decideGroup,
  dupMembers,
  formatSize,
  isComplete,
  keepMember,
  reassignKeep,
  replaceGroup,
} from "./dedupGroups";
import type { DedupGroupResponse } from "../../api/dedup";

const KEEP = "asset-keep";
const DUP = "asset-dup";

function group(overrides: Partial<DedupGroupResponse> = {}): DedupGroupResponse {
  return {
    uuid: "group-1",
    algorithm: "metaloom-multisector-v1",
    status: "PENDING",
    keepAssetUuid: KEEP,
    score: 0.93,
    members: [
      { assetUuid: KEEP, role: "KEEP", score: 1.0, size: 4096, zeroChunkCount: 0 },
      { assetUuid: DUP, role: "DUP", score: 0.93, size: 2048, zeroChunkCount: 0 },
    ],
    ...overrides,
  };
}

function mockFetchOk(body: unknown) {
  const fetchMock = vi.fn().mockResolvedValue({
    ok: true,
    status: 200,
    json: async () => body,
    text: async () => "",
  } as Response);
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("keepMember", () => {
  it("uses the KEEP role when it agrees with the pointer", () => {
    expect(keepMember(group())?.assetUuid).toBe(KEEP);
  });

  it("prefers keepAssetUuid over the member roles", () => {
    // The server writes only the pointer when a reviewer reassigns the keep, so the roles still
    // describe the machine's original choice. Following the roles here would show the wrong file
    // as the keeper immediately after a reassignment.
    expect(keepMember(group({ keepAssetUuid: DUP }))?.assetUuid).toBe(DUP);
  });

  it("falls back to the KEEP member when the pointer was nulled by an asset delete", () => {
    expect(keepMember(group({ keepAssetUuid: undefined }))?.assetUuid).toBe(KEEP);
  });

  it("falls back to the first member when no role says KEEP", () => {
    const g = group({
      keepAssetUuid: undefined,
      members: [{ assetUuid: DUP, role: "DUP" }],
    });
    expect(keepMember(g)?.assetUuid).toBe(DUP);
  });

  it("returns undefined for a group with no members", () => {
    expect(keepMember(group({ members: [] }))).toBeUndefined();
  });
});

describe("dupMembers", () => {
  it("is everything that is not the keep", () => {
    expect(dupMembers(group()).map(m => m.assetUuid)).toEqual([DUP]);
  });

  it("follows a reassigned keep", () => {
    expect(dupMembers(group({ keepAssetUuid: DUP })).map(m => m.assetUuid)).toEqual([KEEP]);
  });
});

describe("isComplete", () => {
  it("treats zero missing chunks as complete", () => {
    expect(isComplete({ assetUuid: "a", role: "DUP", zeroChunkCount: 0 })).toBe(true);
  });

  it("flags a truncated file", () => {
    expect(isComplete({ assetUuid: "a", role: "DUP", zeroChunkCount: 12 })).toBe(false);
  });

  it("treats an unmeasured file as complete rather than flagging the whole queue", () => {
    expect(isComplete({ assetUuid: "a", role: "DUP" })).toBe(true);
  });
});

describe("formatSize", () => {
  it("formats byte scales", () => {
    expect(formatSize(512)).toBe("512 B");
    expect(formatSize(2048)).toBe("2.0 KB");
    expect(formatSize(5 * 1024 * 1024)).toBe("5.0 MB");
    expect(formatSize(52_000_000)).toBe("50 MB");
  });

  it("renders an unknown size as a dash rather than NaN", () => {
    expect(formatSize(undefined)).toBe("—");
  });
});

describe("replaceGroup", () => {
  it("swaps the matching group and leaves the rest alone", () => {
    const a = group({ uuid: "a" });
    const b = group({ uuid: "b" });
    const updated = { ...b, status: "CONFIRMED" };

    const next = replaceGroup([a, b], updated);

    expect(next[0]).toBe(a);
    expect(next[1]).toEqual(updated);
  });

  it("is a no-op for an unknown uuid", () => {
    const a = group({ uuid: "a" });
    expect(replaceGroup([a], group({ uuid: "zzz" }))).toEqual([a]);
  });
});

describe("decideGroup", () => {
  it("PATCHes the status and preserves the current keep", async () => {
    const fetchMock = mockFetchOk({ ...group(), status: "CONFIRMED" });

    await decideGroup("tok", group(), "CONFIRMED");

    const [, options] = fetchMock.mock.calls[0];
    expect(options.method).toBe("PATCH");
    expect(JSON.parse(options.body)).toEqual({ status: "CONFIRMED", keepAssetUuid: KEEP });
  });
});

describe("reassignKeep", () => {
  it("repeats the current status so picking a file does not decide the group", async () => {
    const fetchMock = mockFetchOk({ ...group(), keepAssetUuid: DUP });

    await reassignKeep("tok", group(), DUP);

    const [, options] = fetchMock.mock.calls[0];
    expect(JSON.parse(options.body)).toEqual({ status: "PENDING", keepAssetUuid: DUP });
  });

  it("does not silently re-open a group that was already decided", async () => {
    const fetchMock = mockFetchOk({});

    await reassignKeep("tok", group({ status: "CONFIRMED" }), DUP);

    const [, options] = fetchMock.mock.calls[0];
    expect(JSON.parse(options.body).status).toBe("CONFIRMED");
  });
});
