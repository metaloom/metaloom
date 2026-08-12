package io.metaloom.loom.rest.model.remix;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.common.AbstractCreatorEditorRestResponse;

public class RemixResponse extends AbstractCreatorEditorRestResponse<RemixResponse> implements RemixModel<RemixResponse> {

	@JsonPropertyDescription("Name of the remix.")
	private String name;

	@JsonPropertyDescription("Free-text description of what this group of assets has in common.")
	private String description;

	@JsonPropertyDescription("Uuid of the asset the remix is built around, or null when the original is not in the catalogue.")
	private UUID sourceAssetUuid;

	@JsonPropertyDescription("Number of assets in the remix.")
	private long memberCount;

	@Override
	public String getName() {
		return name;
	}

	@Override
	public RemixResponse setName(String name) {
		this.name = name;
		return this;
	}

	@Override
	public String getDescription() {
		return description;
	}

	@Override
	public RemixResponse setDescription(String description) {
		this.description = description;
		return this;
	}

	@Override
	public UUID getSourceAssetUuid() {
		return sourceAssetUuid;
	}

	@Override
	public RemixResponse setSourceAssetUuid(UUID sourceAssetUuid) {
		this.sourceAssetUuid = sourceAssetUuid;
		return this;
	}

	public long getMemberCount() {
		return memberCount;
	}

	public RemixResponse setMemberCount(long memberCount) {
		this.memberCount = memberCount;
		return this;
	}

	@Override
	public RemixResponse self() {
		return this;
	}

}
