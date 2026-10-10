import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for the application shell itself: the sidebar, the session it holds, the route error
 * boundary, the failure-report dialog and the boot path in front of all of them.
 *
 * Every other spec walks *through* the shell to reach its feature, so a shell regression shows up
 * there only as an unrelated timeout - or not at all. These assert on the shell's own behaviour:
 * that signing out really signs out, that a restored session gets its name back, that a screen
 * which throws is contained to the routed area, and that the report form for a failure cannot be
 * the next thing that fails.
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const TOKEN_KEY = "loom.auth.token";
const TRACE_ID = "9f2c41ab7d0e4c6fa1b83e5d72c09148";

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

/** A JWT the client can decode: header and signature are opaque to it, the payload is not. */
function jwt(claims: Record<string, unknown>): string {
  return `x.${Buffer.from(JSON.stringify(claims)).toString("base64url")}.y`;
}

interface Mocks {
  /** What GET /me answers. A function so a test can kill the session mid-way. */
  me: () => { status: number; body: unknown };
  loginToken: string;
}

async function installMocks(page: Page, overrides: Partial<Mocks> = {}): Promise<Mocks> {
  const mocks: Mocks = {
    me: () => ({ status: 200, body: { uuid: ME_UUID, username: "ada", enabled: true } }),
    loginToken: "fake-jwt",
    ...overrides,
  };
  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, route => json(route, { token: mocks.loginToken }));
  await page.route(/\/api\/v1\/me$/, route => {
    const { status, body } = mocks.me();
    return json(route, body, status);
  });
  return mocks;
}

async function signIn(page: Page) {
  await page.getByPlaceholder("Username").fill("ada");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
  await expect(page.getByTestId("sidebar-item-/tags")).toBeVisible({ timeout: 10_000 });
}

/**
 * Make the next `count` ResizeObserver constructions throw.
 *
 * The lever for a render-phase failure that does not depend on any one screen's data handling:
 * MUI's Tabs and multiline TextField construct one while mounting, so arming this and then opening
 * a screen that has either is a throw inside the routed tree, exactly where a real one would be.
 * `Infinity` keeps it broken, which is how the report form gets to fail too.
 */
async function installResizeObserverTrap(page: Page) {
  await page.addInitScript(() => {
    const Real = window.ResizeObserver;
    const w = window as unknown as { __roThrows: number };
    w.__roThrows = 0;
    (window as unknown as { ResizeObserver: unknown }).ResizeObserver = class {
      constructor(cb: ResizeObserverCallback) {
        if (w.__roThrows > 0) {
          w.__roThrows -= 1;
          throw new Error("ResizeObserver exploded");
        }
        return new Real(cb);
      }
    };
  });
}

async function armResizeObserverTrap(page: Page, count: number) {
  await page.evaluate(n => { (window as unknown as { __roThrows: number }).__roThrows = n; }, count);
}

