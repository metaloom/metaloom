import { test, expect, Page, WebSocketRoute } from "@playwright/test";

/**
 * Round-trip tests for the Cortex node-restriction editor (Tasks 2 & 4).
 *
 * A live cortex worker is impractical to stand up in CI, so — like `cortex-mocked.spec.ts`
 * — the REST calls are intercepted with `page.route`. The test still exercises the full
 * client contract: it asserts the `PUT /processors/:nodeId/restrictions` request body and
 * the `DELETE /processors/:nodeId` forget call, and that the UI reflects both.
 */

interface ProcOpts {
  state?: string;
  caps?: string[];
  whitelist?: string[];
  blacklist?: string[];
  persisted?: boolean;
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
    systemStatus: { cpuLoad: 10, gpuLoad: 0, ioLoad: 0, memoryUsed: 512, memoryTotal: 1024 },
    lastSeen: new Date().toISOString(),
    nodeWhitelist: o.whitelist ?? [],
    nodeBlacklist: o.blacklist ?? [],
    persisted: o.persisted ?? true,
  };
}

/** A minimal node-descriptor payload; only `kind` feeds the restriction Autocomplete. */
const NODE_KINDS = ["sha256", "fingerprint", "loom", "whisper", "embedding"];
function descriptorsBody() {
  return {
    nodeDescriptors: NODE_KINDS.map(kind => ({
      kind, name: kind, description: "", icon: "", category: "ANALYSIS",
      inputs: [], outputs: [], parameters: [], defaultConcurrency: 1,
      defaultMode: "PARALLEL", defaultBlocking: false, events: [],
    })),
    contentTypes: [],
  };
}

async function mockRest(page: Page) {
  // Catch-all first so the specific routes registered afterwards take priority.
  await page.route("**/api/v1/**", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [] }) })
  );
  await page.route("**/api/v1/login", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ token: "fake-jwt" }) })
  );
  await page.route("**/api/v1/pipeline/node-descriptors", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(descriptorsBody()) })
  );
  await page.route("**/api/v1/processors", route =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        data: [
          proc("node-1", "cortex-gpu-01", { caps: ["GPU", "CPU"], whitelist: ["sha256"], blacklist: ["loom"] }),
          proc("node-2", "cortex-ghost-02", { state: "OFFLINE", persisted: true, whitelist: ["whisper"] }),
        ],
      }),
    })
  );
}

function mockEventsSocket(page: Page): Promise<void> {
  // Mock mode: Playwright plays the server so the app's events socket never dangles.
  return Promise.resolve(page.routeWebSocket(/\/pipelines\/events\/ws/, (_ws: WebSocketRoute) => { /* no frames pushed */ }));
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

test.describe("Cortex node restrictions – backend round-trip", () => {

  test("renders each worker's whitelist/blacklist and the remembered badge", async ({ page }) => {
    await mockRest(page);
    await mockEventsSocket(page);
    await loginAndOpenCortex(page);

    await expect(page.getByTestId("worker-whitelist-node-1")).toContainText("sha256");
    await expect(page.getByTestId("worker-blacklist-node-1")).toContainText("loom");

    // The offline-but-persisted worker is marked distinctly and stays listed.
    await expect(page.getByTestId("worker-card-node-2")).toBeVisible();
    await expect(page.getByTestId("worker-badge-persisted-node-2")).toBeVisible();
  });

  test("editing restrictions round-trips through PUT /restrictions", async ({ page }) => {
    await mockRest(page);
    await mockEventsSocket(page);

    let putBody: unknown = null;
    await page.route("**/api/v1/processors/node-1/restrictions", async route => {
      expect(route.request().method()).toBe("PUT");
      putBody = route.request().postDataJSON();
      // Echo back the persisted processor with the new whitelist.
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(proc("node-1", "cortex-gpu-01", {
          caps: ["GPU", "CPU"], whitelist: ["sha256", "fingerprint"], blacklist: ["loom"],
        })),
      });
    });

    await loginAndOpenCortex(page);

    // Open the per-worker menu and the restrictions editor.
    await page.getByTestId("worker-card-node-1").getByRole("button").click();
    await page.getByTestId("worker-menu-restrictions-node-1").click();
    await expect(page.getByTestId("worker-restrictions-dialog")).toBeVisible();

    // Add "fingerprint" to the allow list.
    const whitelistInput = page.getByTestId("worker-restrictions-dialog").getByLabel("Allowed node kinds");
    await whitelistInput.click();
    await whitelistInput.fill("fingerprint");
    await page.getByRole("option", { name: "fingerprint" }).click();

    await page.getByTestId("worker-restrictions-save").click();

    // The request carried both lists…
    await expect.poll(() => putBody).not.toBeNull();
    expect((putBody as { nodeWhitelist: string[] }).nodeWhitelist).toContain("sha256");
    expect((putBody as { nodeWhitelist: string[] }).nodeWhitelist).toContain("fingerprint");
    expect((putBody as { nodeBlacklist: string[] }).nodeBlacklist).toContain("loom");

    // …and the card reflects the change plus a success toast.
    await expect(page.getByTestId("worker-whitelist-node-1")).toContainText("fingerprint");
    await expect(page.getByText("Node restrictions saved")).toBeVisible();
  });

  test("forgetting an offline worker calls DELETE and removes the card", async ({ page }) => {
    await mockRest(page);
    await mockEventsSocket(page);

    let deleteCalled = false;
    await page.route("**/api/v1/processors/node-2", async route => {
      if (route.request().method() === "DELETE") {
        deleteCalled = true;
        await route.fulfill({ status: 204, body: "" });
      } else {
        await route.fallback();
      }
    });

    await loginAndOpenCortex(page);
    await expect(page.getByTestId("worker-card-node-2")).toBeVisible();

    await page.getByTestId("worker-card-node-2").getByRole("button").click();
    await page.getByTestId("worker-menu-forget-node-2").click();

    await expect.poll(() => deleteCalled).toBe(true);
    await expect(page.getByTestId("worker-card-node-2")).toHaveCount(0);
  });
});
