import { test, expect, Page } from "@playwright/test";

/**
 * Backend e2e for the cluster panels.
 *
 * Assumes a Loom server with demo data and the default admin credentials (admin / finger).
 *
 * `ClustersPanel` and `PersonsPanel` have no route of their own — both are mounted by
 * `FaceDetectionManagement`, which is the Faces tab of `/detection` (LOOM_UI.md §4.2). Everything
 * below therefore goes through the panel switcher rather than addressing a URL.
 */

async function login(page: Page) {
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
}

async function openFaces(page: Page) {
  await page.getByRole("button", { name: "Detection", exact: true }).first().click();
  await expect(page.getByTestId("facedetection-switcher")).toBeVisible({ timeout: 10_000 });
}

async function loginAndGoToClusters(page: Page) {
  await login(page);
  await openFaces(page);
  // Clusters is the landing panel; click it anyway so the test does not depend on that default.
  await page.getByTestId("facedetection-section-clusters").click();
}

test.describe("Clusters - backend e2e", () => {
  test("create, update and delete a cluster", async ({ page }) => {
    await loginAndGoToClusters(page);

    const name = `pw-cluster-${Date.now()}`;
    const updatedName = `${name}-updated`;

    // Create
    await page.getByTestId("facedetection-add-cluster").click();
    await page.getByTestId("facedetection-cluster-name").locator("input").fill(name);
    await page.getByTestId("facedetection-cluster-create").click();

    const clusterCard = page.getByTestId("cluster-card").filter({ hasText: name });
    await expect(clusterCard).toHaveCount(1, { timeout: 10_000 });

    // Update
    await clusterCard.getByTestId("cluster-edit").click();
    await page.getByTestId("cluster-edit-name").locator("input").fill(updatedName);
    await page.getByTestId("cluster-edit-save").click();

    const updatedCard = page.getByTestId("cluster-card").filter({ hasText: updatedName });
    await expect(updatedCard).toHaveCount(1, { timeout: 10_000 });

    // Delete
    await updatedCard.getByTestId("cluster-delete").click();
    await expect(page.getByTestId("cluster-card").filter({ hasText: updatedName })).toHaveCount(0, { timeout: 10_000 });
  });

  /**
   * The review step the panels exist for: put a group onto a person.
   *
   * Driven through both panels rather than through `/clusters/:uuid/confirm` directly, because the
   * defect this guards against was never in the endpoint — the assignment used to mutate React state
   * and nothing else, so it looked right until the page was reloaded. The reload below is the test.
   */
  test("assign a cluster to a person through the panels and the link survives a reload", async ({ page }) => {
    const stamp = Date.now();
    const alias = `pw-person-${stamp}`;
    const firstname = `Pw${stamp}`;
    const personName = `${firstname} User`;
    const clusterName = `pw-cluster-${stamp}`;

    await login(page);
    await openFaces(page);

    // A person to assign to.
    await page.getByTestId("facedetection-section-persons").click();
    await page.getByTestId("facedetection-add-person").click();
    await page.getByTestId("facedetection-person-alias").locator("input").fill(alias);
    await page.getByTestId("facedetection-person-firstname").locator("input").fill(firstname);
    await page.getByTestId("facedetection-person-lastname").locator("input").fill("User");
    await page.getByTestId("facedetection-person-create").click();

    const personCard = page.getByTestId("person-card").filter({ hasText: personName });
    await expect(personCard).toHaveCount(1, { timeout: 10_000 });

    // A cluster to assign.
    await page.getByTestId("facedetection-section-clusters").click();
    await page.getByTestId("facedetection-add-cluster").click();
    await page.getByTestId("facedetection-cluster-name").locator("input").fill(clusterName);
    await page.getByTestId("facedetection-cluster-create").click();

    const clusterCard = page.getByTestId("cluster-card").filter({ hasText: clusterName });
    await expect(clusterCard).toHaveCount(1, { timeout: 10_000 });
    await expect(clusterCard).toHaveAttribute("data-assigned", "false");

    // Assign.
    await clusterCard.getByTestId("cluster-assign").click();
    await expect(page.getByTestId("facedetection-assign-dialog")).toBeVisible({ timeout: 5_000 });
    await page.getByTestId("facedetection-assign-select").click();
    await page.getByRole("option", { name: personName }).click();
    await page.getByTestId("facedetection-assign-save").click();

    await expect(clusterCard).toHaveAttribute("data-assigned", "true", { timeout: 10_000 });
    await expect(clusterCard.getByTestId("cluster-person-chip")).toHaveText(personName);

    // Auth is in-memory, so a reload lands on the login form. Signing back in and still finding the
    // person on the card proves the confirmation reached the server.
    await page.reload();
    await login(page);
    await openFaces(page);

    const reloadedCluster = page.getByTestId("cluster-card").filter({ hasText: clusterName });
    await expect(reloadedCluster.getByTestId("cluster-person-chip")).toHaveText(personName, { timeout: 10_000 });

    // ...and the person carries the group, which is the same fact read from the other side.
    await page.getByTestId("facedetection-section-persons").click();
    const reloadedPerson = page.getByTestId("person-card").filter({ hasText: personName });
    await expect(reloadedPerson.getByTestId("person-cluster-chip").filter({ hasText: clusterName }))
      .toHaveCount(1, { timeout: 10_000 });

    // Clean up, so a re-run does not accumulate review fixtures in the demo database.
    await page.getByTestId("facedetection-section-clusters").click();
    await page.getByTestId("cluster-card").filter({ hasText: clusterName }).getByTestId("cluster-delete").click();
    await expect(page.getByTestId("cluster-card").filter({ hasText: clusterName })).toHaveCount(0, { timeout: 10_000 });

    await page.getByTestId("facedetection-section-persons").click();
    await page.getByTestId("person-card").filter({ hasText: personName }).getByTestId("person-delete").click();
    await expect(page.getByTestId("person-card").filter({ hasText: personName })).toHaveCount(0, { timeout: 10_000 });
  });
});
