import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for the documentation coachmarks — the help icon beside a screen heading.
 *
 * What is actually under test is a contract with something outside this repository's runtime: a
 * hint must open `metaloom.io/help/` carrying a topic id **and** the fallback query, in a new tab,
 * and it must name the section the reader is looking at rather than the screen's neighbour. The
 * destination itself is somebody else's job — `src/help/topics.test.ts` checks the ids against the
 * site's map, and the site's `check-links.mjs` checks that map against real anchors.
 *
 * The mode-following hints (Workflow, Detection) are the reason this file exists at all. A single
 * fixed hint per screen would be simpler and would point at the wrong review mode five times out
 * of six, which is precisely the failure a coachmark is supposed to prevent.
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

async function installMocks(page: Page) {
  // Registered first so the specific routes below win — Playwright matches last-registered first.
  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route => json(route, { uuid: ME_UUID, username: "admin", enabled: true }));
  await page.route(/\/api\/v1\/search\/status$/, route =>
    json(route, {
      provider: "lucene", available: true, reason: "", capabilities: ["FACETS"],
      documentCount: 0, dirtyCount: 0,
    }));
}

/**
 * Deep-link, then sign in — the token is in-memory only, so a `goto` *after* signing in throws it
 * away and lands back on the login form.
 */
async function open(page: Page, path: string) {
  await installMocks(page);
  await page.goto(`/ui${path}`);
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
}

/**
 * Assert the hint on screen is the one for `topic`, and that it carries a usable fallback.
 *
 * The *wording* of the fallback query is not this file's business — `src/help/topics.test.ts` pins
 * each one against the website's map, and importing the registry here would make a black-box spec
 * agree with the app by construction rather than by observation. What belongs here is that the
 * screen wired up the right id at all, and that the fallback the site needs actually travelled.
 */
async function expectHint(page: Page, topic: string) {
  const hint = page.getByTestId(`help-hint-${topic}`);
  await expect(hint).toBeVisible();
  const url = new URL((await hint.getAttribute("href"))!);

  expect(url.origin + url.pathname).toBe("https://metaloom.io/help/");
  expect(url.searchParams.get("t")).toBe(topic);

  // Without `q`, a site that has never heard of this id has nothing to fall back to and the
  // reader gets a shortcut index instead of an answer.
  const query = url.searchParams.get("q") ?? "";
  expect(query.split(/\s+/).filter(Boolean).length).toBeGreaterThanOrEqual(5);
  expect(query).not.toBe(topic);
}

test.describe("documentation coachmarks", () => {
  test("a hint opens the documentation junction in a new tab, not this one", async ({ page }) => {
    await open(page, "/search");
    const hint = page.getByTestId("help-hint-search");

    // The UI holds unsaved work on other screens and its session only in memory; navigating the
    // app away to read documentation is a cost no help icon should be able to impose.
    await expect(hint).toHaveAttribute("target", "_blank");
    // The documentation is another origin and this one holds a session.
    await expect(hint).toHaveAttribute("rel", /noopener/);
    await expectHint(page, "search");
  });

  test("the hint is reachable by name, not only by pointer", async ({ page }) => {
    await open(page, "/search");
    // A tooltip is not an accessible name; a link with no text needs one of its own.
    await expect(page.getByRole("link", { name: /Read the documentation: Search/ })).toBeVisible();
  });

  /**
   * The screens whose hint does not depend on anything the reader has done. One test each rather
   * than one walk through all of them: the token is in-memory, so moving between them means
   * signing in again anyway, and a failure then names the screen instead of the first screen after
   * the one that broke.
   */
  const FLAGSHIP: [string, string][] = [
    ["/", "chat"],
    ["/memory", "memory"],
    ["/search", "search"],
    ["/uploads", "uploads"],
    ["/pipelines", "pipeline.editing"],
    ["/admin/permissions", "admin.acl"],
  ];

  for (const [path, topic] of FLAGSHIP) {
    test(`${path} carries the ${topic} hint`, async ({ page }) => {
      await open(page, path);
      await expectHint(page, topic);
    });
  }

  test("the detection hint follows the tab", async ({ page }) => {
    await open(page, "/detection");

    // Faces have a documentation section of their own: grouping a stranger's face is a different
    // job from confirming a box a model drew.
    await expectHint(page, "detection.faces");

    await page.getByRole("tab", { name: /objects/i }).click();
    await expectHint(page, "detection.results");
    await expect(page.getByTestId("help-hint-detection.faces")).toHaveCount(0);

    await page.getByRole("tab", { name: /llm/i }).click();
    await expectHint(page, "detection.results");
  });

  test("the workflow hint follows the review mode", async ({ page }) => {
    await open(page, "/workflow");

    // Rating is the mode the screen opens on.
    await expectHint(page, "workflow.rating");

    await page.getByTestId("workflow-mode-deduplication").click();
    await expectHint(page, "workflow.dedup");
    await expect(page.getByTestId("help-hint-workflow.rating")).toHaveCount(0);

    await page.getByTestId("workflow-mode-facedetection").click();
    await expectHint(page, "detection.results");

    await page.getByTestId("workflow-mode-llm").click();
    await expectHint(page, "detection.results");
  });

  test("every hint on screen names its topic in the current language", async ({ page }) => {
    await open(page, "/workflow");
    // A missing i18n key renders as the raw key, which would read "help.topic.workflow.rating" in
    // the tooltip and in the accessible name — visible to a reader, invisible to a type-checker.
    const label = await page.getByTestId("help-hint-workflow.rating").getAttribute("aria-label");
    expect(label).not.toContain("help.topic");
    expect(label).toContain("Rating and tagging");
  });
});
