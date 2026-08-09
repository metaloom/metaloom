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
import io.metaloom.loom.rest.service.impl.LibraryEndpointService;

public class LibraryEndpoint extends AbstractEndpoint {

	private static final Logger log = LoggerFactory.getLogger(LibraryEndpoint.class);

	private final LibraryEndpointService service;
	private final ModelExamples examples;

	@Inject
	public LibraryEndpoint(LibraryEndpointService service, EndpointDependencies deps, ModelExamples examples) {
		super(deps);
		this.service = service;
		this.examples = examples;
	}

	@Override
	public String name() {
		return "library";
	}

	@Override
	public String basePath() {
		return API_V1_PATH + "/libraries";
	}

	@Override
	public void register() {
		log.info("Registering {} endpoint", name());

		secure(basePath() + "*");

		// Create
		addRoute(basePath(), POST,
			"Create a new library",
			examples.libraryCreateRequestExample(),
			examples.libraryResponseExample(),
			lrc -> {
				service.create(lrc);
			});

		// Update
		addRoute(basePath() + "/:uuid", POST,
			"Update a library",
			examples.libraryUpdateRequestExample(),
			examples.libraryResponseExample(),
			lrc -> {
				service.update(lrc, lrc.pathParamUUID("uuid"));
			});

		// Delete
		addRoute(basePath() + "/:uuid", DELETE,
			"Delete a library",
			null,
			examples.deleteResponseExample(),
			lrc -> {
				service.delete(lrc, lrc.pathParamUUID("uuid"));
			});

		// List
		addListRoute(basePath(), GET,
			"Load a paged list of librarys",
			examples.libraryListResponseExample(),
			lrc -> {
				service.list(lrc);
			});

		// Read
		addRoute(basePath() + "/:uuid", GET,
			"Load a library",
			null,
			examples.libraryResponseExample(),
			lrc -> {
				service.load(lrc, lrc.pathParamUUID("uuid"));
			});

		// Membership. These write library_asset - the organizational membership - and are deliberately
		// separate from asset_location.library_uuid, which records where the bytes were scanned or stored.
		addRoute(basePath() + "/:uuid/assets", POST,
			"Add an asset to the library",
			examples.libraryAssetRequestExample(),
			examples.libraryResponseExample(),
			lrc -> {
				service.addAsset(lrc, lrc.pathParamUUID("uuid"));
			});

		addRoute(basePath() + "/:uuid/assets/:assetUuid", DELETE,
			"Remove an asset from the library",
			lrc -> {
				service.removeAsset(lrc, lrc.pathParamUUID("uuid"), lrc.pathParamUUID("assetUuid"));
			});

		addListRoute(basePath() + "/:uuid/assets", GET,
			"Load a paged list of the assets in the library",
			examples.assetListResponseExample(),
			lrc -> {
				service.listAssets(lrc, lrc.pathParamUUID("uuid"));
			});
	}
}
