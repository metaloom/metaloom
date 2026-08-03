import { test, expect, Page, WebSocketRoute } from "@playwright/test";

/**
 * A worker that connects after the tab opened must reach the palette without a reload.
 *
 * This is the user-visible point of node self-registration: start a Python worker, and its node is
 * placeable a second later. Before the `NODE_REGISTRY` channel existed, the editor fetched the
 * registry once at mount and never looked again — `refresh` was defined in the context and nothing
 * ever called it — so the answer was "press F5".
 *
 * The two frame types are checked separately because they cost different amounts:
 *  - `NODE_DESCRIPTORS_CHANGED` means the contract set moved, and the client re-fetches;
 *  - `NODE_AVAILABILITY_CHANGED` carries its delta inline and must fetch **nothing**, because
 *    presence flips on every worker restart and the full response is ~115 KB.
 */

const PIPELINE_UUID = "33333333-3333-3333-3333-333333333333";

function port(id: string, contentType: string) {
  return { id, label: id, contentType, cardinality: "ONE", required: true };
}

function descriptor(nodeId: string, name: string, category = "ANALYSIS") {
  return {
    nodeId, kind: nodeId, name, category,
    description: "A node", icon: "", dynamicPorts: false, parameters: [],
    inputPorts: [port("media", "media/*")], outputPorts: [port("out", "struct/json")],
    inputGroups: [], outputGroups: [],
    defaultConcurrency: 1, defaultMode: "PARALLEL", defaultBlocking: false, events: [],
  };
}

const CONTENT_TYPES = [
  { id: "media/*", label: "Any Media", family: "media", wildcard: true },
  { id: "struct/json", label: "JSON", family: "struct", wildcard: false },
];

const BEFORE = [descriptor("whisper", "Whisper")];
const AFTER = [descriptor("whisper", "Whisper"), descriptor("py-hello", "Python Hello")];

interface Mock {
  /** How many times the full descriptor list was fetched — the expensive call. */
  descriptorFetches: number;
  /** Flip to serve the worker's newly announced node. */
  workerConnected: boolean;
}

async function mockBackend(page: Page): Promise<Mock> {
  const state: Mock = { descriptorFetches: 0, workerConnected: false };
  const pipeline = {
    uuid: PIPELINE_UUID, versionUuid: `${PIPELINE_UUID}-v1`, versionNumber: 1,
    name: "Live", description: "Mocked", definition: { nodes: [], edges: [] },
    enabled: true, priority: 0, dryRun: false,
    status: { creator: { uuid: "u1", name: "admin" }, created: "2026-07-01T10:00:00Z" },
  };

  await page.route("**/api/v1/**", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [] }) })
  );
  await page.route("**/api/v1/login", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ token: "fake-jwt" }) })
  );
  await page.route("**/api/v1/pipeline/node-descriptors", route => {
    state.descriptorFetches++;
    route.fulfill({
      status: 200, contentType: "application/json",
      body: JSON.stringify({
        nodeDescriptors: state.workerConnected ? AFTER : BEFORE,
        contentTypes: CONTENT_TYPES,
        availability: state.workerConnected
          ? { whisper: { available: true }, "py-hello": { available: true, source: "ANNOUNCED" } }
          : { whisper: { available: true } },
      }),
    });
  });
  await page.route("**/api/v1/pipeline/content-types", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(CONTENT_TYPES) })
  );
  await page.route("**/api/v1/pipelines", route =>
    route.fulfill({
      status: 200, contentType: "application/json",
      body: JSON.stringify({ data: [pipeline], _metainfo: { totalCount: 1 } }),
    })
  );
  await page.route(/\/api\/v1\/pipelines\/[^/]+\/runs$/, route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [] }) })
  );
  await page.route(/\/api\/v1\/pipelines\/([^/]+)\/versions$/, route =>
    route.fulfill({
      status: 200, contentType: "application/json",
      body: JSON.stringify({ data: [pipeline], _metainfo: { totalCount: 1 } }),
    })
  );
  await page.route(/\/api\/v1\/pipelines\/[^/]+$/, route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(pipeline) })
  );
  return state;
}

function mockEventsSocket(page: Page) {
  const sockets: WebSocketRoute[] = [];
  const registered = page.routeWebSocket(/\/pipelines\/events\/ws/, ws => { sockets.push(ws); });
  async function waitForSocket(index: number): Promise<WebSocketRoute> {
    await expect.poll(() => sockets.length, { timeout: 15_000 }).toBeGreaterThan(index);
    return sockets[index];
  }
  return { registered: Promise.resolve(registered), waitForSocket };
}

async function loginAndOpenEditor(page: Page) {
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
  await page.getByRole("button", { name: "Pipelines" }).first().click();
  await expect(page.getByTestId("pipeline-canvas")).toBeVisible({ timeout: 10_000 });
}

