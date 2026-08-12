import { test, expect, Page } from "@playwright/test";
import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";

/**
 * The customer-facing share area.
 *
 * **The only spec in the suite that never signs in.** That is the whole feature: a member of the
 * public opens a URL and sees the material. If a future change puts the share route back inside
 * `AppShell` — below `AuthGate` — every test here fails on the login form appearing, which is
 * exactly the regression worth catching.
 */

const OPEN_SLUG = "demoOpenCollection001";
const LOCKED_SLUG = "demoLockedAssetLink01";
const PASSWORD = "amber-lantern-42";

const VIDEO = {
  uuid: "a0000000-0000-0000-0000-000000000001",
  filename: "autumn-cut-02.mp4",
  mimeType: "video/mp4",
  size: 184320512,
  // Milliseconds, as the share endpoint sends it; api/shares.ts normalises to seconds.
  duration: 92_500,
  width: 1920,
  height: 1080,
  title: "Autumn campaign, cut 2",
  description: "Second assembly, no grade yet.",
};

const STILL = {
  uuid: "a0000000-0000-0000-0000-000000000002",
  filename: "hero-shot.jpg",
  mimeType: "image/jpeg",
  size: 2400000,
  width: 4000,
  height: 3000,
  title: "Hero shot",
};

/**
 * A real, playable one-second clip.
 *
 * Serving invalid bytes here would make every media assertion pass for the wrong reason: the
 * player errors, the component swaps in its "no preview" placeholder, and a test looking only for
 * "something rendered" would never notice the difference.
 */
const TINY_MP4 = fs.readFileSync(
  path.join(path.dirname(fileURLToPath(import.meta.url)), "fixtures", "tiny.mp4"),
);

function json(body: unknown, status = 200) {
  return { status, contentType: "application/json", body: JSON.stringify(body) };
}

/**
 * Route handlers are matched most-recently-registered first, so the catch-all goes on first and
 * every specific override after it.
 */
