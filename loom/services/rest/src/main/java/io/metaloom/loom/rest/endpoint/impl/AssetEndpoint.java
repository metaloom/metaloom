package io.metaloom.loom.rest.endpoint.impl;

import static io.metaloom.loom.rest.RESTConstants.API_V1_PATH;
import static io.vertx.core.http.HttpMethod.DELETE;
import static io.vertx.core.http.HttpMethod.GET;
import static io.vertx.core.http.HttpMethod.POST;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.asset.AssetId;
import io.metaloom.loom.rest.AbstractEndpoint;
import io.metaloom.loom.rest.EndpointDependencies;
import io.metaloom.loom.rest.model.ModelExamples;
import io.metaloom.loom.rest.service.impl.AssetEndpointService;
import io.metaloom.loom.rest.service.impl.AssetBinaryEndpointService;
import io.metaloom.loom.rest.service.impl.ReactionEndpointService;
import io.metaloom.loom.rest.service.impl.TagEndpointService;
import io.metaloom.utils.hash.SHA512;

public class AssetEndpoint extends AbstractEndpoint {

	private static final Logger log = LoggerFactory.getLogger(AssetEndpoint.class);

	private final AssetEndpointService service;
	private final TagEndpointService tagService;
	private final AssetBinaryEndpointService binaryService;
	private final ReactionEndpointService reactionService;
	private final ModelExamples examples;

	@Inject
	public AssetEndpoint(AssetEndpointService service, TagEndpointService tagService,
		AssetBinaryEndpointService binaryService,
		ReactionEndpointService reactionService,
		EndpointDependencies deps, ModelExamples examples) {
		super(deps);
		this.service = service;
		this.tagService = tagService;
		this.binaryService = binaryService;
		this.reactionService = reactionService;
		this.examples = examples;
	}

	@Override
	public String name() {
		return "asset";
	}

