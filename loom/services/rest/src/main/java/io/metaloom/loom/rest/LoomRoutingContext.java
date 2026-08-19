package io.metaloom.loom.rest;

import java.util.List;
import java.util.UUID;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.asset.AssetId;
import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.auth.LoomAuthorizationProvider;
import io.metaloom.loom.auth.LoomUser;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.graphql.GraphQLPermissionChecker;
import io.metaloom.loom.rest.json.LoomJson;
import io.metaloom.loom.rest.model.RestRequestModel;
import io.metaloom.loom.rest.model.RestResponseModel;
import io.metaloom.loom.rest.model.message.GenericMessageResponse;
import io.metaloom.loom.rest.parameter.FilterParameters;
import io.metaloom.loom.rest.parameter.PagingParameters;
import io.metaloom.loom.rest.parameter.SortParameters;
import io.metaloom.loom.rest.validation.ReplaceValidator;
import io.metaloom.loom.rest.validation.ValidationException;
import io.vertx.core.Future;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.auth.authorization.PermissionBasedAuthorization;
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.RoutingContext;

public class LoomRoutingContext {

	private static final Logger log = LoggerFactory.getLogger(LoomRoutingContext.class);

	private final RoutingContext rc;
	private final LoomAuthorizationProvider authorizationProvider;

	@Inject
	public LoomRoutingContext(RoutingContext rc, LoomAuthorizationProvider authorizationProvider) {
		this.rc = rc;
		this.authorizationProvider = authorizationProvider;
	}

	public <T extends RestRequestModel> T requestBody(Class<T> clazz) {
		T model = LoomJson.parse(rc.body().buffer(), clazz);
		return model;
	}

	/**
	 * Return the request body parsed as a raw {@link JsonObject}.
	 */
	public JsonObject bodyAsJson() {
		return rc.body().asJsonObject();
	}

	/**
	 * Assert that the request body contains every replaceable field of the given request model. Used for full replace (PUT) routes.
	 *
	 * @param clazz
	 * @throws ValidationException
	 *             When the body could not be parsed or is incomplete. Mapped to HTTP 400.
	 */
	public void requireFullBody(Class<? extends RestRequestModel> clazz) {
		JsonObject body;
		try {
			body = bodyAsJson();
		} catch (Exception e) {
			throw new ValidationException("The request body could not be parsed as a JSON object.");
		}
		ReplaceValidator.assertComplete(body, clazz);
	}

	public void send(RestResponseModel<?> response) {
		send(response, 200);
	}

	public void send(RestResponseModel<?> response, int statusCode) {
		rc.response().headers().set(HttpHeaders.CONTENT_TYPE, HTTPConstants.APPLICATION_JSON);
		rc.response().setStatusCode(statusCode).end(LoomJson.encodeToBuffer(response));
	}

	public void sendText(String body, String mimeType, int code) {
		rc.response().headers().set(HttpHeaders.CONTENT_TYPE, mimeType);
		rc.response().setStatusCode(code).end(body);
	}

	/**
	 * Send a 204 (No Content) response.
	 */
	public void sendNoContent() {
		rc.response().setStatusCode(204).end();
	}

	/**
	 * The id stamped onto this request by {@link TraceIdHandler}, already present as the {@code X-Trace-Id} response header.
	 *
	 * <p>
	 * Null only when no trace handler ran, which outside a hand-built test context does not happen.
	 * </p>
	 */
	public String traceId() {
		return TraceIdHandler.traceIdOf(rc);
	}

	public List<String> queryParam(String key) {
		return rc.queryParam(key);
	}

	public String pathParam(String key) {
		return rc.pathParam(key);
	}

	// TODO wrap into LoomUser for typesafe attr access
	public User user() {
		return rc.user();
	}

	public LoomUser loomUser() {
		return new LoomUser(user());
	}

	/**
	 * Resolve the current user's authorizations and return a synchronous {@link GraphQLPermissionChecker}.
	 *
	 * <p>Authorization loading is asynchronous (it may hit the database), so it is performed once here. The returned
	 * checker can then be evaluated synchronously for every field of a GraphQL query and is passed into the GraphQL
	 * execution context.</p>
	 *
	 * @return future that succeeds with a permission checker bound to the current user
	 */
	public Future<GraphQLPermissionChecker> permissionChecker() {
		User user = user();
		return authorizationProvider.getAuthorizations(user)
			.map(v -> (GraphQLPermissionChecker) perm -> PermissionBasedAuthorization.create(perm.name()).match(user));
	}

	/**
	 * Resolve the current user's authorizations once and return a synchronous, <b>non-throwing</b> predicate over permissions.
	 *
	 * <p>
	 * {@link #requirePerm(Permission...)} is all-or-nothing and {@code checkPerm} throws. Endpoints which have to ask "may this caller see X?" for
	 * several X and silently drop the ones they may not see - cross-entity search is the motivating case - need a boolean instead. Authorization loading
	 * is asynchronous, so it happens once here and the returned predicate can then be evaluated as often as needed.
	 * </p>
	 *
	 * @return future that succeeds with a permission predicate bound to the current user
	 */
	public Future<java.util.function.Predicate<Permission>> permissions() {
		User user = user();
		return authorizationProvider.getAuthorizations(user)
			.map(v -> (java.util.function.Predicate<Permission>) perm -> PermissionBasedAuthorization.create(perm.name()).match(user));
	}

	public Future<LoomRoutingContext> requirePerm(Permission... perms) {
		User user = user();
		LoomRoutingContext context = this;
		return authorizationProvider.getAuthorizations(user).flatMap(e -> {
			for (Permission perm : perms) {
				boolean hasPerm = PermissionBasedAuthorization.create(perm.name()).match(user);
				if (!hasPerm) {
					if (log.isDebugEnabled()) {
						log.debug("User is lacking permission {}", perm);
					}
					return Future.failedFuture("Missing permission " + perm.name());
				}
			}
			return Future.succeededFuture(context);
		});
	}

	public void error(String msg) {
		send(new GenericMessageResponse().setMessage(msg).setTraceId(traceId()), 500);
	}

	public UUID pathParamUUID(String key) {
		String val = pathParam(key);
		if (val == null) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_PATH_PARAMS, "Path parameter " + key + " not found");
		}
		return UUID.fromString(val);
	}

	public int pathParamInt(String key) {
		String val = pathParam(key);
		if (val == null) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_PATH_PARAMS, "Path parameter " + key + " not found");
		}
		return Integer.parseInt(val);
	}

	public AssetId pathParamAssetId(String key) {
		String val = pathParam(key);
		if (val == null) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_PATH_PARAMS, "Path parameter " + key + " not found");
		}
		return AssetId.assetId(val);
	}

	public int pageSize() {
		String limitStr = pathParam("limit");
		int limit = 25;
		if (limitStr != null) {
			limit = Integer.valueOf(limitStr);
		}
		return limit;
	}

	public UUID userUuid() {
		return loomUser().getUuid();
	}

	public PagingParameters pagingParams() {
		return PagingParameters.create(this);
	}

	public FilterParameters filterParams() {
		return FilterParameters.create(this);
	}

	public SortParameters sortParams() {
		return SortParameters.create(this);
	}

	public List<FileUpload> fileUploads() {
		return rc.fileUploads();
	}

	/**
	 * Return the underlying Vert.x routing context.
	 */
	public RoutingContext routingContext() {
		return rc;
	}

}