test.describe("Session – mocked", () => {
  test("signing out from the avatar menu clears the stored token, and a reload stays signed out", async ({ page }) => {
    await installMocks(page);
    await page.goto("/ui/");
    await signIn(page);
    expect(await page.evaluate(k => window.sessionStorage.getItem(k), TOKEN_KEY)).toBe("fake-jwt");

    await page.getByTestId("sidebar-avatar-button").click();
    await page.getByTestId("sidebar-avatar-logout").click();

    await expect(page.getByPlaceholder("Username")).toBeVisible({ timeout: 10_000 });
    // The half that would be a security defect: a logout that leaves the token behind is undone by F5.
    expect(await page.evaluate(k => window.sessionStorage.getItem(k), TOKEN_KEY)).toBeNull();
    await page.reload();
    await expect(page.getByPlaceholder("Username")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("sidebar-avatar-button")).toHaveCount(0);
  });

  test("a restored session gets the user's name back from /me", async ({ page }) => {
    await installMocks(page);
    await page.goto("/ui/");
    await signIn(page);
    await page.reload();

    // The JWT carries a uuid but no name, so without the /me round trip the menu reads "Unknown".
    await expect(page.getByTestId("sidebar-avatar-button")).toHaveText("A", { timeout: 10_000 });
    await page.getByTestId("sidebar-avatar-button").click();
    await expect(page.getByTestId("sidebar-avatar-menu").getByText("ada", { exact: true })).toBeVisible();
  });

  test("a restored token the server no longer accepts drops to the login form", async ({ page }) => {
    let alive = true;
    await installMocks(page, {
      me: () => (alive
        ? { status: 200, body: { uuid: ME_UUID, username: "ada", enabled: true } }
        : { status: 401, body: { message: "Unauthorized" } }),
    });
    await page.goto("/ui/");
    await signIn(page);

    // Revoked server-side while the tab was away. Not expired by its own `exp`, so only /me can tell.
    alive = false;
    await page.reload();

    await expect(page.getByPlaceholder("Username")).toBeVisible({ timeout: 10_000 });
    expect(await page.evaluate(k => window.sessionStorage.getItem(k), TOKEN_KEY)).toBeNull();
  });

  test("a token that expires while the tab sits in the background is noticed on focus", async ({ page }) => {
    // Valid for a few seconds: long enough to sign in, short enough to outlive in the test.
    const exp = Math.floor(Date.now() / 1000) + 4;
    await installMocks(page, { loginToken: jwt({ uuid: ME_UUID, exp }) });
    await page.goto("/ui/");
    await signIn(page);

    await expect.poll(() => Date.now() / 1000, { timeout: 10_000 }).toBeGreaterThan(exp + 0.5);
    // Nothing has 401'd yet - the shell still looks signed in. Coming back to the tab is the cue.
    await expect(page.getByTestId("sidebar-item-/tags")).toBeVisible();
    await page.evaluate(() => window.dispatchEvent(new Event("focus")));

    await expect(page.getByPlaceholder("Username")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText(/session has expired/i)).toHaveCount(1);
  });

  test("an app whose browser blocks site storage still boots to the login form", async ({ page }) => {
    // What "block all site data" does: the bare `localStorage` access throws SecurityError. The theme
    // and language preferences are read at the very root, above every error boundary, so an
    // unguarded read there was a white page rather than a degraded app.
    await page.addInitScript(() => {
      Object.defineProperty(window, "localStorage", {
        configurable: true,
        get() { throw new DOMException("The operation is insecure.", "SecurityError"); },
      });
    });
    await installMocks(page);
    await page.goto("/ui/");
    await expect(page.getByPlaceholder("Username")).toBeVisible({ timeout: 10_000 });
  });

  test("a stored theme that is neither mode falls back to dark instead of reaching MUI", async ({ page }) => {
    await installMocks(page);
    const bodyBackground = async (stored: string) => {
      await page.evaluate(v => window.localStorage.setItem("loom-ui-theme", v), stored);
      await page.reload();
      await expect(page.getByPlaceholder("Username")).toBeVisible({ timeout: 10_000 });
      return page.evaluate(() => getComputedStyle(document.body).backgroundColor);
    };
    await page.goto("/ui/");

    const dark = await bodyBackground("dark");
    const light = await bodyBackground("light");
    expect(light).not.toBe(dark);
    // "solarized" used to be handed to MUI as palette.mode, which has no such mode, and the token
    // set fell through to light - so a corrupt preference silently flipped the theme.
    expect(await bodyBackground("solarized")).toBe(dark);
  });
});

test.describe("Shell routes – mocked", () => {
  test("an unknown path lands on chat, and the retired /faces route lands on detection", async ({ page }) => {
    await installMocks(page);
    await page.goto("/ui/no-such-screen");
    await signIn(page);
    // The router's "/" under the basename, which it writes without the trailing slash.
    await expect(page).toHaveURL(/\/ui\/?$/);
    await expect(page.getByTestId("sidebar-item-/")).toHaveClass(/Mui-selected/);

    await page.goto("/ui/faces");
    await expect(page).toHaveURL(/\/ui\/detection$/, { timeout: 10_000 });
    await expect(page.getByTestId("sidebar-item-/detection")).toHaveClass(/Mui-selected/);
  });

  test("the theme chosen on the profile repaints at once and survives a reload", async ({ page }) => {
    await installMocks(page);
    await page.goto("/ui/profile");
    await signIn(page);
    const background = () => page.evaluate(() => getComputedStyle(document.body).backgroundColor);
    const dark = await background();

    await page.getByRole("button", { name: "Light", exact: true }).click();
    await expect.poll(background).not.toBe(dark);
    const light = await background();
    expect(await page.evaluate(() => window.localStorage.getItem("loom-ui-theme"))).toBe("light");

    await page.reload();
    await expect(page.getByTestId("sidebar-item-/tags")).toBeVisible({ timeout: 10_000 });
    expect(await background()).toBe(light);
  });
});

