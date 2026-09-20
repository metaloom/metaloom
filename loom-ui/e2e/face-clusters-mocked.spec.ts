import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for the face review loop on Detection → Faces — no running Loom backend required.
 *
 * What this is here to protect, in order of how quietly each one broke before:
 *
 *  - **Membership is real.** Cards used to hardcode `faceIds: []`, so every group read "0 faces"
 *    however many were in it. The card count comes from `memberCount` on the list route and the
 *    thumbnails come from `GET /clusters/:uuid/members`.
 *  - **Confirming persists.** Assignment used to mutate local state and nothing else, so it vanished
 *    on reload. It must POST to `/clusters/:uuid/confirm`.
 *  - **Crops come from this deployment.** They were fetched from `https://i.pravatar.cc`, which both
 *    leaked detection uuids to a third party and showed a stranger. No *image* may leave the origin.
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const ASSET_UUID = "22222222-2222-2222-2222-222222222222";
const CLUSTER_UUID = "33333333-3333-3333-3333-333333333333";
const CLUSTER_B_UUID = "77777777-7777-7777-7777-777777777777";
const PERSON_UUID = "44444444-4444-4444-4444-444444444444";
const DETECTION_A = "55555555-5555-5555-5555-555555555555";
const DETECTION_B = "66666666-6666-6666-6666-666666666666";

/** A 1x1 JPEG, so the crop route returns something a browser will actually decode. */
const TINY_JPEG = Buffer.from(
  "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AKp//2Q==",
  "base64",
);

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

interface Confirmed {
  personUuid?: string;
  alias?: string;
}

/** Records what the UI actually sent to /confirm, so the test can assert it rather than infer it. */
interface Recorder {
  confirms: { clusterUuid: string; body: Confirmed }[];
  /** Clusters whose person was taken back off. */
  detaches: string[];
  cropRequests: string[];
  /**
   * Off-origin *image* requests. Must stay empty.
   *
   * Scoped to images rather than to every request: the app loads a webfont stylesheet from Google,
   * which is a separate concern with its own trade-off and not what this test is about. What must
   * never happen is a picture of somebody's face being fetched from, or their detection uuid handed
   * to, an outside host.
   */
  offOriginImages: string[];
}

