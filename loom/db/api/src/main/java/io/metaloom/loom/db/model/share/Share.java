package io.metaloom.loom.db.model.share;

import java.time.Instant;
import java.util.UUID;

import io.metaloom.loom.db.CUDElement;
import io.metaloom.loom.db.MetaElement;

/**
 * A shareable link to one asset or one collection, openable without a Loom account.
 *
 * <p>
 * <b>This row is the authority.</b> The session token a visitor carries proves only that they satisfied the password once; every capability they have
 * is re-read from here on each request. Nothing about a share is baked into a token, which is what makes revoking one a plain
 * {@code DELETE}.
 * </p>
 *
 * <p>
 * {@link #getCreatorUuid()} is nullable, unlike every other {@link CUDElement} in the schema: deleting a user must not delete their shares, so the FK
 * is {@code ON DELETE SET NULL} and a link handed to a client outlives the editor who made it.
 * </p>
 *
 * @see ShareDao
 */
public interface Share extends CUDElement<Share>, MetaElement<Share> {

	/**
	 * The public half of the capability - 128 random bits, base64url-encoded. This is what appears in the URL, never the uuid.
	 */
	String getSlug();

	Share setSlug(String slug);

	/**
	 * {@code ASSET} or {@code COLLECTION}. Use {@link #targetType()} for the parsed form.
	 */
	String getTargetType();

	Share setTargetType(String targetType);

	default ShareTargetType targetType() {
		return ShareTargetType.parse(getTargetType());
	}

	default Share setTargetType(ShareTargetType type) {
		return setTargetType(type == null ? null : type.name());
	}

	/** Set when {@link #getTargetType()} is {@code ASSET}, null otherwise. */
	UUID getAssetUuid();

	Share setAssetUuid(UUID assetUuid);

	/** Set when {@link #getTargetType()} is {@code COLLECTION}, null otherwise. */
	UUID getCollectionUuid();

	Share setCollectionUuid(UUID collectionUuid);

	/**
	 * The bcrypt hash of the link password, or null when the link is open.
	 *
	 * <p>
	 * Never leaves the server. The plaintext is returned exactly once, in the response to the request that set it.
	 * </p>
	 */
	String getPasswordHash();

	Share setPasswordHash(String passwordHash);

	/** When the link stops working, or null for never. */
	Instant getExpiresAt();

	Share setExpiresAt(Instant expiresAt);

	/**
	 * Whether the visitor may fetch the original bytes as a download rather than only streaming them for playback.
	 */
	Boolean getAllowDownload();

	Share setAllowDownload(Boolean allowDownload);

	/** Whether the visitor sees title, description, size, duration and dimensions, or only the media itself. */
	Boolean getShowMetadata();

	Share setShowMetadata(Boolean showMetadata);

	Boolean getAllowComments();

	Share setAllowComments(Boolean allowComments);

	Boolean getAllowReactions();

	Share setAllowReactions(Boolean allowReactions);

	Boolean getAllowAnnotations();

	Share setAllowAnnotations(Boolean allowAnnotations);

	/**
	 * How the first visitor identified themselves, or null while nobody has opened the link.
	 *
	 * <p>
	 * Set once, on the first redeemed session, and never overwritten - so a second visitor cannot silently rename the first one's feedback. A visitor
	 * who declined the question is stored as the localised "Anonymous" rather than left null, which keeps "nobody has opened it" distinguishable from
	 * "somebody opened it and would not say who".
	 * </p>
	 */
	String getVisitorName();

	Share setVisitorName(String visitorName);

	Instant getFirstVisitedAt();

	Share setFirstVisitedAt(Instant firstVisitedAt);

	Instant getLastViewedAt();

	Share setLastViewedAt(Instant lastViewedAt);

	/** Incremented once per redeemed session, not per request. */
	Integer getViewCount();

	Share setViewCount(Integer viewCount);

	/**
	 * Whether the link has lapsed as of now.
	 *
	 * @return true when an expiry is set and has passed
	 */
	default boolean isExpired() {
		Instant expiry = getExpiresAt();
		return expiry != null && expiry.isBefore(Instant.now());
	}

	/**
	 * Whether opening this link requires a password.
	 */
	default boolean isPasswordProtected() {
		return getPasswordHash() != null;
	}

	/**
	 * The uuid of whatever this share points at, whichever kind it is.
	 */
	default UUID getTargetUuid() {
		return getAssetUuid() != null ? getAssetUuid() : getCollectionUuid();
	}
}
