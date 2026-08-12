import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for comment edit/delete inside the Task detail drawer — no running
 * Loom backend required. All `/api/v1/**` calls are intercepted via `page.route`,
 * with a small in-memory comment store so edit → delete round-trips against the
 * task-scoped routes:
 *   GET/POST /api/v1/tasks/:uuid/comments   and   POST/DELETE /api/v1/comments/:uuid
 *
 * Mirrors comments-mocked.spec.ts (which covers the Asset Detail view) to prove the
 * task drawer now wires the same edit/delete affordances into the shared CommentItem.
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const OTHER_UUID = "99999999-9999-9999-9999-999999999999";
const TASK_UUID = "33333333-3333-3333-3333-333333333333";

interface StoredComment {
  uuid: string;
  text: string;
  /** Set on replies. `threadComments` groups on it, one level deep. */
  parentUuid?: string;
  status: { creator: { uuid: string }; created: string };
}

/** What the browser actually sent, so an *absent* parentUuid can be asserted rather than inferred. */
interface Recorder {
  /** Bodies of every POST /tasks/:uuid/comments, in order. */
  posts: { text?: string; parentUuid?: string }[];
}

function recorder(): Recorder {
  return { posts: [] };
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

function task() {
  return {
    uuid: TASK_UUID,
    title: "Mock task",
    priority: "MEDIUM",
    status: { creator: { uuid: ME_UUID }, created: new Date().toISOString() },
  };
}

async function installMocks(page: Page, seed: StoredComment[], rec: Recorder) {
  const comments: StoredComment[] = [...seed];
  let seq = 0;

  // Catch-all first (lowest priority) — empty collections for the many list
  // endpoints the tasks view / drawer fan out to (reactions, …).
  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));

  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route =>
    json(route, { uuid: ME_UUID, username: "admin", enabled: true })
  );

  // Task list + detail
  await page.route(/\/api\/v1\/tasks(\?|$)/, route =>
    json(route, { data: [task()], _metainfo: { totalCount: 1 } })
  );
  await page.route(/\/api\/v1\/tasks\/[^/]+$/, route => json(route, task()));

  // Task-scoped comments: list + create
  await page.route(/\/api\/v1\/tasks\/[^/]+\/comments$/, route => {
    if (route.request().method() === "POST") {
      const body = JSON.parse(route.request().postData() || "{}");
      rec.posts.push(body);
      const created: StoredComment = {
        uuid: `comment-${++seq}`,
        text: body.text ?? "",
        // Echoed back so the reply threads under its parent on the next render.
        ...(body.parentUuid ? { parentUuid: body.parentUuid } : {}),
        status: { creator: { uuid: ME_UUID }, created: new Date().toISOString() },
      };
      comments.push(created);
      return json(route, created, 201);
    }
    return json(route, { data: comments });
  });

  // Comment by uuid: update (POST) + delete
  await page.route(/\/api\/v1\/comments\/[^/]+$/, route => {
    const uuid = decodeURIComponent(route.request().url().split("/comments/")[1].split("?")[0]);
    const found = comments.find(c => c.uuid === uuid);
    if (route.request().method() === "DELETE") {
      const idx = comments.findIndex(c => c.uuid === uuid);
      if (idx >= 0) comments.splice(idx, 1);
      return route.fulfill({ status: 204, body: "" });
    }
    // POST update
    const body = JSON.parse(route.request().postData() || "{}");
    if (found && body.text != null) found.text = body.text;
    return json(route, found ?? {}, 200);
  });
}

async function loginAndOpenTaskDrawer(page: Page, seed: StoredComment[], rec: Recorder = recorder()) {
  await installMocks(page, seed, rec);
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });

  await page.getByRole("button", { name: "Tasks" }).first().click();
  await expect(page.getByRole("heading", { name: "Tasks" })).toBeVisible({ timeout: 10_000 });

  await page.getByText("Mock task").first().click();
  await expect(page.getByRole("heading", { name: "Mock task" })).toBeVisible({ timeout: 10_000 });
  return rec;
}

/** Arm the reply banner against the (single) root comment on screen. */
async function startReply(page: Page) {
  await page.getByTestId("comment-item").first().hover();
  await page.getByTestId("comment-reply").first().click();
  await expect(page.getByTestId("tasks-comment-reply-banner")).toBeVisible({ timeout: 5_000 });
}

async function postComment(page: Page, text: string) {
  await page.getByTestId("tasks-comment-input").fill(text);
  await page.getByTestId("tasks-comment-post").click();
  await expect(page.getByText(text, { exact: true })).toBeVisible({ timeout: 10_000 });
}

function ownComment(): StoredComment {
  return {
    uuid: "own-comment",
    text: "task-comment-by-me",
    status: { creator: { uuid: ME_UUID }, created: new Date().toISOString() },
  };
}

function otherComment(): StoredComment {
  return {
    uuid: "other-comment",
    text: "task-comment-by-someone-else",
    status: { creator: { uuid: OTHER_UUID }, created: new Date().toISOString() },
  };
}

