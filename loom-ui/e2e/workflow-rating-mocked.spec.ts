import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for the Workflow "rating" mode — no running Loom backend required.
 * All `/api/v1/**` calls are intercepted via `page.route`, with a small in-memory
 * reaction store so a star-rating round-trips against the asset-scoped reaction
 * routes and survives a reload:
 *   GET/POST /api/v1/assets/:uuid/reactions
 *
 * Because auth is held in memory only, a reload logs the user out; re-logging in
 * and finding the rating still present proves it was persisted server-side (the
 * mock store) and re-hydrated, not just kept in React state.
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const ASSET_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
const ASSET_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
const CLUSTER_UUID = "cccccccc-cccc-cccc-cccc-cccccccccccc";
const PERSON_UUID = "dddddddd-dddd-dddd-dddd-dddddddddddd";
const DETECTION_UUID = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
const VLM_COMP_UUID = "ffffffff-ffff-ffff-ffff-ffffffffffff";

interface StoredReaction {
  uuid: string;
  assetUuid: string;
  type?: string;
  rating?: number;
  status: { creator: { uuid: string }; created: string };
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

function asset(uuid: string, filename: string) {
  return {
    uuid,
    file: { filename, mimeType: "image/jpeg", size: 1024 },
    status: { creator: { uuid: ME_UUID } },
  };
}

async function installMocks(page: Page, opts: { failReactionWrites?: boolean } = {}) {
  const reactions: StoredReaction[] = [];
  let seq = 0;

  // Catch-all first (lowest priority) — empty collections for the many list
  // endpoints the workflow view fans out to (detections, …).
  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));

  // ── Face review: clusters, their members, and the person vocabulary ──
  //
  // All three used to come from mock arrays in the bundle — two of which were empty, so the pane
  // rendered nothing whatever the server held, and the third seeded people who do not exist.

  await page.route(/\/api\/v1\/assets\/[^/]+\/clusters$/, route =>
    json(route, {
      data: [{
        uuid: CLUSTER_UUID,
        name: "Face group 1",
        reviewStatus: "PENDING",
        assetUuid: ASSET_A,
        memberCount: 1,
      }],
    })
  );
  await page.route(/\/api\/v1\/clusters\/[^/]+\/members$/, route =>
    json(route, {
      members: [{ detectionUuid: DETECTION_UUID, assetUuid: ASSET_A, bboxX: 0.1, bboxY: 0.1, bboxWidth: 0.2, bboxHeight: 0.2 }],
      total: 1,
    })
  );
  await page.route(/\/api\/v1\/persons(\?|$)/, route =>
    json(route, {
      data: [
        { uuid: PERSON_UUID, alias: "Ada Lovelace", firstname: "Ada", lastname: "Lovelace" },
        { uuid: "eeee0000-0000-0000-0000-000000000001", alias: "Grace Hopper" },
      ],
      _metainfo: { totalCount: 2 },
    })
  );

  // ── LLM review: the asset's vlm JSON components ──────────────────────
  await page.route(/\/api\/v1\/assets\/[^/]+\/json-comps(\?|$)/, route =>
    json(route, {
      data: [
        {
          uuid: VLM_COMP_UUID,
          assetUuid: ASSET_A,
          nodeKind: "vlm",
          schemaType: "vlm",
          variant: "describe",
          producerVersion: "Qwen2.5-VL-7B",
          data: { text: "A rack of servers in a cold aisle, blue status lights." },
        },
        // A second schema on the same asset must not leak into this pane.
        {
          uuid: "aaaa0000-0000-0000-0000-000000000002",
          assetUuid: ASSET_A,
          nodeKind: "metadata",
          schemaType: "dublincore",
          data: { title: "not a model output" },
        },
      ],
    })
  );

  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route =>
    json(route, { uuid: ME_UUID, username: "admin", enabled: true })
  );

  await page.route(/\/api\/v1\/assets(\?|$)/, route =>
    json(route, { data: [asset(ASSET_A, "workflow-a.jpg"), asset(ASSET_B, "workflow-b.jpg")], _metainfo: { totalCount: 2 } })
  );

  // Asset-scoped reactions: list (filtered by asset) + create.
  await page.route(/\/api\/v1\/assets\/[^/]+\/reactions$/, route => {
    const assetUuid = decodeURIComponent(route.request().url().split("/assets/")[1].split("/reactions")[0]);
    if (route.request().method() === "POST") {
      if (opts.failReactionWrites) {
        return json(route, { message: "nope" }, 500);
      }
      const body = JSON.parse(route.request().postData() || "{}");
      const created: StoredReaction = {
        uuid: `reaction-${++seq}`,
        assetUuid,
        type: body.type,
        rating: body.rating,
        status: { creator: { uuid: ME_UUID }, created: new Date().toISOString() },
      };
      reactions.push(created);
      return json(route, created, 201);
    }
    return json(route, { data: reactions.filter(r => r.assetUuid === assetUuid) });
  });

  // Asset reaction by uuid: update (POST) + delete.
  await page.route(/\/api\/v1\/assets\/[^/]+\/reactions\/[^/]+$/, route => {
    const uuid = decodeURIComponent(route.request().url().split("/reactions/")[1].split("?")[0]);
    const existing = reactions.find(r => r.uuid === uuid);
    if (route.request().method() === "POST") {
      const body = JSON.parse(route.request().postData() || "{}");
      if (existing) {
        existing.type = body.type;
        existing.rating = body.rating;
      }
      return json(route, existing ?? {}, 200);
    }
    const idx = reactions.findIndex(r => r.uuid === uuid);
    if (idx >= 0) reactions.splice(idx, 1);
    return route.fulfill({ status: 204, body: "" });
  });

  // Single asset by id (registered after the reaction routes so those win).
  await page.route(/\/api\/v1\/assets\/[^/]+$/, route => {
    const uuid = decodeURIComponent(route.request().url().split("/assets/")[1].split("?")[0]);
    return json(route, asset(uuid, "workflow-a.jpg"));
  });
}

