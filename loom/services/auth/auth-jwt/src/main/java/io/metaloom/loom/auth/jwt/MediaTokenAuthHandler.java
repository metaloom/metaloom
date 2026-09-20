package io.metaloom.loom.auth.jwt;

import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.Handler;
import io.vertx.ext.auth.authentication.TokenCredentials;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.impl.UserContextInternal;

/**
 * Accepts a short-lived, asset-scoped media token from the <code>?mt=</code> query parameter.
 *
 * <p>
 * <b>Why this exists.</b> An <code>&lt;img&gt;</code> or <code>&lt;video&gt;</code> element cannot send an <code>Authorization</code> header, so
 * media URLs had only the session cookie to authenticate with - and that cookie is <code>Secure</code> and <code>__Host-</code> prefixed, so a
 * browser drops it on any plain-HTTP deployment. The result was a 401 on every preview and a placeholder icon in place of every thumbnail, with
 * nothing in the UI to say why.
 * </p>
 *
 * <p>
 * <b>What keeps it narrow.</b> Three things, and all three matter:
 * </p>
 * <ul>
 * <li>It is mounted only on the media routes. Nothing else in the API looks at <code>?mt=</code>.</li>
 * <li>The token names one asset, and the handler compares that against the asset in the path. A token minted for one asset cannot read another.</li>
 * <li>It carries <code>scope=media</code>, and {@link LoomJWTAuthHandlerImpl} refuses any token carrying that claim - so a media URL that leaks
 * into a log or a chat cannot be replayed as a session.</li>
 * </ul>
 *
 * <p>
 * On anything other than a valid token this handler simply calls {@code next()} rather than rejecting: the ordinary auth handler runs after it and
 * owns the cookie, the bearer header and the 401. A missing or bad {@code mt} is therefore indistinguishable from not having tried one.
 * </p>
 */
@Singleton
public class MediaTokenAuthHandler implements Handler<RoutingContext> {

	private static final Logger log = LoggerFactory.getLogger(MediaTokenAuthHandler.class);

	/** Query parameter carrying the token. Short because it rides in every poster URL on a grid. */
	public static final String QUERY_PARAM = "mt";

	/** Claim naming what the token may be used for. */
	public static final String CLAIM_SCOPE = "scope";

	/** Claim naming the single asset the token grants access to. */
	public static final String CLAIM_ASSET = "asset";

	/** The only scope this handler accepts, and the one the session handler refuses. */
	public static final String SCOPE_MEDIA = "media";

	/** Path parameter holding the asset uuid on every route this handler is mounted on. */
	private static final String ASSET_PATH_PARAM = "uuid";

	private final JWTAuth authProvider;

	@Inject
	public MediaTokenAuthHandler(JWTAuth authProvider) {
		this.authProvider = authProvider;
	}

	@Override
	public void handle(RoutingContext context) {
		if (context.user() != null) {
			context.next();
			return;
		}
		String token = context.request().getParam(QUERY_PARAM);
		if (token == null || token.isBlank()) {
			context.next();
			return;
		}

		authProvider.authenticate(new TokenCredentials(token))
			.onSuccess(user -> {
				if (!SCOPE_MEDIA.equals(user.principal().getString(CLAIM_SCOPE))) {
					// A session token in the query string. Refused rather than honoured: accepting it
					// here would turn every media URL into a way to smuggle a full session.
					log.warn("Refused a non-media token presented as a media token.");
					context.next();
					return;
				}
				if (!matchesRequestedAsset(context, user.principal().getString(CLAIM_ASSET))) {
					log.warn("Refused a media token minted for a different asset.");
					context.next();
					return;
				}
				((UserContextInternal) context.userContext()).setUser(user);
				context.next();
			})
			.onFailure(err -> {
				if (log.isDebugEnabled()) {
					log.debug("Media token rejected", err);
				}
				context.next();
			});
	}

	/**
	 * Whether the token's asset is the asset being requested.
	 *
	 * <p>
	 * Compared as {@link UUID} rather than as text, so a differently-cased or differently-formatted uuid is still the same asset - and an
	 * unparseable one is a mismatch rather than an accidental match.
	 * </p>
	 */
	private boolean matchesRequestedAsset(RoutingContext context, String tokenAsset) {
		String requested = context.pathParam(ASSET_PATH_PARAM);
		if (tokenAsset == null || requested == null) {
			return false;
		}
		try {
			return UUID.fromString(tokenAsset).equals(UUID.fromString(requested));
		} catch (IllegalArgumentException e) {
			return false;
		}
	}
}
