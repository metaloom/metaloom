import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for the skills feature — management view (CRUD, publish, library,
 * install) and the per-chat activation panel. All `/api/v1/**` calls are
 * intercepted; skills live in a small in-memory store.
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const FOREIGN_SKILL_UUID = "44444444-4444-4444-4444-444444444444";

interface StoredSkill {
  uuid: string;
  name: string;
  description: string;
  content: string;
  enabled: boolean;
  published: boolean;
  originSkillUuid?: string;
  status?: { creator?: { uuid: string; name?: string } };
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

function foreignSkill(): StoredSkill {
  return {
    uuid: FOREIGN_SKILL_UUID,
    name: "transcript-summarizer",
    description: "Summarize transcripts into bullet lists",
    content: "# Summarizer\nDo the thing.",
    enabled: true,
    published: true,
    status: { creator: { uuid: "other-user", name: "joedoe" } },
  };
}

async function installMocks(page: Page) {
  const mySkills: StoredSkill[] = [];
  let seq = 0;
  const streamBodies: string[] = [];

  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route => json(route, { uuid: ME_UUID, username: "admin", enabled: true }));

  await page.route(/\/api\/v1\/skills(\?|$)/, route => {
    if (route.request().method() === "POST") {
      const body = JSON.parse(route.request().postData() || "{}");
      const created: StoredSkill = {
        uuid: `skill-${++seq}`,
        name: body.name,
        description: body.description,
        content: body.content,
        enabled: body.enabled ?? true,
        published: body.published ?? false,
        status: { creator: { uuid: ME_UUID } },
      };
      mySkills.push(created);
      return json(route, created, 201);
    }
    return json(route, { data: mySkills });
  });

  await page.route(/\/api\/v1\/skills\/[^/]+$/, route => {
    const uuid = route.request().url().split("/skills/")[1].split("?")[0];
    const found = mySkills.find(s => s.uuid === uuid);
    if (route.request().method() === "DELETE") {
      const idx = mySkills.findIndex(s => s.uuid === uuid);
      if (idx >= 0) mySkills.splice(idx, 1);
      return route.fulfill({ status: 204, body: "" });
    }
    if (route.request().method() === "POST") {
      const body = JSON.parse(route.request().postData() || "{}");
      if (found) Object.assign(found, body);
      return json(route, found ?? {}, found ? 200 : 404);
    }
    return json(route, found ?? {}, found ? 200 : 404);
  });

  // Registered AFTER the generic /skills matchers — Playwright routes are LIFO,
  // so these more specific paths must come later to take precedence.
  await page.route(/\/api\/v1\/skills\/library(\?|$)/, route =>
    json(route, { data: [foreignSkill(), ...mySkills.filter(s => s.published)] }));

  await page.route(/\/api\/v1\/skills\/[^/]+\/install$/, route => {
    const uuid = route.request().url().split("/skills/")[1].split("/")[0];
    const source = uuid === FOREIGN_SKILL_UUID ? foreignSkill() : mySkills.find(s => s.uuid === uuid);
    if (!source) return json(route, {}, 404);
    const nameTaken = mySkills.some(s => s.name === source.name);
    const copy: StoredSkill = {
      ...source,
      uuid: `skill-${++seq}`,
      name: nameTaken ? `${source.name}-2` : source.name,
      published: false,
      originSkillUuid: source.uuid,
      status: { creator: { uuid: ME_UUID } },
    };
    mySkills.push(copy);
    return json(route, copy, 201);
  });

  // Chat plumbing for the SkillsPanel test
  await page.route(/\/api\/v1\/chats$/, route => {
    if (route.request().method() === "POST") {
      return json(route, { uuid: "chat-1", title: "chat", messages: [] }, 201);
    }
    return json(route, { data: [] });
  });
  await page.route(/\/api\/v1\/chats\/[^/]+\/stream$/, route => {
    streamBodies.push(route.request().postData() || "{}");
    const body = "event: agent_start\ndata: {\"chatUuid\":\"chat-1\"}\n\n"
      + "event: text_delta\ndata: {\"turn\":1,\"text\":\"ok\"}\n\n"
      + "event: message_end\ndata: {\"message\":{\"id\":\"m1\",\"role\":\"assistant\",\"content\":\"ok\",\"createdAt\":\"2026-07-22T10:00:00Z\"}}\n\n"
      + "event: agent_end\ndata: {\"chatUuid\":\"chat-1\",\"status\":\"completed\"}\n\n";
    return route.fulfill({ status: 200, contentType: "text/event-stream", body });
  });
  await page.route(/\/api\/v1\/chats\/[^/]+$/, route => json(route, { uuid: "chat-1", title: "chat", messages: [] }));

