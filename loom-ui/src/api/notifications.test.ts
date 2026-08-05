import { afterEach, describe, expect, it, vi } from "vitest";
import {
  clearNotifications, deleteNotification, listNotifications,
  markAllNotificationsRead, markNotificationRead,
} from "./notifications";
import { API_BASE_URL } from "./config";

const TOKEN = "test-token";

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

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("notifications API client", () => {
  it("listNotifications GETs the inbox", async () => {
    const body = { data: [{ uuid: "n1", title: "hi", type: "TASK_ASSIGNED", read: false }], unreadCount: 1 };
    const fetchMock = mockFetchOk(body);

    const result = await listNotifications(TOKEN);

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/notifications`);
    expect(options.method).toBe("GET");
    expect(options.headers.Authorization).toBe(`Bearer ${TOKEN}`);
    expect(result.unreadCount).toBe(1);
  });

  it("listNotifications adds the unread filter only when asked", async () => {
    const fetchMock = mockFetchOk({ data: [], unreadCount: 0 });

    await listNotifications(TOKEN, { unreadOnly: true });
    await listNotifications(TOKEN, { unreadOnly: false });
    await listNotifications(TOKEN);

    expect(fetchMock.mock.calls[0][0]).toBe(`${API_BASE_URL}/notifications?unread=true`);
    expect(fetchMock.mock.calls[1][0]).toBe(`${API_BASE_URL}/notifications`);
    expect(fetchMock.mock.calls[2][0]).toBe(`${API_BASE_URL}/notifications`);
  });

  it("markNotificationRead POSTs the read flag to the entry", async () => {
    const fetchMock = mockFetchOk({ uuid: "n1", read: true });

    await markNotificationRead(TOKEN, "n1");

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/notifications/n1`);
    expect(options.method).toBe("POST");
    expect(JSON.parse(options.body)).toEqual({ read: true });
  });

  it("markNotificationRead can also un-read an entry", async () => {
    const fetchMock = mockFetchOk({ uuid: "n1", read: false });

    await markNotificationRead(TOKEN, "n1", false);

    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({ read: false });
  });

  it("markAllNotificationsRead POSTs to the literal read-all route", async () => {
    const fetchMock = mockFetchOk();

    await markAllNotificationsRead(TOKEN);

    // A literal segment, not a uuid — the server registers it before the /:uuid wildcard.
    expect(fetchMock.mock.calls[0][0]).toBe(`${API_BASE_URL}/notifications/read-all`);
    expect(fetchMock.mock.calls[0][1].method).toBe("POST");
  });

  it("dismiss and clear use DELETE on the entry and the collection", async () => {
    const fetchMock = mockFetchOk();

    await deleteNotification(TOKEN, "n1");
    await clearNotifications(TOKEN);

    expect(fetchMock.mock.calls[0][0]).toBe(`${API_BASE_URL}/notifications/n1`);
    expect(fetchMock.mock.calls[0][1].method).toBe("DELETE");
    expect(fetchMock.mock.calls[1][0]).toBe(`${API_BASE_URL}/notifications`);
    expect(fetchMock.mock.calls[1][1].method).toBe("DELETE");
  });

  it("encodes uuids into the entry routes", async () => {
    const fetchMock = mockFetchOk();

    await deleteNotification(TOKEN, "a b/c");
    expect(fetchMock.mock.calls[0][0]).toBe(`${API_BASE_URL}/notifications/a%20b%2Fc`);
  });

  it("throws on a non-ok response", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 403,
      json: async () => ({}),
      text: async () => "forbidden",
    } as Response);
    vi.stubGlobal("fetch", fetchMock);

    await expect(listNotifications(TOKEN)).rejects.toThrow(/403/);
    await expect(clearNotifications(TOKEN)).rejects.toThrow(/403/);
  });
});
