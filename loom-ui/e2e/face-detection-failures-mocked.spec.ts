import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for the face screen's create-cluster dialog and its failure paths — no running Loom
 * backend.
 *
 * `face-panels-mocked.spec.ts`, `face-clusters-mocked.spec.ts` and
 * `face-review-consistency-mocked.spec.ts` cover the happy paths. What they leave open:
 *
 *  - the create-cluster dialog (what it posts, and that the card appears);
 *  - a failed load offers a retry, and the retry really re-runs the load;
 *  - a rejected cluster delete, person delete and person update keep the card as it was and say so;
 *  - a member whose crop is 404 renders the placeholder, not a broken image.
 *
 * Routes exercised:
 *   GET      /api/v1/clusters, /api/v1/persons, /api/v1/persons/:uuid/clusters
 *   POST     /api/v1/clusters                  (create)
 *   DELETE   /api/v1/clusters/:uuid
 *   GET      /api/v1/clusters/:uuid/members
 *   POST     /api/v1/persons/:uuid             (update)
 *   DELETE   /api/v1/persons/:uuid
 *   GET      /api/v1/assets/:uuid/detections/:uuid/crop
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const ASSET_UUID = "22222222-2222-2222-2222-222222222222";
const CLUSTER_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
const CLUSTER_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
const CLUSTER_NEW = "cccccccc-cccc-cccc-cccc-cccccccccccc";
const PERSON_ANNA = "44444444-4444-4444-4444-444444444444";

const TINY_JPEG = Buffer.from(
  "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AKp//2Q==",
  "base64",
);

interface StoredCluster {
  uuid: string;
  name: string;
  type: string;
  reviewStatus: string;
  personUuid?: string | null;
  assetUuid: string;
  memberCount: number;
}

interface StoredPerson {
  uuid: string;
  alias: string;
  firstname?: string;
  lastname?: string;
}

interface Recorder {
  clusterCreates: Record<string, unknown>[];
  clusterDeletes: string[];
  personUpdates: { uuid: string; body: Record<string, unknown> }[];
  personDeletes: string[];
  clusterListReads: number;
  cropReads: string[];
}

function recorder(): Recorder {
  return { clusterCreates: [], clusterDeletes: [], personUpdates: [], personDeletes: [], clusterListReads: 0, cropReads: [] };
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

function cluster(uuid: string, name: string, memberCount: number, personUuid?: string): StoredCluster {
  return {
    uuid, name, type: "face", assetUuid: ASSET_UUID, memberCount, personUuid,
    reviewStatus: personUuid ? "CONFIRMED" : "PENDING",
  };
}

interface MockOptions {
  clusters?: StoredCluster[];
  persons?: StoredPerson[];
  /** How many cluster-list reads answer 500 before the list is served. */
  failClusterListTimes?: number;
  failClusterDelete?: boolean;
  failPersonDelete?: boolean;
  failPersonUpdate?: boolean;
  /** Detection uuids whose crop is missing (404). */
  missingCrops?: string[];
}

/** Playwright matches the most recently registered route first, so the catch-all goes in first. */
async function installMocks(page: Page, rec: Recorder, opts: MockOptions = {}) {
  const clusters: StoredCluster[] = [...(opts.clusters ?? [])];
  const persons: StoredPerson[] = [...(opts.persons ?? [])];
  let clusterListFailuresLeft = opts.failClusterListTimes ?? 0;

  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route => json(route, { uuid: ME_UUID, username: "admin", enabled: true }));

  await page.route(/\/api\/v1\/persons(\?|$)/, route =>
    json(route, { data: persons, _metainfo: { totalCount: persons.length } }));

  await page.route(/\/api\/v1\/persons\/[^/]+$/, route => {
    const uuid = decodeURIComponent(route.request().url().split("/persons/")[1].split("?")[0]);
    const stored = persons.find(p => p.uuid === uuid);
    if (route.request().method() === "DELETE") {
      rec.personDeletes.push(uuid);
      if (opts.failPersonDelete) return json(route, { message: "person is referenced elsewhere" }, 409);
      persons.splice(persons.findIndex(p => p.uuid === uuid), 1);
      return route.fulfill({ status: 204, body: "" });
    }
    const body = JSON.parse(route.request().postData() || "{}") as Record<string, unknown>;
    rec.personUpdates.push({ uuid, body });
    if (opts.failPersonUpdate) return json(route, { message: "alias already taken" }, 409);
    if (!stored) return json(route, { message: "no such person" }, 404);
    for (const [k, v] of Object.entries(body)) if (v !== undefined && v !== null) (stored as unknown as Record<string, unknown>)[k] = v;
    return json(route, stored);
  });

  await page.route(/\/api\/v1\/persons\/[^/]+\/clusters$/, route => {
    const uuid = decodeURIComponent(route.request().url().split("/persons/")[1].split("/clusters")[0]);
    return json(route, { data: clusters.filter(c => c.personUuid === uuid) });
  });

  await page.route(/\/api\/v1\/clusters(\?|$)/, route => {
    if (route.request().method() === "POST") {
      const body = JSON.parse(route.request().postData() || "{}") as Record<string, unknown>;
      rec.clusterCreates.push(body);
      const created = { ...cluster(CLUSTER_NEW, body.name as string, 0), type: (body.type as string) ?? "face" };
      clusters.push(created);
      return json(route, created, 201);
    }
    rec.clusterListReads++;
    if (clusterListFailuresLeft > 0) {
      clusterListFailuresLeft--;
      return json(route, { message: "cluster store unavailable" }, 500);
    }
    return json(route, { data: clusters, _metainfo: { totalCount: clusters.length } });
  });

  await page.route(/\/api\/v1\/clusters\/[^/]+$/, route => {
    const uuid = decodeURIComponent(route.request().url().split("/clusters/")[1].split("?")[0]);
    if (route.request().method() === "DELETE") {
      rec.clusterDeletes.push(uuid);
      if (opts.failClusterDelete) return json(route, { message: "cluster is locked by a running pipeline" }, 409);
      clusters.splice(clusters.findIndex(c => c.uuid === uuid), 1);
      return route.fulfill({ status: 204, body: "" });
    }
    return json(route, clusters.find(c => c.uuid === uuid) ?? { message: "no such cluster" }, clusters.some(c => c.uuid === uuid) ? 200 : 404);
  });

  await page.route(/\/api\/v1\/clusters\/[^/]+\/members$/, route => {
    const uuid = decodeURIComponent(route.request().url().split("/clusters/")[1].split("/members")[0]);
    const stored = clusters.find(c => c.uuid === uuid);
    const members = Array.from({ length: stored?.memberCount ?? 0 }, (_, i) => ({
      embeddingUuid: `${uuid}-e${i}`, detectionUuid: `${uuid}-d${i}`, assetUuid: ASSET_UUID, confidence: 0.9, origin: "AUTO",
    }));
    return json(route, { total: members.length, members });
  });

  await page.route(/\/api\/v1\/assets\/[^/]+\/detections\/[^/]+\/crop/, route => {
    const detection = decodeURIComponent(route.request().url().split("/detections/")[1].split("/crop")[0]);
    rec.cropReads.push(detection);
    if (opts.missingCrops?.includes(detection)) return json(route, { message: "no crop" }, 404);
    return route.fulfill({ status: 200, contentType: "image/jpeg", body: TINY_JPEG });
  });
}