async function installMocks(page: Page, recorder: Recorder, opts: { memberCount?: number } = {}) {
  const memberCount = opts.memberCount ?? 2;
  let reviewStatus = "PENDING";
  let personUuid: string | undefined;
  let reviewStatusB = "PENDING";
  let personUuidB: string | undefined;

  // An off-origin image is a bug, not a fixture: recorded and aborted so a reintroduced third-party
  // avatar host fails this test instead of silently working. Non-image requests are let through —
  // the app's webfont is a separate trade-off and not what this test speaks to.
  await page.route(/^https?:\/\/(?!localhost|127\.0\.0\.1)/, route => {
    if (route.request().resourceType() === "image") {
      recorder.offOriginImages.push(route.request().url());
      return route.abort();
    }
    return route.continue();
  });

  // Catch-all first (lowest priority) — empty collections for the endpoints the view fans out to.
  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));

  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route =>
    json(route, { uuid: ME_UUID, username: "admin", enabled: true })
  );

  await page.route(/\/api\/v1\/persons(\?|$)/, route =>
    json(route, {
      data: [{ uuid: PERSON_UUID, alias: "Anna Meyer", firstname: "Anna", lastname: "Meyer" }],
      _metainfo: { totalCount: 1 },
    })
  );
  await page.route(/\/api\/v1\/persons\/[^/]+\/clusters$/, route => json(route, { data: [] }));

  // The review queue. Two clusters, from two different assets - which is the normal case in a
  // global list, and the reason a merge cannot be a structural one.
  await page.route(/\/api\/v1\/clusters(\?|$)/, route =>
    json(route, {
      data: [
        {
          uuid: CLUSTER_UUID,
          name: "",
          type: "face",
          reviewStatus,
          personUuid,
          assetUuid: ASSET_UUID,
          clusterIndex: 0,
          score: 0.93,
          memberCount,
          nodeKind: "facedetect",
          status: { creator: { uuid: ME_UUID } },
        },
        {
          uuid: CLUSTER_B_UUID,
          name: "",
          type: "face",
          reviewStatus: reviewStatusB,
          personUuid: personUuidB,
          assetUuid: "99999999-9999-9999-9999-999999999999",
          clusterIndex: 0,
          score: 0.88,
          memberCount,
          nodeKind: "facedetect",
          status: { creator: { uuid: ME_UUID } },
        },
      ],
      _metainfo: { totalCount: 2 },
    })
  );

  await page.route(/\/api\/v1\/clusters\/[^/]+\/members$/, route =>
    json(route, {
      total: 2,
      members: [
        { embeddingUuid: "e1", detectionUuid: DETECTION_A, assetUuid: ASSET_UUID, confidence: 0.97, origin: "AUTO" },
        { embeddingUuid: "e2", detectionUuid: DETECTION_B, assetUuid: ASSET_UUID, confidence: 0.91, origin: "AUTO" },
      ],
    })
  );

  await page.route(/\/api\/v1\/clusters\/[^/]+\/confirm$/, route => {
    const clusterUuid = route.request().url().split("/clusters/")[1].split("/confirm")[0];
    const body = JSON.parse(route.request().postData() || "{}") as Confirmed;
    recorder.confirms.push({ clusterUuid, body });
    // An alias creates the person; a personUuid links to one that exists. Modelled here because
    // the merge flow depends on the difference: it must not send the alias twice.
    const resolved = body.personUuid ?? PERSON_UUID;
    if (clusterUuid === CLUSTER_B_UUID) {
      reviewStatusB = "CONFIRMED";
      personUuidB = resolved;
    } else {
      reviewStatus = "CONFIRMED";
      personUuid = resolved;
    }
    return json(route, {
      uuid: clusterUuid,
      name: "",
      type: "face",
      reviewStatus: "CONFIRMED",
      personUuid: resolved,
      assetUuid: ASSET_UUID,
      memberCount,
      status: { creator: { uuid: ME_UUID } },
    });
  });

  await page.route(/\/api\/v1\/clusters\/[^/]+\/person$/, route => {
    const clusterUuid = route.request().url().split("/clusters/")[1].split("/person")[0];
    recorder.detaches.push(clusterUuid);
    if (clusterUuid === CLUSTER_B_UUID) {
      reviewStatusB = "PENDING";
      personUuidB = undefined;
    } else {
      reviewStatus = "PENDING";
      personUuid = undefined;
    }
    return json(route, {
      uuid: clusterUuid, name: "", type: "face", reviewStatus: "PENDING",
      assetUuid: ASSET_UUID, memberCount, status: { creator: { uuid: ME_UUID } },
    });
  });

  // The face crops, served from this origin.
  await page.route(/\/api\/v1\/assets\/[^/]+\/detections\/[^/]+\/crop/, route => {
    recorder.cropRequests.push(route.request().url());
    return route.fulfill({ status: 200, contentType: "image/jpeg", body: TINY_JPEG });
  });
}

async function openFaces(page: Page, recorder: Recorder, opts: { memberCount?: number } = {}) {
  await installMocks(page, recorder, opts);
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });

  await page.getByRole("button", { name: "Detection", exact: true }).first().click();
  await expect(page.getByText(/faces/i).first()).toBeVisible({ timeout: 10_000 });
}

function recorder(): Recorder {
  return { confirms: [], detaches: [], cropRequests: [], offOriginImages: [] };
}

