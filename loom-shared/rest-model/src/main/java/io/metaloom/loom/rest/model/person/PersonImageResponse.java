package io.metaloom.loom.rest.model.person;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.common.AbstractCreatorEditorRestResponse;

/**
 * One picture belonging to a person.
 *
 * <p>
 * Person images are stored independently of any asset, so a person keeps their pictures when the material they were found in is deleted. The caller
 * never has to know how the bytes are addressed: {@link #getUrl()} is the whole contract.
 * </p>
 */
public class PersonImageResponse extends AbstractCreatorEditorRestResponse<PersonImageResponse> {

	@JsonProperty(required = true)
	@JsonPropertyDescription("The original filename of the image.")
	private String filename;

	@JsonProperty(required = true)
	@JsonPropertyDescription("The mime type of the image.")
	private String mimeType;

	@JsonProperty(required = true)
	@JsonPropertyDescription("The size of the image in bytes.")
	private long size;

	@JsonProperty(required = true)
	@JsonPropertyDescription("URL the image bytes can be loaded from.")
	private String url;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Whether this image is the person's avatar.")
	private boolean avatar;

	public PersonImageResponse() {
	}

	public String getFilename() {
		return filename;
	}

	public PersonImageResponse setFilename(String filename) {
		this.filename = filename;
		return this;
	}

	public String getMimeType() {
		return mimeType;
	}

	public PersonImageResponse setMimeType(String mimeType) {
		this.mimeType = mimeType;
		return this;
	}

	public long getSize() {
		return size;
	}

	public PersonImageResponse setSize(long size) {
		this.size = size;
		return this;
	}

	public String getUrl() {
		return url;
	}

	public PersonImageResponse setUrl(String url) {
		this.url = url;
		return this;
	}

	public boolean isAvatar() {
		return avatar;
	}

	public PersonImageResponse setAvatar(boolean avatar) {
		this.avatar = avatar;
		return this;
	}

	@Override
	public PersonImageResponse self() {
		return this;
	}
}
