import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for chat file attachments — no running Loom backend.
 *
 * The composer is only reachable by rendering it, and `loom-ui` has no component-render tests
 * (`vitest.config.ts` is `environment: "node"`, no jsdom), so drag-and-drop and the chip strip are
 * Playwright's job. The pure rules behind them — which files are accepted, what a chip claims a file
 * can be used for — are unit-tested in `src/features/chat/attachmentState.test.ts`.
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";

interface StoredChat {
  uuid: string;
  title: string;
  messages: Record<string, unknown>[];
  meta?: Record<string, unknown>;
}

interface StoredAttachment {
  uuid: string;
  filename: string;
  mimeType: string;
  size: number;
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

/**
 * Build a `DataTransfer` inside the page.
 *
 * Playwright has no file-drag API, and `setInputFiles` drives the hidden `<input type=file>` — a
 * different code path from the column's `onDrop`, which reads `e.dataTransfer.files`. A synthetic
 * `DataTransfer` handed to `dispatchEvent` is the only way to reach it.
 *
 * `uris` become `text/uri-list` items, which is how a drag can carry something that is not a file —
 * the case `dragCarriesFiles` exists to ignore.
 */
async function buildDataTransfer(page: Page, spec: { files?: { name: string; type: string }[]; uris?: string[] }) {
  return page.evaluateHandle(({ files, uris }) => {
    const dt = new DataTransfer();
    for (const f of files ?? []) {
      dt.items.add(new File(["hello-bytes"], f.name, { type: f.type }));
    }
    for (const uri of uris ?? []) {
      dt.items.add(uri, "text/uri-list");
    }
    return dt;
  }, spec);
}

interface Captured {
  uploads: { chatUuid: string; url: string }[];
  deletes: string[];
  promotes: string[];
  chatsCreated: number;
}

async function installMocks(page: Page, opts: { uploadStatus?: number; uploadBody?: string } = {}) {
  const chats: StoredChat[] = [];
  const attachments = new Map<string, StoredAttachment[]>();
  const captured: Captured = { uploads: [], deletes: [], promotes: [], chatsCreated: 0 };
  let seq = 0;

  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route => json(route, { uuid: ME_UUID, username: "admin", enabled: true }));

  await page.route(/\/api\/v1\/chats$/, route => {
    if (route.request().method() === "POST") {
      const body = JSON.parse(route.request().postData() || "{}");
      const created: StoredChat = { uuid: `chat-${++seq}`, title: body.title ?? "chat", messages: [] };
      chats.unshift(created);
      attachments.set(created.uuid, []);
      captured.chatsCreated += 1;
      return json(route, created, 201);
    }
    return json(route, { data: chats });
  });

  // Attachment routes MUST be registered before the generic /chats/:uuid matcher, or that one
  // swallows them — the same ordering the backend endpoint has to observe.
  await page.route(/\/api\/v1\/chats\/[^/]+\/attachments\/[^/]+\/asset/, route => {
    captured.promotes.push(route.request().url());
    return json(route, { uuid: `asset-${++seq}` }, 201);
  });

  await page.route(/\/api\/v1\/chats\/[^/]+\/attachments\/[^/]+$/, route => {
    const [chatUuid, attachmentUuid] = route.request().url().split("/chats/")[1].split("/attachments/");
    if (route.request().method() === "DELETE") {
      captured.deletes.push(attachmentUuid);
      const list = attachments.get(chatUuid) ?? [];
      attachments.set(chatUuid, list.filter(a => a.uuid !== attachmentUuid));
      return route.fulfill({ status: 204, body: "" });
    }
    return json(route, {});
  });

  await page.route(/\/api\/v1\/chats\/[^/]+\/attachments$/, route => {
    const chatUuid = route.request().url().split("/chats/")[1].split("/")[0];
    if (route.request().method() === "POST") {
      if (opts.uploadStatus && opts.uploadStatus !== 201) {
        return route.fulfill({ status: opts.uploadStatus, contentType: "text/plain", body: opts.uploadBody ?? "nope" });
      }
      captured.uploads.push({ chatUuid, url: route.request().url() });
      // The multipart body carries the filename; reading it back keeps the chip honest.
      const raw = route.request().postData() ?? "";
      const match = raw.match(/filename="([^"]+)"/);
      const filename = match ? match[1] : `file-${seq}`;
      const stored: StoredAttachment = {
        uuid: `att-${++seq}`,
        filename,
        mimeType: filename.endsWith(".jpg") ? "image/jpeg" : "text/markdown",
        size: 11,
      };
      attachments.set(chatUuid, [...(attachments.get(chatUuid) ?? []), stored]);
      return json(route, stored, 201);
    }
    return json(route, { data: attachments.get(chatUuid) ?? [] });
  });

  await page.route(/\/api\/v1\/chats\/[^/]+$/, route => {
    const uuid = route.request().url().split("/chats/")[1].split("?")[0];
    const found = chats.find(c => c.uuid === uuid);
    return json(route, found ?? {}, found ? 200 : 404);
  });

  return captured;
}

async function login(page: Page) {
  await page.goto("/");
  if (await page.getByPlaceholder("Username").count() === 0) return;
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
}

function overlayVisibility(page: Page) {
  return page.getByTestId("chat-drop-overlay").evaluate(el => getComputedStyle(el).visibility);
}

