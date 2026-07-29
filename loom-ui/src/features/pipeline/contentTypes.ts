import type { ContentType } from "../../types/nodeDescriptors";

/**
 * The TypeScript mirror of Java's `ContentTypeLattice`
 * (`loom-shared/node-model/.../ContentTypeLattice.java`).
 *
 * A content type id is always `family/subtype`; `family/*` is the family root. The entire rule is:
 *
 * ```
 * assignable(actual, declared) := actual === declared                    // exact
 *                              || declared === family(actual) + "/*"     // consumer takes the family
 *                              || actual   === family(declared) + "/*"   // producer is unspecific
 * ```
 *
 * Assignability never crosses families, which is what keeps the rule free of special cases and
 * cheap enough to mirror here rather than round-tripping every drag over HTTP. `contentTypes.test.ts`
 * pins this mirror against the same fixture `ContentTypeLatticeTest` asserts on the Java side.
 *
 * **The vocabulary itself is not mirrored.** Labels and descriptions come from the `contentTypes`
 * list served by `GET /api/v1/node-descriptors`; only the rule and the colours live here.
 */

const WILDCARD_SUFFIX = "/*";

/** The family part of a content type id — everything before the slash, or null if there is none. */
export function family(contentType: string | null | undefined): string | null {
  if (!contentType) return null;
  const slash = contentType.indexOf("/");
  return slash < 0 ? null : contentType.slice(0, slash);
}

/** Whether the id is a family wildcard such as `media/*`. */
export function isWildcard(contentType: string | null | undefined): boolean {
  return !!contentType && contentType.endsWith(WILDCARD_SUFFIX);
}

/** The wildcard for a content type's family, e.g. `detection/face` → `detection/*`. */
export function wildcardOf(contentType: string | null | undefined): string | null {
  const fam = family(contentType);
  return fam === null ? null : fam + WILDCARD_SUFFIX;
}

/**
 * Whether a value produced as `actual` may be fed into a port declared as `declared`.
 *
 * @param actual the producing port's content type
 * @param declared the consuming port's content type
 */
export function isAssignable(actual: string | null | undefined, declared: string | null | undefined): boolean {
  if (!actual || !declared) return false;
  if (actual === declared) return true;
  const actualFamily = family(actual);
  const declaredFamily = family(declared);
  if (actualFamily === null || declaredFamily === null || actualFamily !== declaredFamily) return false;
  return isWildcard(declared) || isWildcard(actual);
}

/**
 * Whether the producer is only *provisionally* compatible — it emits a family wildcard while the
 * consumer wants a concrete subtype, so the real verdict can only be reached at runtime with the
 * file in hand. The editor still allows the connection; it just renders the handle hollow.
 */
export function isProvisional(actual: string | null | undefined, declared: string | null | undefined): boolean {
  return isAssignable(actual, declared) && isWildcard(actual) && !isWildcard(declared);
}

/**
 * One colour per family — the eight families of `ContentTypeRegistry.FAMILIES`. Replaces the old
 * five-bucket `toConnectorDataType` collapse, which crushed every type into the same handful of
 * colours and validated connections by bucket equality.
 */
export const FAMILY_COLORS: Record<string, string> = {
  media:     "#66bb6a",
  text:      "#42a5f5",
  detection: "#ec407a",
  hash:      "#ab47bc",
  scalar:    "#ffa726",
  artifact:  "#26c6da",
  struct:    "#ffca28",
  control:   "#78909c",
};

/** Colour for a content type, by family. Falls back for ids from a newer server than this build. */
export function contentTypeColor(contentType: string | null | undefined, fallback: string): string {
  const fam = family(contentType);
  return (fam !== null && FAMILY_COLORS[fam]) || fallback;
}

/**
 * Look up a served content type. The vocabulary is never hardcoded here — an id the server did not
 * send simply has no label, and callers fall back to showing the raw id.
 */
export function findContentType(contentType: string | null | undefined, contentTypes: ContentType[]): ContentType | undefined {
  if (!contentType) return undefined;
  return contentTypes.find(ct => ct.id === contentType);
}

/** The served label for a content type, falling back to the raw id. */
export function contentTypeLabel(contentType: string | null | undefined, contentTypes: ContentType[]): string {
  return findContentType(contentType, contentTypes)?.label ?? contentType ?? "";
}
