package io.metaloom.loom.rest.model.similarity;

import io.metaloom.loom.rest.model.common.AbstractListResponse;

/**
 * List of near-duplicate hits for an asset, ordered by score descending.
 */
public class SimilarAssetListResponse extends AbstractListResponse<SimilarAssetListResponse, SimilarAssetResponse> {

	@Override
	public SimilarAssetListResponse self() {
		return this;
	}
}