async function openAddNode(page: Page) {
  await page.getByPlaceholder(/add node/i).click();
  await expect(page.getByTestId("add-node-whisper")).toBeVisible({ timeout: 5_000 });
}

test.describe("live node registry updates", () => {

  test("a node announced after mount appears in the picker without a reload", async ({ page }) => {
    const state = await mockBackend(page);
    const { registered, waitForSocket } = mockEventsSocket(page);
    await registered;
    await loginAndOpenEditor(page);
    await openAddNode(page);

    // The worker has not connected yet, so its node is nowhere.
    await expect(page.getByTestId("add-node-py-hello")).toHaveCount(0);

    // A Python worker registers and announces. Loom notices the registry changed and says so.
    state.workerConnected = true;
    const ws = await waitForSocket(0);
    ws.send(JSON.stringify({ channel: "NODE_REGISTRY", type: "NODE_DESCRIPTORS_CHANGED" }));

    // No F5. This is the whole point of the feature.
    await expect(page.getByTestId("add-node-py-hello")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("add-node-py-hello")).toHaveAttribute("data-available", "true");
  });

  test("an availability flip patches in place and fetches nothing", async ({ page }) => {
    const state = await mockBackend(page);
    state.workerConnected = true;
    const { registered, waitForSocket } = mockEventsSocket(page);
    await registered;
    await loginAndOpenEditor(page);
    await openAddNode(page);

    await expect(page.getByTestId("add-node-py-hello")).toHaveAttribute("data-available", "true");
    const fetchesBefore = state.descriptorFetches;

    const ws = await waitForSocket(0);
    ws.send(JSON.stringify({
      channel: "NODE_REGISTRY",
      type: "NODE_AVAILABILITY_CHANGED",
      availability: { "py-hello": { available: false, source: "ANNOUNCED", providedBy: ["cortex-py"] } },
    }));

    await expect(page.getByTestId("add-node-py-hello")).toHaveAttribute("data-available", "false", { timeout: 10_000 });
    await expect(page.getByTestId("add-node-py-hello")).toContainText(/cortex-py/i);

    // Presence flips on every worker restart. Pulling the whole descriptor response for each one, in
    // every open tab, is exactly what the separate frame type exists to avoid.
    expect(state.descriptorFetches).toBe(fetchesBefore);
  });

  test("a burst of descriptor changes collapses into one re-fetch", async ({ page }) => {
    const state = await mockBackend(page);
    const { registered, waitForSocket } = mockEventsSocket(page);
    await registered;
    await loginAndOpenEditor(page);
    await openAddNode(page);

    const fetchesBefore = state.descriptorFetches;
    state.workerConnected = true;

    // A fleet restart emits one frame per worker.
    const ws = await waitForSocket(0);
    for (let i = 0; i < 6; i++) {
      ws.send(JSON.stringify({ channel: "NODE_REGISTRY", type: "NODE_DESCRIPTORS_CHANGED" }));
    }

    await expect(page.getByTestId("add-node-py-hello")).toBeVisible({ timeout: 10_000 });
    // Debounced: six frames, one download.
    expect(state.descriptorFetches).toBe(fetchesBefore + 1);
  });

  test("registry frames survive being interleaved with the other two channels", async ({ page }) => {
    const state = await mockBackend(page);
    const { registered, waitForSocket } = mockEventsSocket(page);
    await registered;
    await loginAndOpenEditor(page);
    await openAddNode(page);

    const ws = await waitForSocket(0);
    state.workerConnected = true;

    // Three channels share one socket, separated only by the `channel` discriminator: pipeline
    // frames carry none, processor frames say PROCESSOR, registry frames say NODE_REGISTRY. Sandwich
    // the registry frame between the other two - if the routing were wrong in either direction, the
    // middle frame is the one that would silently vanish.
    ws.send(JSON.stringify({ type: "NODE_STARTED", pipelineName: "Live", nodeId: "whisper", timestamp: 0 }));
    ws.send(JSON.stringify({ channel: "NODE_REGISTRY", type: "NODE_DESCRIPTORS_CHANGED" }));
    ws.send(JSON.stringify({ channel: "PROCESSOR", type: "HEARTBEAT", nodeId: "cortex-1" }));

    await expect(page.getByTestId("add-node-py-hello")).toBeVisible({ timeout: 10_000 });

    // And the reverse holds too: a following availability frame still lands.
    ws.send(JSON.stringify({
      channel: "NODE_REGISTRY",
      type: "NODE_AVAILABILITY_CHANGED",
      availability: { "py-hello": { available: false } },
    }));
    await expect(page.getByTestId("add-node-py-hello")).toHaveAttribute("data-available", "false", { timeout: 10_000 });
  });
});
