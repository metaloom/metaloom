import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for skill versioning — the version-history section inside the skill
 * editor dialog: listing versions, the "current" marker, and reverting (which
 * deletes newer versions). All `/api/v1/**` calls are intercepted; skills and
 * their versions live in a small in-memory store.
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";

interface StoredVersion {
  versionNumber: number;
  versionUuid: string;
  description: string;
  content: string;
}

interface StoredSkill {
  uuid: string;
  name: string;
  description: string;
  content: string;
  enabled: boolean;
  published: boolean;
  versionNumber: number;
  versions: StoredVersion[];
  status?: { creator?: { uuid: string; name?: string }; created?: string };
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

function versionEntry(skill: StoredSkill, v: StoredVersion) {
  return {
    uuid: skill.uuid,
    name: skill.name,
    description: v.description,
    content: v.content,
    enabled: skill.enabled,
    published: skill.published,
    versionNumber: v.versionNumber,
    versionUuid: v.versionUuid,
    status: { creator: { uuid: ME_UUID, name: "admin" }, created: "2026-07-22T10:00:00Z" },
  };
}

function skillResponse(skill: StoredSkill) {
  return {
    uuid: skill.uuid,
    name: skill.name,
    description: skill.description,
    content: skill.content,
    enabled: skill.enabled,
    published: skill.published,
    versionNumber: skill.versionNumber,
    versionUuid: skill.versions[skill.versions.length - 1]?.versionUuid,
    status: { creator: { uuid: ME_UUID, name: "admin" }, created: "2026-07-22T10:00:00Z" },
  };
}

async function installMocks(page: Page) {
  const mySkills: StoredSkill[] = [];
  let seq = 0;
  let vseq = 0;

  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route => json(route, { uuid: ME_UUID, username: "admin", enabled: true }));

  await page.route(/\/api\/v1\/skills$/, route => {
    if (route.request().method() === "POST") {
      const body = JSON.parse(route.request().postData() || "{}");
      const skill: StoredSkill = {
        uuid: `skill-${++seq}`,
        name: body.name,
        description: body.description,
        content: body.content,
        enabled: body.enabled ?? true,
        published: body.published ?? false,
        versionNumber: 1,
        versions: [{ versionNumber: 1, versionUuid: `v-${++vseq}`, description: body.description, content: body.content }],
        status: { creator: { uuid: ME_UUID, name: "admin" }, created: "2026-07-22T10:00:00Z" },
      };
      mySkills.push(skill);
      return json(route, skillResponse(skill), 201);
    }
    return json(route, { data: mySkills.map(skillResponse) });
  });

  await page.route(/\/api\/v1\/skills\/[^/]+$/, route => {
    const uuid = route.request().url().split("/skills/")[1].split("?")[0];
    const skill = mySkills.find(s => s.uuid === uuid);
    if (route.request().method() === "POST") {
      const body = JSON.parse(route.request().postData() || "{}");
      if (skill) {
        const bodyChanged = (body.content != null && body.content !== skill.content)
          || (body.description != null && body.description !== skill.description);
        if (body.name != null) skill.name = body.name;
        if (body.enabled != null) skill.enabled = body.enabled;
        if (body.published != null) skill.published = body.published;
        if (bodyChanged) {
          skill.description = body.description ?? skill.description;
          skill.content = body.content ?? skill.content;
          skill.versionNumber = skill.versions[skill.versions.length - 1].versionNumber + 1;
          skill.versions.push({ versionNumber: skill.versionNumber, versionUuid: `v-${++vseq}`, description: skill.description, content: skill.content });
        }
      }
      return json(route, skill ? skillResponse(skill) : {}, skill ? 200 : 404);
    }
    return json(route, skill ? skillResponse(skill) : {}, skill ? 200 : 404);
  });

  // Registered AFTER the generic /skills matchers — Playwright routes are LIFO.
  await page.route(/\/api\/v1\/skills\/[^/]+\/versions$/, route => {
    const uuid = route.request().url().split("/skills/")[1].split("/")[0];
    const skill = mySkills.find(s => s.uuid === uuid);
    if (!skill) return json(route, {}, 404);
    return json(route, { data: skill.versions.map(v => versionEntry(skill, v)) });
  });