test.describe("Sidebar – mocked", () => {
  test("a deep link into the ACL group opens the group and marks its entry", async ({ page }) => {
    await installMocks(page);
    await page.goto("/ui/admin/users");
    await signIn(page);

    // The group opens itself for its own routes - otherwise the page is on screen and its nav entry
    // is hidden behind a collapsed toggle.
    const users = page.getByTestId("sidebar-item-/admin/users");
    await expect(users).toBeVisible();
    await expect(users).toHaveClass(/Mui-selected/);
    await expect(page.getByTestId("sidebar-item-/tags")).not.toHaveClass(/Mui-selected/);

    // An explicit toggle wins over the route.
    await page.getByTestId("sidebar-group-acl").click();
    await expect(users).toHaveCount(0);
    await page.getByTestId("sidebar-group-acl").click();
    await expect(users).toBeVisible();
  });

  test("the ACL group starts closed on routes outside it", async ({ page }) => {
    await installMocks(page);
    await page.goto("/ui/tags");
    await signIn(page);

    await expect(page.getByTestId("sidebar-item-/tags")).toHaveClass(/Mui-selected/);
    await expect(page.getByTestId("sidebar-item-/admin/users")).toHaveCount(0);
    // Chat is "/", which every path starts with - it must only light up on "/" itself.
    await expect(page.getByTestId("sidebar-item-/")).not.toHaveClass(/Mui-selected/);
  });

  test("the collapsed rail flattens the ACL group so its entries stay reachable", async ({ page }) => {
    await installMocks(page);
    await page.goto("/ui/tags");
    await signIn(page);

    await page.getByRole("button", { name: "Collapse sidebar", exact: true }).click();
    await expect(page.getByTestId("sidebar-group-acl")).toHaveCount(0);
    await page.getByTestId("sidebar-item-/admin/groups").click();
    await expect(page).toHaveURL(/\/ui\/admin\/groups$/);

    await page.getByRole("button", { name: "Expand sidebar", exact: true }).click();
    await expect(page.getByTestId("sidebar-group-acl")).toBeVisible();
  });
});

test.describe("Route error boundary – mocked", () => {
  test("a screen that throws is contained: fallback, report form, and the sidebar still works", async ({ page }) => {
    await installResizeObserverTrap(page);
    await installMocks(page);
    await page.goto("/ui/tags");
    await signIn(page);

    // One throw, from the Tabs on the detection screen while it mounts.
    await armResizeObserverTrap(page, 1);
    await page.getByTestId("sidebar-item-/detection").click();

    const fallback = page.getByTestId("error-boundary-fallback");
    await expect(fallback).toBeVisible({ timeout: 10_000 });
    await expect(fallback).toContainText("/detection");
    await expect(fallback).toContainText("ResizeObserver exploded");

    // Straight to the report form, naming the screen that broke - not a toast saying it again.
    const dialog = page.getByTestId("failure-report-dialog");
    await expect(dialog).toBeVisible();
    await expect(page.getByTestId("failure-report-action")).toContainText("render:/detection");
    await expect(page.getByTestId("failure-report-no-trace-id")).toBeVisible();
    await page.getByTestId("failure-report-cancel").click();
    await expect(dialog).toBeHidden();

    // The boundary is keyed by path: leaving the broken screen leaves the broken state behind.
    await page.getByTestId("sidebar-item-/tags").click();
    await expect(fallback).toHaveCount(0);
    await expect(page.getByTestId("tag-new-name")).toBeVisible({ timeout: 10_000 });
  });

  test("'Reload this view' re-renders the screen in place, without a page reload", async ({ page }) => {
    await installResizeObserverTrap(page);
    await installMocks(page);
    await page.goto("/ui/tags");
    await signIn(page);

    await armResizeObserverTrap(page, 1);
    await page.getByTestId("sidebar-item-/detection").click();
    await expect(page.getByTestId("error-boundary-fallback")).toBeVisible({ timeout: 10_000 });
    await page.getByTestId("failure-report-cancel").click();

    // A marker a real reload would wipe: the retry must be a re-render, not location.reload().
    await page.evaluate(() => { (window as unknown as { __marker: string }).__marker = "still-here"; });
    await page.getByTestId("error-boundary-retry").click();

    await expect(page.getByTestId("error-boundary-fallback")).toHaveCount(0);
    await expect(page.getByRole("tab", { name: "Faces", exact: true })).toBeVisible({ timeout: 10_000 });
    expect(await page.evaluate(() => (window as unknown as { __marker?: string }).__marker)).toBe("still-here");
  });

  test("a report form that itself throws does not take the whole app down", async ({ page }) => {
    await installResizeObserverTrap(page);
    await installMocks(page);
    await page.goto("/ui/tags");
    await signIn(page);

    // Keep it broken. The route throws, its boundary opens the report form, and the form's multiline
    // field throws the same way. That form lived outside every boundary, so this second throw
    // unmounted the root: a white page, from the surface whose job is handling failures.
    await armResizeObserverTrap(page, Number.MAX_SAFE_INTEGER);
    await page.getByTestId("sidebar-item-/detection").click();

    await expect(page.getByTestId("error-boundary-fallback").first()).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("sidebar-item-/tags")).toBeVisible();

    await armResizeObserverTrap(page, 0);
    await page.getByTestId("sidebar-item-/tags").click();
    await expect(page.getByTestId("tag-new-name")).toBeVisible({ timeout: 10_000 });
  });
});

