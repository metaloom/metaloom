import { afterEach, describe, expect, it, vi } from "vitest";
import { createTask, listAssetTasks, assignTaskToAsset, unassignTaskFromAsset } from "./tasks";
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

describe("asset task assignment API client", () => {
  it("listAssetTasks GETs the asset-scoped tasks route", async () => {
    const list = { data: [{ uuid: "t1", title: "Task", taskStatus: "PENDING" }] };
    const fetchMock = mockFetchOk(list);

    const result = await listAssetTasks(TOKEN, "a1");

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/assets/a1/tasks`);
    expect(options.method).toBe("GET");
    expect(options.headers.Authorization).toBe(`Bearer ${TOKEN}`);
    expect(result).toEqual(list);
  });

  it("assignTaskToAsset POSTs the asset-scoped task route with no body", async () => {
    const assigned = { uuid: "t1", title: "Task" };
    const fetchMock = mockFetchOk(assigned, 201);

    const result = await assignTaskToAsset(TOKEN, "a1", "t1");

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/assets/a1/tasks/t1`);
    expect(options.method).toBe("POST");
    expect(options.headers.Authorization).toBe(`Bearer ${TOKEN}`);
    expect(options.body).toBeUndefined();
    expect(result).toEqual(assigned);
  });

  it("unassignTaskFromAsset DELETEs the asset-scoped task route", async () => {
    const fetchMock = mockFetchOk({}, 204);

    await unassignTaskFromAsset(TOKEN, "a1", "t1");

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/assets/a1/tasks/t1`);
    expect(options.method).toBe("DELETE");
    expect(options.headers.Authorization).toBe(`Bearer ${TOKEN}`);
  });

  it("encodes uuids into the asset task routes", async () => {
    const fetchMock = mockFetchOk({ data: [] });

    await listAssetTasks(TOKEN, "a b/c");
    await assignTaskToAsset(TOKEN, "a b/c", "x/y");

    expect(fetchMock.mock.calls[0][0]).toBe(`${API_BASE_URL}/assets/a%20b%2Fc/tasks`);
    expect(fetchMock.mock.calls[1][0]).toBe(`${API_BASE_URL}/assets/a%20b%2Fc/tasks/x%2Fy`);
  });

  it("createTask passes taskStatus and dueDate through the request body", async () => {
    const fetchMock = mockFetchOk({ uuid: "t1", title: "Task" }, 201);
    const request = {
      title: "Task",
      priority: "HIGH",
      taskStatus: "REVIEW",
      dueDate: "2026-08-01T12:00:00Z",
    };

    await createTask(TOKEN, request);

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/tasks`);
    expect(options.method).toBe("POST");
    expect(JSON.parse(options.body)).toEqual(request);
  });

  it("listAssetTasks throws on a non-ok response", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 404,
      json: async () => ({}),
      text: async () => "not found",
    } as Response);
    vi.stubGlobal("fetch", fetchMock);

    await expect(listAssetTasks(TOKEN, "missing")).rejects.toThrow(/404/);
  });
});
