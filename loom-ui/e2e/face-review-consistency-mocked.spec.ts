import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for the face screen's bookkeeping between its two panels — no running Loom backend.
 *
 * `face-panels-mocked.spec.ts` and `face-clusters-mocked.spec.ts` cover each action on its own.
 * What they do not cover is the *second* thing every action has to update: a cluster belongs to
 * at most one person, so re-assigning it, or deleting the person, changes two cards rather than
 * one. These specs pin the fixes for the places that changed only the first:
 *
 *  - re-assigning a cluster left it listed under its previous person as well;
 *  - deleting a person left their clusters grouped under a heading that could only print a uuid
 *    (the server's FK is ON DELETE SET NULL, so the clusters are already unattributed there);
 *  - every keystroke in the search box cancelled and re-sent the member-list requests;
 *  - editing an unnamed cluster prefilled — and so saved — the "Unnamed cluster" placeholder;
 *  - editing a person who only has an alias prefilled the alias as their first name.
 *
 * Routes exercised:
 *   GET      /api/v1/clusters, /api/v1/persons, /api/v1/persons/:uuid/clusters
 *   POST     /api/v1/clusters/:uuid            (rename)
 *   GET      /api/v1/clusters/:uuid/members
 *   POST     /api/v1/clusters/:uuid/confirm
 *   POST     /api/v1/persons/:uuid             (rename)
 *   DELETE   /api/v1/persons/:uuid
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const ASSET_UUID = "22222222-2222-2222-2222-222222222222";
const CLUSTER_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
const CLUSTER_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
const PERSON_ANNA = "44444444-4444-4444-4444-444444444444";
const PERSON_BOB = "55555555-5555-5555-5555-555555555555";

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
  confirms: { clusterUuid: string; body: Record<string, unknown> }[];
  clusterUpdates: { uuid: string; body: Record<string, unknown> }[];
  personUpdates: { uuid: string; body: Record<string, unknown> }[];
  personDeletes: string[];
  /** Every member-list request, by cluster uuid. */
  memberReads: string[];
}

function recorder(): Recorder {
  return { confirms: [], clusterUpdates: [], personUpdates: [], personDeletes: [], memberReads: [] };
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
  /** Hold every member-list answer this long, so a re-render can land while it is in flight. */
  memberDelayMs?: number;
}

