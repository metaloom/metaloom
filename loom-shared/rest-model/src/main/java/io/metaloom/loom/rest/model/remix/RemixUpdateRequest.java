package io.metaloom.loom.rest.model.remix;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestRequestModel;
import io.metaloom.loom.rest.model.common.AbstractMetaModel;

/**
 * Update a remix's own fields. Membership is changed through the {@code /remixes/:uuid/assets}
 * routes, not here.
 */
public class RemixUpdateRequest extends AbstractMetaModel<RemixUpdateRequest> implements RestRequestModel {

	@JsonPropertyDescription("Name of the remix.")
	private String name;

	@JsonPropertyDescription("Free-text description of what this group of assets has in common.")
	private String description;

	@JsonPropertyDescription("Uuid of the asset to mark as the source. Must already be a member.")
	private UUID sourceAssetUuid;

	public String getName() {
		return name;
	}

	public RemixUpdateRequest setName(String name) {
		this.name = name;
		return this;
	}

	public String getDescription() {
		return description;
	}

	public RemixUpdateRequest setDescription(String description) {
		this.description = description;
		return this;
	}

	public UUID getSourceAssetUuid() {
		return sourceAssetUuid;
	}

	public RemixUpdateRequest setSourceAssetUuid(UUID sourceAssetUuid) {
		this.sourceAssetUuid = sourceAssetUuid;
		return this;
	}

	@Override
	public RemixUpdateRequest self() {
		return this;
	}

}
