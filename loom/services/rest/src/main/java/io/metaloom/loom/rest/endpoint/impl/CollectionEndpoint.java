package io.metaloom.loom.rest.endpoint.impl;

import static io.metaloom.loom.rest.RESTConstants.API_V1_PATH;
import static io.vertx.core.http.HttpMethod.DELETE;
import static io.vertx.core.http.HttpMethod.GET;
import static io.vertx.core.http.HttpMethod.POST;
import static io.vertx.core.http.HttpMethod.PUT;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.rest.AbstractEndpoint;
import io.metaloom.loom.rest.EndpointDependencies;
import io.metaloom.loom.rest.model.ModelExamples;
import io.metaloom.loom.rest.service.impl.CollectionEndpointService;
import io.metaloom.loom.rest.service.impl.ShareLinkEndpointService;

public class CollectionEndpoint extends AbstractEndpoint {

	private static final Logger log = LoggerFactory.getLogger(CollectionEndpoint.class);
	private final CollectionEndpointService service;
	private final ShareLinkEndpointService shareService;
	private final ModelExamples examples;

	@Inject
	public CollectionEndpoint(CollectionEndpointService service, ShareLinkEndpointService shareService, EndpointDependencies deps,
		ModelExamples examples) {
		super(deps);
		this.service = service;
		this.shareService = shareService;
		this.examples = examples;
	}

	@Override
	public String name() {
		return "collection";
	}

	@Override
	public String basePath() {
		return API_V1_PATH + "/collections";
	}

	@Override
	public void register() {
		log.info("Registering {} endpoint", name());

		secure(basePath() + "*");

		// Create
		addRoute(basePath(), POST,
			"Create new collection",
			examples.collectionCreateRequestExample(),
			examples.collectionResponseExample(),
			lrc -> {
				service.create(lrc);
			});

		// Update
		addRoute(basePath() + "/:uuid", POST,
			"Update a collection",
			examples.collectionUpdateRequestExample(),
			examples.collectionResponseExample(),
			lrc -> {
				service.update(lrc, lrc.pathParamUUID("uuid"));
			});

		// Delete
		addRoute(basePath() + "/:uuid", DELETE,
			"Delete a collection",
			null,
			examples.deleteResponseExample(),
			lrc -> {
				service.delete(lrc, lrc.pathParamUUID("uuid"));
			});

		// List
		addListRoute(basePath(), GET,
			"Load a paged list of collections",
			examples.collectionListResponseExample(),
			lrc -> {
				service.list(lrc);
			});

		// Read
		addRoute(basePath() + "/:uuid", GET,
			"Load a collection",
			null,
			examples.collectionResponseExample(),
			lrc -> {
				service.load(lrc, lrc.pathParamUUID("uuid"));
			});

		// Membership. A collection groups assets logically - these routes write collection_asset and
		// never touch a binary. Moving the bytes of an asset is /binaries, and the two are independent.
		addRoute(basePath() + "/:uuid/assets", POST,
			"Add an asset to the collection",
			examples.collectionAssetRequestExample(),
			examples.collectionResponseExample(),
			lrc -> {
				service.addAsset(lrc, lrc.pathParamUUID("uuid"));
			});

		addRoute(basePath() + "/:uuid/assets", PUT,
			"Add several assets to the collection",
			examples.collectionAssetBulkRequestExample(),
			examples.collectionAssetBulkResponseExample(),
			lrc -> {
				service.addAssets(lrc, lrc.pathParamUUID("uuid"));
			});

		addRoute(basePath() + "/:uuid/assets/:assetUuid", DELETE,
			"Remove an asset from the collection",
			lrc -> {
				service.removeAsset(lrc, lrc.pathParamUUID("uuid"), lrc.pathParamUUID("assetUuid"));
			});

		addListRoute(basePath() + "/:uuid/assets", GET,
			"Load a paged list of the assets in the collection",
			examples.assetListResponseExample(),
			lrc -> {
				service.listAssets(lrc, lrc.pathParamUUID("uuid"));
			});

		// The links that publish this collection.
		addListRoute(basePath() + "/:uuid/share-links", GET,
			"Load a paged list of the share links pointing at this collection",
			examples.shareListResponseExample(),
			lrc -> {
				shareService.listByCollection(lrc, lrc.pathParamUUID("uuid"));
			});
	}

}