	@Override
	public void register() {
		log.info("Registering assets endpoint");

		secure(basePath() + "*");

		// --- Collection-level routes (no path param) ---

		addRoute(basePath(), POST,
			"Create a new asset",
			examples.assetCreateRequestExample(),
			examples.assetResponseExample(),
			lrc -> {
				service.create(lrc);
			});

		addRoute(basePath(), GET,
			"List assets",
			null,
			examples.assetListResponseExample(),
			lrc -> {
				service.list(lrc);
			});

		// --- Bulk routes (literal prefix — registered before :uuid wildcard) ---

		addRoute(basePath() + "/bulk/create", POST,
			"Bulk create assets",
			examples.assetBulkCreateRequestExample(),
			examples.assetBulkResponseExample(),
			lrc -> {
				service.bulkCreate(lrc);
			});

		addRoute(basePath() + "/bulk/update", POST,
			"Bulk update assets",
			examples.assetBulkUpdateRequestExample(),
			examples.assetBulkResponseExample(),
			lrc -> {
				service.bulkUpdate(lrc);
			});

		// --- SHA-512 routes (literal prefix — registered before :uuid wildcard) ---

		addRoute(basePath() + "/sha512/:sha512", GET,
			"Load an asset by SHA-512 hash",
			null,
			examples.assetResponseExample(),
			lrc -> {
				SHA512 hash = SHA512.fromString(lrc.pathParam("sha512"));
				service.load(lrc, AssetId.assetId(hash));
			});

		addRoute(basePath() + "/sha512/:sha512", POST,
			"Update an asset by SHA-512 hash",
			examples.assetUpdateRequestExample(),
			examples.assetResponseExample(),
			lrc -> {
				SHA512 hash = SHA512.fromString(lrc.pathParam("sha512"));
				service.update(lrc, AssetId.assetId(hash));
			});

		addRoute(basePath() + "/sha512/:sha512", DELETE,
			"Delete an asset by SHA-512 hash",
			null,
			examples.deleteResponseExample(),
			lrc -> {
				SHA512 hash = SHA512.fromString(lrc.pathParam("sha512"));
				service.delete(lrc, AssetId.assetId(hash));
			});

		// --- UUID routes (wildcard — registered last to avoid capturing literal prefixes) ---

		addRoute(basePath() + "/:uuid", GET,
			"Load an asset by UUID",
			null,
			examples.assetResponseExample(),
			lrc -> {
				service.load(lrc, lrc.pathParamUUID("uuid"));
			});

		addRoute(basePath() + "/:uuid", POST,
			"Update an asset by UUID",
			examples.assetUpdateRequestExample(),
			examples.assetResponseExample(),
			lrc -> {
				service.update(lrc, lrc.pathParamUUID("uuid"));
			});

		addRoute(basePath() + "/:uuid", DELETE,
			"Delete an asset by UUID",
			null,
			examples.deleteResponseExample(),
			lrc -> {
				service.delete(lrc, lrc.pathParamUUID("uuid"));
			});

		// --- TAG (UUID-based sub-resource) ---

		addRoute(basePath() + "/:uuid/tags", POST,
			"Tag the asset",
			lrc -> {
				tagService.tagAsset(lrc, lrc.pathParamAssetId("uuid"));
			});

		addRoute(basePath() + "/:uuid/tags/:tagUuid", DELETE,
			"Remove a tag from an asset",
			lrc -> {
				tagService.untagAsset(lrc, lrc.pathParamAssetId("uuid"), lrc.pathParamUUID("tagUuid"));
			});

		// --- REACTION (UUID-based sub-resource) ---

		addRoute(basePath() + "/:uuid/reactions", POST,
			"Create a new reaction on an asset",
			lrc -> {
				reactionService.createAssetReaction(lrc, lrc.pathParamAssetId("uuid"));
			});

		addRoute(basePath() + "/:uuid/reactions/:reactionUuid", DELETE,
			"Delete the reaction on an asset",
			lrc -> {
				reactionService.deleteAssetReaction(lrc, lrc.pathParamAssetId("uuid"), lrc.pathParamUUID("reactionUuid"));
			});

		addRoute(basePath() + "/:uuid/reactions", GET,
			"List the reactions on an asset",
			lrc -> {
				reactionService.listAssetReactions(lrc, lrc.pathParamAssetId("uuid"));
			});

		addRoute(basePath() + "/:uuid/reactions/:reactionUuid", GET,
			"Load a reaction for an asset",
			lrc -> {
				reactionService.loadAssetReaction(lrc, lrc.pathParamAssetId("uuid"), lrc.pathParamUUID("reactionUuid"));
			});

		addRoute(basePath() + "/:uuid/reactions/:reactionUuid", POST,
			"Update an reaction for an asset",
			lrc -> {
				reactionService.updateAssetReaction(lrc, lrc.pathParamAssetId("uuid"), lrc.pathParamUUID("reactionUuid"));
			});

		// --- BINARY (UUID-based sub-resource, one-to-one) ---

		addRoute(basePath() + "/:uuid/binary", POST,
			"Create a binary for the asset",
			examples.binaryCreateRequestExample(),
			examples.binaryResponseExample(),
			lrc -> {
				binaryService.createForAsset(lrc, lrc.pathParamUUID("uuid"));
			});

		addRoute(basePath() + "/:uuid/binary", GET,
			"Load the binary for the asset",
			null,
			examples.binaryResponseExample(),
			lrc -> {
				binaryService.loadByAssetUuid(lrc, lrc.pathParamUUID("uuid"));
			});

		addRoute(basePath() + "/:uuid/binary", DELETE,
			"Delete the binary for the asset",
			null,
			examples.deleteResponseExample(),
			lrc -> {
				binaryService.deleteByAssetUuid(lrc, lrc.pathParamUUID("uuid"));
			});

	}

	public String basePath() {
		return API_V1_PATH + "/assets";
	}

}
