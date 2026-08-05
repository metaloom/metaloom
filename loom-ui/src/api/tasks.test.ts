import { afterEach, describe, expect, it, vi } from "vitest";
import {
  createTask,
  listAssetTasks,
  assignTaskToAsset,
  unassignTaskFromAsset,
  listTaskAssignees,
  assignTask,
  unassignTaskFromUser,
  unassignTaskFromGroup,
} from "./tasks";
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

describe("task assignee API client", () => {
  it("listTaskAssignees GETs the task-scoped assignees route", async () => {
    const list = { data: [{ userUuid: "u1", name: "joedoe" }] };
    const fetchMock = mockFetchOk(list);

    const result = await listTaskAssignees(TOKEN, "t1");

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/tasks/t1/assignees`);
    expect(options.method).toBe("GET");
    expect(options.headers.Authorization).toBe(`Bearer ${TOKEN}`);
    expect(result).toEqual(list);
  });

  it("assignTask POSTs both target lists in one body", async () => {
    const fetchMock = mockFetchOk({ data: [] }, 201);
    const request = { userUuids: ["u1", "u2"], groupUuids: ["g1"] };

    await assignTask(TOKEN, "t1", request);

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(`${API_BASE_URL}/tasks/t1/assignees`);
    expect(options.method).toBe("POST");
    expect(JSON.parse(options.body)).toEqual(request);
  });

  it("unassign uses a distinct sub-path per target kind", async () => {
    const fetchMock = mockFetchOk();

    await unassignTaskFromUser(TOKEN, "t1", "u1");
    await unassignTaskFromGroup(TOKEN, "t1", "g1");

    // A user and a group could share a uuid shape, so the kind has to be in the path
    // rather than inferred at the server.
    expect(fetchMock.mock.calls[0][0]).toBe(`${API_BASE_URL}/tasks/t1/assignees/users/u1`);
    expect(fetchMock.mock.calls[0][1].method).toBe("DELETE");
    expect(fetchMock.mock.calls[1][0]).toBe(`${API_BASE_URL}/tasks/t1/assignees/groups/g1`);
    expect(fetchMock.mock.calls[1][1].method).toBe("DELETE");
  });

  it("encodes uuids into the assignee routes", async () => {
    const fetchMock = mockFetchOk({ data: [] });

    await listTaskAssignees(TOKEN, "a b/c");
    await unassignTaskFromUser(TOKEN, "a b/c", "x/y");
    await unassignTaskFromGroup(TOKEN, "a b/c", "p q");

    expect(fetchMock.mock.calls[0][0]).toBe(`${API_BASE_URL}/tasks/a%20b%2Fc/assignees`);
    expect(fetchMock.mock.calls[1][0]).toBe(`${API_BASE_URL}/tasks/a%20b%2Fc/assignees/users/x%2Fy`);
    expect(fetchMock.mock.calls[2][0]).toBe(`${API_BASE_URL}/tasks/a%20b%2Fc/assignees/groups/p%20q`);
  });

  it("assignTask throws on a non-ok response", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 404,
      json: async () => ({}),
      text: async () => "not found",
    } as Response);
    vi.stubGlobal("fetch", fetchMock);

    await expect(assignTask(TOKEN, "t1", { userUuids: ["gone"] })).rejects.toThrow(/404/);
  });
});
