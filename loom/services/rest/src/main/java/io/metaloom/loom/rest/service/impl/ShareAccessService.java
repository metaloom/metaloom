package io.metaloom.loom.rest.service.impl;

import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.share.Share;
import io.metaloom.loom.db.model.share.ShareTargetType;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.vertx.core.http.Cookie;

/**
 * The authorization boundary for everything an unauthenticated share visitor can reach.
 *
 * <p>
 * The public share routes are the only ones in the API that are not behind {@code secure()}, so there is no
 * {@code LoomAuthenticationHandler} in front of them, no {@code rc.user()}, and {@code checkPerm} is not merely unnecessary but impossible - there is
 * nobody to check permissions for. Every one of those routes therefore begins with a call into this class, and this class is where the entire
 * question of "may they?" is answered.
 * </p>
 *
 * <p>
 * Three checks, in this order, on <b>every</b> request:
 * </p>
 * <ol>
 * <li><b>The link exists and has not lapsed.</b> Re-read from the database each time rather than trusted from the token, so revoking a share or
 * shortening its expiry takes effect on the next request instead of when the last issued token happens to run out.</li>
 * <li><b>The session token is ours, is for this link, and is unexpired.</b> See {@link ShareSessionTokens} for why it is not a JWT.</li>
 * <li><b>The addressed asset actually belongs to the link.</b> This is the one that matters most. Without it a slug would be a read capability over
 * every asset in the installation, because the asset uuid arrives in the path and nothing else in the request constrains it.</li>
 * </ol>
 *
 * <p>
 * Unknown, deleted and expired links all answer <b>404</b>, never 403 or 410. A distinct status for "this used to exist" would turn the endpoint into
 * an oracle telling anyone with a list of guesses which slugs were ever real.
 * </p>
 */
@Singleton
public class ShareAccessService {

	/**
	 * Header carrying the session token for {@code fetch} calls.
	 */
	public static final String SESSION_HEADER = "X-Loom-Share-Session";

	/**
	 * Cookie carrying the same token, for {@code <img>} and {@code <video>} elements, which cannot set headers.
	 *
	 * <p>
	 * Deliberately <b>not</b> {@code __Host-} prefixed, unlike {@code __Host-loom_token}. That prefix requires the {@code Secure} attribute, and the
	 * demo container and the dev server both run plain HTTP - a share viewer that only worked behind TLS would be untestable in exactly the setups
	 * people try it in first. It is scoped to {@link #SESSION_COOKIE_PATH} instead, so it is never sent to any other part of the API.
	 * </p>
	 */
	public static final String SESSION_COOKIE = "loom_share_session";

	/** The cookie is confined to the public share routes; nothing else should ever see it. */
	public static final String SESSION_COOKIE_PATH = "/api/v1/shares";

	private final DaoCollection daos;
	private final ShareSessionTokens tokens;

	@Inject
	public ShareAccessService(DaoCollection daos, ShareSessionTokens tokens) {
		this.daos = daos;
		this.tokens = tokens;
	}

	public ShareSessionTokens tokens() {
		return tokens;
	}

	/**
	 * Resolve a slug to a live share, or fail with 404.
	 *
	 * <p>
	 * Used by the pre-authentication challenge route as well as by {@link #requireSession(LoomRoutingContext, String)}, which is why it does not look
	 * at the session token.
	 * </p>
	 */
	public Share requireShare(String slug) {
		Share share = slug == null ? null : daos.shareDao().loadBySlug(slug);
		if (share == null || share.isExpired()) {
			// One answer for "never existed", "revoked" and "lapsed". See the class comment.
			throw notFound();
		}
		return share;
	}

	/**
	 * Resolve the share and require a valid session for it.
	 *
	 * @return the share, guaranteed live and unlocked for this caller
	 */
	public Share requireSession(LoomRoutingContext lrc, String slug) {
		Share share = requireShare(slug);
		String token = sessionToken(lrc);
		if (!tokens.isValid(token, slug)) {
			throw new LoomRestException(401, LoomRestErrorCode.BAD_REQUEST,
				"This share session is missing or has expired. Open the link again.");
		}
		return share;
	}

	/**
	 * Resolve the share, require a session, and require that the named asset is part of what was shared.
	 *
	 * <p>
	 * <b>The single most important check in the feature.</b> A share of one asset admits exactly that asset; a share of a collection admits its
	 * current members, resolved live so that removing an asset from a collection also removes it from every link to that collection.
	 * </p>
	 */
	public Share requireAssetAccess(LoomRoutingContext lrc, String slug, UUID assetUuid) {
		Share share = requireSession(lrc, slug);
		requireMembership(share, assetUuid);
		return share;
	}

	/**
	 * Whether the asset is part of the share, throwing 404 when it is not.
	 *
	 * <p>
	 * 404 rather than 403, for the same reason as an unknown slug: answering "forbidden" would confirm that the asset exists, turning a share link
	 * into a probe for the uuids of material the visitor was never shown.
	 * </p>
	 */
	public void requireMembership(Share share, UUID assetUuid) {
		if (assetUuid == null) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST, "An asset uuid must be given");
		}
		if (!isMember(share, assetUuid)) {
			throw notFound();
		}
	}

	/**
	 * Whether the asset is behind this link.
	 */
	public boolean isMember(Share share, UUID assetUuid) {
		if (assetUuid == null) {
			return false;
		}
		if (share.targetType() == ShareTargetType.ASSET) {
			return assetUuid.equals(share.getAssetUuid());
		}
		if (share.getCollectionUuid() == null) {
			return false;
		}
		// Live membership, not a snapshot taken when the link was made: an asset pulled out of the collection
		// stops being visible through every link to that collection, which is what an editor expects removal to mean.
		return daos.collectionDao().containsAsset(share.getCollectionUuid(), assetUuid);
	}

	/**
	 * Load an asset that the visitor is allowed to see, or fail with 404.
	 */
	public Asset requireAsset(Share share, UUID assetUuid) {
		requireMembership(share, assetUuid);
		Asset asset = daos.assetDao().load(assetUuid);
		if (asset == null) {
			throw notFound();
		}
		return asset;
	}

	/**
	 * Fail with 403 when the share does not grant the named capability.
	 *
	 * <p>
	 * 403 here rather than 404, and the difference is deliberate: the visitor is allowed to know this link exists - they are looking at it - they are
	 * simply not allowed to comment through it. Hiding that would leave the UI unable to explain why its own button did nothing.
	 * </p>
	 *
	 * @param allowed
	 *            the flag off the share row
	 * @param capability
	 *            what was attempted, for the message
	 */
	public void requireCapability(Boolean allowed, String capability) {
		if (!Boolean.TRUE.equals(allowed)) {
			throw new LoomRestException(403, LoomRestErrorCode.MISSING_PERM,
				"This share link does not allow " + capability + ".");
		}
	}

	/**
	 * The session token, from the header if present and the cookie otherwise.
	 *
	 * <p>
	 * Header first: it is the explicit channel, used by every {@code fetch} the viewer makes. The cookie exists for media elements, which cannot carry
	 * a header at all.
	 * </p>
	 */
	public String sessionToken(LoomRoutingContext lrc) {
		String header = lrc.routingContext().request().getHeader(SESSION_HEADER);
		if (header != null && !header.isBlank()) {
			return header;
		}
		Cookie cookie = lrc.routingContext().request().getCookie(SESSION_COOKIE);
		return cookie == null ? null : cookie.getValue();
	}

	private LoomRestException notFound() {
		return new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "This share link is not available.");
	}
}
