package io.metaloom.loom.rest.model.comment;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.asset.location.social.SocialInfo;
import io.metaloom.loom.rest.model.common.AbstractCreatorEditorRestResponse;

public class CommentResponse extends AbstractCreatorEditorRestResponse<CommentResponse> implements CommentModel<CommentResponse> {

	private String title;

	private String text;

	private UUID assetUuid;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Uuid of the comment this one replies to. Absent on a top-level comment.")
	private UUID parentUuid;

	private SocialInfo social;

	@Override
	public String getTitle() {
		return title;
	}

	@Override
	public CommentResponse setTitle(String title) {
		this.title = title;
		return this;
	}

	@Override
	public String getText() {
		return text;
	}

	@Override
	public CommentResponse setText(String text) {
		this.text = text;
		return this;
	}

	public UUID getAssetUuid() {
		return assetUuid;
	}

	public CommentResponse setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	/**
	 * The comment being replied to, or null for a root comment. See {@link CommentCreateRequest#getParentUuid()} for why this does not live on
	 * {@code CommentModel}.
	 */
	public UUID getParentUuid() {
		return parentUuid;
	}

	public CommentResponse setParentUuid(UUID parentUuid) {
		this.parentUuid = parentUuid;
		return this;
	}

	public SocialInfo getSocial() {
		return social;
	}

	public CommentResponse setSocial(SocialInfo social) {
		this.social = social;
		return this;
	}

	@Override
	public CommentResponse self() {
		return this;
	}

}