  return { streamBodies };
}

async function login(page: Page) {
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
}

async function openSkillsView(page: Page) {
  await page.getByRole("button", { name: "Skills" }).first().click();
  await expect(page.getByTestId("skills-view")).toBeVisible({ timeout: 10_000 });
}

test.describe("Skills – mocked e2e", () => {
  test("create, edit, publish and delete a skill", async ({ page }) => {
    await installMocks(page);
    await login(page);
    await openSkillsView(page);

    // Create
    await page.getByTestId("skill-create-button").click();
    await page.getByTestId("skill-editor-name").fill("review-helper");
    await page.getByTestId("skill-editor-description").fill("Open review tasks for flagged assets");
    await page.getByTestId("skill-editor-content").fill("# Review helper\nInstructions.");
    await page.getByTestId("skill-editor-save").click();
    await expect(page.getByTestId("skill-row-review-helper")).toBeVisible({ timeout: 5_000 });

    // Edit
    await page.getByTestId("skill-edit-review-helper").click();
    await page.getByTestId("skill-editor-description").fill("Updated description");
    await page.getByTestId("skill-editor-save").click();
    await expect(page.getByText("Updated description")).toBeVisible({ timeout: 5_000 });

    // Toggle publish
    await page.getByTestId("skill-published-review-helper").click();
    await expect(page.getByTestId("skill-published-review-helper").locator("input")).toBeChecked({ timeout: 5_000 });

    // Delete with confirmation
    await page.getByTestId("skill-delete-review-helper").click();
    await page.getByTestId("skill-delete-confirm").click();
    await expect(page.getByTestId("skill-row-review-helper")).toBeHidden({ timeout: 5_000 });
  });

  test("library lists published skills and install creates an own copy", async ({ page }) => {
    await installMocks(page);
    await login(page);
    await openSkillsView(page);

    await page.getByTestId("skills-library-tab").click();
    await expect(page.getByTestId("skills-library-table")).toBeVisible();
    await expect(page.getByText("transcript-summarizer")).toBeVisible();
    await expect(page.getByText("joedoe")).toBeVisible();

    await page.getByTestId("skill-install-transcript-summarizer").click();

    // The copy appears in the own list, flagged with its provenance badge
    await page.getByTestId("skills-mine-tab").click();
    await expect(page.getByTestId("skill-row-transcript-summarizer")).toBeVisible({ timeout: 5_000 });
    await expect(page.getByTestId("skill-origin-badge")).toBeVisible();
  });

  test("skill toggles in the chat are sent with the stream request and persisted", async ({ page }) => {
    const { streamBodies } = await installMocks(page);
    await login(page);

    // Seed one skill via the management view
    await openSkillsView(page);
    await page.getByTestId("skill-create-button").click();
    await page.getByTestId("skill-editor-name").fill("chat-skill");
    await page.getByTestId("skill-editor-description").fill("Skill used from the chat");
    await page.getByTestId("skill-editor-content").fill("# Chat skill");
    await page.getByTestId("skill-editor-save").click();
    await expect(page.getByTestId("skill-row-chat-skill")).toBeVisible();

    // Activate it for the conversation
    await page.getByRole("button", { name: "Chat" }).first().click();
    await page.getByTestId("chat-skills-button").click();
    await expect(page.getByTestId("chat-skills-panel")).toBeVisible();
    await page.getByTestId("skill-toggle-chat-skill").click();
    await page.keyboard.press("Escape");

    // The active set is sent with the next stream request
    const input = page.getByPlaceholder(/Ask about assets/i);
    await input.fill("use my skill");
    await input.press("Enter");
    await expect(page.getByTestId("markdown-content").filter({ hasText: "ok" }).last()).toBeVisible({ timeout: 10_000 });

    expect(streamBodies.length).toBe(1);
    const sent = JSON.parse(streamBodies[0]);
    expect(sent.skillUuids).toEqual(["skill-1"]);
  });
});
