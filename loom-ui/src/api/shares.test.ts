import { afterEach, describe, expect, it, vi } from "vitest";
import {
  createSharedComment,
  listSharedAssets,
  loadShareChallenge,
  openShare,
  ShareApiError,
  sharedBinaryUrl,
  sharedDownloadUrl,
} from "./shares";
import { API_BASE_URL } from "./config";

const SLUG = "k3Rm2pQwXbN7vTsLd9aYc1";
const SESSION = "payload.signature";

function mockFetchOk(body: unknown = {}, status = 200) {
  const fetchMock = vi.fn().mockResolvedValue({
    ok: true,
    status,
    json: async () => body,
    text: async () => "",
  } as Response);
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

function mockFetchError(status: number, body = "nope") {
  const fetchMock = vi.fn().mockResolvedValue({
    ok: false,
    status,
    json: async () => ({}),
    text: async () => body,
  } as Response);
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("share challenge", () => {
  it("GETs the bare slug and sends no credential at all", async () => {
    const fetchMock = mockFetchOk({ targetType: "COLLECTION", passwordRequired: true, visitorNameKnown: false });

    await loadShareChallenge(SLUG);

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/shares/${SLUG}`);
    expect(options.method).toBe("GET");
    // The visitor has no account. An Authorization header here would be meaningless at best.
    expect(options.headers?.Authorization).toBeUndefined();
    expect(options.credentials).toBe("include");
  });

  it("percent-encodes the slug so a hand-typed one cannot escape the path", async () => {
    const fetchMock = mockFetchOk({});
    await loadShareChallenge("a/b");
    expect(fetchMock.mock.calls[0][0]).toBe(`${API_BASE_URL}/shares/a%2Fb`);
  });
});

describe("openShare", () => {
  it("POSTs the password and name to the sessions sub-resource", async () => {
    const fetchMock = mockFetchOk({ sessionToken: SESSION, visitorName: "Maria" });

    await openShare(SLUG, { password: "amber-lantern-42", visitorName: "Maria" });

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/shares/${SLUG}/sessions`);
    expect(options.method).toBe("POST");
    expect(JSON.parse(options.body)).toEqual({ password: "amber-lantern-42", visitorName: "Maria" });
  });

  it("raises a typed error carrying the status, so the gate can tell 401 from 429", async () => {
    mockFetchError(429, "Too many attempts");
    await expect(openShare(SLUG, {})).rejects.toBeInstanceOf(ShareApiError);
    await expect(openShare(SLUG, {})).rejects.toMatchObject({ status: 429 });
  });
});

describe("session-scoped calls", () => {
  it("sends the session token in its own header, never as a bearer token", async () => {
    const fetchMock = mockFetchOk({ data: [] });

    await listSharedAssets(SLUG, SESSION, { limit: 100 });

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/shares/${SLUG}/assets?limit=100`);
    expect(options.headers["X-Loom-Share-Session"]).toBe(SESSION);
    expect(options.headers.Authorization).toBeUndefined();
    // The cookie is what authenticates <video src>; the header and the cookie must stay in step.
    expect(options.credentials).toBe("include");
  });

  it("posts a comment to the share-scoped route", async () => {
    const fetchMock = mockFetchOk({ uuid: "c1" }, 201);

    await createSharedComment(SLUG, SESSION, { text: "runs long", assetUuid: "a1" });

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/shares/${SLUG}/comments`);
    expect(options.method).toBe("POST");
    expect(options.headers["X-Loom-Share-Session"]).toBe(SESSION);
    expect(JSON.parse(options.body)).toEqual({ text: "runs long", assetUuid: "a1" });
  });
});

describe("media URLs", () => {
  it("builds a token-free binary URL, because media elements cannot set a header", () => {
    expect(sharedBinaryUrl(SLUG, "a1")).toBe(`${API_BASE_URL}/shares/${SLUG}/assets/a1/binary/data`);
    expect(sharedBinaryUrl(SLUG, "a1")).not.toContain("token");
  });

  it("asks for an attachment only on the download variant", () => {
    expect(sharedDownloadUrl(SLUG, "a1")).toBe(`${API_BASE_URL}/shares/${SLUG}/assets/a1/binary/data?download=1`);
  });
});
