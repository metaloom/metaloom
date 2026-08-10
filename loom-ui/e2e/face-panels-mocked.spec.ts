import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for the two face panels — `ClustersPanel` and `PersonsPanel` — no running Loom backend
 * required.
 *
 * Neither panel has a route of its own ([LOOM_UI.md](../../spec/loom/ui/LOOM_UI.md) §4.2): both are
 * mounted by `FaceDetectionManagement`, which `DetectionManagement` mounts as the Faces tab of
 * `/detection`. So the only way in is the tab plus the chip switcher, and the switcher is itself part
 * of what is under test — a reviewer who cannot reach Persons cannot assign anything to anybody.
 *
 * `face-clusters-mocked.spec.ts` covers the privacy and membership invariants of a single card. This
 * one covers the screen a reviewer operates: switching panels, moving a group onto a person, and the
 * create/rename/empty paths on both sides.
 *
 * Routes exercised:
 *   GET/POST /api/v1/clusters
 *   POST     /api/v1/clusters/:uuid            (rename)
 *   GET      /api/v1/clusters/:uuid/members
 *   POST     /api/v1/clusters/:uuid/confirm
 *   GET/POST /api/v1/persons
 *   POST     /api/v1/persons/:uuid             (rename)
 *   GET      /api/v1/persons/:uuid/clusters
 *   GET      /api/v1/persons/:uuid/images/:imageUuid/data   (the avatar on a person card)
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const ASSET_UUID = "22222222-2222-2222-2222-222222222222";
const CLUSTER_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
const CLUSTER_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
const PERSON_ANNA = "44444444-4444-4444-4444-444444444444";
const PERSON_IMAGE = "55555555-5555-5555-5555-555555555555";

/** A 1x1 JPEG, so a face crop resolves to something the browser will decode. */
const TINY_JPEG = Buffer.from(
  "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AKp//2Q==",
  "base64",
);

interface StoredCluster {
  uuid: string;
  name: string;
  type: string;
  reviewStatus: string;
  personUuid?: string;
  reviewedAt?: string;
  reviewerUuid?: string;
  assetUuid: string;
  memberCount: number;
  /** The creator/editor audit block. Machine-written provenance — deliberately not the review record. */
  status?: {
    creator?: { uuid: string; name?: string };
    created?: string;
    editor?: { uuid: string; name?: string };
    edited?: string;
  };
}

interface StoredPerson {
  uuid: string;
  alias: string;
  firstname?: string;
  lastname?: string;
  /**
   * Where the person's avatar is served from. A URL rather than a uuid: the server decides how a
   * person's picture is addressed, and the panel only renders it.
   */
  avatarUrl?: string;
}

interface Recorder {
  confirms: { clusterUuid: string; body: Record<string, unknown> }[];
  clusterUpdates: { uuid: string; body: Record<string, unknown> }[];
  personCreates: Record<string, unknown>[];
  personUpdates: { uuid: string; body: Record<string, unknown> }[];
}

