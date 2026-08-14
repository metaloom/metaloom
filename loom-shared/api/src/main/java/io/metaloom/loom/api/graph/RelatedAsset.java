package io.metaloom.loom.api.graph;

import java.util.List;
import java.util.UUID;

/**
 * One answer to "what else is like this asset", with enough of the path to explain itself.
 *
 * <p>
 * {@code via} is the reason, not decoration. "These two assets are related" is not a useful answer to a user; "they share the tag
 * <i>Sunset</i> and the person <i>Wes</i>" is, and it is also what makes a wrong answer debuggable. Every hit carries the intermediate nodes that
 * connect it to the query asset.
 * </p>
 *
 * @param assetUuid
 *            the related asset
 * @param sharedConnections
 *            how many distinct intermediate nodes connect it to the query asset. This is the ranking signal: an asset sharing three tags and a person
 *            is more related than one sharing a single collection
 * @param via
 *            the intermediate nodes themselves, ordered by kind then uuid so the list is stable
 */
public record RelatedAsset(UUID assetUuid, int sharedConnections, List<GraphNodeRef> via) {

	public RelatedAsset {
		via = List.copyOf(via);
	}

	@Override
	public String toString() {
		return assetUuid + " (" + sharedConnections + " shared: " + via + ")";
	}
}
