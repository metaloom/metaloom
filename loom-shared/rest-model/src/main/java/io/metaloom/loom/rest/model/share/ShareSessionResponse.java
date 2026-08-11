package io.metaloom.loom.rest.model.share;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.common.AbstractResponse;

/**
 * A redeemed share session: the token to send on subsequent requests, and everything the viewer needs to render itself.
 *
 * <p>
 * The token is opaque and carries no authority of its own - it only proves the password was satisfied. What the visitor may actually do is re-read
 * from the share row on every request, which is why the capability flags here are a convenience for the UI rather than the enforcement point.
 * </p>
 */
public class ShareSessionResponse extends AbstractResponse<ShareSessionResponse> {

	@JsonPropertyDescription("Opaque session token. Send as the X-Loom-Share-Session header. Also set as an HttpOnly cookie so media elements, "
		+ "which cannot carry headers, can load the bytes.")
	private String sessionToken;

	@JsonPropertyDescription("When the session token stops being accepted. The link's own expiry, when earlier, still wins.")
	private Instant sessionExpiresAt;

	@JsonPropertyDescription("The name this link is known by - the one just given, or the one stored from an earlier visit.")
	private String visitorName;

	@JsonPropertyDescription("What is behind the link: ASSET or COLLECTION.")
	private String targetType;

	@JsonPropertyDescription("Name of the shared asset or collection, for the viewer's heading.")
	private String targetName;

	@JsonPropertyDescription("Description of the shared collection, when it has one.")
	private String targetDescription;

	private Boolean allowDownload;

	private Boolean showMetadata;

	private Boolean allowComments;

	private Boolean allowReactions;

	private Boolean allowAnnotations;

	public String getSessionToken() {
		return sessionToken;
	}

	public ShareSessionResponse setSessionToken(String sessionToken) {
		this.sessionToken = sessionToken;
		return this;
	}

	public Instant getSessionExpiresAt() {
		return sessionExpiresAt;
	}

	public ShareSessionResponse setSessionExpiresAt(Instant sessionExpiresAt) {
		this.sessionExpiresAt = sessionExpiresAt;
		return this;
	}

	public String getVisitorName() {
		return visitorName;
	}

	public ShareSessionResponse setVisitorName(String visitorName) {
		this.visitorName = visitorName;
		return this;
	}

	public String getTargetType() {
		return targetType;
	}

	public ShareSessionResponse setTargetType(String targetType) {
		this.targetType = targetType;
		return this;
	}

	public String getTargetName() {
		return targetName;
	}

	public ShareSessionResponse setTargetName(String targetName) {
		this.targetName = targetName;
		return this;
	}

	public String getTargetDescription() {
		return targetDescription;
	}

	public ShareSessionResponse setTargetDescription(String targetDescription) {
		this.targetDescription = targetDescription;
		return this;
	}

	public Boolean getAllowDownload() {
		return allowDownload;
	}

	public ShareSessionResponse setAllowDownload(Boolean allowDownload) {
		this.allowDownload = allowDownload;
		return this;
	}

	public Boolean getShowMetadata() {
		return showMetadata;
	}

	public ShareSessionResponse setShowMetadata(Boolean showMetadata) {
		this.showMetadata = showMetadata;
		return this;
	}

	public Boolean getAllowComments() {
		return allowComments;
	}

	public ShareSessionResponse setAllowComments(Boolean allowComments) {
		this.allowComments = allowComments;
		return this;
	}

	public Boolean getAllowReactions() {
		return allowReactions;
	}

	public ShareSessionResponse setAllowReactions(Boolean allowReactions) {
		this.allowReactions = allowReactions;
		return this;
	}

	public Boolean getAllowAnnotations() {
		return allowAnnotations;
	}

	public ShareSessionResponse setAllowAnnotations(Boolean allowAnnotations) {
		this.allowAnnotations = allowAnnotations;
		return this;
	}

	@Override
	public ShareSessionResponse self() {
		return this;
	}
}