test.describe("Face cluster review – mocked e2e", () => {
  test("a pending group shows its real member count", async ({ page }) => {
    const rec = recorder();
    await openFaces(page, rec, { memberCount: 2 });

    // "0 faces" is what the hardcoded-empty membership produced for every group, whatever was in it.
    await expect(page.getByText(/2 faces/i).first()).toBeVisible({ timeout: 10_000 });
  });

  test("member crops are fetched from this deployment, never a third party", async ({ page }) => {
    const rec = recorder();
    await openFaces(page, rec);

    await expect.poll(() => rec.cropRequests.length, { timeout: 10_000 }).toBeGreaterThan(0);

    expect(rec.cropRequests.some(u => u.includes(DETECTION_A))).toBe(true);
    // The load-bearing assertion: no face picture was fetched from anywhere but here. A reintroduced
    // i.pravatar.cc (or any other avatar host) fails on this line.
    expect(rec.offOriginImages).toEqual([]);
  });

  test("confirming a group posts to the confirm route", async ({ page }) => {
    const rec = recorder();
    await openFaces(page, rec);

    // The link icon on an unassigned card opens the assign dialog.
    await page.locator("svg[data-testid='LinkOutlinedIcon']").first().click();

    const dialog = page.getByRole("dialog");
    await expect(dialog).toBeVisible({ timeout: 5_000 });
    await dialog.getByRole("combobox").click();
    await page.getByRole("option", { name: /anna meyer/i }).click();
    await dialog.getByRole("button", { name: /assign|confirm|save/i }).last().click();

    await expect.poll(() => rec.confirms.length, { timeout: 10_000 }).toBe(1);
    expect(rec.confirms[0].clusterUuid).toBe(CLUSTER_UUID);
    expect(rec.confirms[0].body.personUuid).toBe(PERSON_UUID);
  });

  /**
   * Dragging one cluster onto another says "these are the same person".
   *
   * Not a structural merge, and the schema is the reason: `cluster.asset_uuid` is scalar, this
   * grid is global, and the two cards in this fixture come from different assets - so no single
   * row could hold both. "Several clusters, one subject" is already expressible as
   * `cluster.person_uuid`, and it is the only form the facedetect node cannot undo, because its
   * upsert preserves the review columns.
   */
  test("dropping one cluster on another attributes both to one person", async ({ page }) => {
    const rec = recorder();
    await openFaces(page, rec);

    const cards = page.getByTestId("cluster-card");
    await expect(cards).toHaveCount(2, { timeout: 10_000 });
    const source = cards.nth(1);
    const target = cards.nth(0);

    await source.dragTo(target);

    // Neither was attributed, so the merge has to name somebody.
    const dialog = page.getByTestId("facedetection-merge-dialog");
    await expect(dialog).toBeVisible({ timeout: 5_000 });
    await page.getByTestId("facedetection-merge-name").fill("Anna Meyer");
    await page.getByTestId("facedetection-merge-save").click();

    await expect.poll(() => rec.confirms.length, { timeout: 10_000 }).toBe(2);
    // The first confirm creates the person by alias; the second links to the uuid it returned.
    // Sending the alias twice would either make two people of the same name or hit the unique
    // index on (type, name).
    expect(rec.confirms[0].body.alias).toBe("Anna Meyer");
    expect(rec.confirms[1].body.alias).toBeUndefined();
    expect(rec.confirms[1].body.personUuid).toBe(PERSON_UUID);
    expect(new Set(rec.confirms.map(c => c.clusterUuid)).size).toBe(2);
  });

  test("dropping onto an already attributed cluster joins that person without asking", async ({ page }) => {
    const rec = recorder();
    await openFaces(page, rec);
    await expect(page.getByTestId("cluster-card")).toHaveCount(2, { timeout: 10_000 });

    // Attribute the first card through the existing dialog, so the second drop has a person to join.
    await page.locator("svg[data-testid='LinkOutlinedIcon']").first().click();
    const assign = page.getByTestId("facedetection-assign-dialog");
    await expect(assign).toBeVisible({ timeout: 5_000 });
    await assign.getByRole("combobox").click();
    await page.getByRole("option", { name: /anna meyer/i }).click();
    await page.getByTestId("facedetection-assign-save").click();
    await expect.poll(() => rec.confirms.length, { timeout: 10_000 }).toBe(1);

    // The grid regroups once a cluster is attributed: the confirmed card moves under its person's
    // heading and the unattributed one sits below. So index 0 is now the assigned card.
    const cards = page.getByTestId("cluster-card");
    await expect(cards.nth(0)).toHaveAttribute("data-assigned", "true", { timeout: 10_000 });
    await expect(cards.nth(1)).toHaveAttribute("data-assigned", "false");

    // Stack the unattributed one onto it. No dialog: the person is already known.
    await cards.nth(1).dragTo(cards.nth(0));

    await expect.poll(() => rec.confirms.length, { timeout: 10_000 }).toBe(2);
    await expect(page.getByTestId("facedetection-merge-dialog")).toBeHidden();
    expect(rec.confirms[1].body.personUuid).toBe(PERSON_UUID);
  });

  test("clusters of one person are grouped under a heading", async ({ page }) => {
    const rec = recorder();
    await openFaces(page, rec);
    await expect(page.getByTestId("cluster-card")).toHaveCount(2, { timeout: 10_000 });

    await page.getByTestId("cluster-card").nth(1).dragTo(page.getByTestId("cluster-card").nth(0));
    await page.getByTestId("facedetection-merge-name").fill("Anna Meyer");
    await page.getByTestId("facedetection-merge-save").click();

    // Per-asset clustering means one person is many cards; scattered through the grid there is no
    // way to see that the attribution work is done.
    const heading = page.getByTestId("cluster-person-group");
    await expect(heading).toHaveCount(1, { timeout: 10_000 });
    await expect(heading).toHaveAttribute("data-person-name", "Anna Meyer");
  });

  test("a wrong stack can be taken back off the person", async ({ page }) => {
    const rec = recorder();
    await openFaces(page, rec);
    await expect(page.getByTestId("cluster-card")).toHaveCount(2, { timeout: 10_000 });

    await page.getByTestId("cluster-card").nth(1).dragTo(page.getByTestId("cluster-card").nth(0));
    await page.getByTestId("facedetection-merge-name").fill("Anna Meyer");
    await page.getByTestId("facedetection-merge-save").click();
    await expect.poll(() => rec.confirms.length, { timeout: 10_000 }).toBe(2);

    // Dragging onto the wrong card is the obvious way to get this wrong, and `reject` is not the
    // way back - it would record that somebody's face is not a real subject.
    const chip = page.getByTestId("cluster-person-chip").first();
    await expect(chip).toBeVisible();
    await chip.locator("svg").last().click();

    await expect.poll(() => rec.detaches.length, { timeout: 10_000 }).toBe(1);
  });

  // ── Reading the grid ───────────────────────────────────────────────────

  test("hovering a crop opens an enlarged copy, and leaving closes it", async ({ page }) => {
    const rec = recorder();
    await openFaces(page, rec);
    await expect.poll(() => rec.cropRequests.length, { timeout: 10_000 }).toBeGreaterThan(0);

    const crop = page.getByTestId("face-crop").first();
    await expect(page.getByTestId("face-crop-zoom")).toHaveCount(0);

    await crop.hover();
    await expect(page.getByTestId("face-crop-zoom")).toBeVisible({ timeout: 5_000 });

    // The zoom must not need a second fetch: it is the same object URL at a different CSS size.
    // A wrapper component that re-fetched would hold a duplicate blob per face in the grid.
    const afterHover = rec.cropRequests.length;
    await page.mouse.move(0, 0);
    await expect(page.getByTestId("face-crop-zoom")).toBeHidden({ timeout: 5_000 });
    expect(rec.cropRequests.length).toBe(afterHover);
  });

  test("the small size drops the card chrome and keeps the faces", async ({ page }) => {
    const rec = recorder();
    await openFaces(page, rec);
    await expect(page.getByTestId("cluster-card")).toHaveCount(2, { timeout: 10_000 });
    await expect(page.getByTestId("cluster-name").first()).toBeVisible();

    await page.getByTestId("facedetection-card-size-small").click();

    // The point of the size: the name, count, review date and buttons are three lines of chrome
    // between every two rows of the thing the reviewer is actually comparing.
    await expect(page.getByTestId("cluster-name")).toHaveCount(0);
    await expect(page.getByTestId("clusters-grid")).toHaveAttribute("data-card-size", "small");
    await expect(page.getByTestId("cluster-card")).toHaveCount(2);
    await expect(page.getByTestId("face-crop").first()).toBeVisible();
  });

  test("the small size draws the faces large enough to compare", async ({ page }) => {
    const rec = recorder();
    await openFaces(page, rec);
    await expect.poll(() => rec.cropRequests.length, { timeout: 10_000 }).toBeGreaterThan(0);

    await page.getByTestId("facedetection-card-size-small").click();
    await expect(page.getByTestId("clusters-grid")).toHaveAttribute("data-card-size", "small");

    // The mode exists for looking at faces, and it used to draw them at 40px — below what a face
    // is recognisable at, so the size that dropped the chrome to make room for faces was the one
    // you could see them worst in.
    const box = await page.getByTestId("face-crop").first().boundingBox();
    expect(box?.width).toBeGreaterThanOrEqual(56);
  });

  // ── Getting around the grid ─────────────────────────────────

  test("double-clicking a card opens the person picker with the caret in it", async ({ page }) => {
    const rec = recorder();
    await openFaces(page, rec);
    await expect(page.getByTestId("cluster-card")).toHaveCount(2, { timeout: 10_000 });

    await page.getByTestId("cluster-card").first().dblclick();

    const dialog = page.getByTestId("facedetection-assign-dialog");
    await expect(dialog).toBeVisible({ timeout: 5_000 });
    // The caret has to be in the box already: the gesture that opened the dialog was a double
    // click, and the next thing the reviewer does is type a name.
    await expect(page.getByTestId("facedetection-assign-input")).toBeFocused();

    await page.getByTestId("facedetection-assign-input").fill("Anna");
    await page.getByRole("option", { name: /anna meyer/i }).click();
    await page.getByTestId("facedetection-assign-save").click();

    await expect.poll(() => rec.confirms.length, { timeout: 10_000 }).toBe(1);
    expect(rec.confirms[0].body.personUuid).toBe(PERSON_UUID);
  });

  test("the arrow keys walk the cards and Enter opens the picker", async ({ page }) => {
    const rec = recorder();
    await openFaces(page, rec);
    const cards = page.getByTestId("cluster-card");
    await expect(cards).toHaveCount(2, { timeout: 10_000 });

    await cards.first().focus();
    await expect(cards.first()).toHaveAttribute("data-focused", "true");

    await page.keyboard.press("ArrowRight");
    await expect(cards.nth(1)).toBeFocused();
    await page.keyboard.press("ArrowLeft");
    await expect(cards.nth(0)).toBeFocused();

    await page.keyboard.press("Enter");
    await expect(page.getByTestId("facedetection-assign-dialog")).toBeVisible({ timeout: 5_000 });
  });

  test("a card dropped on a person's group joins that person", async ({ page }) => {
    const rec = recorder();
    await openFaces(page, rec);
    const cards = page.getByTestId("cluster-card");
    await expect(cards).toHaveCount(2, { timeout: 10_000 });

    // Attribute one card first, so there is a group to aim at.
    await cards.first().dblclick();
    await page.getByTestId("facedetection-assign-input").fill("Anna");
    await page.getByRole("option", { name: /anna meyer/i }).click();
    await page.getByTestId("facedetection-assign-save").click();
    await expect.poll(() => rec.confirms.length, { timeout: 10_000 }).toBe(1);

    // The group is the drop target, not only the card inside it: aiming at a particular tile of
    // somebody's stack is a precision the statement "this is the same person" does not need.
    const group = page.getByTestId("cluster-person-group");
    await expect(group).toHaveCount(1, { timeout: 10_000 });
    await page.getByTestId("cluster-card").nth(1).dragTo(group);

    await expect.poll(() => rec.confirms.length, { timeout: 10_000 }).toBe(2);
    expect(rec.confirms[1].body.personUuid).toBe(PERSON_UUID);
    // No dialog: the person is already known, so there is nothing to ask.
    await expect(page.getByTestId("facedetection-merge-dialog")).toBeHidden();
  });

  test("the chosen size survives leaving the screen and coming back", async ({ page }) => {
    const rec = recorder();
    await openFaces(page, rec);
    await page.getByTestId("facedetection-card-size-large").click();
    await expect(page.getByTestId("clusters-grid")).toHaveAttribute("data-card-size", "large");

    // Which size suits you is a property of the work, not of the visit. Re-picking it on every
    // navigation is the kind of friction that stops a control being used at all.
    await page.getByRole("button", { name: "Tags", exact: true }).first().click();
    await expect(page.getByTestId("clusters-grid")).toHaveCount(0, { timeout: 10_000 });
    await page.getByRole("button", { name: "Detection", exact: true }).first().click();
    await expect(page.getByTestId("clusters-grid")).toHaveAttribute("data-card-size", "large", { timeout: 10_000 });
  });
});
