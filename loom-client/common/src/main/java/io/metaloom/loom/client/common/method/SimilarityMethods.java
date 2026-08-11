package io.metaloom.loom.client.common.method;

import java.util.UUID;

import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.similarity.SimilarAssetListResponse;

/**
 * Client access to the fingerprint similarity routes (see spec/loom/SEARCH_LUCENE.md).
 *
 * <p>
 * Drives the near-duplicate discovery performed by the {@code fingerprint-dedup} node.
 * </p>
 */
public interface SimilarityMethods {

	/**
	 * Return the assets whose fingerprint is near-duplicate to the given asset's fingerprint.
	 *
	 * @param assetUuid
	 *            the query asset (its stored fingerprint is used); the asset itself is excluded from the result
	 * @param algorithm
	 *            fingerprint algorithm to query, or {@code null} for the server default
	 * @param limit
	 *            maximum neighbours to return, or {@code null} for the server default
	 * @param threshold
	 *            minimum similarity score, or {@code null} for the server default
	 * @return the near-duplicate hits, ordered by score descending
	 */
	LoomClientRequest<SimilarAssetListResponse> listSimilarAssets(UUID assetUuid, String algorithm, Integer limit, Float threshold);

	/**
	 * Trigger a full rebuild of the similarity index from {@code asset_fingerprint_comp} (admin).
	 */
	LoomClientRequest<NoResponse> rebuildSimilarityIndex();
}