async function mockShare(
  page: Page,
  options: {
    slug?: string;
    passwordRequired?: boolean;
    allowComments?: boolean;
    allowReactions?: boolean;
    allowAnnotations?: boolean;
    allowDownload?: boolean;
    showMetadata?: boolean;
    targetType?: "ASSET" | "COLLECTION";
    assets?: unknown[];
    gone?: boolean;
    /** Feedback the recipient left on an earlier visit, already on the server when the page loads. */
    seedComments?: Record<string, unknown>[];
    seedAnnotations?: Record<string, unknown>[];
  } = {},
) {
  const slug = options.slug ?? OPEN_SLUG;
  const targetType = options.targetType ?? "COLLECTION";
  const assets = options.assets ?? [VIDEO, STILL];
  const comments: Record<string, unknown>[] = [...(options.seedComments ?? [])];
  const reactions: Record<string, unknown>[] = [];
  const annotations: Record<string, unknown>[] = [...(options.seedAnnotations ?? [])];
  /** Every uuid a DELETE named, so a removed row can be told apart from one that never rendered. */
  const deleted: string[] = [];

  const removeFrom = (list: Record<string, unknown>[], url: string) => {
    const uuid = decodeURIComponent(url.split("?")[0].split("/").pop() ?? "");
    deleted.push(uuid);
    const index = list.findIndex((entry) => (entry.uuid as string) === uuid);
    if (index >= 0) list.splice(index, 1);
  };

  await page.route("**/api/v1/**", (route) => route.fulfill(json({ data: [] })));

  await page.route(/\/api\/v1\/shares\/[^/]+\/assets\/[^/]+\/binary\/data/, (route) =>
    route.fulfill({ status: 200, contentType: "video/mp4", body: TINY_MP4 }),
  );

  await page.route(/\/api\/v1\/shares\/[^/]+\/comments(\?|$)/, async (route) => {
    if (route.request().method() === "POST") {
      const body = JSON.parse(route.request().postData() ?? "{}");
      const created = {
        uuid: `c${comments.length + 1}`,
        text: body.text,
        parentUuid: body.parentUuid,
        assetUuid: body.assetUuid,
        authorName: "Maria from Acme",
        created: new Date().toISOString(),
      };
      comments.push(created);
      return route.fulfill(json(created, 201));
    }
    return route.fulfill(json({ data: comments }));
  });

  // Sub-resource deletes. The list patterns above stop at `comments`/`annotations`, so these never
  // shadow one another whatever order they are registered in.
  await page.route(/\/api\/v1\/shares\/[^/]+\/comments\/[^/]+$/, (route) => {
    removeFrom(comments, route.request().url());
    return route.fulfill({ status: 204, body: "" });
  });

  await page.route(/\/api\/v1\/shares\/[^/]+\/reactions(\?|$)/, async (route) => {
    if (route.request().method() === "POST") {
      const body = JSON.parse(route.request().postData() ?? "{}");
      const created = { uuid: `r${reactions.length + 1}`, type: body.type, assetUuid: body.assetUuid, authorName: "Maria from Acme", created: "" };
      reactions.push(created);
      return route.fulfill(json(created, 201));
    }
    return route.fulfill(json({ data: reactions }));
  });

  await page.route(/\/api\/v1\/shares\/[^/]+\/annotations(\?|$)/, async (route) => {
    if (route.request().method() === "POST") {
      const body = JSON.parse(route.request().postData() ?? "{}");
      const created = { uuid: `an${annotations.length + 1}`, authorName: "Maria from Acme", created: "", ...body };
      annotations.push(created);
      return route.fulfill(json(created, 201));
    }
    return route.fulfill(json({ data: annotations }));
  });

  await page.route(/\/api\/v1\/shares\/[^/]+\/annotations\/[^/]+$/, (route) => {
    removeFrom(annotations, route.request().url());
    return route.fulfill({ status: 204, body: "" });
  });

  await page.route(/\/api\/v1\/shares\/[^/]+\/assets(\?|$)/, (route) => route.fulfill(json({ data: assets })));

  await page.route(/\/api\/v1\/shares\/[^/]+\/sessions$/, async (route) => {
    const body = JSON.parse(route.request().postData() ?? "{}");
    if (options.passwordRequired && body.password !== PASSWORD) {
      return route.fulfill(json({ message: "The password is not correct." }, 401));
    }
    return route.fulfill(
      json({
        sessionToken: "payload.signature",
        visitorName: body.visitorName || "Anonymous",
        targetType,
        targetName: targetType === "COLLECTION" ? "Demo Videos" : VIDEO.filename,
        allowDownload: options.allowDownload ?? true,
        showMetadata: options.showMetadata ?? true,
        allowComments: options.allowComments ?? true,
        allowReactions: options.allowReactions ?? true,
        allowAnnotations: options.allowAnnotations ?? true,
      }),
    );
  });

  // Registered last so it is matched first for the bare slug, and does not swallow the sub-resources.
  await page.route(/\/api\/v1\/shares\/[^/?]+(\?|$)/, (route) => {
    if (options.gone) return route.fulfill(json({ message: "not available" }, 404));
    return route.fulfill(
      json({ targetType, passwordRequired: options.passwordRequired ?? false, visitorNameKnown: false }),
    );
  });

  return { slug, annotations, comments, deleted };
}

