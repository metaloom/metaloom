import { test, expect, Page } from "@playwright/test";

/**
 * Numeric node parameters in the editor sidebar, using the face-detection cluster radius as the case.
 *
 * Two things are pinned here, and they are the two halves of "this option is configurable":
 *
 * 1. The bounds a node declares reach the input. `faceClusterEPS` has always declared `0 … 2` in
 *    steps of `0.05`, the backend has always emitted them, and the editor dropped them — so the
 *    field accepted `50` (or `-1`, which the worker rejects outright) and the browser's spinner
 *    stepped by a whole 1 through a range that is only 2 wide.
 * 2. The edited value is persisted under `options`, which is the key `PipelineGraphParser` reads.
 *    An earlier defect wrote `config`, which no parser read, so every parameter an author set was
 *    dropped at the Loom boundary.
 *
 * No running backend — every REST call is intercepted, following `pipeline-affinity-mocked.spec.ts`.
 */

const PIPELINE_UUID = "22222222-2222-2222-2222-222222222222";

/** Seed: a source feeding one face-detection node carrying the shipped default radius. */
const DEFINITION = {
  nodes: [
    { id: "src", type: "filesystem-source", label: "Source", position: { x: 0, y: 0 }, data: {} },
    { id: "faces", type: "facedetect", label: "Face Detection", position: { x: 260, y: 0 }, options: { faceClusterEPS: 0.6 } },
  ],
  edges: [
    { id: "e1", source: "src", sourcePort: "media", target: "faces", targetPort: "image", branch: "ANY" },
  ],
};

/**
 * The `facedetect` parameters copied from the served descriptor, bounds included.
 *
 * `faceClusterMinimum` is here on purpose: it declares no bounds at all, so it is the control that
 * proves the editor omits the attributes rather than inventing a default range for every number.
 */
const FACEDETECT_PARAMETERS = [
  {
    key: "faceClusterMinimum",
    type: "INTEGER",
    defaultValue: 2,
    label: "Min Cluster Size",
    description: "Minimum detections to form a cluster",
  },
  {
    key: "faceClusterEPS",
    type: "NUMBER",
    defaultValue: 0.6,
    label: "Cluster Radius",
    description: "DBSCAN cluster radius threshold",
    min: 0.0,
    max: 2.0,
    step: 0.05,
  },
];

const DESCRIPTORS = [
  {
    kind: "filesystem-source",
    name: "Filesystem Source",
    description: "",
    icon: "",
    category: "SOURCE",
    inputPorts: [],
    outputPorts: [{ id: "media", contentType: "media/*", cardinality: "ONE", required: true }],
    inputGroups: [],
    outputGroups: [],
    dynamicPorts: false,
    parameters: [],
    defaultConcurrency: 1,
    defaultMode: "SEQUENTIAL",
    defaultBlocking: false,
    events: [],
  },
  {
    kind: "facedetect",
    name: "Face Detection",
    description: "Detect and cluster faces in images and video frames.",
    icon: "face",
    category: "ANALYSIS",
    inputPorts: [{ id: "image", label: "Image", contentType: "media/image", cardinality: "ONE", required: true }],
    outputPorts: [{ id: "face_count", contentType: "scalar/integer", cardinality: "ONE", required: true }],
    inputGroups: [],
    outputGroups: [],
    dynamicPorts: false,
    parameters: FACEDETECT_PARAMETERS,
    defaultConcurrency: 2,
    defaultMode: "SEQUENTIAL",
    defaultBlocking: false,
    events: [],
  },
];

function pipelineResponse(uuid: string, name: string, definition: unknown, version = 1, extra: Record<string, unknown> = {}) {
  return {
    uuid,
    versionUuid: `${uuid}-v${version}`,
    versionNumber: version,
    name,
    description: "Mocked pipeline",
    definition,
    enabled: true,
    priority: 0,
    dryRun: false,
    status: { creator: { uuid: "u1", name: "admin" }, created: "2026-07-01T10:00:00Z" },
    ...extra,
  };
}

interface MockState {
  pipelines: ReturnType<typeof pipelineResponse>[];
  saved: any[];
}

