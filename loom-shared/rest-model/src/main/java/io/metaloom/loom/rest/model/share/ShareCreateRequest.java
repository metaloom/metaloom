package io.metaloom.loom.rest.model.share;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestRequestModel;
import io.metaloom.loom.rest.model.common.AbstractMetaModel;

/**
 * Create a share link over one asset or one collection.
 *
 * <p>
 * The slug is never supplied by the client. It is the credential, and letting a caller choose it would let them pick a guessable one, or squat on one
 * somebody else's link would have been issued.
 * </p>
 */
public class ShareCreateRequest extends AbstractMetaModel<ShareCreateRequest> implements RestRequestModel {

	@JsonPropertyDescription("What to share: ASSET or COLLECTION.")
	private String targetType;

	@JsonPropertyDescription("The uuid of the asset or collection to share. The caller must be allowed to read it.")
	private UUID targetUuid;

	@JsonPropertyDescription("Password for the link, in clear. Omit or send null for an open link. Stored hashed and echoed back exactly once.")
	private String password;

	@JsonPropertyDescription("When the link should stop working. Omit or send null for a link that never expires.")
	private Instant expiresAt;

	@JsonPropertyDescription("Whether the visitor may download the original file. Defaults to true.")
	private Boolean allowDownload;

	@JsonPropertyDescription("Whether the visitor sees title, description, size, duration and dimensions. Defaults to true.")
	private Boolean showMetadata;

	@JsonPropertyDescription("Whether the visitor may leave comments. Defaults to false.")
	private Boolean allowComments;

	@JsonPropertyDescription("Whether the visitor may react. Defaults to false.")
	private Boolean allowReactions;

	@JsonPropertyDescription("Whether the visitor may mark regions and timecodes. Defaults to false.")
	private Boolean allowAnnotations;

	public String getTargetType() {
		return targetType;
	}

	public ShareCreateRequest setTargetType(String targetType) {
		this.targetType = targetType;
		return this;
	}

	public UUID getTargetUuid() {
		return targetUuid;
	}

	public ShareCreateRequest setTargetUuid(UUID targetUuid) {
		this.targetUuid = targetUuid;
		return this;
	}

	public String getPassword() {
		return password;
	}

	public ShareCreateRequest setPassword(String password) {
		this.password = password;
		return this;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public ShareCreateRequest setExpiresAt(Instant expiresAt) {
		this.expiresAt = expiresAt;
		return this;
	}

	public Boolean getAllowDownload() {
		return allowDownload;
	}

	public ShareCreateRequest setAllowDownload(Boolean allowDownload) {
		this.allowDownload = allowDownload;
		return this;
	}

	public Boolean getShowMetadata() {
		return showMetadata;
	}

	public ShareCreateRequest setShowMetadata(Boolean showMetadata) {
		this.showMetadata = showMetadata;
		return this;
	}

	public Boolean getAllowComments() {
		return allowComments;
	}

	public ShareCreateRequest setAllowComments(Boolean allowComments) {
		this.allowComments = allowComments;
		return this;
	}

	public Boolean getAllowReactions() {
		return allowReactions;
	}

	public ShareCreateRequest setAllowReactions(Boolean allowReactions) {
		this.allowReactions = allowReactions;
		return this;
	}

	public Boolean getAllowAnnotations() {
		return allowAnnotations;
	}

	public ShareCreateRequest setAllowAnnotations(Boolean allowAnnotations) {
		this.allowAnnotations = allowAnnotations;
		return this;
	}

	@Override
	public ShareCreateRequest self() {
		return this;
	}
}