function recorder(): Recorder {
  return { confirms: [], clusterUpdates: [], personCreates: [], personUpdates: [] };
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

function cluster(uuid: string, name: string, memberCount: number): StoredCluster {
  return { uuid, name, type: "face", reviewStatus: "PENDING", assetUuid: ASSET_UUID, memberCount };
}

interface MockOptions {
  clusters?: StoredCluster[];
  persons?: StoredPerson[];
}

/** Routes are matched most-recently-registered first, so the catch-all goes in first. */
async function installMocks(page: Page, rec: Recorder, opts: MockOptions = {}) {
  const clusters: StoredCluster[] = [...(opts.clusters ?? [])];
  const persons: StoredPerson[] = [...(opts.persons ?? [])];
  let seq = 0;

  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));

  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route =>
    json(route, { uuid: ME_UUID, username: "admin", enabled: true })
  );

  await page.route(/\/api\/v1\/persons(\?|$)/, route => {
    if (route.request().method() === "POST") {
      const body = JSON.parse(route.request().postData() || "{}");
      rec.personCreates.push(body);
      const created: StoredPerson = {
        uuid: `person-${++seq}`,
        alias: body.alias,
        firstname: body.firstname,
        lastname: body.lastname,
      };
      persons.push(created);
      return json(route, created, 201);
    }
    return json(route, { data: persons, _metainfo: { totalCount: persons.length } });
  });

  await page.route(/\/api\/v1\/persons\/[^/]+$/, route => {
    const uuid = decodeURIComponent(route.request().url().split("/persons/")[1].split("?")[0]);
    const stored = persons.find(p => p.uuid === uuid);
    if (route.request().method() === "DELETE") {
      const idx = persons.findIndex(p => p.uuid === uuid);
      if (idx >= 0) persons.splice(idx, 1);
      return route.fulfill({ status: 200, body: "" });
    }
    const body = JSON.parse(route.request().postData() || "{}") as Record<string, unknown>;
    rec.personUpdates.push({ uuid, body });
    if (!stored) return json(route, { message: "no such person" }, 404);
    Object.assign(stored, body);
    return json(route, stored);
  });

  // The inverse of the confirmation — what makes the cluster chips on a person card real.
  await page.route(/\/api\/v1\/persons\/[^/]+\/clusters$/, route => {
    const uuid = decodeURIComponent(route.request().url().split("/persons/")[1].split("/clusters")[0]);
    return json(route, { data: clusters.filter(c => c.personUuid === uuid) });
  });

  await page.route(/\/api\/v1\/clusters(\?|$)/, route => {
    if (route.request().method() === "POST") {
      const body = JSON.parse(route.request().postData() || "{}");
      const created = cluster(`cluster-${++seq}`, body.name, 0);
      clusters.push(created);
      return json(route, created, 201);
    }
    return json(route, { data: clusters, _metainfo: { totalCount: clusters.length } });
  });

  await page.route(/\/api\/v1\/clusters\/[^/]+$/, route => {
    const uuid = decodeURIComponent(route.request().url().split("/clusters/")[1].split("?")[0]);
    const stored = clusters.find(c => c.uuid === uuid);
    if (route.request().method() === "DELETE") {
      const idx = clusters.findIndex(c => c.uuid === uuid);
      if (idx >= 0) clusters.splice(idx, 1);
      return route.fulfill({ status: 200, body: "" });
    }
    const body = JSON.parse(route.request().postData() || "{}") as Record<string, unknown>;
    rec.clusterUpdates.push({ uuid, body });
    if (!stored) return json(route, { message: "no such cluster" }, 404);
    Object.assign(stored, body);
    return json(route, stored);
  });

  await page.route(/\/api\/v1\/clusters\/[^/]+\/members$/, route => {
    const uuid = decodeURIComponent(route.request().url().split("/clusters/")[1].split("/members")[0]);
    const stored = clusters.find(c => c.uuid === uuid);
    const members = Array.from({ length: stored?.memberCount ?? 0 }, (_, i) => ({
      embeddingUuid: `${uuid}-e${i}`,
      detectionUuid: `${uuid}-d${i}`,
      assetUuid: ASSET_UUID,
      confidence: 0.9,
      origin: "AUTO",
    }));
    return json(route, { total: members.length, members });
  });

  // Registered last so it wins over the by-uuid pattern above.
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
    route.fulfill({ status: 200, contentType: "image/jpeg", body: TINY_JPEG })
  );

  // A person's avatar comes from the person's own images, never from an asset. Serving it here is what
  // lets the card assert a real <img> rather than the MUI fallback.
  await page.route(/\/api\/v1\/persons\/[^/]+\/images\/[^/]+\/data$/, route =>
    route.fulfill({ status: 200, contentType: "image/jpeg", body: TINY_JPEG })
  );
}

async function login(page: Page) {
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
}

/**
 * There is no `/clusters` or `/persons` route — both panels hang off the Faces tab of `/detection`,
 * which is the default tab. Getting here is part of what the switcher test asserts.
 */
async function openFaces(page: Page) {
  await page.getByRole("button", { name: "Detection", exact: true }).first().click();
  await expect(page.getByTestId("facedetection-switcher")).toBeVisible({ timeout: 10_000 });
}

async function showPersons(page: Page) {
  await page.getByTestId("facedetection-section-persons").click();
}

async function showClusters(page: Page) {
  await page.getByTestId("facedetection-section-clusters").click();
}