async function mockBackend(page: Page): Promise<MockState> {
  const state: MockState = {
    pipelines: [pipelineResponse(PIPELINE_UUID, "Face Pipeline", DEFINITION)],
    saved: [],
  };

  // Least-specific → most-specific (Playwright resolves newest-registered first).
  await page.route("**/api/v1/**", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [] }) })
  );
  await page.route("**/api/v1/login", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ token: "fake-jwt" }) })
  );
  await page.route("**/api/v1/pipeline/node-descriptors", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ nodeDescriptors: DESCRIPTORS, contentTypes: [] }) })
  );
  await page.route("**/api/v1/pipeline/content-types", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify([]) })
  );
  await page.route("**/api/v1/pipelines", route =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ data: state.pipelines, _metainfo: { totalCount: state.pipelines.length } }),
    })
  );
  await page.route(/\/api\/v1\/pipelines\/[^/]+\/runs$/, route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [] }) })
  );
  await page.route(/\/api\/v1\/pipelines\/([^/]+)\/versions$/, route => {
    const uuid = route.request().url().match(/pipelines\/([^/]+)\/versions/)![1];
    const p = state.pipelines.find(x => x.uuid === uuid);
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ data: p ? [p] : [], _metainfo: { totalCount: p ? 1 : 0 } }),
    });
  });

  await page.route(/\/api\/v1\/pipelines\/[^/]+$/, route => {
    const req = route.request();
    const uuid = req.url().match(/pipelines\/([^/?]+)/)![1];
    if (req.method() === "POST") {
      const body = req.postDataJSON();
      state.saved.push(body);
      const idx = state.pipelines.findIndex(p => p.uuid === uuid);
      const nextVersion = (state.pipelines[idx]?.versionNumber ?? 1) + 1;
      const updated = pipelineResponse(uuid, body.name, body.definition, nextVersion, {
        description: body.description ?? "",
        enabled: body.enabled ?? true,
      });
      if (idx >= 0) state.pipelines[idx] = updated;
      route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(updated) });
      return;
    }
    const p = state.pipelines.find(x => x.uuid === uuid);
    route.fulfill({ status: p ? 200 : 404, contentType: "application/json", body: JSON.stringify(p ?? {}) });
  });

  // Registered last: the /pipelines/:uuid route above also matches this URL, and without this the
  // validation request would be counted as a save.
  await page.route("**/api/v1/pipelines/validate", route =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ valid: true, errors: [], warnings: [] }),
    })
  );

  return state;
}

async function login(page: Page) {
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
  await page.getByRole("button", { name: "Pipelines" }).first().click();
  await expect(page.getByTestId("pipeline-canvas")).toBeVisible({ timeout: 10_000 });
}

/** The sidebar input for one of the selected node's declared parameters. */
function parameterField(page: Page, key: string) {
  return page.getByTestId(`pipeline-node-param-${key}`);
}

test.describe("Pipeline numeric node parameters – mocked", () => {

  test("a bounded parameter carries its declared range onto the input", async ({ page }) => {
    await mockBackend(page);
    await login(page);

    await expect(page.getByTestId("pipeline-canvas").locator(".react-flow__node")).toHaveCount(2, { timeout: 10_000 });
    await page.getByTestId("pipeline-node-faces").click();

    const radius = parameterField(page, "faceClusterEPS");
    await expect(radius).toBeVisible({ timeout: 5_000 });
    await expect(radius).toHaveAttribute("type", "number");
    await expect(radius).toHaveAttribute("min", "0");
    await expect(radius).toHaveAttribute("max", "2");
    // Without this the spinner walks 0 → 1 → 2 and the browser marks the 0.6 default invalid.
    await expect(radius).toHaveAttribute("step", "0.05");

    // The control: a parameter declaring no bounds must not acquire invented ones.
    const minimum = parameterField(page, "faceClusterMinimum");
    await expect(minimum).toHaveAttribute("type", "number");
    await expect(minimum).not.toHaveAttribute("min", /.*/);
    await expect(minimum).not.toHaveAttribute("max", /.*/);
  });

  test("editing the radius persists it under options, where the parser reads it", async ({ page }) => {
    const state = await mockBackend(page);
    await login(page);

    await expect(page.getByTestId("pipeline-canvas").locator(".react-flow__node")).toHaveCount(2, { timeout: 10_000 });
    await page.getByTestId("pipeline-node-faces").click();

    const radius = parameterField(page, "faceClusterEPS");
    await expect(radius).toBeVisible({ timeout: 5_000 });
    await radius.fill("0.35");

    await page.getByText("Save", { exact: true }).click();
    await expect.poll(() => state.saved.length, { timeout: 10_000 }).toBeGreaterThan(0);

    const posted = state.saved[state.saved.length - 1];
    const faces = posted.definition.nodes.find((n: any) => n.id === "faces");
    // `options`, not `config`: the latter is a legacy alias that no Java parser ever read.
    expect(faces.options.faceClusterEPS).toBe(0.35);
    expect(faces.type).toBe("facedetect");
  });
});
