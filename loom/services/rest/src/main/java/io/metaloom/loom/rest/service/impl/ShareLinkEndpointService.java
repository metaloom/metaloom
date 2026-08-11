package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.CREATE_SHARE;
import static io.metaloom.loom.db.model.perm.Permission.DELETE_SHARE;
import static io.metaloom.loom.db.model.perm.Permission.READ_ASSET;
import static io.metaloom.loom.db.model.perm.Permission.READ_COLLECTION;
import static io.metaloom.loom.db.model.perm.Permission.READ_SHARE;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_SHARE;

import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.auth.AuthenticationService;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.collection.Collection;
import io.metaloom.loom.db.model.share.Share;
import io.metaloom.loom.db.model.share.ShareDao;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.db.model.share.ShareTargetType;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.builder.ShareModelBuilder;
import io.metaloom.loom.rest.model.share.ShareCreateRequest;
import io.metaloom.loom.rest.model.share.ShareResponse;
import io.metaloom.loom.rest.model.share.ShareUpdateRequest;
import io.metaloom.loom.rest.parameter.PagingParameters;
import io.metaloom.loom.rest.service.AbstractCRUDEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.vertx.core.http.HttpServerRequest;

/**
 * The owner-facing half of sharing: creating, listing, changing and revoking links.
 *
 * <p>
 * Everything here runs behind {@code secure()} and checks a {@code *_SHARE} permission. The visitor-facing half is
 * {@link PublicShareEndpointService}, which has no permissions at all and authorizes from the share row instead.
 * </p>
 */
@Singleton
public class ShareLinkEndpointService extends AbstractCRUDEndpointService<ShareDao, Share> {

	/**
	 * How many times to retry slug generation before giving up.
	 *
	 * <p>
	 * With 128 bits of entropy a collision is not a thing that happens; the retry exists so that if one ever does, it costs a second round trip rather
	 * than surfacing a unique-constraint violation as a 500.
	 * </p>
	 */
	private static final int SLUG_ATTEMPTS = 5;

	private final AuthenticationService authService;
	private final ShareSessionTokens tokens;

	@Inject
	public ShareLinkEndpointService(ShareDao shareDao, DaoCollection daos, LoomModelBuilder modelBuilder, LoomModelValidator validator,
		AuthenticationService authService, ShareSessionTokens tokens) {
		super(shareDao, daos, modelBuilder, validator);
		this.authService = authService;
		this.tokens = tokens;
	}

	@Override
	public void create(LoomRoutingContext lrc) {
		ShareCreateRequest request = lrc.requestBody(ShareCreateRequest.class);
		validator.validate(request);

		// Sharing an asset requires being allowed to READ that asset, over and above CREATE_SHARE: publishing
		// material to the open internet must not be a way around not being allowed to look at it in the first
		// place. Which second permission applies depends on what is being shared, so it is resolved from the body.
		ShareTargetType targetType = ShareTargetType.parse(request.getTargetType());
		Permission targetPermission = targetType == ShareTargetType.COLLECTION ? READ_COLLECTION : READ_ASSET;

		checkPerms(lrc, () -> {
			UUID userUuid = lrc.userUuid();
			String slug = freshSlug();
			Share share;
			if (targetType == ShareTargetType.COLLECTION) {
				Collection collection = daos().collectionDao().load(request.getTargetUuid());
				if (collection == null) {
					throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Collection not found " + request.getTargetUuid());
				}
				share = dao().createCollectionShare(userUuid, collection.getUuid(), slug);
			} else {
				Asset asset = daos().assetDao().load(request.getTargetUuid());
				if (asset == null) {
					throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Asset not found " + request.getTargetUuid());
				}
				share = dao().createAssetShare(userUuid, asset.getUuid(), slug);
			}

			if (request.getPassword() != null) {
				share.setPasswordHash(authService.encodePassword(request.getPassword()));
			}
			share.setExpiresAt(request.getExpiresAt());
			update(request::getAllowDownload, share::setAllowDownload);
			update(request::getShowMetadata, share::setShowMetadata);
			update(request::getAllowComments, share::setAllowComments);
			update(request::getAllowReactions, share::setAllowReactions);
			update(request::getAllowAnnotations, share::setAllowAnnotations);
			update(request::getMeta, share::setMeta);

			dao().store(share);

			ShareResponse response = toAbsolute(lrc, modelBuilder.toResponse(share));
			// The one and only time the password leaves the server. It is stored as a bcrypt hash, so this response
			// is the sole opportunity to show it to the person who has to pass it on - which is also why the dialog
			// that creates a link must display it rather than hiding it behind a "reveal" the user may never press.
			response.setPassword(request.getPassword());
			lrc.send(response, 201);
		}, CREATE_SHARE, targetPermission);
	}