test.describe("Customer share area – mocked", () => {
  test("an open link asks for a name and then shows the material – with no sign-in anywhere", async ({ page }) => {
    await mockShare(page);
    await page.goto(`/ui/share/${OPEN_SLUG}`);

    // The gate, not the login form. This assertion is the point of the whole spec.
    await expect(page.getByTestId("share-gate")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByPlaceholder("Username")).toHaveCount(0);

    await page.getByTestId("share-gate-name").fill("Maria from Acme");
    await page.getByTestId("share-gate-submit").click();

    await expect(page.getByTestId("share-viewer")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("share-visitor")).toContainText("Maria from Acme");
    await expect(page.getByTestId("share-grid")).toBeVisible();
    await expect(page.getByTestId("share-tile")).toHaveCount(2);
  });

  test("skipping the name question opens the link as Anonymous", async ({ page }) => {
    await mockShare(page);
    await page.goto(`/ui/share/${OPEN_SLUG}`);

    await page.getByTestId("share-gate-skip").click();

    await expect(page.getByTestId("share-viewer")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("share-visitor")).toContainText("Anonymous");
  });

  test("a wrong password is refused in place, and the right one opens the link", async ({ page }) => {
    await mockShare(page, { slug: LOCKED_SLUG, passwordRequired: true, targetType: "ASSET", assets: [VIDEO] });
    await page.goto(`/ui/share/${LOCKED_SLUG}`);

    await page.getByTestId("share-gate-name").fill("Maria");
    await page.getByTestId("share-gate-password").fill("wrong-password");
    await page.getByTestId("share-gate-submit").click();

    // Refused without navigating away, so the visitor can simply try again.
    await expect(page.getByTestId("share-gate-error")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("share-gate")).toBeVisible();

    await page.getByTestId("share-gate-password").fill(PASSWORD);
    await page.getByTestId("share-gate-submit").click();

    await expect(page.getByTestId("share-viewer")).toBeVisible({ timeout: 10_000 });
  });

  test("an asset share opens straight into the player, with no grid to click through", async ({ page }) => {
    await mockShare(page, { targetType: "ASSET", assets: [VIDEO] });
    await page.goto(`/ui/share/${OPEN_SLUG}`);
    await page.getByTestId("share-gate-skip").click();

    await expect(page.getByTestId("share-media-video")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("share-grid")).toHaveCount(0);
    await expect(page.getByTestId("share-back")).toHaveCount(0);
    await expect(page.getByTestId("share-asset-facts")).toContainText("1:32");
  });

  test("a collection tile opens the asset and Back returns to the grid", async ({ page }) => {
    await mockShare(page);
    await page.goto(`/ui/share/${OPEN_SLUG}`);
    await page.getByTestId("share-gate-skip").click();

    await page.getByTestId("share-tile").first().click();
    await expect(page.getByTestId("share-media-video")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("share-title")).toContainText("Autumn campaign, cut 2");

    await page.getByTestId("share-back").click();
    await expect(page.getByTestId("share-grid")).toBeVisible();
  });

  test("a comment can be left and appears in the thread", async ({ page }) => {
    await mockShare(page, { targetType: "ASSET", assets: [VIDEO] });
    await page.goto(`/ui/share/${OPEN_SLUG}`);
    await page.getByTestId("share-gate-skip").click();

    await expect(page.getByTestId("share-comments-empty")).toBeVisible({ timeout: 10_000 });

    await page.getByTestId("share-comment-input").fill("The second cut runs long");
    await page.getByTestId("share-comment-submit").click();

    await expect(page.getByTestId("share-comment")).toHaveCount(1, { timeout: 10_000 });
    await expect(page.getByTestId("share-comment")).toContainText("The second cut runs long");
  });

  test("a reaction can be left", async ({ page }) => {
    await mockShare(page, { targetType: "ASSET", assets: [VIDEO] });
    await page.goto(`/ui/share/${OPEN_SLUG}`);
    await page.getByTestId("share-gate-skip").click();

    const approve = page.getByTestId("share-reaction-APPROVE");
    await expect(approve).toBeVisible({ timeout: 10_000 });
    await approve.click();
    await expect(approve).toContainText("1", { timeout: 10_000 });
  });

  test("a link that forbids feedback and downloading offers neither", async ({ page }) => {
    await mockShare(page, {
      targetType: "ASSET",
      assets: [VIDEO],
      allowComments: false,
      allowReactions: false,
      allowAnnotations: false,
      allowDownload: false,
    });
    await page.goto(`/ui/share/${OPEN_SLUG}`);
    await page.getByTestId("share-gate-skip").click();

    await expect(page.getByTestId("share-media-video")).toBeVisible({ timeout: 10_000 });
    // Absent, not disabled: a greyed-out button advertises a capability the owner withheld.
    await expect(page.getByTestId("share-feedback")).toHaveCount(0);
    await expect(page.getByTestId("share-download")).toHaveCount(0);
  });

  test("a link that hides metadata still plays the file but reports nothing about it", async ({ page }) => {
    await mockShare(page, {
      targetType: "ASSET",
      showMetadata: false,
      assets: [{ uuid: VIDEO.uuid, filename: VIDEO.filename, mimeType: VIDEO.mimeType }],
    });
    await page.goto(`/ui/share/${OPEN_SLUG}`);
    await page.getByTestId("share-gate-skip").click();

    await expect(page.getByTestId("share-media-video")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("share-asset-facts")).toHaveCount(0);
  });

  test("a region can be drawn on the media and comes back as a normalised box", async ({ page }) => {
    const { annotations } = await mockShare(page, { targetType: "ASSET", assets: [VIDEO] });
    await page.goto(`/ui/share/${OPEN_SLUG}`);
    await page.getByTestId("share-gate-skip").click();
    await expect(page.getByTestId("share-media-video")).toBeVisible({ timeout: 10_000 });

    // Until it is armed the overlay must not swallow clicks, or the player's own controls are
    // unreachable and the reviewer cannot press play.
    await expect(page.getByTestId("share-region-overlay")).toHaveCSS("pointer-events", "none");

    await page.getByTestId("share-draw-region").click();
    await expect(page.getByTestId("share-region-overlay")).toHaveCSS("pointer-events", "auto");

    const frame = await page.getByTestId("share-media-video").boundingBox();
    if (!frame) throw new Error("the player has no box");
    await page.mouse.move(frame.x + frame.width * 0.3, frame.y + frame.height * 0.25);
    await page.mouse.down();
    await page.mouse.move(frame.x + frame.width * 0.6, frame.y + frame.height * 0.55, { steps: 8 });
    await page.mouse.up();

    await expect.poll(() => annotations.length, { timeout: 10_000 }).toBe(1);
    const drawn = annotations[0] as Record<string, number | string>;
    // Fractions of the media, never pixels - that is what makes the box land in the same place on
    // a phone as on the laptop it was drawn on.
    expect(drawn.kind).toBe("SPATIOTEMPORAL");
    expect(drawn.areaX as number).toBeGreaterThan(0.2);
    expect(drawn.areaX as number).toBeLessThan(0.4);
    expect(drawn.areaWidth as number).toBeGreaterThan(0.2);
    expect(drawn.areaWidth as number).toBeLessThanOrEqual(1);
    expect(drawn.areaHeight as number).toBeLessThanOrEqual(1);

    // And it is drawn back over the media.
    await expect(page.getByTestId("share-region")).toHaveCount(1, { timeout: 10_000 });
  });

  test("a stray click draws nothing rather than a zero-area box", async ({ page }) => {
    const { annotations } = await mockShare(page, { targetType: "ASSET", assets: [VIDEO] });
    await page.goto(`/ui/share/${OPEN_SLUG}`);
    await page.getByTestId("share-gate-skip").click();
    await expect(page.getByTestId("share-media-video")).toBeVisible({ timeout: 10_000 });

    await page.getByTestId("share-draw-region").click();
    const frame = await page.getByTestId("share-media-video").boundingBox();
    if (!frame) throw new Error("the player has no box");
    await page.mouse.click(frame.x + frame.width * 0.5, frame.y + frame.height * 0.5);

    await page.waitForTimeout(500);
    // The database refuses a zero-extent box; discarding it here means the visitor never sees a 400.
    expect(annotations).toHaveLength(0);
  });

  test("a mark left earlier renders with its timecode and can be removed", async ({ page }) => {
    const { annotations, deleted } = await mockShare(page, {
      targetType: "ASSET",
      assets: [VIDEO],
      seedAnnotations: [
        {
          uuid: "an-seed-1",
          assetUuid: VIDEO.uuid,
          kind: "TEMPORAL",
          timeFrom: 45,
          text: "The logo pops in a frame late",
          authorName: "Maria from Acme",
          created: "",
        },
      ],
    });
    await page.goto(`/ui/share/${OPEN_SLUG}`);
    await page.getByTestId("share-gate-skip").click();

    const mark = page.getByTestId("share-annotation");
    await expect(mark).toHaveCount(1, { timeout: 10_000 });
    await expect(mark).toContainText("The logo pops in a frame late");
    // The timecode is what makes a mark actionable — a note with no moment attached is a comment.
    await expect(mark).toContainText("0:45");

    await mark.getByRole("button", { name: /delete/i }).click();
    await expect.poll(() => deleted, { timeout: 10_000 }).toContain("an-seed-1");
    expect(annotations).toHaveLength(0);
    await expect(page.getByTestId("share-annotation")).toHaveCount(0);
  });

  test("a note typed against the playhead is posted as a mark", async ({ page }) => {
    const { annotations } = await mockShare(page, { targetType: "ASSET", assets: [VIDEO] });
    await page.goto(`/ui/share/${OPEN_SLUG}`);
    await page.getByTestId("share-gate-skip").click();
    await expect(page.getByTestId("share-media-video")).toBeVisible({ timeout: 10_000 });

    await page.getByTestId("share-mark-input").fill("Hold this frame a beat longer");
    await page.getByTestId("share-mark-submit").click();

    await expect.poll(() => annotations.length, { timeout: 10_000 }).toBe(1);
    const mark = annotations[0] as Record<string, unknown>;
    expect(mark).toMatchObject({ kind: "TEMPORAL", text: "Hold this frame a beat longer", assetUuid: VIDEO.uuid });
    // Anchored where the player is, never typed by hand — so the field is left empty again after.
    expect(typeof mark.timeFrom).toBe("number");
    await expect(page.getByTestId("share-annotation")).toContainText("Hold this frame a beat longer");
    await expect(page.getByTestId("share-mark-input")).toHaveValue("");
  });

  test("replying to a comment posts against the parent and nests under it", async ({ page }) => {
    const { comments } = await mockShare(page, {
      targetType: "ASSET",
      assets: [VIDEO],
      seedComments: [
        { uuid: "seed-1", text: "The grade is too warm", authorName: "Maria from Acme", created: "" },
      ],
    });
    await page.goto(`/ui/share/${OPEN_SLUG}`);
    await page.getByTestId("share-gate-skip").click();

    await expect(page.getByTestId("share-comment")).toHaveCount(1, { timeout: 10_000 });
    await page.getByTestId("share-comment-reply").click();
    await expect(page.getByText(/replying to Maria from Acme/i)).toBeVisible();

    await page.getByTestId("share-comment-input").fill("Agreed, pulling it back");
    await page.getByTestId("share-comment-submit").click();

    await expect(page.getByTestId("share-comment")).toHaveCount(2, { timeout: 10_000 });
    // The parent uuid is what keeps this a thread rather than a second unrelated remark.
    expect(comments[comments.length - 1]).toMatchObject({ parentUuid: "seed-1", text: "Agreed, pulling it back" });
    // Only the root carries a reply affordance — a reply to a reply would have nowhere to nest.
    await expect(page.getByTestId("share-comment-reply")).toHaveCount(1);
    await expect(page.getByText(/replying to/i)).toHaveCount(0);
  });

  test("deleting a comment issues the DELETE and drops the row", async ({ page }) => {
    const { comments, deleted } = await mockShare(page, {
      targetType: "ASSET",
      assets: [VIDEO],
      seedComments: [
        { uuid: "seed-1", text: "Second cut runs long", authorName: "Maria from Acme", created: "" },
      ],
    });
    await page.goto(`/ui/share/${OPEN_SLUG}`);
    await page.getByTestId("share-gate-skip").click();

    await expect(page.getByTestId("share-comment")).toHaveCount(1, { timeout: 10_000 });
    await page.getByTestId("share-comment-delete").click();

    await expect.poll(() => deleted, { timeout: 10_000 }).toContain("seed-1");
    expect(comments).toHaveLength(0);
    // The list is re-read after the delete, so the row goes without a reload.
    await expect(page.getByTestId("share-comment")).toHaveCount(0);
    await expect(page.getByTestId("share-comments-empty")).toBeVisible();
  });

  test("a revoked, expired or unknown link says so without saying which", async ({ page }) => {
    await mockShare(page, { gone: true });
    await page.goto(`/ui/share/${OPEN_SLUG}`);

    await expect(page.getByTestId("share-unavailable")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("share-gate")).toHaveCount(0);
  });
});
