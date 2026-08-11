package io.metaloom.loom.rest.model.share;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestRequestModel;
import io.metaloom.loom.rest.model.common.AbstractMetaModel;

/**
 * Change a share link: its expiry, its password, or what the visitor may do.
 *
 * <p>
 * Absent fields are left alone, as everywhere else in this API. The password is the one field where "absent" and "null" have to mean different
 * things - leaving it out keeps the current password, sending an explicit null removes it - so it carries {@link #getRemovePassword()} rather than
 * relying on a null the Java client physically cannot send ({@code LoomJson.mapper} is configured {@code NON_NULL}).
 * </p>
 *
 * <p>
 * The target cannot be changed. Repointing an existing link at different material would silently hand whoever already has the URL something they were
 * never given.
 * </p>
 */
public class ShareUpdateRequest extends AbstractMetaModel<ShareUpdateRequest> implements RestRequestModel {

	@JsonPropertyDescription("A new password, in clear. Absent leaves the current one unchanged.")
	private String password;

	@JsonPropertyDescription("Set true to make the link open. Wins over any password sent in the same request.")
	private Boolean removePassword;

	@JsonPropertyDescription("A new expiry date. Absent leaves it unchanged; use clearExpiry to make the link permanent.")
	private Instant expiresAt;

	@JsonPropertyDescription("Set true to remove the expiry date, making the link permanent.")
	private Boolean clearExpiry;

	private Boolean allowDownload;

	private Boolean showMetadata;

	private Boolean allowComments;

	private Boolean allowReactions;

	private Boolean allowAnnotations;

	public String getPassword() {
		return password;
	}

	public ShareUpdateRequest setPassword(String password) {
		this.password = password;
		return this;
	}

	public Boolean getRemovePassword() {
		return removePassword;
	}

	public ShareUpdateRequest setRemovePassword(Boolean removePassword) {
		this.removePassword = removePassword;
		return this;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public ShareUpdateRequest setExpiresAt(Instant expiresAt) {
		this.expiresAt = expiresAt;
		return this;
	}

	public Boolean getClearExpiry() {
		return clearExpiry;
	}

	public ShareUpdateRequest setClearExpiry(Boolean clearExpiry) {
		this.clearExpiry = clearExpiry;
		return this;
	}

	public Boolean getAllowDownload() {
		return allowDownload;
	}

	public ShareUpdateRequest setAllowDownload(Boolean allowDownload) {
		this.allowDownload = allowDownload;
		return this;
	}

	public Boolean getShowMetadata() {
		return showMetadata;
	}

	public ShareUpdateRequest setShowMetadata(Boolean showMetadata) {
		this.showMetadata = showMetadata;
		return this;
	}

	public Boolean getAllowComments() {
		return allowComments;
	}

	public ShareUpdateRequest setAllowComments(Boolean allowComments) {
		this.allowComments = allowComments;
		return this;
	}

	public Boolean getAllowReactions() {
		return allowReactions;
	}

	public ShareUpdateRequest setAllowReactions(Boolean allowReactions) {
		this.allowReactions = allowReactions;
		return this;
	}

	public Boolean getAllowAnnotations() {
		return allowAnnotations;
	}

	public ShareUpdateRequest setAllowAnnotations(Boolean allowAnnotations) {
		this.allowAnnotations = allowAnnotations;
		return this;
	}

	@Override
	public ShareUpdateRequest self() {
		return this;
	}
}
