package io.metaloom.loom.rest.model.user;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.common.AbstractCreatorEditorRestResponse;

/**
 * The picture belonging to a user account.
 *
 * <p>
 * Stored independently of any asset, like a person image, so nothing in the media library can delete somebody's profile picture. The caller never has
 * to know how the bytes are addressed: {@link #getUrl()} is the whole contract.
 * </p>
 *
 * <p>
 * This is {@code PersonImageResponse} without its {@code avatar} flag. An account has at most one picture - a partial unique index enforces it - so a
 * flag saying "this is the one" would be true on every response ever returned.
 * </p>
 */
public class UserAvatarResponse extends AbstractCreatorEditorRestResponse<UserAvatarResponse> {

	@JsonProperty(required = true)
	@JsonPropertyDescription("The original filename of the picture.")
	private String filename;

	@JsonProperty(required = true)
	@JsonPropertyDescription("The mime type of the picture.")
	private String mimeType;

	@JsonProperty(required = true)
	@JsonPropertyDescription("The size of the picture in bytes.")
	private long size;

	@JsonProperty(required = true)
	@JsonPropertyDescription("URL the picture bytes can be loaded from.")
	private String url;

	public UserAvatarResponse() {
	}

	public String getFilename() {
		return filename;
	}

	public UserAvatarResponse setFilename(String filename) {
		this.filename = filename;
		return this;
	}

	public String getMimeType() {
		return mimeType;
	}

	public UserAvatarResponse setMimeType(String mimeType) {
		this.mimeType = mimeType;
		return this;
	}

	public long getSize() {
		return size;
	}

	public UserAvatarResponse setSize(long size) {
		this.size = size;
		return this;
	}

	public String getUrl() {
		return url;
	}

	public UserAvatarResponse setUrl(String url) {
		this.url = url;
		return this;
	}

	@Override
	public UserAvatarResponse self() {
		return this;
	}
}
