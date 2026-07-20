import { test, expect, Page, WebSocketRoute } from "@playwright/test";

/**
 * Smoke tests for the Cortex view's live processor updates.
 *
 * No running Loom backend is required: the REST snapshot (`GET /api/v1/processors`)
 * is intercepted via `page.route`, and the multiplexed UI events WebSocket
 * (`/api/v1/pipelines/events/ws`) is intercepted via `page.routeWebSocket`, so the
 * test can push `channel: "PROCESSOR"` frames and assert the worker cards react
 * without a manual reload.
 */

interface ProcOpts {
  state?: string;
  cpu?: number;
  gpu?: number;
  io?: number;
  caps?: string[];
}

/** Build a ProcessorResponse-shaped snapshot as the REST list / events return it. */
function proc(nodeId: string, name: string, o: ProcOpts = {}) {
  return {
    uuid: `00000000-0000-0000-0000-0000000000${nodeId.replace(/\D/g, "").padStart(2, "0")}`,
    nodeId,
    name,
    host: "10.0.0.1:9090",
    priority: 1,
    state: o.state ?? "ONLINE",
    capabilities: o.caps ?? ["CPU"],
    systemStatus: { cpuLoad: o.cpu ?? 10, gpuLoad: o.gpu ?? 0, ioLoad: o.io ?? 0, memoryUsed: 512, memoryTotal: 1024 },
    lastSeen: new Date().toISOString(),
  };
}

/** Install REST routes; the initial snapshot holds a single online worker. */
async function mockRest(page: Page) {
  await page.route("**/api/v1/**", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [] }) })
  );
  await page.route("**/api/v1/login", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ token: "fake-jwt" }) })
  );
  await page.route("**/api/v1/processors", route =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ data: [proc("node-1", "cortex-gpu-01", { cpu: 10, gpu: 20, caps: ["GPU", "CPU"] })] }),
    })
  );
}

/**
 * Intercept the UI events socket. `registered` resolves once the route is active
 * (await it before navigating); `ready` resolves to the route once the app opens
 * the socket, so the test can push processor frames to the page.
 */
function mockEventsSocket(page: Page): { registered: Promise<void>; ready: Promise<WebSocketRoute> } {
  let resolve!: (ws: WebSocketRoute) => void;
  const ready = new Promise<WebSocketRoute>(r => { resolve = r; });
  // Mock mode (no connectToServer): Playwright plays the server, we push frames.
  const registered = page.routeWebSocket(/\/pipelines\/events\/ws/, ws => resolve(ws));
  return { registered: Promise.resolve(registered), ready };
}

function pushProcessorEvent(ws: WebSocketRoute, type: string, nodeId: string, snapshot?: unknown, lastSeen?: string) {
  ws.send(JSON.stringify({ channel: "PROCESSOR", type, nodeId, processor: snapshot, lastSeen }));
}

async function loginAndOpenCortex(page: Page) {
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
  await page.getByRole("button", { name: "Cortex" }).first().click();
  await expect(page.getByTestId("worker-card-node-1")).toBeVisible({ timeout: 10_000 });
}

test.describe("Cortex live updates – mocked", () => {

  test("renders workers from the REST snapshot", async ({ page }) => {
    await mockRest(page);
    await mockEventsSocket(page).registered;
    await loginAndOpenCortex(page);

    const card = page.getByTestId("worker-card-node-1");
    await expect(card).toContainText("cortex-gpu-01");
    await expect(page.getByTestId("worker-status-node-1")).toHaveText("online");
  });

  test("reflects live processor events without a reload", async ({ page }) => {
    await mockRest(page);
    const { registered, ready } = mockEventsSocket(page);
    await registered;
    await loginAndOpenCortex(page);

    const ws = await ready;

    // 1. A new registration appears as a card without reloading.
    pushProcessorEvent(ws, "REGISTERED", "node-2", proc("node-2", "cortex-cpu-02", { cpu: 33 }));
    await expect(page.getByTestId("worker-card-node-2")).toBeVisible({ timeout: 5_000 });
    await expect(page.getByTestId("worker-card-node-2")).toContainText("cortex-cpu-02");

    // 2. A processor going OFFLINE is reflected live.
    pushProcessorEvent(ws, "STATE_CHANGED", "node-1", proc("node-1", "cortex-gpu-01", { state: "OFFLINE", cpu: 0, caps: ["GPU", "CPU"] }));
    await expect(page.getByTestId("worker-status-node-1")).toHaveText("offline", { timeout: 5_000 });

    // 3. Metrics update live.
    pushProcessorEvent(ws, "STATUS_UPDATED", "node-2", proc("node-2", "cortex-cpu-02", { cpu: 77 }));
    await expect(page.getByTestId("worker-card-node-2")).toContainText("CPU 77%", { timeout: 5_000 });
  });
});
