import { afterEach, describe, expect, it, vi } from "vitest";
import {
  listSkills,
  loadSkill,
  createSkill,
  updateSkill,
  deleteSkill,
  listSkillLibrary,
  installSkill,
} from "./skills";
import { API_BASE_URL } from "./config";

const TOKEN = "test-token";
const UUID = "11111111-2222-3333-4444-555555555555";

function mockFetchOk(body: unknown = {}) {
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

describe("skills api", () => {
  it("listSkills GETs /skills with auth header", async () => {
    const fetchMock = mockFetchOk({ data: [] });
    await listSkills(TOKEN);
    expect(fetchMock).toHaveBeenCalledWith(
      `${API_BASE_URL}/skills`,
      expect.objectContaining({
        method: "GET",
        headers: expect.objectContaining({ Authorization: `Bearer ${TOKEN}` }),
      }),
    );
  });

  it("loadSkill GETs /skills/:uuid", async () => {
    const fetchMock = mockFetchOk({ uuid: UUID });
    await loadSkill(TOKEN, UUID);
    expect(fetchMock).toHaveBeenCalledWith(`${API_BASE_URL}/skills/${UUID}`, expect.objectContaining({ method: "GET" }));
  });

  it("createSkill POSTs /skills with the body", async () => {
    const fetchMock = mockFetchOk({ uuid: UUID });
    await createSkill(TOKEN, { name: "s", description: "d", content: "c" });
    expect(fetchMock).toHaveBeenCalledWith(
      `${API_BASE_URL}/skills`,
      expect.objectContaining({
        method: "POST",
        body: JSON.stringify({ name: "s", description: "d", content: "c" }),
      }),
    );
  });

  it("updateSkill uses POST (not PUT)", async () => {
    const fetchMock = mockFetchOk({ uuid: UUID });
    await updateSkill(TOKEN, UUID, { published: true });
    expect(fetchMock).toHaveBeenCalledWith(
      `${API_BASE_URL}/skills/${UUID}`,
      expect.objectContaining({ method: "POST", body: JSON.stringify({ published: true }) }),
    );
  });

  it("deleteSkill DELETEs /skills/:uuid", async () => {
    const fetchMock = mockFetchOk();
    await deleteSkill(TOKEN, UUID);
    expect(fetchMock).toHaveBeenCalledWith(`${API_BASE_URL}/skills/${UUID}`, expect.objectContaining({ method: "DELETE" }));
  });

  it("listSkillLibrary GETs /skills/library", async () => {
    const fetchMock = mockFetchOk({ data: [] });
    await listSkillLibrary(TOKEN);
    expect(fetchMock).toHaveBeenCalledWith(`${API_BASE_URL}/skills/library`, expect.objectContaining({ method: "GET" }));
  });

  it("installSkill POSTs /skills/:uuid/install", async () => {
    const fetchMock = mockFetchOk({ uuid: "copy" });
    await installSkill(TOKEN, UUID);
    expect(fetchMock).toHaveBeenCalledWith(`${API_BASE_URL}/skills/${UUID}/install`, expect.objectContaining({ method: "POST" }));
  });

  it("throws on error responses", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: false, status: 404, text: async () => "nope" } as Response));
    await expect(loadSkill(TOKEN, UUID)).rejects.toThrow("API error 404");
  });
});
