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
  /** Absent means the worker has no card at all — which is not the same as an idle one. */
  gpu?: number | null;
  io?: number;
  caps?: string[];
  /** Video memory in bytes. Reported apart from the load: a card can be idle with no room left. */
  vramUsed?: number;
  vramTotal?: number;
  gpuName?: string;
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
    systemStatus: {
      cpuLoad: o.cpu ?? 10,
      ...(o.gpu == null ? {} : { gpuLoad: o.gpu }),
      ioLoad: o.io ?? 0,
      memoryUsed: 512,
      memoryTotal: 1024,
      ...(o.vramUsed == null ? {} : { gpuMemoryUsed: o.vramUsed }),
      ...(o.vramTotal == null ? {} : { gpuMemoryTotal: o.vramTotal }),
      ...(o.gpuName == null ? {} : { gpuName: o.gpuName }),
    },
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

  /**
   * Both GPU figures, and only when the worker reported them.
   *
   * The screen used to gate the GPU readout on `capabilities.includes("GPU")` — which is what a
   * worker advertises it can *run*, not what it has — and the cortex daemon never filled the
   * load in at all, so the row was empty even on a box with four cards.
   */
  test("a worker with a card reports its load and its video memory", async ({ page }) => {
    await page.route("**/api/v1/**", route =>
      route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [] }) }));
    await page.route("**/api/v1/login", route =>
      route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ token: "fake-jwt" }) }));
    await page.route("**/api/v1/processors", route =>
      route.fulfill({
        status: 200, contentType: "application/json",
        body: JSON.stringify({ data: [
          proc("node-1", "cortex-gpu-01", {
            gpu: 37, caps: ["GPU", "CPU"],
            // 8 GiB of 24 GiB — a third, and the gigabytes are what say whether the next model fits.
            vramUsed: 8 * 1024 ** 3, vramTotal: 24 * 1024 ** 3, gpuName: "NVIDIA GeForce RTX 4090",
          }),
          proc("node-2", "cortex-cpu-01", { caps: ["CPU"] }),
        ] }),
      }));
    await mockEventsSocket(page).registered;
    await loginAndOpenCortex(page);

    const gpu = page.getByTestId("worker-gpu-node-1");
    await expect(gpu).toContainText("GPU 37%");
    const vram = page.getByTestId("worker-vram-node-1");
    await expect(vram).toContainText("VRAM 33%");
    await expect(vram).toContainText("8.0 GB / 24.0 GB");

    // The CPU-only worker says nothing about a GPU rather than claiming an idle one — an empty
    // bar reads as "plenty of room", which would make it the most attractive target on the fleet.
    await expect(page.getByTestId("worker-card-node-2")).toBeVisible();
    await expect(page.getByTestId("worker-gpu-node-2")).toHaveCount(0);
    await expect(page.getByTestId("worker-vram-node-2")).toHaveCount(0);
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

  test("a worker evicted by the presence sweep leaves online without dropping its card", async ({ page }) => {
    await mockRest(page);
    const { registered, ready } = mockEventsSocket(page);
    await registered;
    await loginAndOpenCortex(page);

    const ws = await ready;
    await expect(page.getByTestId("worker-status-node-1")).toHaveText("online");

    // Exactly what ProcessorPresenceReaper produces for a worker that stopped heartbeating:
    // the shared eviction path emits STATE_CHANGED(OFFLINE) and then DISCONNECTED. No socket
    // closed and no reload happened - the frames are the only signal the view gets.
    pushProcessorEvent(ws, "STATE_CHANGED", "node-1", proc("node-1", "cortex-gpu-01", { state: "OFFLINE", cpu: 0, caps: ["GPU", "CPU"] }));
    pushProcessorEvent(ws, "DISCONNECTED", "node-1");

    await expect(page.getByTestId("worker-status-node-1")).toHaveText("offline", { timeout: 5_000 });
    // The card stays: the instance is persisted, and an operator still has to be able to see
    // and edit the restrictions of a worker that just went away.
    await expect(page.getByTestId("worker-card-node-1")).toContainText("cortex-gpu-01");
  });
});