/** Playwright matches the most recently registered route first, so the catch-all goes in first. */
async function installMocks(page: Page, rec: Recorder, opts: MockOptions = {}) {
  const clusters: StoredCluster[] = [...(opts.clusters ?? [])];
  const persons: StoredPerson[] = [...(opts.persons ?? [])];

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
      const idx = persons.findIndex(p => p.uuid === uuid);
      if (idx >= 0) persons.splice(idx, 1);
      // cluster.person_uuid is ON DELETE SET NULL.
      clusters.forEach(c => { if (c.personUuid === uuid) c.personUuid = null; });
      return route.fulfill({ status: 204, body: "" });
    }
    const body = JSON.parse(route.request().postData() || "{}") as Record<string, unknown>;
    rec.personUpdates.push({ uuid, body });
    if (!stored) return json(route, { message: "no such person" }, 404);
    // `update(...)` on the server skips nulls: an absent field is "unchanged".
    for (const [k, v] of Object.entries(body)) if (v !== undefined && v !== null) (stored as unknown as Record<string, unknown>)[k] = v;
    return json(route, stored);
  });

  await page.route(/\/api\/v1\/persons\/[^/]+\/clusters$/, route => {
    const uuid = decodeURIComponent(route.request().url().split("/persons/")[1].split("/clusters")[0]);
    return json(route, { data: clusters.filter(c => c.personUuid === uuid) });
  });

  await page.route(/\/api\/v1\/clusters(\?|$)/, route =>
    json(route, { data: clusters, _metainfo: { totalCount: clusters.length } }));

  await page.route(/\/api\/v1\/clusters\/[^/]+$/, route => {
    const uuid = decodeURIComponent(route.request().url().split("/clusters/")[1].split("?")[0]);
    const stored = clusters.find(c => c.uuid === uuid);
    if (route.request().method() === "DELETE") {
      clusters.splice(clusters.findIndex(c => c.uuid === uuid), 1);
      return route.fulfill({ status: 204, body: "" });
    }
    const body = JSON.parse(route.request().postData() || "{}") as Record<string, unknown>;
    rec.clusterUpdates.push({ uuid, body });
    if (!stored) return json(route, { message: "no such cluster" }, 404);
    Object.assign(stored, body);
    return json(route, stored);
  });

  await page.route(/\/api\/v1\/clusters\/[^/]+\/members$/, async route => {
    const uuid = decodeURIComponent(route.request().url().split("/clusters/")[1].split("/members")[0]);
    rec.memberReads.push(uuid);
    if (opts.memberDelayMs) await new Promise(r => setTimeout(r, opts.memberDelayMs));
    const stored = clusters.find(c => c.uuid === uuid);
    const members = Array.from({ length: stored?.memberCount ?? 0 }, (_, i) => ({
      embeddingUuid: `${uuid}-e${i}`, detectionUuid: `${uuid}-d${i}`, assetUuid: ASSET_UUID, confidence: 0.9, origin: "AUTO",
    }));
    // The page may be gone by the time the delay is over.
    return json(route, { total: members.length, members }).catch(() => {});
  });

  await page.route(/\/api\/v1\/clusters\/[^/]+\/confirm$/, route => {
    const uuid = decodeURIComponent(route.request().url().split("/clusters/")[1].split("/confirm")[0]);
    const body = JSON.parse(route.request().postData() || "{}") as Record<string, unknown>;
    rec.confirms.push({ clusterUuid: uuid, body });
    const stored = clusters.find(c => c.uuid === uuid);
    if (!stored) return json(route, { message: "no such cluster" }, 404);
    stored.reviewStatus = "CONFIRMED";
    stored.personUuid = body.personUuid as string;
    return json(route, stored);
  });

  await page.route(/\/api\/v1\/assets\/[^/]+\/detections\/[^/]+\/crop/, route =>
    route.fulfill({ status: 200, contentType: "image/jpeg", body: TINY_JPEG }));
}

async function login(page: Page) {
  if (await page.getByPlaceholder("Username").count() === 0) return;
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
}