async function openFaces(page: Page) {
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
  await page.getByRole("button", { name: "Detection", exact: true }).first().click();
  await expect(page.getByTestId("facedetection-switcher")).toBeVisible({ timeout: 10_000 });
}

/** Up to the first step that draws the card header (name, chip, edit, delete). */
async function showCardChrome(page: Page) {
  const slider = page.getByTestId("facedetection-card-size-slider").locator("input");
  await slider.focus();
  for (let i = 0; i < 4; i++) await slider.press("ArrowLeft");
  for (let i = 0; i < 2; i++) await slider.press("ArrowRight");
  await expect(page.getByTestId("clusters-grid")).toHaveAttribute("data-card-size", "2");
}

/**
 * The failure toast. A CSS locator rather than `getByRole`: while a modal dialog is open MUI marks
 * everything outside it aria-hidden, which takes the toast out of the accessibility tree.
 */
function errorToast(page: Page) {
  return page.locator("[role=alert]").filter({ has: page.getByTestId("toast-report-failure") });
}

function clusterCard(page: Page, uuid: string) {
  return page.locator(`[data-testid=cluster-card][data-cluster-id="${uuid}"]`);
}

test.describe("Face detection – create dialog and failure paths (mocked e2e)", () => {
  test("the create-cluster dialog posts the name and the new cluster gets a card", async ({ page }) => {
    const rec = recorder();
    await installMocks(page, rec, { clusters: [cluster(CLUSTER_A, "Group A", 1)] });
    await openFaces(page);
    await showCardChrome(page);
    await expect(page.getByTestId("cluster-card")).toHaveCount(1, { timeout: 10_000 });

    await page.getByTestId("facedetection-add-cluster").click();
    const create = page.getByTestId("facedetection-cluster-create");
    // An empty name is not a cluster.
    await expect(create).toBeDisabled();
    await page.getByTestId("facedetection-cluster-name").locator("input").fill("Holiday 2025");
    await create.click();

    await expect.poll(() => rec.clusterCreates, { timeout: 10_000 }).toEqual([{ name: "Holiday 2025" }]);
    await expect(page.getByRole("dialog")).toBeHidden();
    await expect(page.getByTestId("cluster-card")).toHaveCount(2);
    await expect(clusterCard(page, CLUSTER_NEW).getByTestId("cluster-name")).toHaveText("Holiday 2025");
  });

  test("a failed load says so, and Try again re-runs the load", async ({ page }) => {
    const rec = recorder();
    await installMocks(page, rec, { clusters: [cluster(CLUSTER_A, "Group A", 1)], failClusterListTimes: 1 });
    await openFaces(page);

    const failure = page.getByTestId("face-detection-load-failure");
    await expect(failure).toBeVisible({ timeout: 10_000 });
    await expect(failure).toContainText("cluster store unavailable");
    // Not "no clusters": the empty state would be a different and false statement.
    await expect(page.getByTestId("cluster-card")).toHaveCount(0);
    expect(rec.clusterListReads).toBe(1);

    await page.getByTestId("face-detection-load-failure-retry").click();
    await expect.poll(() => rec.clusterListReads, { timeout: 10_000 }).toBe(2);
    await expect(failure).toHaveCount(0);
    await expect(clusterCard(page, CLUSTER_A)).toBeVisible();
  });

  test("a rejected cluster delete keeps the card and says why", async ({ page }) => {
    const rec = recorder();
    await installMocks(page, rec, {
      clusters: [cluster(CLUSTER_A, "Group A", 1), cluster(CLUSTER_B, "Group B", 1)],
      failClusterDelete: true,
    });
    await openFaces(page);
    await showCardChrome(page);

    await clusterCard(page, CLUSTER_A).getByTestId("cluster-delete").click();
    await expect.poll(() => rec.clusterDeletes, { timeout: 10_000 }).toEqual([CLUSTER_A]);
    await expect(errorToast(page)).toContainText("cluster is locked by a running pipeline");
    await expect(page.getByTestId("cluster-card")).toHaveCount(2);
    await expect(clusterCard(page, CLUSTER_A).getByTestId("cluster-name")).toHaveText("Group A");
  });

  test("a rejected person delete keeps the person and their clusters", async ({ page }) => {
    const rec = recorder();
    await installMocks(page, rec, {
      clusters: [cluster(CLUSTER_A, "Group A", 1, PERSON_ANNA)],
      persons: [{ uuid: PERSON_ANNA, alias: "anna", firstname: "Anna", lastname: "Meyer" }],
      failPersonDelete: true,
    });
    await openFaces(page);
    await page.getByTestId("facedetection-section-persons").click();
    await expect(page.getByTestId("person-card")).toHaveCount(1, { timeout: 10_000 });

    await page.getByTestId("person-delete").click();
    await expect.poll(() => rec.personDeletes, { timeout: 10_000 }).toEqual([PERSON_ANNA]);
    await expect(errorToast(page)).toContainText("person is referenced elsewhere");
    await expect(page.getByTestId("person-card")).toHaveCount(1);
    await expect(page.getByTestId("person-name")).toHaveText("Anna Meyer");
    await expect(page.getByTestId("person-cluster-chip")).toHaveText("Group A");

    // The cluster is still attributed: the SET NULL bookkeeping only runs on an accepted delete.
    await page.getByTestId("facedetection-section-clusters").click();
    await expect(page.getByTestId("cluster-person-group")).toHaveCount(1);
  });

  test("a rejected person update keeps the dialog open with the typed values and the card unchanged", async ({ page }) => {
    const rec = recorder();
    await installMocks(page, rec, {
      persons: [{ uuid: PERSON_ANNA, alias: "anna", firstname: "Anna", lastname: "Meyer" }],
      failPersonUpdate: true,
    });
    await openFaces(page);
    await page.getByTestId("facedetection-section-persons").click();
    await expect(page.getByTestId("person-name")).toHaveText("Anna Meyer", { timeout: 10_000 });

    await page.getByTestId("person-edit").click();
    await page.getByTestId("person-edit-firstname").locator("input").fill("Hanna");
    await page.getByTestId("person-edit-save").click();

    await expect.poll(() => rec.personUpdates.length, { timeout: 10_000 }).toBe(1);
    expect(rec.personUpdates[0]).toEqual({ uuid: PERSON_ANNA, body: { alias: "anna", firstname: "Hanna", lastname: "Meyer" } });
    await expect(errorToast(page)).toContainText("alias already taken");
    // Closing here would read as a saved edit.
    await expect(page.getByTestId("person-edit-firstname").locator("input")).toHaveValue("Hanna");
    await expect(page.getByTestId("person-name")).toHaveText("Anna Meyer");
  });

  test("a member whose crop is missing shows the placeholder, not a broken image", async ({ page }) => {
    const rec = recorder();
    const missing = `${CLUSTER_A}-d1`;
    await installMocks(page, rec, { clusters: [cluster(CLUSTER_A, "Group A", 2)], missingCrops: [missing] });
    await openFaces(page);

    const crops = clusterCard(page, CLUSTER_A).getByTestId("face-crop");
    await expect(crops).toHaveCount(2, { timeout: 10_000 });
    // Both crops were asked for; the one that exists is drawn as a picture.
    await expect.poll(() => [...rec.cropReads].sort(), { timeout: 10_000 }).toEqual([`${CLUSTER_A}-d0`, missing]);
    await expect(crops.locator("img")).toHaveCount(1);
    await expect(crops.locator("img")).toHaveAttribute("src", /^blob:/);

    // The 404 one is the labelled placeholder.
    const placeholders = page.getByLabel("No face crop available", { exact: true });
    await expect(placeholders).toHaveCount(1);
    await expect(placeholders.locator("img")).toHaveCount(0);

    // And hovering it opens no zoom: a popup of the placeholder icon says nothing.
    await placeholders.hover();
    await expect(page.getByTestId("face-crop-zoom")).toHaveCount(0);
  });
});
