package io.metaloom.loom.client.common.method;

import java.util.UUID;

import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.segmentcomp.SegmentCompCreateRequest;
import io.metaloom.loom.rest.model.segmentcomp.SegmentCompListResponse;
import io.metaloom.loom.rest.model.segmentcomp.SegmentCompResponse;

public interface SegmentCompMethods {

	/**
	 * Replace the whole set of segments for {@code (asset, node_kind, segment_type)} in {@code asset_segment_comp}. Surplus rows from a shorter re-run
	 * are deleted.
	 */
	LoomClientRequest<SegmentCompListResponse> createAssetSegmentComps(UUID assetUuid, SegmentCompCreateRequest request);

	LoomClientRequest<SegmentCompResponse> loadAssetSegmentComp(UUID assetUuid, UUID compUuid);

	LoomClientRequest<SegmentCompListResponse> listAssetSegmentComps(UUID assetUuid);

	LoomClientRequest<NoResponse> deleteAssetSegmentComp(UUID assetUuid, UUID compUuid);

}