test.describe("Face panels – mocked e2e", () => {
  test("the panel switcher is the only way between clusters and persons, and it says which is showing", async ({ page }) => {
    const rec = recorder();
    await installMocks(page, rec, {
      clusters: [cluster(CLUSTER_A, "Group A", 2)],
      persons: [{ uuid: PERSON_ANNA, alias: "anna", firstname: "Anna", lastname: "Meyer" }],
    });
    await page.goto("/");
    await login(page);
    await openFaces(page);

    // Clusters is the landing panel.
    await expect(page.getByTestId("facedetection-section-clusters")).toHaveAttribute("aria-pressed", "true");
    await expect(page.getByTestId("facedetection-section-persons")).toHaveAttribute("aria-pressed", "false");
    await expect(page.getByTestId("cluster-card")).toHaveCount(1, { timeout: 10_000 });
    await expect(page.getByTestId("person-card")).toHaveCount(0);
    // The create action belongs to the panel that is showing, not to the screen.
    await expect(page.getByTestId("facedetection-add-cluster")).toBeVisible();
    await expect(page.getByTestId("facedetection-add-person")).toHaveCount(0);

    await showPersons(page);

    await expect(page.getByTestId("facedetection-section-persons")).toHaveAttribute("aria-pressed", "true");
    await expect(page.getByTestId("facedetection-section-clusters")).toHaveAttribute("aria-pressed", "false");
    await expect(page.getByTestId("person-card")).toHaveCount(1);
    await expect(page.getByTestId("cluster-card")).toHaveCount(0);
    await expect(page.getByTestId("facedetection-add-person")).toBeVisible();
    await expect(page.getByTestId("facedetection-add-cluster")).toHaveCount(0);

    await showClusters(page);
    await expect(page.getByTestId("cluster-card")).toHaveCount(1);
  });

  test("a cluster card lists its member faces", async ({ page }) => {
    const rec = recorder();
    await installMocks(page, rec, { clusters: [cluster(CLUSTER_A, "Group A", 3)] });
    await page.goto("/");
    await login(page);
    await openFaces(page);

    const card = page.getByTestId("cluster-card");
    await expect(card).toHaveCount(1, { timeout: 10_000 });
    // The count comes from the list route; the crops come from GET /clusters/:uuid/members.
    await expect(card.getByTestId("cluster-face-count")).toHaveText("3 faces");
    await expect(card.getByTestId("face-crop")).toHaveCount(3, { timeout: 10_000 });
  });

  /**
   * A decided cluster says who decided it; a pending one claims nobody.
   *
   * The stamp reads `reviewedAt`/`reviewerUuid`, never the `status` audit block — that block is
   * machine-written provenance the facedetect node rewrites on every pass, so a card sourced from it
   * would credit the pipeline with a human's attribution of a face to a named person. Both clusters
   * below carry a full `status` block precisely so a regression to it would show up here.
   */
  test("a decided cluster shows who reviewed it, and a pending one shows nobody", async ({ page }) => {
    const rec = recorder();
    const machineAudit = {
      creator: { uuid: ME_UUID, name: "admin" },
      created: "2026-01-01T00:00:00Z",
      editor: { uuid: ME_UUID, name: "admin" },
      edited: "2026-08-10T09:00:00Z",
    };
    await installMocks(page, rec, {
      clusters: [
        {
          ...cluster(CLUSTER_A, "Anna Meyer", 2),
          reviewStatus: "CONFIRMED",
          personUuid: PERSON_ANNA,
          reviewedAt: "2026-08-09T10:15:30Z",
          reviewerUuid: ME_UUID,
          status: machineAudit,
        },
        { ...cluster(CLUSTER_B, "Group B", 1), status: machineAudit },
      ],
      persons: [{ uuid: PERSON_ANNA, alias: "anna", firstname: "Anna", lastname: "Meyer" }],
    });
    await page.goto("/");
    await login(page);
    await openFaces(page);

    await expect(page.getByTestId("cluster-card")).toHaveCount(2, { timeout: 10_000 });

    const decided = page.getByTestId("cluster-card").filter({ hasText: "Anna Meyer" });
    const stamp = decided.getByTestId("cluster-reviewed-at");
    await expect(stamp).toBeVisible();
    // The uuid rides in an attribute rather than the label: resolving it to a name needs READ_USER,
    // which a reviewer is not required to hold.
    await expect(stamp).toHaveAttribute("data-reviewer-uuid", ME_UUID);

    const pending = page.getByTestId("cluster-card").filter({ hasText: "Group B" });
    await expect(pending.getByTestId("cluster-reviewed-at")).toHaveCount(0);
  });

  test("assigning a cluster to a person confirms it and it stops being unassigned", async ({ page }) => {
    const rec = recorder();
    await installMocks(page, rec, {
      clusters: [cluster(CLUSTER_A, "Group A", 2), cluster(CLUSTER_B, "Group B", 1)],
      persons: [{ uuid: PERSON_ANNA, alias: "anna", firstname: "Anna", lastname: "Meyer" }],
    });
    await page.goto("/");
    await login(page);
    await openFaces(page);

    const unassigned = page.getByTestId("cluster-card").filter({ has: page.getByTestId("cluster-assign") });
    await expect(unassigned).toHaveCount(2, { timeout: 10_000 });

    const cardA = page.getByTestId("cluster-card").filter({ hasText: "Group A" });
    await cardA.getByTestId("cluster-assign").click();

    await expect(page.getByTestId("facedetection-assign-dialog")).toBeVisible({ timeout: 5_000 });
    await page.getByTestId("facedetection-assign-select").click();
    await page.getByRole("option", { name: "Anna Meyer" }).click();
    await page.getByTestId("facedetection-assign-save").click();

    await expect.poll(() => rec.confirms.length, { timeout: 10_000 }).toBe(1);
    expect(rec.confirms[0].clusterUuid).toBe(CLUSTER_A);
    expect(rec.confirms[0].body.personUuid).toBe(PERSON_ANNA);

    // The card carries the person now and offers no second assignment.
    await expect(cardA).toHaveAttribute("data-assigned", "true");
    await expect(cardA.getByTestId("cluster-person-chip")).toHaveText("Anna Meyer");
    await expect(unassigned).toHaveCount(1);

    // The other side of the same fact: the person picked up the group.
    await showPersons(page);
    const person = page.getByTestId("person-card").filter({ hasText: "Anna Meyer" });
    await expect(person.getByTestId("person-cluster-count")).toHaveText("1 clusters");
    await expect(person.getByTestId("person-cluster-chip")).toHaveText("Group A");

    // Assignment used to be local state only, so it vanished on reload. Auth is in-memory, so the
    // reload logs us out; signing back in and still finding the link is what proves it was stored.
    await page.reload();
    await login(page);
    await openFaces(page);

    await expect(page.getByTestId("cluster-card").filter({ hasText: "Group A" }).getByTestId("cluster-person-chip"))
      .toHaveText("Anna Meyer", { timeout: 10_000 });
    await expect(page.getByTestId("cluster-card").filter({ has: page.getByTestId("cluster-assign") })).toHaveCount(1);
  });

  /**
   * The path a reviewer takes when the group is somebody the library has never seen: create the
   * person from the Persons panel, then confirm the group onto them. There is no "create person"
   * button on a cluster card — the assign dialog only picks from persons that already exist, so
   * creating first is the whole flow, not a detour.
   */
  test("a person created from the persons panel becomes assignable to a cluster", async ({ page }) => {
    const rec = recorder();
    await installMocks(page, rec, { clusters: [cluster(CLUSTER_A, "Group A", 2)], persons: [] });
    await page.goto("/");
    await login(page);
    await openFaces(page);
    await showPersons(page);

    await expect(page.getByTestId("persons-empty")).toBeVisible({ timeout: 10_000 });

    await page.getByTestId("facedetection-add-person").click();
    await page.getByTestId("facedetection-person-alias").locator("input").fill("bob");
    await page.getByTestId("facedetection-person-firstname").locator("input").fill("Bob");
    await page.getByTestId("facedetection-person-lastname").locator("input").fill("Ross");
    await page.getByTestId("facedetection-person-create").click();

    await expect.poll(() => rec.personCreates.length, { timeout: 10_000 }).toBe(1);
    expect(rec.personCreates[0]).toMatchObject({ alias: "bob", firstname: "Bob", lastname: "Ross" });
    const created = page.getByTestId("person-card").filter({ hasText: "Bob Ross" });
    await expect(created).toHaveCount(1);
    await expect(created.getByTestId("person-cluster-count")).toHaveText("0 clusters");

    await showClusters(page);
    await page.getByTestId("cluster-assign").first().click();
    await page.getByTestId("facedetection-assign-select").click();
    await page.getByRole("option", { name: "Bob Ross" }).click();
    await page.getByTestId("facedetection-assign-save").click();

    await expect.poll(() => rec.confirms.length, { timeout: 10_000 }).toBe(1);
    expect(rec.confirms[0].clusterUuid).toBe(CLUSTER_A);
    expect(rec.confirms[0].body.personUuid).toBe("person-1");
    await expect(page.getByTestId("cluster-person-chip")).toHaveText("Bob Ross");
  });

  test("renaming a cluster from its card persists", async ({ page }) => {
    const rec = recorder();
    await installMocks(page, rec, { clusters: [cluster(CLUSTER_A, "Group A", 2)] });
    await page.goto("/");
    await login(page);
    await openFaces(page);

    await expect(page.getByTestId("cluster-name")).toHaveText("Group A", { timeout: 10_000 });

    await page.getByTestId("cluster-edit").click();
    await page.getByTestId("cluster-edit-name").locator("input").fill("The neighbours");
    await page.getByTestId("cluster-edit-save").click();

    await expect.poll(() => rec.clusterUpdates.length, { timeout: 10_000 }).toBe(1);
    expect(rec.clusterUpdates[0]).toMatchObject({ uuid: CLUSTER_A, body: { name: "The neighbours" } });
    await expect(page.getByTestId("cluster-name")).toHaveText("The neighbours");

    await page.reload();
    await login(page);
    await openFaces(page);
    await expect(page.getByTestId("cluster-name")).toHaveText("The neighbours", { timeout: 10_000 });
  });

  test("renaming a person from its card persists", async ({ page }) => {
    const rec = recorder();
    await installMocks(page, rec, {
      persons: [{ uuid: PERSON_ANNA, alias: "anna", firstname: "Anna", lastname: "Meyer" }],
    });
    await page.goto("/");
    await login(page);
    await openFaces(page);
    await showPersons(page);

    await expect(page.getByTestId("person-name")).toHaveText("Anna Meyer", { timeout: 10_000 });

    await page.getByTestId("person-edit").click();
    await page.getByTestId("person-edit-alias").locator("input").fill("annam");
    await page.getByTestId("person-edit-lastname").locator("input").fill("Meyer-Schmidt");
    await page.getByTestId("person-edit-save").click();

    await expect.poll(() => rec.personUpdates.length, { timeout: 10_000 }).toBe(1);
    expect(rec.personUpdates[0]).toMatchObject({
      uuid: PERSON_ANNA,
      body: { alias: "annam", firstname: "Anna", lastname: "Meyer-Schmidt" },
    });
    // The card echoes what the server stored, not the strings that were typed.
    await expect(page.getByTestId("person-name")).toHaveText("Anna Meyer-Schmidt");
    await expect(page.getByTestId("person-alias")).toHaveText("annam");

    await page.reload();
    await login(page);
    await openFaces(page);
    await showPersons(page);
    await expect(page.getByTestId("person-name")).toHaveText("Anna Meyer-Schmidt", { timeout: 10_000 });
  });

  test("a person card shows the avatar the server gave it, and links to the person", async ({ page }) => {
    const rec = recorder();
    const avatarUrl = `/api/v1/persons/${PERSON_ANNA}/images/${PERSON_IMAGE}/data`;
    await installMocks(page, rec, {
      persons: [{ uuid: PERSON_ANNA, alias: "anna", firstname: "Anna", lastname: "Meyer", avatarUrl }],
    });
    await page.goto("/");
    await login(page);
    await openFaces(page);
    await showPersons(page);

    // A real <img>, not the MUI initials fallback: the point of the whole person-image model is that
    // there is a picture of the person to show. Before it, the card composed an asset URL from
    // primaryImageUuid — which for somebody found in a video was the video file.
    const avatar = page.getByTestId("person-card").locator("img");
    await expect(avatar).toHaveAttribute("src", avatarUrl, { timeout: 10_000 });

    await page.getByTestId("person-name").click();
    await expect(page).toHaveURL(new RegExp(`/persons/${PERSON_ANNA}$`));
  });

  test("both panels state that they are empty rather than rendering nothing", async ({ page }) => {
    const rec = recorder();
    await installMocks(page, rec, { clusters: [], persons: [] });
    await page.goto("/");
    await login(page);
    await openFaces(page);

    await expect(page.getByTestId("clusters-empty")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("cluster-card")).toHaveCount(0);

    await showPersons(page);

    await expect(page.getByTestId("persons-empty")).toBeVisible();
    await expect(page.getByTestId("person-card")).toHaveCount(0);
  });
});