test.describe("Task comments – mocked e2e", () => {
  test("author can edit and delete their own task comment", async ({ page }) => {
    await loginAndOpenTaskDrawer(page, [ownComment()]);

    const text = "task-comment-by-me";
    const editedText = "task-comment-edited";

    await expect(page.getByText(text, { exact: true })).toBeVisible({ timeout: 10_000 });

    // Edit
    await page.locator("div").filter({ hasText: text }).last().hover();
    await page.getByTestId("comment-edit").click();
    await page.getByTestId("comment-edit-field").fill(editedText);
    await page.getByTestId("comment-save").click();
    await expect(page.getByText(editedText, { exact: true })).toBeVisible({ timeout: 10_000 });

    // Delete
    await page.locator("div").filter({ hasText: editedText }).last().hover();
    await page.getByTestId("comment-delete").click();
    await expect(page.getByText(editedText, { exact: true })).toBeHidden({ timeout: 10_000 });
  });

  test("a comment authored by another user shows no edit/delete controls", async ({ page }) => {
    await loginAndOpenTaskDrawer(page, [otherComment()]);

    await expect(page.getByText("task-comment-by-someone-else", { exact: true }))
      .toBeVisible({ timeout: 10_000 });

    // Reveal any hover affordances, then assert the author-only controls are absent.
    await page.locator("div").filter({ hasText: "task-comment-by-someone-else" }).last().hover();
    await expect(page.getByTestId("comment-edit")).toHaveCount(0);
    await expect(page.getByTestId("comment-delete")).toHaveCount(0);
  });

  test("the drawer composer posts a top-level comment", async ({ page }) => {
    const rec = await loginAndOpenTaskDrawer(page, []);

    // Nothing to send until something is typed.
    await expect(page.getByTestId("tasks-comment-post")).toBeDisabled();
    await page.getByTestId("tasks-comment-input").fill("   ");
    await expect(page.getByTestId("tasks-comment-post")).toBeDisabled();

    await postComment(page, "a fresh task comment");

    expect(rec.posts).toHaveLength(1);
    expect(rec.posts[0].text).toBe("a fresh task comment");
    // No reply was armed, so nothing may be smuggled into parentUuid.
    expect(rec.posts[0].parentUuid).toBeUndefined();

    // The composer empties, and the comment lands as a root rather than an indented reply.
    await expect(page.getByTestId("tasks-comment-input")).toHaveValue("");
    await expect(page.getByTestId("comment-item")).toHaveCount(1);
    await expect(page.getByTestId("comment-reply-item")).toHaveCount(0);
  });

  test("replying names the comment being answered and threads the post under it", async ({ page }) => {
    const rec = await loginAndOpenTaskDrawer(page, [ownComment()]);

    await expect(page.getByText("task-comment-by-me", { exact: true })).toBeVisible({ timeout: 10_000 });
    // The banner is a reply-only affordance — it must not be sitting there by default.
    await expect(page.getByTestId("tasks-comment-reply-banner")).toHaveCount(0);

    await startReply(page);
    // It names the comment being answered, so the author can see which thread they are in.
    await expect(page.getByTestId("tasks-comment-reply-banner")).toContainText("task-comment-by-me");

    await postComment(page, "an answer to that");

    expect(rec.posts).toHaveLength(1);
    expect(rec.posts[0].parentUuid).toBe("own-comment");
    // One root, one reply indented under it — and the banner is spent.
    await expect(page.getByTestId("comment-item")).toHaveCount(1);
    await expect(page.getByTestId("comment-reply-item")).toHaveCount(1);
    await expect(page.getByTestId("tasks-comment-reply-banner")).toHaveCount(0);
  });

  /**
   * The assertion that makes the banner worth having. A cancel that only hides the banner while
   * leaving `replyTo` set would send the next comment — a new, unrelated one — into someone else's
   * thread, and nothing on screen would say so.
   */
  test("cancelling a reply clears the banner and the next post carries no parent", async ({ page }) => {
    const rec = await loginAndOpenTaskDrawer(page, [ownComment()]);

    await expect(page.getByText("task-comment-by-me", { exact: true })).toBeVisible({ timeout: 10_000 });

    await startReply(page);
    // Text typed before the cancel must not go out as a reply either.
    await page.getByTestId("tasks-comment-input").fill("changed my mind");
    await page.getByTestId("tasks-comment-reply-cancel").click();
    await expect(page.getByTestId("tasks-comment-reply-banner")).toHaveCount(0);

    await postComment(page, "changed my mind");

    expect(rec.posts).toHaveLength(1);
    expect(rec.posts[0].text).toBe("changed my mind");
    expect(rec.posts[0].parentUuid).toBeUndefined();

    // Two roots, no reply — the abandoned parent did not survive the cancel.
    await expect(page.getByTestId("comment-item")).toHaveCount(2);
    await expect(page.getByTestId("comment-reply-item")).toHaveCount(0);
  });
});
