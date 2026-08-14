package io.metaloom.loom.api.graph;

import java.util.Set;
import java.util.UUID;

/**
 * "Which assets are related to this one, and why."
 *
 * <p>
 * The shape of the question is fixed at two hops - asset to intermediate to asset - because that is what the relations in the schema mean. An asset is
 * never directly connected to another asset; it is connected through a tag, a collection, a remix or a person. Three hops would be "assets sharing a
 * tag with an asset that shares a tag with this one", which is not a question anyone has asked.
 * </p>
 *
 * @param assetUuid
 *            the asset to start from
 * @param viaTypes
 *            which relation types may be traversed, or {@code null} for all of them
 * @param limit
 *            maximum hits to return, best first
 * @param minSharedConnections
 *            drop hits connected by fewer than this many intermediates. 1 returns everything reachable
 */
public record RelatedAssetsQuery(UUID assetUuid, Set<String> viaTypes, int limit, int minSharedConnections) {

	public static final int DEFAULT_LIMIT = 50;

	public RelatedAssetsQuery {
		if (assetUuid == null) {
			throw new IllegalArgumentException("A related-assets query needs an asset");
		}
		if (limit <= 0) {
			throw new IllegalArgumentException("limit must be positive");
		}
		viaTypes = viaTypes == null ? null : Set.copyOf(viaTypes);
	}

	public static RelatedAssetsQuery of(UUID assetUuid) {
		return new RelatedAssetsQuery(assetUuid, null, DEFAULT_LIMIT, 1);
	}

	public static RelatedAssetsQuery via(UUID assetUuid, String... types) {
		return new RelatedAssetsQuery(assetUuid, Set.of(types), DEFAULT_LIMIT, 1);
	}
}
