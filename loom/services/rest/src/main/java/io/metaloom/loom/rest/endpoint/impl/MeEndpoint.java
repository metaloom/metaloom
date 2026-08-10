package io.metaloom.loom.rest.endpoint.impl;

import static io.metaloom.loom.rest.RESTConstants.API_V1_PATH;
import static io.vertx.core.http.HttpMethod.DELETE;
import static io.vertx.core.http.HttpMethod.GET;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.rest.AbstractEndpoint;
import io.metaloom.loom.rest.EndpointDependencies;
import io.metaloom.loom.rest.model.ModelExamples;
import io.metaloom.loom.rest.service.impl.UserEndpointService;

public class MeEndpoint extends AbstractEndpoint {

	private static final Logger log = LoggerFactory.getLogger(MeEndpoint.class);

	private final UserEndpointService service;
	private final ModelExamples examples;

	@Inject
	public MeEndpoint(UserEndpointService service, EndpointDependencies deps, ModelExamples examples) {
		super(deps);
		this.service = service;
		this.examples = examples;
	}

	@Override
	public String name() {
		return "me";
	}

	@Override
	public String basePath() {
		return API_V1_PATH + "/me";
	}

	@Override
	public void register() {
		log.info("Registering {} endpoint", name());

		secure(basePath() + "*");

		// Read the currently authenticated user
		addRoute(basePath(), GET,
			"Load the currently authenticated user",
			null,
			examples.userResponseExample(),
			lrc -> {
				service.me(lrc);
			});

		// --- My own picture ------------------------------------------------------------------------
		// The same four routes UserEndpoint mounts under /users/:uuid, aimed at the caller. They exist
		// because the profile screen has to work for a user who holds neither READ_USER nor UPDATE_USER,
		// which is every non-administrator - the same reason GET /me itself requires no permission.
		//
		// The URL the UI renders still comes back as /users/:uuid/avatar/data: a picture shown beside a
		// comment author's name is loaded by other people's browsers, where a self-relative URL would
		// resolve to their own face.

		addRoute(basePath() + "/avatar", GET,
			"Load the metadata of your own avatar picture. 404 when you have none.",
			null,
			examples.userAvatarResponseExample(),
			lrc -> {
				service.loadAvatar(lrc, lrc.userUuid());
			});

		addDownloadRoute(basePath() + "/avatar/data",
			"Load the bytes of your own avatar picture",
			lrc -> {
				service.downloadAvatar(lrc, lrc.userUuid());
			});

		addUploadRoute(basePath() + "/avatar",
			"Upload your own avatar picture, replacing any previous one. Form fields: 'file' (required, the image) and 'poolUuid' (optional storage "
				+ "pool). Requires no permission beyond being signed in.",
			examples.userAvatarResponseExample(),
			lrc -> {
				service.uploadAvatar(lrc, lrc.userUuid());
			});

		addRoute(basePath() + "/avatar", DELETE,
			"Delete your own avatar picture",
			null,
			examples.deleteResponseExample(),
			lrc -> {
				service.deleteAvatar(lrc, lrc.userUuid());
			});
	}

}
