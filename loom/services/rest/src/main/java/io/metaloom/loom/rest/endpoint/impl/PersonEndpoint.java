package io.metaloom.loom.rest.endpoint.impl;

import static io.metaloom.loom.rest.RESTConstants.API_V1_PATH;
import static io.vertx.core.http.HttpMethod.DELETE;
import static io.vertx.core.http.HttpMethod.GET;
import static io.vertx.core.http.HttpMethod.POST;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.rest.AbstractEndpoint;
import io.metaloom.loom.rest.EndpointDependencies;
import io.metaloom.loom.rest.model.ModelExamples;
import io.metaloom.loom.rest.service.impl.PersonEndpointService;

public class PersonEndpoint extends AbstractEndpoint {

	private static final Logger log = LoggerFactory.getLogger(PersonEndpoint.class);

	private final PersonEndpointService service;
	private final ModelExamples examples;

	@Inject
	public PersonEndpoint(PersonEndpointService service, EndpointDependencies deps, ModelExamples examples) {
		super(deps);
		this.service = service;
		this.examples = examples;
	}

	@Override
	public String name() {
		return "person";
	}

	@Override
	public String basePath() {
		return API_V1_PATH + "/persons";
	}

	@Override
	public void register() {
		log.info("Registering {} endpoint", name());

		secure(basePath() + "*");

		// Create
		addRoute(basePath(), POST,
			"Create a new person",
			examples.personCreateRequestExample(),
			examples.personResponseExample(),
			lrc -> {
				service.create(lrc);
			});

		// Update
		addRoute(basePath() + "/:uuid", POST,
			"Update a person",
			examples.personUpdateRequestExample(),
			examples.personResponseExample(),
			lrc -> {
				service.update(lrc, lrc.pathParamUUID("uuid"));
			});

		// Delete
		addRoute(basePath() + "/:uuid", DELETE,
			"Delete a person",
			null,
			examples.deleteResponseExample(),
			lrc -> {
				service.delete(lrc, lrc.pathParamUUID("uuid"));
			});

		// List
		addListRoute(basePath(), GET,
			"Load a paged list of persons",
			examples.personListResponseExample(),
			lrc -> {
				service.list(lrc);
			});

		// Read
		addRoute(basePath() + "/:uuid", GET,
			"Load a person",
			null,
			examples.personResponseExample(),
			lrc -> {
				service.load(lrc, lrc.pathParamUUID("uuid"));
			});

		// Clusters confirmed to be this person - the inverse of POST /clusters/:uuid/confirm
		addRoute(basePath() + "/:uuid/clusters", GET,
			"List the face clusters confirmed to be this person, across every asset they appear in",
			null,
			examples.clusterListResponseExample(),
			lrc -> {
				service.listClusters(lrc, lrc.pathParamUUID("uuid"));
			});

		// --- The person's own images ---------------------------------------------------------------
		// Scoped under the person rather than under /attachments because the person is what owns them: they reference no asset, and they are the one
		// kind of binary that outlives the material somebody was found in.

		addListRoute(basePath() + "/:uuid/images", GET,
			"List the person's images, newest first",
			examples.personImageListResponseExample(),
			lrc -> {
				service.listImages(lrc, lrc.pathParamUUID("uuid"));
			});

		addUploadRoute(basePath() + "/:uuid/images",
			"Upload a picture of this person. Form fields: 'file' (required, the image) and 'poolUuid' (optional storage pool; without it the image "
				+ "lands in the deployment's default storage, since a person image has no parent asset to inherit a pool from).",
			examples.personImageResponseExample(),
			lrc -> {
				service.uploadImage(lrc, lrc.pathParamUUID("uuid"));
			});

		// Registered after the upload route so the more specific path wins over "/:uuid/images".
		addRoute(basePath() + "/:uuid/images/from-detection", POST,
			"Copy a detection's face crop into this person's images. The copy shares the crop's bytes but belongs to the person, so it survives "
				+ "deletion of the asset the face was found in. Requires READ_DETECTION as well as UPDATE_PERSON: the result is readable face crop "
				+ "content, which is biometric.",
			examples.personImageImportRequestExample(),
			examples.personImageResponseExample(),
			lrc -> {
				service.importImageFromDetection(lrc, lrc.pathParamUUID("uuid"));
			});

		addDownloadRoute(basePath() + "/:uuid/images/:imageUuid/data",
			"Load the bytes of one of the person's images",
			lrc -> {
				service.downloadImage(lrc, lrc.pathParamUUID("uuid"), lrc.pathParamUUID("imageUuid"));
			});

		addRoute(basePath() + "/:uuid/images/:imageUuid", DELETE,
			"Delete one of the person's images. Deleting the one currently used as the avatar leaves the person without one.",
			null,
			examples.deleteResponseExample(),
			lrc -> {
				service.deleteImage(lrc, lrc.pathParamUUID("uuid"), lrc.pathParamUUID("imageUuid"));
			});

		addRoute(basePath() + "/:uuid/avatar", POST,
			"Set which of the person's images is their avatar. Send a blank or absent imageUuid to clear it.",
			examples.personAvatarRequestExample(),
			examples.personResponseExample(),
			lrc -> {
				service.setAvatar(lrc, lrc.pathParamUUID("uuid"));
			});
	}
}
