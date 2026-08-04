import { describe, it, expect } from "vitest";
// `?raw` rather than fs: this package has no @types/node, so reading the file through `fs` compiles
// under vitest and then fails `tsc --noEmit`. Vite resolves the import at collection time and
// `vite/client` (referenced from react-app-env.d.ts) already types it.
import websiteEditorSource from "../../../../website/themes/meghna-hugo/assets/js/pipeline-editor.js?raw";
import { CATEGORY_COLORS, expandHex, isHexColor, nodeColor, nodeColorTint } from "./nodeColors";
import { darkTokens } from "../../theme";
import type { NodeCategory } from "../../types/nodeDescriptors";

/**
 * Contract test for the node palette.
 *
 * The public website ships a second, backend-free pipeline editor as a vanilla-JS file that cannot
 * import TypeScript, so it keeps its own copy of these five values. They were never generated from
 * this table — they were hand-tuned lookalikes — and they had drifted far enough that the same node
 * was `#e040fb` in the product and `#c56be0` on the website. This file reads that JS and fails when
 * the two disagree, which is the only thing standing between them and drifting again.
 */

/** The five categories of Java's `NodeCategory`. */
const CATEGORIES: NodeCategory[] = ["SOURCE", "FILTER", "ANALYSIS", "TRANSFORM", "OUTPUT"];

/** Pull `var CATEGORY_COLORS = { ... }` out of the website editor without executing it. */
function websiteCategoryColors(): Record<string, string> {
  const block = /var CATEGORY_COLORS = \{([^}]*)\}/.exec(websiteEditorSource);
  if (!block) {
    throw new Error("No CATEGORY_COLORS block in the website editor — did it get renamed?");
  }
  const colors: Record<string, string> = {};
  for (const [, key, value] of block[1].matchAll(/(\w+)\s*:\s*"(#[0-9a-fA-F]{3,6})"/g)) {
    colors[key] = value;
  }
  return colors;
}

describe("the category palette", () => {
  it("covers every category exactly once", () => {
    expect(Object.keys(CATEGORY_COLORS).sort()).toEqual([...CATEGORIES].sort());
  });

  it("resolves through theme tokens rather than literal hexes", () => {
    // The point of the table: TRANSFORM used to be a literal `#e040fb`, which meant it alone could
    // not follow the light theme. Comparing against the *dark* tokens also pins that the `tokens`
    // proxy defaults to dark outside a React tree, which is what the website mirror assumes.
    expect(CATEGORY_COLORS).toEqual({
      SOURCE: darkTokens.accent.blue,
      FILTER: darkTokens.accent.amber,
      ANALYSIS: darkTokens.primary.main,
      TRANSFORM: darkTokens.accent.violet,
      OUTPUT: darkTokens.accent.green,
    });
  });

  it("keeps ANALYSIS and OUTPUT visually apart", () => {
    // These were `#57cbcc` and `#00c9b1` — two teals a few percent apart. ANALYSIS is 22 of the 38
    // shipped nodes, so it sits next to an OUTPUT node in almost every graph.
    expect(CATEGORY_COLORS.ANALYSIS).not.toBe(CATEGORY_COLORS.OUTPUT);
    const distance = (a: string, b: string) =>
      [1, 3, 5].reduce((sum, i) =>
        sum + Math.abs(parseInt(a.slice(i, i + 2), 16) - parseInt(b.slice(i, i + 2), 16)), 0);
    expect(distance(CATEGORY_COLORS.ANALYSIS, CATEGORY_COLORS.OUTPUT)).toBeGreaterThan(60);
  });

  it("is mirrored verbatim by the website editor", () => {
    expect(websiteCategoryColors()).toEqual(CATEGORY_COLORS);
  });
});

describe("nodeColor", () => {
  it("falls back to the category default, which is the normal case", () => {
    // No shipped node authors a colour: category grouping is the feature, and one TRANSFORM node
    // opting out would be the thing that breaks it.
    expect(nodeColor({ category: "TRANSFORM" })).toBe(CATEGORY_COLORS.TRANSFORM);
    expect(nodeColor({ category: "SOURCE", color: undefined })).toBe(CATEGORY_COLORS.SOURCE);
  });

  it("prefers a descriptor's authored colour", () => {
    expect(nodeColor({ category: "TRANSFORM", color: "#123456" })).toBe("#123456");
  });

  it("ignores an authored colour that is not a hex literal", () => {
    // Loom drops these before serving a descriptor, but the website editor is also staged from a
    // checked-in snapshot, and this value is written straight into a style attribute.
    for (const hostile of ["red", "var(--x)", "#fff; background: url(http://evil)", "", "#12345"]) {
      expect(nodeColor({ category: "OUTPUT", color: hostile })).toBe(CATEGORY_COLORS.OUTPUT);
    }
  });

  it("falls back to ANALYSIS for an unknown category", () => {
    expect(nodeColor({ category: "NOPE" as NodeCategory })).toBe(CATEGORY_COLORS.ANALYSIS);
    expect(nodeColor(undefined)).toBe(CATEGORY_COLORS.ANALYSIS);
  });
});

describe("isHexColor", () => {
  it("accepts both hex lengths, in either case", () => {
    for (const ok of ["#abc", "#ABC", "#a1b2c3", "#A1B2C3"]) {
      expect(isHexColor(ok)).toBe(true);
    }
  });

  it("rejects everything else", () => {
    for (const bad of ["abc", "#ab", "#abcd", "#abcdefa", "rgb(1,2,3)", "red", null, undefined, ""]) {
      expect(isHexColor(bad)).toBe(false);
    }
  });
});

describe("nodeColorTint", () => {
  it("expands a short hex before appending the alpha", () => {
    // `#abc` + `18` is `#abc18`, which is not a colour — CSS drops the declaration and the icon
    // chip loses its fill entirely rather than looking slightly wrong.
    expect(expandHex("#abc")).toBe("#aabbcc");
    expect(nodeColorTint("#abc")).toBe("#aabbcc18");
  });

  it("leaves a full hex alone", () => {
    expect(nodeColorTint("#9d7bea")).toBe("#9d7bea18");
  });
});