test.describe("Failure report dialog – mocked", () => {
  /** Two failing tag creates: two toasts, each carrying its own Report button. */
  async function raiseTwoFailures(page: Page) {
    await page.route(/\/api\/v1\/tags(\?|$)/, route =>
      route.request().method() === "POST"
        ? route.fulfill({
          status: 500,
          contentType: "application/json",
          headers: { "X-Trace-Id": TRACE_ID },
          body: JSON.stringify({ message: "Internal Server Error", traceId: TRACE_ID }),
        })
        : json(route, { data: [] }),
    );
    await page.getByTestId("sidebar-item-/tags").click();
    await page.getByTestId("tag-new-name").fill("doomed-tag");
    await page.getByTestId("tag-create-button").click();
    await expect(page.getByTestId("toast-report-failure")).toHaveCount(1, { timeout: 10_000 });
    await page.getByTestId("tag-create-button").click();
    await expect(page.getByTestId("toast-report-failure")).toHaveCount(2, { timeout: 10_000 });
  }

  test("the close after a sent report does not close the next report opened meanwhile", async ({ page }) => {
    await installMocks(page);
    await page.route(/\/api\/v1\/failure-reports$/, route =>
      json(route, { uuid: "22222222-2222-2222-2222-222222222222", action: "createTag", triageStatus: "NEW" }, 201));
    await page.goto("/ui/");
    await signIn(page);
    await raiseTwoFailures(page);

    await page.getByTestId("toast-report-failure").first().click();
    await page.getByTestId("failure-report-submit").click();
    await expect(page.getByTestId("failure-report-submitted")).toBeVisible();

    // Inside the 1.2s the confirmation is held for: open the second report.
    await page.getByTestId("toast-report-failure").click();
    await expect(page.getByTestId("failure-report-submitted")).toHaveCount(0);
    await page.getByTestId("failure-report-text").fill("second problem, half typed");

    // Well past the delay. The first report's timer used to close whatever was open when it fired.
    await page.waitForTimeout(2_000);
    await expect(page.getByTestId("failure-report-dialog")).toBeVisible();
    await expect(page.getByTestId("failure-report-text")).toHaveValue("second problem, half typed");
  });

  test("sending after the session ended says so instead of silently doing nothing", async ({ page }) => {
    await installMocks(page);
    let posted = 0;
    await page.route(/\/api\/v1\/failure-reports$/, route => { posted += 1; return json(route, {}, 201); });
    await page.goto("/ui/");
    await signIn(page);
    await raiseTwoFailures(page);

    await page.getByTestId("toast-report-failure").first().click();
    await expect(page.getByTestId("failure-report-dialog")).toBeVisible();

    // The global 401 path signs the user out underneath the open form.
    await page.evaluate(() => window.dispatchEvent(new CustomEvent("loom:session-expired")));
    await expect(page.getByPlaceholder("Username")).toBeVisible({ timeout: 10_000 });

    await page.getByTestId("failure-report-submit").click();
    await expect(page.getByTestId("failure-report-error")).toContainText(/signed out/i);
    await expect(page.getByTestId("failure-report-submitted")).toHaveCount(0);
    expect(posted).toBe(0);
  });

  test("a failed submit is reported inline and can be retried", async ({ page }) => {
    await installMocks(page);
    let attempts = 0;
    await page.route(/\/api\/v1\/failure-reports$/, route => {
      attempts += 1;
      return attempts === 1
        ? json(route, { message: "database unavailable" }, 503)
        : json(route, { uuid: "22222222-2222-2222-2222-222222222222", action: "createTag", triageStatus: "NEW" }, 201);
    });
    await page.goto("/ui/");
    await signIn(page);
    await raiseTwoFailures(page);

    await page.getByTestId("toast-report-failure").first().click();
    await page.getByTestId("failure-report-submit").click();
    await expect(page.getByTestId("failure-report-error")).toBeVisible();
    // Not routed through the failure surface: a toast offering to report the failed report is a loop.
    await expect(page.getByTestId("toast-report-failure")).toHaveCount(1);

    await page.getByTestId("failure-report-submit").click();
    await expect(page.getByTestId("failure-report-submitted")).toBeVisible();
    await expect(page.getByTestId("failure-report-error")).toHaveCount(0);
    expect(attempts).toBe(2);
  });
});