	@Override
	public void update(LoomRoutingContext lrc, UUID uuid) {
		update(lrc, UPDATE_SHARE, () -> {
			ShareUpdateRequest request = lrc.requestBody(ShareUpdateRequest.class);
			validator.validate(request);

			Share share = loadShare(uuid);

			// removePassword wins over password. Sending both is a confused client, and the safe reading of
			// "remove the password" is the one that does not leave a link protected by something unintended.
			if (Boolean.TRUE.equals(request.getRemovePassword())) {
				share.setPasswordHash(null);
			} else if (request.getPassword() != null) {
				share.setPasswordHash(authService.encodePassword(request.getPassword()));
			}

			if (Boolean.TRUE.equals(request.getClearExpiry())) {
				share.setExpiresAt(null);
			} else if (request.getExpiresAt() != null) {
				share.setExpiresAt(request.getExpiresAt());
			}

			update(request::getAllowDownload, share::setAllowDownload);
			update(request::getShowMetadata, share::setShowMetadata);
			update(request::getAllowComments, share::setAllowComments);
			update(request::getAllowReactions, share::setAllowReactions);
			update(request::getAllowAnnotations, share::setAllowAnnotations);
			update(request::getMeta, share::setMeta);
			setEditor(share, lrc.userUuid());
			return share;
		}, share -> toAbsolute(lrc, modelBuilder.toResponse(share)));
	}

	@Override
	public void load(LoomRoutingContext lrc, UUID uuid) {
		load(lrc, READ_SHARE, () -> dao().load(uuid), share -> toAbsolute(lrc, modelBuilder.toResponse(share)));
	}

	@Override
	public void list(LoomRoutingContext lrc) {
		list(lrc, READ_SHARE, page -> absolutise(lrc, modelBuilder.toShareList(page)));
	}

	@Override
	public void delete(LoomRoutingContext lrc, UUID uuid) {
		delete(lrc, DELETE_SHARE, uuid);
	}

	/**
	 * The links pointing at one asset.
	 */
	public void listByAsset(LoomRoutingContext lrc, UUID assetUuid) {
		checkPerm(lrc, READ_SHARE, () -> {
			PagingParameters paging = lrc.pagingParams();
			Page<Share> page = dao().loadPageByAsset(assetUuid, paging.from(), paging.limit());
			lrc.send(absolutise(lrc, modelBuilder.toShareList(page)));
		});
	}

	/**
	 * The links pointing at one collection.
	 */
	public void listByCollection(LoomRoutingContext lrc, UUID collectionUuid) {
		checkPerm(lrc, READ_SHARE, () -> {
			PagingParameters paging = lrc.pagingParams();
			Page<Share> page = dao().loadPageByCollection(collectionUuid, paging.from(), paging.limit());
			lrc.send(absolutise(lrc, modelBuilder.toShareList(page)));
		});
	}

	/**
	 * Everything the visitor said through one link.
	 */
	public void loadFeedback(LoomRoutingContext lrc, UUID uuid) {
		checkPerm(lrc, READ_SHARE, () -> {
			Share share = loadShare(uuid);
			lrc.send(modelBuilder.toShareFeedback(share));
		});
	}

	private Share loadShare(UUID uuid) {
		Share share = dao().load(uuid);
		if (share == null) {
			throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Share not found " + uuid);
		}
		return share;
	}

	private String freshSlug() {
		for (int i = 0; i < SLUG_ATTEMPTS; i++) {
			String slug = tokens.generateSlug();
			if (!dao().slugExists(slug)) {
				return slug;
			}
		}
		throw new LoomRestException(500, LoomRestErrorCode.INTERNAL_ERROR, "Could not generate a free share slug");
	}

	/**
	 * Turn the model builder's root-relative share path into an absolute URL.
	 *
	 * <p>
	 * Done here rather than in the builder because only the request knows which host the caller reached this server on. A link that is copied out of
	 * the dialog and pasted into an email is useless as a relative path, and hardcoding a configured base URL would break every install behind a proxy
	 * whose external name differs from its internal one - so the request's own {@code X-Forwarded-*} headers, then {@code Host}, are the source of
	 * truth. This mirrors what {@code LoomOpenAPI} does for the advertised server URL.
	 * </p>
	 */
	private ShareResponse toAbsolute(LoomRoutingContext lrc, ShareResponse response) {
		String base = externalBaseUrl(lrc);
		if (base != null && response.getUrl() != null && response.getUrl().startsWith("/")) {
			response.setUrl(base + response.getUrl());
		}
		return response;
	}

	private io.metaloom.loom.rest.model.share.ShareListResponse absolutise(LoomRoutingContext lrc,
		io.metaloom.loom.rest.model.share.ShareListResponse list) {
		list.getData().forEach(response -> toAbsolute(lrc, response));
		return list;
	}

	private String externalBaseUrl(LoomRoutingContext lrc) {
		HttpServerRequest request = lrc.routingContext().request();
		String proto = firstOf(request.getHeader("X-Forwarded-Proto"));
		String host = firstOf(request.getHeader("X-Forwarded-Host"));
		if (host == null) {
			host = request.getHeader("Host");
		}
		if (host == null) {
			// No Host header at all is an HTTP/1.0 client or a synthetic request. Leaving the URL relative is
			// better than inventing a hostname that would send the recipient somewhere that does not exist.
			return null;
		}
		if (proto == null) {
			proto = request.isSSL() ? "https" : "http";
		}
		return proto + "://" + host;
	}

	/**
	 * A forwarded header may carry a comma-separated chain; the first entry is the client-facing hop.
	 */
	private String firstOf(String headerValue) {
		if (headerValue == null || headerValue.isBlank()) {
			return null;
		}
		int comma = headerValue.indexOf(',');
		String value = comma == -1 ? headerValue : headerValue.substring(0, comma);
		return value.trim();
	}

	public ShareModelBuilder builder() {
		return modelBuilder;
	}
}
