import { afterEach, describe, expect, it, vi } from "vitest";
import { API_BASE_URL } from "./config";
import { listAssets } from "./assets";
import { listBlacklists } from "./blacklist";
import { listClusters } from "./clusters";
import { listCollections } from "./collections";
import { listGroups } from "./groups";
import { listLibraries } from "./libraries";
import { listPersons } from "./persons";
import { listPools } from "./pools";
import { listRoles } from "./roles";
import { listShareLinks } from "./shareLinks";
import { listSkillLibrary, listSkills } from "./skills";
import { listSpaces } from "./spaces";
import { listTags } from "./tags";
import { listTasks } from "./tasks";
import { listTokens } from "./tokens";
import { listUsers } from "./users";

const TOKEN = "test-token";

function mockFetchOk(body: unknown = { data: [] }) {
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

/** Every collection client that accepts keyset paging, with the route it must hit. */
const CLIENTS: [name: string, fn: (t: string, p?: { limit?: number; from?: string }) => Promise<unknown>, path: string][] = [
  ["listAssets", listAssets, "/assets"],
  ["listCollections", listCollections, "/collections"],
  ["listLibraries", listLibraries, "/libraries"],
  ["listTags", listTags, "/tags"],
  ["listPools", listPools, "/pools"],
  ["listUsers", listUsers, "/users"],
  ["listGroups", listGroups, "/groups"],
  ["listRoles", listRoles, "/roles"],
  ["listTokens", listTokens, "/tokens"],
  ["listSpaces", listSpaces, "/spaces"],
  ["listTasks", listTasks, "/tasks"],
  ["listSkills", listSkills, "/skills"],
  ["listSkillLibrary", listSkillLibrary, "/skills/library"],
  ["listClusters", listClusters, "/clusters"],
  ["listPersons", listPersons, "/persons"],
  ["listBlacklists", listBlacklists, "/blacklists"],
  ["listShareLinks", listShareLinks, "/share-links"],
];

describe.each(CLIENTS)("%s paging", (_name, listFn, path) => {
  it("issues a bare collection request when no paging is given", async () => {
    const fetchMock = mockFetchOk();

    await listFn(TOKEN);

    expect(fetchMock.mock.calls[0][0]).toBe(`${API_BASE_URL}${path}`);
  });

  it("serializes limit and the seek cursor", async () => {
    const fetchMock = mockFetchOk();

    await listFn(TOKEN, { limit: 100, from: "e829f0f1-4775-4857-a326-850440cf9577" });

    expect(fetchMock.mock.calls[0][0])
      .toBe(`${API_BASE_URL}${path}?limit=100&from=e829f0f1-4775-4857-a326-850440cf9577`);
  });

  it("sends the bearer token", async () => {
    const fetchMock = mockFetchOk();

    await listFn(TOKEN, { limit: 1 });

    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBe(`Bearer ${TOKEN}`);
  });
});

describe("list responses carry the paging metainfo through", () => {
  it("returns _metainfo verbatim — lastUuid is the cursor for the next page", async () => {
    mockFetchOk({
      data: [{ uuid: "a1" }, { uuid: "a2" }],
      _metainfo: { lastUuid: "a2", perPage: 2, totalCount: 300 },
    });

    const result = await listAssets(TOKEN, { limit: 2 });

    expect(result._metainfo).toEqual({ lastUuid: "a2", perPage: 2, totalCount: 300 });
    expect(result.data).toHaveLength(2);
  });

  it("tolerates a response without _metainfo", async () => {
    mockFetchOk({ data: [] });

    const result = await listCollections(TOKEN);

    expect(result._metainfo).toBeUndefined();
  });
});