  await page.route(/\/api\/v1\/skills\/[^/]+\/versions\/\d+\/restore$/, route => {
    const parts = route.request().url().split("/skills/")[1].split("/");
    const uuid = parts[0];
    const versionNumber = parseInt(parts[2], 10);
    const skill = mySkills.find(s => s.uuid === uuid);
    if (!skill) return json(route, {}, 404);
    const target = skill.versions.find(v => v.versionNumber === versionNumber);
    if (!target) return json(route, {}, 404);
    // Destructive revert: drop every newer version and re-point the active version.
    skill.versions = skill.versions.filter(v => v.versionNumber <= versionNumber);
    skill.description = target.description;
    skill.content = target.content;
    skill.versionNumber = versionNumber;
    return json(route, skillResponse(skill), 200);
  });

  return { mySkills };
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

async function seedVersionedSkill(page: Page) {
  // Create v1
  await page.getByTestId("skill-create-button").click();
  await page.getByTestId("skill-editor-name").fill("versioned");
  await page.getByTestId("skill-editor-description").fill("A versioned skill");
  await page.getByTestId("skill-editor-content").fill("content v1");
  await page.getByTestId("skill-editor-save").click();
  await expect(page.getByTestId("skill-row-versioned")).toBeVisible({ timeout: 5_000 });

  // Edit content → v2
  await page.getByTestId("skill-edit-versioned").click();
  await page.getByTestId("skill-editor-content").fill("content v2");
  await page.getByTestId("skill-editor-save").click();
  await expect(page.getByTestId("skill-editor-dialog")).toBeHidden({ timeout: 5_000 });

  // Edit content → v3
  await page.getByTestId("skill-edit-versioned").click();
  await page.getByTestId("skill-editor-content").fill("content v3");
  await page.getByTestId("skill-editor-save").click();
  await expect(page.getByTestId("skill-editor-dialog")).toBeHidden({ timeout: 5_000 });
}

test.describe("Skill versioning – mocked e2e", () => {
  test("version history lists all versions and marks the current one", async ({ page }) => {
    await installMocks(page);
    await login(page);
    await openSkillsView(page);
    await seedVersionedSkill(page);

    await page.getByTestId("skill-edit-versioned").click();
    await expect(page.getByTestId("skill-version-history")).toBeVisible({ timeout: 5_000 });

    // All three versions are listed
    await expect(page.getByTestId("skill-version-item-1")).toBeVisible();
    await expect(page.getByTestId("skill-version-item-2")).toBeVisible();
    await expect(page.getByTestId("skill-version-item-3")).toBeVisible();

    // The active (newest) version is flagged and has no revert action
    await expect(page.getByTestId("skill-version-item-3").getByTestId("skill-version-current")).toBeVisible();
    await expect(page.getByTestId("skill-version-revert-3")).toHaveCount(0);

    // Older versions offer a revert action
    await expect(page.getByTestId("skill-version-revert-1")).toBeVisible();
    await expect(page.getByTestId("skill-version-revert-2")).toBeVisible();
  });

  test("reverting to an older version deletes the newer versions", async ({ page }) => {
    await installMocks(page);
    await login(page);
    await openSkillsView(page);
    await seedVersionedSkill(page);

    await page.getByTestId("skill-edit-versioned").click();
    await expect(page.getByTestId("skill-version-item-3")).toBeVisible({ timeout: 5_000 });

    // Revert to v1 → confirm
    await page.getByTestId("skill-version-revert-1").click();
    await expect(page.getByTestId("skill-version-revert-dialog")).toBeVisible();
    await page.getByTestId("skill-version-revert-confirm").click();

    // v2 and v3 are gone; only v1 remains and it is now current
    await expect(page.getByTestId("skill-version-item-2")).toHaveCount(0, { timeout: 5_000 });
    await expect(page.getByTestId("skill-version-item-3")).toHaveCount(0);
    await expect(page.getByTestId("skill-version-item-1")).toBeVisible();
    await expect(page.getByTestId("skill-version-item-1").getByTestId("skill-version-current")).toBeVisible();

    // The editor now shows v1's content
    await expect(page.getByTestId("skill-editor-content")).toHaveValue("content v1");
  });
});
