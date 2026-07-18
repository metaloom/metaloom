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

import io.metaloom.loom.api.asset.AssetId;
import io.metaloom.loom.rest.AbstractEndpoint;
import io.metaloom.loom.rest.EndpointDependencies;
import io.metaloom.loom.rest.model.ModelExamples;
import io.metaloom.loom.rest.model.asset.AssetUpdateRequest;
import io.metaloom.loom.rest.service.impl.AssetEndpointService;
import io.metaloom.loom.rest.service.impl.AssetBinaryEndpointService;
import io.metaloom.loom.rest.service.impl.DetectionEndpointService;
import io.metaloom.loom.rest.service.impl.ReactionEndpointService;
import io.metaloom.loom.rest.service.impl.TagEndpointService;
import io.metaloom.loom.rest.service.impl.TranscriptEndpointService;
import io.metaloom.utils.hash.SHA512;

public class AssetEndpoint extends AbstractEndpoint {

	private static final Logger log = LoggerFactory.getLogger(AssetEndpoint.class);

	private final AssetEndpointService service;
	private final TagEndpointService tagService;
	private final AssetBinaryEndpointService binaryService;
	private final ReactionEndpointService reactionService;
	private final DetectionEndpointService detectionService;
	private final TranscriptEndpointService transcriptService;
	private final ModelExamples examples;

	@Inject
	public AssetEndpoint(AssetEndpointService service, TagEndpointService tagService,
		AssetBinaryEndpointService binaryService,
		ReactionEndpointService reactionService,
		DetectionEndpointService detectionService,
		TranscriptEndpointService transcriptService,
		EndpointDependencies deps, ModelExamples examples) {
		super(deps);
		this.service = service;
		this.tagService = tagService;
		this.binaryService = binaryService;
		this.reactionService = reactionService;
		this.detectionService = detectionService;
		this.transcriptService = transcriptService;
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

		addRoute(basePath() + "/sha512/:sha512", PATCH,
			"Partially update an asset by SHA-512 hash. Only the fields present in the request body are modified.",
			examples.assetUpdateRequestExample(),
			examples.assetResponseExample(),
			lrc -> {
				SHA512 hash = SHA512.fromString(lrc.pathParam("sha512"));
				service.update(lrc, AssetId.assetId(hash));
			});

		addRoute(basePath() + "/sha512/:sha512", PUT,
			"Replace an asset by SHA-512 hash. All replaceable fields must be present in the request body.",
			examples.assetUpdateRequestExample(),
			examples.assetResponseExample(),
			replaceHandler(AssetUpdateRequest.class, lrc -> {
				SHA512 hash = SHA512.fromString(lrc.pathParam("sha512"));
				service.update(lrc, AssetId.assetId(hash));
			}));

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

		addRoute(basePath() + "/:uuid", PATCH,
			"Partially update an asset by UUID. Only the fields present in the request body are modified.",
			examples.assetUpdateRequestExample(),
			examples.assetResponseExample(),
			lrc -> {
				service.update(lrc, lrc.pathParamUUID("uuid"));
			});

		addRoute(basePath() + "/:uuid", PUT,
			"Replace an asset by UUID. All replaceable fields must be present in the request body.",
			examples.assetUpdateRequestExample(),
			examples.assetResponseExample(),
			replaceHandler(AssetUpdateRequest.class, lrc -> {
				service.update(lrc, lrc.pathParamUUID("uuid"));
			}));

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

		// --- DETECTION (UUID-based sub-resource) ---

		addRoute(basePath() + "/:uuid/detections", POST,
			"Create a new detection on an asset",
			examples.detectionCreateRequestExample(),
			examples.detectionResponseExample(),
			lrc -> {
				detectionService.createAssetDetection(lrc, lrc.pathParamAssetId("uuid"));
			});

		addRoute(basePath() + "/:uuid/detections/bulk", POST,
			"Bulk create detections on an asset",
			examples.detectionBulkCreateRequestExample(),
			examples.detectionBulkResponseExample(),
			lrc -> {
				detectionService.bulkCreateAssetDetections(lrc, lrc.pathParamAssetId("uuid"));
			});

		addRoute(basePath() + "/:uuid/detections/:detectionUuid", DELETE,
			"Delete a detection on an asset",
			lrc -> {
				detectionService.deleteAssetDetection(lrc, lrc.pathParamAssetId("uuid"), lrc.pathParamUUID("detectionUuid"));
			});

		addRoute(basePath() + "/:uuid/detections", GET,
			"List detections on an asset",
			null,
			examples.detectionListResponseExample(),
			lrc -> {
				detectionService.listAssetDetections(lrc, lrc.pathParamAssetId("uuid"));
			});

		addRoute(basePath() + "/:uuid/detections/:detectionUuid", GET,
			"Load a detection for an asset",
			lrc -> {
				detectionService.loadAssetDetection(lrc, lrc.pathParamAssetId("uuid"), lrc.pathParamUUID("detectionUuid"));
			});

		addRoute(basePath() + "/:uuid/detections/:detectionUuid", POST,
			"Update a detection for an asset",
			examples.detectionUpdateRequestExample(),
			examples.detectionResponseExample(),
			lrc -> {
				detectionService.updateAssetDetection(lrc, lrc.pathParamAssetId("uuid"), lrc.pathParamUUID("detectionUuid"));
			});

		// --- TRANSCRIPT (UUID-based sub-resource) ---

		addRoute(basePath() + "/:uuid/transcripts", POST,
			"Create a new transcript for an asset",
			examples.transcriptCreateRequestExample(),
			examples.transcriptResponseExample(),
			lrc -> {
				transcriptService.createAssetTranscript(lrc, lrc.pathParamUUID("uuid"));
			});

		addRoute(basePath() + "/:uuid/transcripts", GET,
			"List transcripts for an asset",
			null,
			examples.transcriptListResponseExample(),
			lrc -> {
				transcriptService.listAssetTranscripts(lrc, lrc.pathParamUUID("uuid"));
			});

		addRoute(basePath() + "/:uuid/transcripts/:transcriptUuid", GET,
			"Load a transcript for an asset",
			lrc -> {
				transcriptService.loadAssetTranscript(lrc, lrc.pathParamUUID("uuid"), lrc.pathParamUUID("transcriptUuid"));
			});

		addRoute(basePath() + "/:uuid/transcripts/:transcriptUuid", POST,
			"Update a transcript for an asset",
			examples.transcriptUpdateRequestExample(),
			examples.transcriptResponseExample(),
			lrc -> {
				transcriptService.updateAssetTranscript(lrc, lrc.pathParamUUID("uuid"), lrc.pathParamUUID("transcriptUuid"));
			});

		addRoute(basePath() + "/:uuid/transcripts/:transcriptUuid", DELETE,
			"Delete a transcript for an asset",
			null,
			examples.deleteResponseExample(),
			lrc -> {
				transcriptService.deleteAssetTranscript(lrc, lrc.pathParamUUID("uuid"), lrc.pathParamUUID("transcriptUuid"));
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