test.describe("Unsaved work and signing out – mocked", () => {
  const DESCRIPTORS = [
    { kind: "filesystem-source", inputPorts: [], outputPorts: [{ id: "media", contentType: "media/*", cardinality: "ONE", required: true }], parameters: [] },
    {
      kind: "sha512",
      inputPorts: [{ id: "media", label: "Media", contentType: "media/*", cardinality: "ONE", required: true }],
      outputPorts: [{ id: "hash", contentType: "hash/sha512", cardinality: "ONE", required: true }],
      parameters: [{ key: "chunkSize", type: "INTEGER", defaultValue: 8192, label: "Chunk Size", description: "Bytes read per hash update" }],
    },
  ].map(d => ({
    ...d, name: d.kind, description: "", icon: "", category: "TRANSFORM", inputGroups: [], outputGroups: [],
    dynamicPorts: false, defaultConcurrency: 1, defaultMode: "SEQUENTIAL", defaultBlocking: false, events: [],
  }));
  const PIPELINE = {
    uuid: "33333333-3333-3333-3333-333333333333",
    versionUuid: "33333333-3333-3333-3333-333333333333-v1",
    versionNumber: 1,
    name: "Quick Hash",
    description: "",
    definition: {
      nodes: [
        { id: "src", type: "filesystem-source", label: "Source", position: { x: 0, y: 0 }, data: {} },
        { id: "sha512", type: "sha512", label: "SHA-512", position: { x: 260, y: 0 }, data: {} },
      ],
      edges: [{ id: "e1", source: "src", sourcePort: "media", target: "sha512", targetPort: "media", branch: "ANY" }],
    },
    enabled: true, priority: 0, dryRun: false,
    status: { creator: { uuid: ME_UUID, name: "ada" }, created: "2026-07-01T10:00:00Z" },
  };

  test("signing out with unsaved pipeline edits asks first, and Cancel keeps the session", async ({ page }) => {
    await installMocks(page);
    await page.route(/\/api\/v1\/pipeline\/node-descriptors/, route => json(route, { nodeDescriptors: DESCRIPTORS, contentTypes: [] }));
    await page.route(/\/api\/v1\/pipelines(\?|$)/, route => json(route, { data: [PIPELINE], _metainfo: { totalCount: 1 } }));
    await page.route(/\/api\/v1\/pipelines\/[^/]+\/runs(\?|$)/, route => json(route, { data: [] }));
    await page.route(/\/api\/v1\/pipelines\/[^/]+\/versions(\?|$)/, route => json(route, { data: [PIPELINE], _metainfo: { totalCount: 1 } }));
    await page.route(/\/api\/v1\/pipelines\/[^/?]+$/, route => json(route, PIPELINE));
    await page.route(/\/api\/v1\/pipelines\/validate$/, route => json(route, { valid: true, errors: [], warnings: [] }));
    await page.route(/\/api\/v1\/pipeline\/content-types$/, route => json(route, []));

    await page.goto("/ui/pipelines");
    await signIn(page);
    const canvas = page.getByTestId("pipeline-canvas");
    await expect(canvas.locator(".react-flow__node")).toHaveCount(2, { timeout: 10_000 });
    await page.getByTestId("pipeline-node-sha512").click();
    await page.getByTestId("pipeline-node-param-chunkSize").fill("4096");
    await expect(page.getByText("Save", { exact: true })).toBeVisible({ timeout: 5_000 });

    // Logout unmounts the whole shell - a bigger exit than any sidebar link, and it used to skip the
    // guard every sidebar link goes through.
    await page.getByTestId("sidebar-avatar-button").click();
    await page.getByTestId("sidebar-avatar-logout").click();
    await expect(page.getByTestId("pipeline-switch-confirm")).toBeVisible({ timeout: 5_000 });

    await page.getByRole("button", { name: "Cancel", exact: true }).click();
    await expect(page.getByPlaceholder("Username")).toHaveCount(0);
    await expect(page.getByTestId("pipeline-node-param-chunkSize")).toHaveValue("4096");

    // Confirming the discard completes the sign-out it was holding.
    await page.getByTestId("sidebar-avatar-button").click();
    await page.getByTestId("sidebar-avatar-logout").click();
    await page.getByTestId("pipeline-switch-confirm").click();
    await expect(page.getByPlaceholder("Username")).toBeVisible({ timeout: 10_000 });
  });
});
