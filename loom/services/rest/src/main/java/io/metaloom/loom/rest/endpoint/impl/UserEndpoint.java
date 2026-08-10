package io.metaloom.loom.rest.endpoint.impl;

import static io.metaloom.loom.rest.RESTConstants.API_V1_PATH;
import static io.vertx.core.http.HttpMethod.DELETE;
import static io.vertx.core.http.HttpMethod.GET;
import static io.vertx.core.http.HttpMethod.PATCH;
import static io.vertx.core.http.HttpMethod.POST;
import static io.vertx.core.http.HttpMethod.PUT;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.rest.AbstractEndpoint;
import io.metaloom.loom.rest.EndpointDependencies;
import io.metaloom.loom.rest.model.ModelExamples;
import io.metaloom.loom.rest.model.user.UserUpdateRequest;
import io.metaloom.loom.rest.service.impl.UserEndpointService;

public class UserEndpoint extends AbstractEndpoint {

	private static final Logger log = LoggerFactory.getLogger(UserEndpoint.class);

	private final UserEndpointService service;
	private final ModelExamples examples;

	@Inject
	public UserEndpoint(UserEndpointService service, EndpointDependencies deps, ModelExamples examples) {
		super(deps);
		this.service = service;
		this.examples = examples;
	}

	@Override
	public String name() {
		return "user";
	}

	@Override
	public String basePath() {
		return API_V1_PATH + "/users";
	}

	@Override
	public void register() {
		log.info("Registering {} endpoint", name());

		secure(basePath() + "*");

		// Create
		addRoute(basePath(), POST,
			"Create new user",
			examples.userCreateRequestExample(),
			examples.userResponseExample(),
			lrc -> {
				service.create(lrc);
			});

		// Update
		addRoute(basePath() + "/:uuid", POST,
			"Update a user",
			examples.userUpdateRequestExample(),
			examples.userResponseExample(),
			lrc -> {
				service.update(lrc, lrc.pathParamUUID("uuid"));
			});

		// Update (partial)
		addRoute(basePath() + "/:uuid", PATCH,
			"Partially update a user. Only the fields present in the request body are modified.",
			examples.userUpdateRequestExample(),
			examples.userResponseExample(),
			lrc -> {
				service.update(lrc, lrc.pathParamUUID("uuid"));
			});

		// Replace (full)
		addRoute(basePath() + "/:uuid", PUT,
			"Replace a user. All replaceable fields must be present in the request body.",
			examples.userUpdateRequestExample(),
			examples.userResponseExample(),
			replaceHandler(UserUpdateRequest.class, lrc -> {
				service.update(lrc, lrc.pathParamUUID("uuid"));
			}));

		// Delete
		addRoute(basePath() + "/:uuid", DELETE,
			"Delete a user",
			null,
			examples.deleteResponseExample(),
			lrc -> {
				service.delete(lrc, lrc.pathParamUUID("uuid"));
			});

		// List
		addListRoute(basePath(), GET,
			"Load a paged list of users",
			examples.userListResponseExample(),
			lrc -> {
				service.list(lrc);
			});

		// Read
		addRoute(basePath() + "/:uuid", GET,
			"Load a user",
			null,
			examples.userResponseExample(),
			lrc -> {
				service.load(lrc, lrc.pathParamUUID("uuid"));
			});

		// --- The account picture -------------------------------------------------------------------
		// Singular, because an account has exactly one. The metadata route is registered before the
		// bytes route so the literal "data" segment is not swallowed - the same ordering rule
		// /assets/:uuid/binary and /binary/data follow.
		//
		// A user may always read and change their own picture without holding READ_USER or UPDATE_USER;
		// see UserEndpointService.checkAvatarPerm. The same four routes are mounted under /me.

		addRoute(basePath() + "/:uuid/avatar", GET,
			"Load the metadata of a user's avatar picture. 404 when the account has none.",
			null,
			examples.userAvatarResponseExample(),
			lrc -> {
				service.loadAvatar(lrc, lrc.pathParamUUID("uuid"));
			});

		addDownloadRoute(basePath() + "/:uuid/avatar/data",
			"Load the bytes of a user's avatar picture",
			lrc -> {
				service.downloadAvatar(lrc, lrc.pathParamUUID("uuid"));
			});

		addUploadRoute(basePath() + "/:uuid/avatar",
			"Upload a user's avatar picture, replacing any previous one. Form fields: 'file' (required, the image) and 'poolUuid' (optional storage "
				+ "pool; without it the picture lands in the deployment's default storage, since an account has no parent asset to inherit a pool from).",
			examples.userAvatarResponseExample(),
			lrc -> {
				service.uploadAvatar(lrc, lrc.pathParamUUID("uuid"));
			});

		addRoute(basePath() + "/:uuid/avatar", DELETE,
			"Delete a user's avatar picture, leaving the account without one.",
			null,
			examples.deleteResponseExample(),
			lrc -> {
				service.deleteAvatar(lrc, lrc.pathParamUUID("uuid"));
			});
	}
}
