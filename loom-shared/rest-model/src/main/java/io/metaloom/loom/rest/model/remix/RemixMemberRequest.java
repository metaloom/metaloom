package io.metaloom.loom.rest.model.remix;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestRequestModel;

/**
 * Add one or more assets to an existing remix.
 *
 * <p>
 * A list rather than a single uuid because the calling gesture is a multi-select: adding five assets
 * should be one request, not five.
 * </p>
 */
public class RemixMemberRequest implements RestRequestModel {

	@JsonPropertyDescription("Uuids of the assets to add. Adding one that is already a member is not an error.")
	private List<UUID> assetUuids = new ArrayList<>();

	@JsonPropertyDescription("Uuid of the asset to mark as the source, or null to leave the source unchanged.")
	private UUID sourceAssetUuid;

	public List<UUID> getAssetUuids() {
		return assetUuids;
	}

	public RemixMemberRequest setAssetUuids(List<UUID> assetUuids) {
		this.assetUuids = assetUuids;
		return this;
	}

	public RemixMemberRequest add(UUID assetUuid) {
		this.assetUuids.add(assetUuid);
		return this;
	}

	public UUID getSourceAssetUuid() {
		return sourceAssetUuid;
	}

	public RemixMemberRequest setSourceAssetUuid(UUID sourceAssetUuid) {
		this.sourceAssetUuid = sourceAssetUuid;
		return this;
	}

}
