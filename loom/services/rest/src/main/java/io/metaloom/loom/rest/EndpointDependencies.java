package io.metaloom.loom.rest;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import io.metaloom.loom.auth.LoomAuthenticationHandler;
import io.metaloom.loom.auth.jwt.MediaTokenAuthHandler;
import io.metaloom.loom.rest.dagger.RestComponent;
import io.metaloom.loom.rest.dagger.RestComponent.Builder;
import io.metaloom.vertx.router.ApiRouter;
import io.vertx.core.Vertx;

@Singleton
public class EndpointDependencies {

	public final Vertx vertx;
	public final ApiRouter router;
	public final LoomAuthenticationHandler authHandler;
	/**
	 * Accepts an asset-scoped {@code ?mt=} token, for routes an {@code <img>} or {@code <video>} element reaches.
	 *
	 * <p>
	 * Mounted by hand, before {@code secure(...)}, on those routes only - see {@code AbstractEndpoint#secureMedia}. It is deliberately not part of
	 * {@code authHandler}: a credential that travels in a URL must not authenticate the rest of the API.
	 * </p>
	 */
	public final MediaTokenAuthHandler mediaTokenHandler;
	public final Provider<Builder> restComponentProvider;

	@Inject
	public EndpointDependencies(Vertx vertx, @Named("restApiRouter") ApiRouter router, Provider<RestComponent.Builder> restComponentProvider,
		LoomAuthenticationHandler authHandler, MediaTokenAuthHandler mediaTokenHandler) {
		this.vertx = vertx;
		this.router = router;
		this.authHandler = authHandler;
		this.mediaTokenHandler = mediaTokenHandler;
		this.restComponentProvider = restComponentProvider;
	}
}
