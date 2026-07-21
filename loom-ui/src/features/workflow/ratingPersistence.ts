import {
  createAssetReaction,
  updateAssetReaction,
  listAssetReactions,
  ReactionCreateRequest,
  TaskReactionType,
} from "../../api/reactions";

/**
 * Persistence for the Workflow "rating" mode.
 *
 * A workflow star-rating (1–10) is stored as an asset reaction carrying an
 * integer `rating`. The backend requires a non-null reaction `type` on create,
 * so we attach a neutral marker type — the rating value is what carries meaning
 * here, not the emoji.
 */
export const RATING_REACTION_TYPE: TaskReactionType = "SATISFIED";

/**
 * Persist a workflow star-rating for an asset as an asset reaction.
 *
 * Creates a new reaction when the asset has none yet, otherwise updates the
 * existing one so repeated ratings don't pile up duplicates. Returns the uuid
 * of the (created or updated) reaction so callers can update subsequent edits.
 */
export async function persistAssetRating(
  token: string,
  assetUuid: string,
  rating: number,
  existingReactionUuid?: string,
): Promise<string> {
  const request: ReactionCreateRequest = { type: RATING_REACTION_TYPE, rating };
  if (existingReactionUuid) {
    const updated = await updateAssetReaction(token, assetUuid, existingReactionUuid, request);
    return updated.uuid ?? existingReactionUuid;
  }
  const created = await createAssetReaction(token, assetUuid, request);
  return created.uuid;
}

export interface HydratedRatings {
  /** assetUuid -> rating value */
  ratings: Record<string, number>;
  /** assetUuid -> uuid of the reaction backing that rating */
  reactionUuids: Record<string, string>;
}

/**
 * Hydrate the workflow rating state from the backend by listing each asset's
 * reactions and picking the first one that carries a numeric rating. Per-asset
 * failures are ignored so one bad asset doesn't blank the whole screen.
 */
export async function hydrateAssetRatings(token: string, assetUuids: string[]): Promise<HydratedRatings> {
  const ratings: Record<string, number> = {};
  const reactionUuids: Record<string, string> = {};
  await Promise.all(
    assetUuids.map(async uuid => {
      try {
        const res = await listAssetReactions(token, uuid);
        const rated = (res.data ?? []).find(r => typeof r.rating === "number");
        if (rated && typeof rated.rating === "number") {
          ratings[uuid] = rated.rating;
          reactionUuids[uuid] = rated.uuid;
        }
      } catch {
        // Ignore hydration errors for individual assets.
      }
    }),
  );
  return { ratings, reactionUuids };
}
