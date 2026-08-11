package io.metaloom.loom.db.jooq.dao.share;

import java.time.Instant;
import java.util.UUID;

import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.share.Share;

public class ShareImpl extends AbstractEditableElement<Share> implements Share {

	private String slug;
	private String targetType;
	private UUID assetUuid;
	private UUID collectionUuid;
	private String passwordHash;
	private Instant expiresAt;
	private Boolean allowDownload;
	private Boolean showMetadata;
	private Boolean allowComments;
	private Boolean allowReactions;
	private Boolean allowAnnotations;
	private String visitorName;
	private Instant firstVisitedAt;
	private Instant lastViewedAt;
	private Integer viewCount;

	@Override
	public String getSlug() {
		return slug;
	}

	@Override
	public Share setSlug(String slug) {
		this.slug = slug;
		return this;
	}

	@Override
	public String getTargetType() {
		return targetType;
	}

	@Override
	public Share setTargetType(String targetType) {
		this.targetType = targetType;
		return this;
	}

	@Override
	public UUID getAssetUuid() {
		return assetUuid;
	}

	@Override
	public Share setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	@Override
	public UUID getCollectionUuid() {
		return collectionUuid;
	}

	@Override
	public Share setCollectionUuid(UUID collectionUuid) {
		this.collectionUuid = collectionUuid;
		return this;
	}

	@Override
	public String getPasswordHash() {
		return passwordHash;
	}

	@Override
	public Share setPasswordHash(String passwordHash) {
		this.passwordHash = passwordHash;
		return this;
	}

	@Override
	public Instant getExpiresAt() {
		return expiresAt;
	}

	@Override
	public Share setExpiresAt(Instant expiresAt) {
		this.expiresAt = expiresAt;
		return this;
	}

	@Override
	public Boolean getAllowDownload() {
		return allowDownload;
	}

	@Override
	public Share setAllowDownload(Boolean allowDownload) {
		this.allowDownload = allowDownload;
		return this;
	}

	@Override
	public Boolean getShowMetadata() {
		return showMetadata;
	}

	@Override
	public Share setShowMetadata(Boolean showMetadata) {
		this.showMetadata = showMetadata;
		return this;
	}

	@Override
	public Boolean getAllowComments() {
		return allowComments;
	}

	@Override
	public Share setAllowComments(Boolean allowComments) {
		this.allowComments = allowComments;
		return this;
	}

	@Override
	public Boolean getAllowReactions() {
		return allowReactions;
	}

	@Override
	public Share setAllowReactions(Boolean allowReactions) {
		this.allowReactions = allowReactions;
		return this;
	}

	@Override
	public Boolean getAllowAnnotations() {
		return allowAnnotations;
	}

	@Override
	public Share setAllowAnnotations(Boolean allowAnnotations) {
		this.allowAnnotations = allowAnnotations;
		return this;
	}

	@Override
	public String getVisitorName() {
		return visitorName;
	}

	@Override
	public Share setVisitorName(String visitorName) {
		this.visitorName = visitorName;
		return this;
	}

	@Override
	public Instant getFirstVisitedAt() {
		return firstVisitedAt;
	}

	@Override
	public Share setFirstVisitedAt(Instant firstVisitedAt) {
		this.firstVisitedAt = firstVisitedAt;
		return this;
	}

	@Override
	public Instant getLastViewedAt() {
		return lastViewedAt;
	}

	@Override
	public Share setLastViewedAt(Instant lastViewedAt) {
		this.lastViewedAt = lastViewedAt;
		return this;
	}

	@Override
	public Integer getViewCount() {
		return viewCount;
	}

	@Override
	public Share setViewCount(Integer viewCount) {
		this.viewCount = viewCount;
		return this;
	}
}