async function login(page: Page) {
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
}

async function openWorkflow(page: Page) {
  await page.getByRole("button", { name: "Workflow" }).click();
  await expect(page.getByTestId("workflow-rating-value")).toBeVisible({ timeout: 10_000 });
}

test.describe("Workflow rating – mocked e2e", () => {
  test("rate an asset, reload, and the rating persists", async ({ page }) => {
    await installMocks(page);
    await page.goto("/");
    await login(page);
    await openWorkflow(page);

    const ratingValue = page.getByTestId("workflow-rating-value");
    await expect(ratingValue).toHaveText("—");

    // Rate the current asset via the keyboard binding (5 stars) → POSTs a reaction.
    const created = page.waitForRequest(
      req => req.method() === "POST" && /\/assets\/[^/]+\/reactions$/.test(req.url())
    );
    await page.keyboard.press("5");
    // The type is the point: a rating rides its own reaction type, so it no longer
    // collides with an emoji reaction by the same reviewer on the same asset.
    expect(JSON.parse((await created).postData() || "{}")).toMatchObject({ type: "RATING", rating: 5 });
    await expect(ratingValue).toHaveText("5", { timeout: 10_000 });

    // Reload: auth is in-memory so this logs us out. Log back in and reopen the
    // workflow — the rating must be re-hydrated from the persisted reaction.
    await page.reload();
    await login(page);
    await openWorkflow(page);

    await expect(page.getByTestId("workflow-rating-value")).toHaveText("5", { timeout: 10_000 });
  });

  test("a failed write is rolled back rather than left on screen", async ({ page }) => {
    await installMocks(page, { failReactionWrites: true });
    await page.goto("/");
    await login(page);
    await openWorkflow(page);

    const ratingValue = page.getByTestId("workflow-rating-value");
    await expect(ratingValue).toHaveText("—");

    await page.keyboard.press("7");
    // The stars must go back to "unrated". Leaving the 7 up would tell the reviewer the
    // decision was recorded when the server never heard about it — the failure mode that
    // makes an unnoticed error worse than no persistence at all.
    await expect(ratingValue).toHaveText("—", { timeout: 10_000 });
    await expect(page.getByText(/could not save the rating/i)).toBeVisible({ timeout: 10_000 });
  });

  // ── Face review ────────────────────────────────────────────────────────

  test("the cluster card and the person list come from the server, not from a bundled seed", async ({ page }) => {
    await installMocks(page);
    await page.goto("/");
    await login(page);
    await openWorkflow(page);

    await page.getByTestId("workflow-mode-facedetection").click();

    // The card is the cluster the asset route returned. It used to be joined against a mock array
    // that was always empty, so this pane rendered nothing however many clusters the server held.
    const cluster = page.getByTestId("workflow-cluster");
    await expect(cluster).toHaveCount(1, { timeout: 10_000 });
    await expect(cluster).toHaveAttribute("data-cluster-id", CLUSTER_UUID);
    await expect(cluster).toContainText("Face group 1");

    // Selecting it reveals the assignment row, whose options are the people this instance knows.
    await cluster.click();
    const personInput = page.getByTestId("workflow-person-input");
    await expect(personInput).toBeVisible();
    await personInput.click();
    await expect(page.getByRole("option", { name: "Ada Lovelace" })).toBeVisible({ timeout: 10_000 });
    await expect(page.getByRole("option", { name: "Grace Hopper" })).toBeVisible();
  });

  test("confirming a cluster sends the assigned person's name rather than a local label", async ({ page }) => {
    await installMocks(page);
    await page.goto("/");
    await login(page);
    await openWorkflow(page);

    await page.getByTestId("workflow-mode-facedetection").click();
    const cluster = page.getByTestId("workflow-cluster");
    await expect(cluster).toHaveCount(1, { timeout: 10_000 });
    await cluster.click();

    await page.getByTestId("workflow-person-input").click();
    await page.getByRole("option", { name: "Ada Lovelace" }).click();

    // A confirm without a name is refused server-side, so the assigned person has to travel with it.
    const confirm = page.waitForRequest(
      req => req.method() === "POST" && /\/clusters\/[^/]+\/confirm$/.test(req.url())
    );
    // The button rather than the "y" binding: focus is still in the person input after picking an
    // option, and the keyboard handler deliberately stands down while a text field has focus.
    await cluster.getByRole("button", { name: /confirm/i }).click();
    const body = JSON.parse((await confirm).postData() || "{}");
    expect(body).toMatchObject({ alias: "Ada Lovelace", name: "Ada Lovelace" });
    await expect(cluster).toContainText("Confirmed", { timeout: 10_000 });
  });

  // ── Model-output review ────────────────────────────────────────────────

  test("the model output is the asset's vlm component, not a hardcoded sentence", async ({ page }) => {
    await installMocks(page);
    await page.goto("/");
    await login(page);
    await openWorkflow(page);

    await page.getByTestId("workflow-mode-llm").click();

    const result = page.getByTestId("workflow-llm-result");
    await expect(result).toHaveCount(1, { timeout: 10_000 });
    await expect(result).toContainText("A rack of servers in a cold aisle");
    // Prompt id and the model that answered, both read off the component.
    await expect(page.getByTestId("workflow-llm-variant")).toHaveText("describe");
    await expect(page.getByTestId("workflow-llm-model")).toHaveText("Qwen2.5-VL-7B");
    // The old fiction, and the old hardcoded model chip, must be gone.
    await expect(result).not.toContainText("Corporate presentation scene");
    await expect(page.getByText("gpt-4o")).toHaveCount(0);
    // A component of another schema on the same asset is not a model output.
    await expect(result).not.toContainText("not a model output");
  });

  test("an asset with no vlm component says so instead of showing an empty card", async ({ page }) => {
    await installMocks(page);
    // No components at all: the vlm node has not run here.
    await page.route(/\/api\/v1\/assets\/[^/]+\/json-comps(\?|$)/, route => json(route, { data: [] }));
    await page.goto("/");
    await login(page);
    await openWorkflow(page);

    await page.getByTestId("workflow-mode-llm").click();

    await expect(page.getByTestId("workflow-llm-empty")).toContainText(
      "No vision-model result has been written for this asset yet.", { timeout: 10_000 });
    await expect(page.getByTestId("workflow-llm-result")).toHaveCount(0);
  });
});
