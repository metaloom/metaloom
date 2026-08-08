package io.metaloom.cortex.node.filter;

import java.util.List;

import javax.annotation.Nullable;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.reaction.ReactionResponse;
import io.metaloom.loom.rest.model.tag.TagReference;

/**
 * Everything a {@link FilterStrategy} is given about one item.
 *
 * <p>
 * The strategies started out reading only the item's own metadata, so {@code classify} took the
 * {@link NodeContext} and nothing else. Routing on a <em>human decision</em> — a rating, a curated
 * tag — needs what Loom knows about the asset, and {@link FilterNode} was already holding it and
 * dropping it on the floor. Handing it down beats giving the strategy a {@code LoomClient}: a
 * strategy has no asset identity of its own, so it would have to re-derive the SHA-512 from the
 * media and load the asset a second time, once per item, forever. It also keeps every Loom call in
 * the node, which is how {@code TagNode} is built.
 * </p>
 *
 * <p>
 * A per-item record rather than more parameters, so the next thing a strategy needs — detections,
 * components — extends this and leaves every existing implementation alone.
 * </p>
 *
 * @param ctx
 *            the node context, carrying the media
 * @param asset
 *            what Loom knows about the item, or {@code null} when we run offline, the load failed,
 *            or the bytes have never been ingested. Not an error: it is a routing answer of
 *            "unknown", and a strategy should say so with {@code Classification.other(...)}
 * @param reactions
 *            the asset's reactions, fetched only for a strategy that asked via
 *            {@link FilterStrategy#needsReactions()}; empty otherwise
 * @param reactionsAvailable
 *            whether {@code reactions} is an answer at all. {@code false} means the fetch failed,
 *            which is <strong>not</strong> the same as "the asset has no reactions" — treating an
 *            outage as "unrated" would route unrated-branch work, typically trash, over a blip
 * @param text
 *            the text wired into the node's {@code text} port; may be {@code null} or blank
 */
public record FilterItem(
	NodeContext<LoomMedia> ctx,
	@Nullable AssetResponse asset,
	List<ReactionResponse> reactions,
	boolean reactionsAvailable,
	@Nullable String text) {

	public LoomMedia media() {
		return ctx.media();
	}

	/** The tags Loom holds for this asset. They ride along on the asset response, so they cost nothing. */
	public List<TagReference> tags() {
		if (asset == null || asset.getTags() == null) {
			return List.of();
		}
		return asset.getTags();
	}

	/** An item with no reactions loaded — the state every strategy that does not ask for them sees. */
	public static FilterItem of(NodeContext<LoomMedia> ctx, @Nullable AssetResponse asset, @Nullable String text) {
		return new FilterItem(ctx, asset, List.of(), true, text);
	}

	public FilterItem withReactions(List<ReactionResponse> reactions) {
		return new FilterItem(ctx, asset, reactions == null ? List.of() : reactions, true, text);
	}

	/** The reaction fetch failed. Kept apart from "none" so a strategy can refuse to guess. */
	public FilterItem withoutReactions() {
		return new FilterItem(ctx, asset, List.of(), false, text);
	}
}
