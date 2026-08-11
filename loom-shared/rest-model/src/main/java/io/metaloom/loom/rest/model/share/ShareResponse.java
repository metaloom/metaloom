package io.metaloom.loom.rest.model.share;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.common.AbstractCreatorEditorRestResponse;

/**
 * One share link, as its owner sees it.
 */
public class ShareResponse extends AbstractCreatorEditorRestResponse<ShareResponse> implements ShareModel<ShareResponse> {

	@JsonPropertyDescription("The public half of the link. Append it to the share URL to open the customer view.")
	private String slug;

	@JsonPropertyDescription("Absolute URL of the customer view, assembled from the request's own host so it is pasteable as-is.")
	private String url;

	@JsonPropertyDescription("What is shared: ASSET or COLLECTION.")
	private String targetType;

	@JsonPropertyDescription("The uuid of the shared asset or collection.")
	private UUID targetUuid;

	@JsonPropertyDescription("Human readable name of the shared asset or collection, so a list of links is readable without a second request.")
	private String targetName;

	/**
	 * The generated password, in clear.
	 *
	 * <p>
	 * Present <b>only</b> on the response to the request that set it, because only the bcrypt hash is stored. A client that loses it has to set a new
	 * one; there is no route that can return it again.
	 * </p>
	 */
	@JsonProperty(required = false)
	@JsonPropertyDescription("The link password in clear. Returned only once, in the response to the request that set it - it is stored hashed.")
	private String password;

	@JsonPropertyDescription("Whether opening this link requires a password.")
	private Boolean passwordProtected;

	@JsonPropertyDescription("Whether the expiry date has passed. A lapsed link answers 404 to its visitors.")
	private Boolean expired;

	private Instant expiresAt;

	private Boolean allowDownload;

	private Boolean showMetadata;

	private Boolean allowComments;

	private Boolean allowReactions;

	private Boolean allowAnnotations;

	@JsonPropertyDescription("The name the first visitor gave. Null until somebody opens the link.")
	private String visitorName;

	private Instant firstVisitedAt;

	private Instant lastViewedAt;

	@JsonPropertyDescription("How many times the link has been opened. Counted once per visit, not per request.")
	private Integer viewCount;

	@JsonPropertyDescription("How many comments, annotations and reactions the visitor has left.")
	private Integer feedbackCount;

	public String getSlug() {
		return slug;
	}

	public ShareResponse setSlug(String slug) {
		this.slug = slug;
		return this;
	}

	public String getUrl() {
		return url;
	}

	public ShareResponse setUrl(String url) {
		this.url = url;
		return this;
	}

	public String getTargetType() {
		return targetType;
	}

	public ShareResponse setTargetType(String targetType) {
		this.targetType = targetType;
		return this;
	}

	public UUID getTargetUuid() {
		return targetUuid;
	}

	public ShareResponse setTargetUuid(UUID targetUuid) {
		this.targetUuid = targetUuid;
		return this;
	}

	public String getTargetName() {
		return targetName;
	}

	public ShareResponse setTargetName(String targetName) {
		this.targetName = targetName;
		return this;
	}

	public String getPassword() {
		return password;
	}

	public ShareResponse setPassword(String password) {
		this.password = password;
		return this;
	}

	public Boolean getPasswordProtected() {
		return passwordProtected;
	}

	public ShareResponse setPasswordProtected(Boolean passwordProtected) {
		this.passwordProtected = passwordProtected;
		return this;
	}

	public Boolean getExpired() {
		return expired;
	}

	public ShareResponse setExpired(Boolean expired) {
		this.expired = expired;
		return this;
	}

	@Override
	public Instant getExpiresAt() {
		return expiresAt;
	}

	@Override
	public ShareResponse setExpiresAt(Instant expiresAt) {
		this.expiresAt = expiresAt;
		return this;
	}

	@Override
	public Boolean getAllowDownload() {
		return allowDownload;
	}

	@Override
	public ShareResponse setAllowDownload(Boolean allowDownload) {
		this.allowDownload = allowDownload;
		return this;
	}

	@Override
	public Boolean getShowMetadata() {
		return showMetadata;
	}

	@Override
	public ShareResponse setShowMetadata(Boolean showMetadata) {
		this.showMetadata = showMetadata;
		return this;
	}

	@Override
	public Boolean getAllowComments() {
		return allowComments;
	}

	@Override
	public ShareResponse setAllowComments(Boolean allowComments) {
		this.allowComments = allowComments;
		return this;
	}

	@Override
	public Boolean getAllowReactions() {
		return allowReactions;
	}

	@Override
	public ShareResponse setAllowReactions(Boolean allowReactions) {
		this.allowReactions = allowReactions;
		return this;
	}

	@Override
	public Boolean getAllowAnnotations() {
		return allowAnnotations;
	}

	@Override
	public ShareResponse setAllowAnnotations(Boolean allowAnnotations) {
		this.allowAnnotations = allowAnnotations;
		return this;
	}

	public String getVisitorName() {
		return visitorName;
	}

	public ShareResponse setVisitorName(String visitorName) {
		this.visitorName = visitorName;
		return this;
	}

	public Instant getFirstVisitedAt() {
		return firstVisitedAt;
	}

	public ShareResponse setFirstVisitedAt(Instant firstVisitedAt) {
		this.firstVisitedAt = firstVisitedAt;
		return this;
	}

	public Instant getLastViewedAt() {
		return lastViewedAt;
	}

	public ShareResponse setLastViewedAt(Instant lastViewedAt) {
		this.lastViewedAt = lastViewedAt;
		return this;
	}

	public Integer getViewCount() {
		return viewCount;
	}

	public ShareResponse setViewCount(Integer viewCount) {
		this.viewCount = viewCount;
		return this;
	}

	public Integer getFeedbackCount() {
		return feedbackCount;
	}

	public ShareResponse setFeedbackCount(Integer feedbackCount) {
		this.feedbackCount = feedbackCount;
		return this;
	}

	@Override
	public ShareResponse self() {
		return this;
	}
}