async function openFaces(page: Page) {
  await page.goto("/");
  await login(page);
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

function personCard(page: Page, name: string) {
  return page.getByTestId("person-card").filter({ has: page.getByTestId("person-name").getByText(name, { exact: true }) });
}

test.describe("Face review bookkeeping – mocked e2e", () => {
  test("re-assigning a cluster takes it off the person who held it before", async ({ page }) => {
    const rec = recorder();
    await installMocks(page, rec, {
      clusters: [cluster(CLUSTER_A, "Group A", 1, PERSON_ANNA)],
      persons: [
        { uuid: PERSON_ANNA, alias: "anna", firstname: "Anna", lastname: "Meyer" },
        { uuid: PERSON_BOB, alias: "bob", firstname: "Bob", lastname: "Ross" },
      ],
    });
    await openFaces(page);

    // Sanity: Anna holds the cluster before anything happens.
    await page.getByTestId("facedetection-section-persons").click();
    await expect(personCard(page, "Anna Meyer").getByTestId("person-cluster-chip")).toHaveText("Group A", { timeout: 10_000 });
    await page.getByTestId("facedetection-section-clusters").click();

    await page.getByTestId("cluster-card").dblclick();
    await expect(page.getByTestId("facedetection-assign-dialog")).toBeVisible();
    // Enter, not the Save button: with one match on offer the popup sits over the button.
    await page.getByTestId("facedetection-assign-input").fill("Bob Ross");
    await page.getByTestId("facedetection-assign-input").press("Enter");

    await expect.poll(() => rec.confirms.length, { timeout: 10_000 }).toBe(1);
    expect(rec.confirms[0]).toEqual({ clusterUuid: CLUSTER_A, body: { personUuid: PERSON_BOB } });

    await page.getByTestId("facedetection-section-persons").click();
    await expect(personCard(page, "Bob Ross").getByTestId("person-cluster-chip")).toHaveText("Group A");
    // The defect: Anna still listed the cluster, so one face group read as two people.
    await expect(personCard(page, "Anna Meyer").getByTestId("person-cluster-chip")).toHaveCount(0);
    await expect(personCard(page, "Anna Meyer").getByTestId("person-cluster-count")).toHaveText("0 clusters");
  });

  test("stacking a card from one person onto another's moves it, and the first person lets go", async ({ page }) => {
    const rec = recorder();
    await installMocks(page, rec, {
      clusters: [cluster(CLUSTER_A, "Group A", 1, PERSON_ANNA), cluster(CLUSTER_B, "Group B", 1, PERSON_BOB)],
      persons: [
        { uuid: PERSON_ANNA, alias: "anna", firstname: "Anna", lastname: "Meyer" },
        { uuid: PERSON_BOB, alias: "bob", firstname: "Bob", lastname: "Ross" },
      ],
    });
    await openFaces(page);

    const source = page.locator(`[data-testid=cluster-card][data-cluster-id="${CLUSTER_A}"]`);
    const target = page.locator(`[data-testid=cluster-card][data-cluster-id="${CLUSTER_B}"]`);
    await expect(source).toBeVisible({ timeout: 10_000 });
    await source.dragTo(target);

    await expect.poll(() => rec.confirms.map(c => c.clusterUuid), { timeout: 10_000 }).toEqual([CLUSTER_A]);
    expect(rec.confirms[0].body).toEqual({ personUuid: PERSON_BOB });

    await page.getByTestId("facedetection-section-persons").click();
    await expect(personCard(page, "Bob Ross").getByTestId("person-cluster-chip")).toHaveCount(2);
    await expect(personCard(page, "Anna Meyer").getByTestId("person-cluster-chip")).toHaveCount(0);
  });

  test("deleting a person returns their clusters to the unassigned band", async ({ page }) => {
    const rec = recorder();
    await installMocks(page, rec, {
      clusters: [cluster(CLUSTER_A, "Group A", 1, PERSON_ANNA), cluster(CLUSTER_B, "Group B", 1)],
      persons: [{ uuid: PERSON_ANNA, alias: "anna", firstname: "Anna", lastname: "Meyer" }],
    });
    await openFaces(page);
    await expect(page.getByTestId("cluster-person-group")).toHaveCount(1, { timeout: 10_000 });

    await page.getByTestId("facedetection-section-persons").click();
    await personCard(page, "Anna Meyer").getByTestId("person-delete").click();
    await expect.poll(() => rec.personDeletes, { timeout: 10_000 }).toEqual([PERSON_ANNA]);
    await expect(page.getByTestId("person-card")).toHaveCount(0);

    await page.getByTestId("facedetection-section-clusters").click();
    // The defect: the group survived, headed by the deleted person's raw uuid.
    await expect(page.getByTestId("cluster-person-group")).toHaveCount(0);
    await expect(page.locator(`[data-person-name="${PERSON_ANNA}"]`)).toHaveCount(0);
    await expect(page.locator(`[data-testid=cluster-card][data-cluster-id="${CLUSTER_A}"]`)).toHaveAttribute("data-assigned", "false");
    await expect(page.getByTestId("cluster-card").and(page.locator("[data-assigned=false]"))).toHaveCount(2);
  });

  test("typing in the search box does not re-request the member lists", async ({ page }) => {
    const rec = recorder();
    await installMocks(page, rec, {
      clusters: [cluster(CLUSTER_A, "Group A", 2), cluster(CLUSTER_B, "Group B", 2)],
      memberDelayMs: 1_500,
    });
    await openFaces(page);
    await expect(page.getByTestId("cluster-card")).toHaveCount(2, { timeout: 10_000 });

    // Keystrokes while the first batch is still in flight. Each one re-renders the parent, which
    // hands the grid a new filtered array.
    await expect.poll(() => rec.memberReads.length, { timeout: 5_000 }).toBeGreaterThanOrEqual(2);
    await page.getByTestId("facedetection-search").locator("input").pressSequentially("Grou", { delay: 60 });

    // The crops land even though the parent kept re-rendering underneath the request.
    await expect(page.getByTestId("face-crop")).toHaveCount(4, { timeout: 10_000 });
    expect(rec.memberReads.filter(id => id === CLUSTER_A)).toHaveLength(1);
    expect(rec.memberReads.filter(id => id === CLUSTER_B)).toHaveLength(1);
  });

  test("renaming an unnamed cluster starts from an empty field, not the placeholder", async ({ page }) => {
    const rec = recorder();
    await installMocks(page, rec, { clusters: [cluster(CLUSTER_A, "", 1)] });
    await openFaces(page);
    await showCardChrome(page);

    const card = page.getByTestId("cluster-card");
    await expect(card.getByTestId("cluster-name")).toHaveText("Unnamed cluster", { timeout: 10_000 });
    await card.getByTestId("cluster-edit").click();

    const field = page.getByTestId("cluster-edit-name").locator("input");
    await expect(field).toHaveValue("");
    // And an untouched field cannot write the placeholder in as a name.
    await expect(page.getByTestId("cluster-edit-save")).toBeDisabled();

    await field.fill("  Kim  ");
    await page.getByTestId("cluster-edit-save").click();
    await expect.poll(() => rec.clusterUpdates.length, { timeout: 10_000 }).toBe(1);
    expect(rec.clusterUpdates[0]).toEqual({ uuid: CLUSTER_A, body: { name: "Kim" } });
    await expect(card.getByTestId("cluster-name")).toHaveText("Kim");
  });

  test("editing a person who only has an alias does not invent a first name", async ({ page }) => {
    const rec = recorder();
    await installMocks(page, rec, { persons: [{ uuid: PERSON_BOB, alias: "bobby" }] });
    await openFaces(page);
    await page.getByTestId("facedetection-section-persons").click();

    await expect(page.getByTestId("person-name")).toHaveText("bobby", { timeout: 10_000 });
    await page.getByTestId("person-edit").click();
    await expect(page.getByTestId("person-edit-alias").locator("input")).toHaveValue("bobby");
    await expect(page.getByTestId("person-edit-firstname").locator("input")).toHaveValue("");
    await expect(page.getByTestId("person-edit-lastname").locator("input")).toHaveValue("");

    await page.getByTestId("person-edit-save").click();
    await expect.poll(() => rec.personUpdates.length, { timeout: 10_000 }).toBe(1);
    expect(rec.personUpdates[0].body.firstname).toBeUndefined();
    expect(rec.personUpdates[0].body.lastname).toBeUndefined();
  });

  test("deleting a cluster takes it off its person's card", async ({ page }) => {
    const rec = recorder();
    await installMocks(page, rec, {
      clusters: [cluster(CLUSTER_A, "Group A", 1, PERSON_ANNA), cluster(CLUSTER_B, "Group B", 1, PERSON_ANNA)],
      persons: [{ uuid: PERSON_ANNA, alias: "anna", firstname: "Anna", lastname: "Meyer" }],
    });
    await openFaces(page);
    await showCardChrome(page);

    await page.locator(`[data-testid=cluster-card][data-cluster-id="${CLUSTER_A}"]`).getByTestId("cluster-delete").click();
    await expect(page.getByTestId("cluster-card")).toHaveCount(1, { timeout: 10_000 });

    await page.getByTestId("facedetection-section-persons").click();
    await expect(personCard(page, "Anna Meyer").getByTestId("person-cluster-count")).toHaveText("1 clusters");
    await expect(personCard(page, "Anna Meyer").getByTestId("person-cluster-chip")).toHaveText("Group B");
  });
});
