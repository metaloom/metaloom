import { DEFAULT_TAG_COLLECTION, listTags, tagAsset, untagAsset } from "../../api/tags";
import type { TagReference } from "../../api/assets";

/**
 * Persistence for the Workflow "tagging" mode.
 *
 * The counterpart of `ratingPersistence`, and deliberately the same shape: pure functions that
 * throw, so the caller owns the optimistic update and its rollback. At a keystroke per decision a
 * failed write that disappears quietly is worse than no persistence at all — the chip says the
 * decision was recorded and the server never heard about it.
 *
 * Two things about the write path are worth knowing before changing it:
 *
 * - `POST /assets/:uuid/tags` **resolves** an existing tag by `(name, collection)` and creates a
 *   *placement*. It does not insert a tag row per asset — `tag` is `UNIQUE (name, collection)`, and
 *   an endpoint that inserted broke on the second asset.
 * - Removal needs the tag's uuid, which is why this module hands the screen {@link WorkflowTag}
 *   objects rather than the bare names the view used to keep.
 */

/** A tag as the workflow screen holds it. The uuid is what {@link removeAssetTag} needs. */
export interface WorkflowTag {
  uuid: string;
  name: string;
  /** `"manual"` when a person attached it, a node kind otherwise. Absent counts as manual. */
  nodeKind?: string;
  /** How sure the writer was, 0.0–1.0. Absent for a person. */
  confidence?: number;
}

/**
 * Prefix of the placeholder uuid an optimistic chip carries until its POST lands.
 *
 * A placeholder rather than an index or the name, because at ten keystrokes a second several
 * writes are in flight: rolling back by position would remove whichever chip happens to sit there
 * by then.
 */
export const PENDING_TAG_PREFIX = "pending:";

export function isPending(tag: WorkflowTag): boolean {
  return tag.uuid.startsWith(PENDING_TAG_PREFIX);
}

/**
 * Whether a person attached this tag.
 *
 * An absent `nodeKind` counts as curated because the column defaults to `'manual'` deliberately: a
 * machine tag mislabelled human is merely not filtered out, while a human tag mislabelled machine
 * could be deleted by a reconciling node.
 */
export function isCurated(tag: WorkflowTag): boolean {
  return tag.nodeKind == null || tag.nodeKind === "manual";
}

export function toWorkflowTags(tags: TagReference[] | undefined): WorkflowTag[] {
  return (tags ?? []).map(t => ({
    uuid: t.uuid,
    name: t.name,
    nodeKind: t.nodeKind,
    confidence: t.confidence,
  }));
}

/** A chip to show while the write is in flight. Never sent anywhere. */
export function pendingTag(name: string): WorkflowTag {
  return { uuid: `${PENDING_TAG_PREFIX}${name}`, name, nodeKind: "manual" };
}

/**
 * Attach a tag to an asset, resolving an existing tag of that name or coining a new one.
 *
 * Rejects on any non-2xx so the caller can roll its optimistic chip back.
 */
export async function addAssetTag(
  token: string,
  assetUuid: string,
  name: string,
  collection: string = DEFAULT_TAG_COLLECTION,
): Promise<WorkflowTag> {
  const created = await tagAsset(token, assetUuid, { name, collection });
  return {
    uuid: created.uuid,
    name: created.name ?? name,
    // A tag written from here is by definition curated; the server defaults node_kind to 'manual'
    // and does not echo it on this response.
    nodeKind: "manual",
  };
}

/** Remove every placement of the tag from the asset. Rejects on any non-2xx. */
export async function removeAssetTag(token: string, assetUuid: string, tagUuid: string): Promise<void> {
  await untagAsset(token, assetUuid, tagUuid);
}

/**
 * The tag names a reviewer can pick from, for the autocomplete.
 *
 * Deliberately unscoped. `listTags` has no collection filter, and `tag.collection` is a free-text
 * namespace rather than an asset collection, so there is nothing here to scope *to*: the review
 * queue is "the first page of assets", not a collection. Scoping the vocabulary needs a queue that
 * carries a collection first. The input stays `freeSolo`, so a word missing from this list is
 * still typeable.
 */
export async function loadTagVocabulary(token: string, limit = 200): Promise<string[]> {
  const res = await listTags(token, { limit });
  const names = new Set((res.data ?? []).map(t => t.name).filter(Boolean));
  return [...names].sort((a, b) => a.localeCompare(b));
}
