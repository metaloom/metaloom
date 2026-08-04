import { tokens } from "../../theme";
import type { NodeCategory, NodeDescriptor } from "../../types/nodeDescriptors";

/**
 * The colour a node card is drawn in.
 *
 * Colour comes from the node's {@link NodeCategory}, so every node of one category looks alike —
 * that grouping is the whole point, and it is what lets you pick the sources out of a large graph
 * without reading a single label. A descriptor may override it with its own `color`, which almost
 * none do.
 *
 * Two things live here rather than in `PipelineEditor.tsx`:
 *
 * - **It is mirrored.** The public website ships a separate, backend-free editor
 *   (`website/themes/meghna-hugo/assets/js/pipeline-editor.js`) that cannot import TypeScript and
 *   therefore keeps its own copy of these five values. They were hand-tuned lookalikes rather than
 *   copies and had drifted — the same node was magenta `#e040fb` in the product and `#c56be0` on the
 *   website. `nodeColors.test.ts` reads that file and fails if they diverge again.
 * - **It is testable.** `PipelineEditor.tsx` is 4600 lines and carries JSX; this is not.
 *
 * The palette resolves entirely through theme tokens, so it follows the light/dark switch. It did
 * not always: `TRANSFORM` held a literal `#e040fb`, the one hardcoded hex in the table, which is why
 * the thumbnail node read as a loud magenta beside four muted neighbours and stayed magenta in light
 * mode. `OUTPUT` moved off `accent.teal` for a different reason — at `#00c9b1` it was nearly
 * indistinguishable from `ANALYSIS`'s `#57cbcc`, and `ANALYSIS` is 22 of the 38 shipped nodes, so
 * the two were adjacent constantly.
 */
export const CATEGORY_COLORS: Record<NodeCategory, string> = {
  SOURCE: tokens.accent.blue,
  FILTER: tokens.accent.amber,
  ANALYSIS: tokens.primary.main,
  TRANSFORM: tokens.accent.violet,
  OUTPUT: tokens.accent.green,
};

/** Whether `value` is a bare `#rgb` or `#rrggbb` literal — the mirror of Java's `NodeDescriptor.isHexColor`. */
export function isHexColor(value: string | null | undefined): value is string {
  return typeof value === "string" && /^#([0-9a-f]{3}|[0-9a-f]{6})$/i.test(value);
}

/**
 * Expand `#rgb` to `#rrggbb` so an alpha suffix can be appended.
 *
 * `${color}18` is how every tint in the editor is built, and on a three-digit hex that silently
 * produces the invalid `#abc18`, which CSS drops — leaving the chip transparent rather than tinted.
 */
export function expandHex(hex: string): string {
  return /^#[0-9a-f]{3}$/i.test(hex)
    ? `#${hex[1]}${hex[1]}${hex[2]}${hex[2]}${hex[3]}${hex[3]}`
    : hex;
}

/**
 * The colour for a descriptor: its own `color` when its author set one, else the category default.
 *
 * Loom rejects a non-hex `color` before serving a descriptor, but the website editor is also staged
 * from a checked-in snapshot and the palette is written straight into a style attribute, so the
 * check is repeated here rather than assumed.
 */
export function nodeColor(desc: Pick<NodeDescriptor, "category" | "color"> | undefined | null): string {
  if (desc && isHexColor(desc.color)) {
    return desc.color;
  }
  return (desc && CATEGORY_COLORS[desc.category]) || CATEGORY_COLORS.ANALYSIS;
}

/** The translucent fill behind a node's icon chip, derived from its colour. */
export function nodeColorTint(color: string): string {
  return `${expandHex(color)}18`;
}
