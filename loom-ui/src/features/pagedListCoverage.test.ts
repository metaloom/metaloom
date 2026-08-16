import { describe, expect, it } from "vitest";

/**
 * Structural guard: a view that pages must offer a way to page.
 *
 * `usePagedList` fetches one page and reports `hasMore`, but nothing forces a view to *render*
 * that — and a screen silently showing the first hundred rows of a collection looks exactly like a
 * screen showing the whole collection. Every defect of that shape in this tree has been invisible
 * until the data outgrew the page.
 *
 * This asserts the pairing at the source level, which no e2e spec can do: `e2e/list-paging-mocked.spec.ts`
 * proves the views that exist today page correctly, while this one fails the moment a *new* view
 * calls `usePagedList` and forgets the footer.
 *
 * The testid is part of the contract rather than decoration — without one the footer is unreachable
 * from a spec, which is how thirteen search fields stayed unverified (LOOM_UI.md §7.5.1).
 *
 * Sources are read through Vite's raw glob rather than `fs`: this package has no `@types/node`, so
 * an `fs` import compiles under vitest and then fails `tsc --noEmit` (see `sourceHygiene.test.ts`).
 */
const VIEWS = import.meta.glob<string>(
  ["./**/*.tsx", "!./**/*.{test,spec}.tsx"],
  { query: "?raw", import: "default", eager: true },
);

/** Views that hold a paged collection. */
const pagedViews = Object.entries(VIEWS).filter(([, source]) => /\busePagedList[<(]/.test(source));

/** Every `"…-paging"` testid literal in a file — a ternary between two of them counts as both. */
function pagingTestIds(source: string): string[] {
  return [...source.matchAll(/"([a-z0-9-]+-paging)"/g)].map(match => match[1]);
}

describe("paged list views", () => {

  it("reads the feature tree", () => {
    // A broken glob, or a renamed hook, would make every assertion below pass vacuously.
    expect(Object.keys(VIEWS).length).toBeGreaterThan(50);
    expect(pagedViews.length).toBeGreaterThanOrEqual(9);
  });

  it.each(pagedViews)("%s renders a ListPaging footer", (_path, source) => {
    expect(source).toContain("components/ListPaging");
    expect(source).toContain("<ListPaging");
  });

  it.each(pagedViews)("%s gives every footer a testid", (_path, source) => {
    expect(pagingTestIds(source).length).toBeGreaterThan(0);
  });

  it("keeps the footer testids unique across the tree", () => {
    const owners = new Map<string, string>();
    const collisions: string[] = [];
    for (const [path, source] of pagedViews) {
      for (const id of pagingTestIds(source)) {
        const owner = owners.get(id);
        // A file may legitimately repeat one id across the branches of a ternary; two different
        // files sharing one makes a spec ambiguous about which footer it drove.
        if (owner && owner !== path) collisions.push(`${id}: ${owner} and ${path}`);
        owners.set(id, path);
      }
    }
    expect(collisions).toEqual([]);
  });

  it("has no ListPaging without a testid anywhere, paged view or not", () => {
    const untestable = Object.entries(VIEWS)
      .filter(([, source]) => source.includes("<ListPaging"))
      .filter(([, source]) => pagingTestIds(source).length === 0)
      .map(([path]) => path);
    expect(untestable).toEqual([]);
  });
});
