package io.metaloom.loom.rest.model.remix;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestRequestModel;
import io.metaloom.loom.rest.model.common.AbstractMetaModel;

/**
 * Create a remix, optionally with its members in the same call.
 *
 * <p>
 * The members are part of the create request because that is how the UI's "combine into remix"
 * action works: the user has a selection in hand and names the group. Making them post the remix and
 * then the membership would leave an empty remix behind whenever the second call fails.
 * </p>
 */
public class RemixCreateRequest extends AbstractMetaModel<RemixCreateRequest> implements RestRequestModel {

	@JsonPropertyDescription("Name of the remix.")
	private String name;

	@JsonPropertyDescription("Free-text description of what this group of assets has in common.")
	private String description;

	@JsonPropertyDescription("Uuids of the assets to put in the remix. May be empty.")
	private List<UUID> assetUuids = new ArrayList<>();

	@JsonPropertyDescription("Uuid of the asset to mark as the source. Must be one of assetUuids.")
	private UUID sourceAssetUuid;

	public String getName() {
		return name;
	}

	public RemixCreateRequest setName(String name) {
		this.name = name;
		return this;
	}

	public String getDescription() {
		return description;
	}

	public RemixCreateRequest setDescription(String description) {
		this.description = description;
		return this;
	}

	public List<UUID> getAssetUuids() {
		return assetUuids;
	}

	public RemixCreateRequest setAssetUuids(List<UUID> assetUuids) {
		this.assetUuids = assetUuids;
		return this;
	}

	public RemixCreateRequest add(UUID assetUuid) {
		this.assetUuids.add(assetUuid);
		return this;
	}

	public UUID getSourceAssetUuid() {
		return sourceAssetUuid;
	}

	public RemixCreateRequest setSourceAssetUuid(UUID sourceAssetUuid) {
		this.sourceAssetUuid = sourceAssetUuid;
		return this;
	}

	@Override
	public RemixCreateRequest self() {
		return this;
	}

}
