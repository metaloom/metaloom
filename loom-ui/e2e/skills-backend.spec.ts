import { test, expect, Page } from "@playwright/test";

/**
 * End-to-end tests for skill management (real backend).
 *
 * Prerequisites — set env vars before running:
 *   VITE_API_BASE_URL  – e.g. /api/v1
 *   VITE_PROXY_TARGET  – e.g. http://localhost:8092
 *
 * Assumes:
 *  1. A Loom server is running (skills need no demo data)
 *  2. Default admin credentials: admin / finger
 */

const RUN_ID = Date.now().toString(36);
const SKILL_NAME = `e2e-skill-${RUN_ID}`;

async function loginAndOpenSkills(page: Page) {
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
  await page.getByRole("button", { name: "Skills" }).first().click();
  await expect(page.getByTestId("skills-table")).toBeVisible({ timeout: 10_000 });
}

test.describe("Skills – backend e2e", () => {
  test("full skill round-trip: create, edit, publish, library, delete", async ({ page }) => {
    await loginAndOpenSkills(page);

    // Create
    await page.getByTestId("skill-create-button").click();
    await page.getByTestId("skill-editor-name").fill(SKILL_NAME);
    await page.getByTestId("skill-editor-description").fill("Created by the e2e suite");
    await page.getByTestId("skill-editor-content").fill("# E2E skill\nInstructions.");
    await page.getByTestId("skill-editor-save").click();
    await expect(page.getByTestId(`skill-row-${SKILL_NAME}`)).toBeVisible({ timeout: 10_000 });

    // Edit
    await page.getByTestId(`skill-edit-${SKILL_NAME}`).click();
    await page.getByTestId("skill-editor-description").fill("Updated by the e2e suite");
    await page.getByTestId("skill-editor-save").click();
    await expect(page.getByText("Updated by the e2e suite")).toBeVisible({ timeout: 10_000 });

    // Publish — the own published skill is NOT expected in the library
    // (the library lists published skills of all users; own ones are marked)
    await page.getByTestId(`skill-published-${SKILL_NAME}`).click();
    await expect(page.getByTestId(`skill-published-${SKILL_NAME}`).locator("input")).toBeChecked({ timeout: 10_000 });

    await page.getByTestId("skills-library-tab").click();
    await expect(page.getByTestId("skills-library-table")).toBeVisible({ timeout: 10_000 });
    // The published skill appears in the library, flagged as the callers own
    await expect(page.getByText(SKILL_NAME)).toBeVisible({ timeout: 10_000 });

    // Delete (cleanup)
    await page.getByTestId("skills-mine-tab").click();
    await page.getByTestId(`skill-delete-${SKILL_NAME}`).click();
    await page.getByTestId("skill-delete-confirm").click();
    await expect(page.getByTestId(`skill-row-${SKILL_NAME}`)).toBeHidden({ timeout: 10_000 });
  });

  test("skill toggles from the chat panel persist onto the chat meta", async ({ page }) => {
    await loginAndOpenSkills(page);

    // Seed a skill
    await page.getByTestId("skill-create-button").click();
    await page.getByTestId("skill-editor-name").fill(`${SKILL_NAME}-chat`);
    await page.getByTestId("skill-editor-description").fill("Chat toggle skill");
    await page.getByTestId("skill-editor-content").fill("# Chat toggle skill");
    await page.getByTestId("skill-editor-save").click();
    await expect(page.getByTestId(`skill-row-${SKILL_NAME}-chat`)).toBeVisible({ timeout: 10_000 });

    // It is offered in the chat skills panel
    await page.getByRole("button", { name: "Chat" }).first().click();
    await page.getByTestId("chat-skills-button").click();
    await expect(page.getByTestId(`skill-toggle-${SKILL_NAME}-chat`)).toBeVisible({ timeout: 10_000 });
    await page.getByTestId(`skill-toggle-${SKILL_NAME}-chat`).click();
    await page.keyboard.press("Escape");

    // Cleanup
    await page.getByRole("button", { name: "Skills" }).first().click();
    await expect(page.getByTestId("skills-table")).toBeVisible({ timeout: 10_000 });
    await page.getByTestId(`skill-delete-${SKILL_NAME}-chat`).click();
    await page.getByTestId("skill-delete-confirm").click();
    await expect(page.getByTestId(`skill-row-${SKILL_NAME}-chat`)).toBeHidden({ timeout: 10_000 });
  });
});