test.describe("Chat attachments – mocked", () => {
  test("drops a file onto the chat column and shows it as a chip", async ({ page }) => {
    const captured = await installMocks(page);
    await login(page);

    const column = page.getByTestId("chat-column");
    const dt = await buildDataTransfer(page, { files: [{ name: "brief.md", type: "text/markdown" }] });

    await column.dispatchEvent("dragenter", { dataTransfer: dt });
    await expect.poll(() => overlayVisibility(page)).toBe("visible");

    await column.dispatchEvent("drop", { dataTransfer: dt });

    await expect(page.getByTestId("chat-attachment-brief.md")).toHaveAttribute("data-status", "ready", { timeout: 10_000 });
    // The overlay has to clear on drop, or it covers the conversation it was invited over.
    await expect.poll(() => overlayVisibility(page)).toBe("hidden");
    expect(captured.uploads).toHaveLength(1);
  });

  test("creates the conversation on the first drop, before any message is sent", async ({ page }) => {
    // A chat is created lazily so an abandoned one never reaches the database. A drop has to
    // trigger that too, or there is nothing to attach the file to.
    const captured = await installMocks(page);
    await login(page);

    const column = page.getByTestId("chat-column");
    const dt = await buildDataTransfer(page, { files: [{ name: "brief.md", type: "text/markdown" }] });
    await column.dispatchEvent("drop", { dataTransfer: dt });

    await expect(page.getByTestId("chat-attachment-brief.md")).toBeVisible({ timeout: 10_000 });
    expect(captured.chatsCreated).toBe(1);
    expect(captured.uploads[0].chatUuid).toBe("chat-1");
  });

  test("ignores a drag that carries no files", async ({ page }) => {
    await installMocks(page);
    await login(page);

    const column = page.getByTestId("chat-column");
    const dt = await buildDataTransfer(page, { uris: ["https://example.org/thing"] });

    await column.dispatchEvent("dragenter", { dataTransfer: dt });

    // Selecting a word and dragging it across the conversation must not light the whole column up.
    await expect.poll(() => overlayVisibility(page)).toBe("hidden");
  });

  test("keeps the overlay up while the drag crosses child elements", async ({ page }) => {
    await installMocks(page);
    await login(page);

    const column = page.getByTestId("chat-column");
    const dt = await buildDataTransfer(page, { files: [{ name: "brief.md", type: "text/markdown" }] });

    await column.dispatchEvent("dragenter", { dataTransfer: dt });
    // Entering a child fires dragleave on the parent. A plain boolean flickers here; the depth
    // counter is what keeps it steady.
    await column.dispatchEvent("dragenter", { dataTransfer: dt });
    await column.dispatchEvent("dragleave", { dataTransfer: dt });

    await expect.poll(() => overlayVisibility(page)).toBe("visible");

    await column.dispatchEvent("dragleave", { dataTransfer: dt });
    await expect.poll(() => overlayVisibility(page)).toBe("hidden");
  });

  test("attaches through the paperclip as well", async ({ page }) => {
    const captured = await installMocks(page);
    await login(page);

    await page.getByTestId("chat-attachment-input").setInputFiles([
      { name: "notes.md", mimeType: "text/markdown", buffer: Buffer.from("hello") },
    ]);

    await expect(page.getByTestId("chat-attachment-notes.md")).toHaveAttribute("data-status", "ready", { timeout: 10_000 });
    expect(captured.uploads).toHaveLength(1);
  });

  test("removes a chip and tells the server", async ({ page }) => {
    const captured = await installMocks(page);
    await login(page);

    await page.getByTestId("chat-attachment-input").setInputFiles([
      { name: "notes.md", mimeType: "text/markdown", buffer: Buffer.from("hello") },
    ]);
    await expect(page.getByTestId("chat-attachment-notes.md")).toHaveAttribute("data-status", "ready", { timeout: 10_000 });

    await page.getByTestId("chat-attachment-remove-notes.md").click();

    await expect(page.getByTestId("chat-attachment-notes.md")).toHaveCount(0);
    expect(captured.deletes).toEqual(["att-2"]);
  });

  test("marks a failed upload on the chip rather than dropping it silently", async ({ page }) => {
    await installMocks(page, { uploadStatus: 409, uploadBody: "This conversation already has 10 attachments" });
    await login(page);

    await page.getByTestId("chat-attachment-input").setInputFiles([
      { name: "notes.md", mimeType: "text/markdown", buffer: Buffer.from("hello") },
    ]);

    // A chip that vanishes reads as "nothing happened"; the user has to see that it did not take.
    await expect(page.getByTestId("chat-attachment-notes.md")).toHaveAttribute("data-status", "failed", { timeout: 10_000 });
  });

  test("saves an attachment to the library", async ({ page }) => {
    const captured = await installMocks(page);
    await login(page);

    await page.getByTestId("chat-attachment-input").setInputFiles([
      { name: "keeper.jpg", mimeType: "image/jpeg", buffer: Buffer.from("hello") },
    ]);
    await expect(page.getByTestId("chat-attachment-keeper.jpg")).toHaveAttribute("data-status", "ready", { timeout: 10_000 });

    await page.getByTestId("chat-attachment-keeper.jpg").getByRole("button", { name: /save to library/i }).click();

    await expect.poll(() => captured.promotes.length).toBe(1);
    // The file stays in the conversation: it is in both places, not moved.
    await expect(page.getByTestId("chat-attachment-keeper.jpg")).toBeVisible();
  });

  test("shows several files as several chips", async ({ page }) => {
    const captured = await installMocks(page);
    await login(page);

    const column = page.getByTestId("chat-column");
    const dt = await buildDataTransfer(page, {
      files: [
        { name: "one.jpg", type: "image/jpeg" },
        { name: "two.jpg", type: "image/jpeg" },
        { name: "three.jpg", type: "image/jpeg" },
      ],
    });
    await column.dispatchEvent("drop", { dataTransfer: dt });

    await expect(page.getByTestId("chat-attachment-three.jpg")).toHaveAttribute("data-status", "ready", { timeout: 10_000 });
    expect(captured.uploads).toHaveLength(3);
    // One conversation, not one per file.
    expect(captured.chatsCreated).toBe(1);
  });
});
