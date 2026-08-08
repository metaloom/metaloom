import {
  createAssetReaction,
  updateAssetReaction,
  listAssetReactions,
  ReactionCreateRequest,
  ReactionResponseItem,
  TaskReactionType,
} from "../../api/reactions";

/**
 * Persistence for the Workflow "rating" mode.
 *
 * A workflow star-rating (1–10) is stored as an asset reaction carrying an
 * integer `rating`, under its own reaction type. The backend's
 * `UNIQUE (creator_uuid, type, asset_uuid)` then means exactly "one rating per
 * user per asset" without colliding with a real emoji reaction on the same
 * asset — which is what happened while the rating borrowed `SATISFIED`.
 */
export const RATING_REACTION_TYPE: TaskReactionType = "RATING";

/**
 * The type ratings were written under before `RATING` existed.
 *
 * Rows written by an older UI are migrated server-side by `V2.78`, but a UI
 * deployed ahead of that migration would otherwise show every asset as unrated.
 * {@link hydrateAssetRatings} therefore still recognises them. Drop this once no
 * reachable server predates V2.78.
 */
const LEGACY_RATING_REACTION_TYPE: TaskReactionType = "SATISFIED";

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
 * Pick the reaction that holds *this* reviewer's rating of the asset.
 *
 * `GET /assets/:uuid/reactions` returns every user's reactions. Taking the first
 * one carrying a number would show a colleague's rating as the reviewer's own —
 * and, worse, hand its uuid to {@link persistAssetRating}, which would then
 * overwrite that colleague's row on the next keystroke. So the owner filter is a
 * correctness requirement, not a display nicety.
 *
 * When the caller has no user uuid (nothing is signed in yet) nothing matches,
 * which is the safe answer: a create writes a row owned by whoever is signed in.
 */
function ownRating(reactions: ReactionResponseItem[], userUuid?: string): ReactionResponseItem | undefined {
  const own = reactions.filter(r => typeof r.rating === "number" && r.status?.creator?.uuid === userUuid);
  return own.find(r => r.type === RATING_REACTION_TYPE) ?? own.find(r => r.type === LEGACY_RATING_REACTION_TYPE);
}

/**
 * Hydrate the workflow rating state from the backend by listing each asset's
 * reactions and picking the signed-in reviewer's own rating. Per-asset failures
 * are ignored so one bad asset doesn't blank the whole screen.
 */
export async function hydrateAssetRatings(
  token: string,
  assetUuids: string[],
  userUuid?: string,
): Promise<HydratedRatings> {
  const ratings: Record<string, number> = {};
  const reactionUuids: Record<string, string> = {};
  await Promise.all(
    assetUuids.map(async uuid => {
      try {
        const res = await listAssetReactions(token, uuid);
        const rated = ownRating(res.data ?? [], userUuid);
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
