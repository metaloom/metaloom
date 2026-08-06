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
import io.metaloom.loom.rest.service.impl.AssetUploadEndpointService;
import io.metaloom.loom.rest.service.impl.CommentEndpointService;
import io.metaloom.loom.rest.service.impl.DetectionEndpointService;
import io.metaloom.loom.rest.service.impl.EmbeddingEndpointService;
import io.metaloom.loom.rest.service.impl.FingerprintCompEndpointService;
import io.metaloom.loom.rest.service.impl.JsonCompEndpointService;
import io.metaloom.loom.rest.service.impl.NodeResultEndpointService;
import io.metaloom.loom.rest.service.impl.DedupGroupEndpointService;
import io.metaloom.loom.rest.service.impl.SegmentCompEndpointService;
import io.metaloom.loom.rest.service.impl.SimilarityEndpointService;
import io.metaloom.loom.rest.service.impl.ReactionEndpointService;
import io.metaloom.loom.rest.service.impl.TagEndpointService;
import io.metaloom.loom.rest.service.impl.TaskEndpointService;
import io.metaloom.loom.rest.service.impl.TranscriptEndpointService;
import io.metaloom.utils.hash.SHA512;

public class AssetEndpoint extends AbstractEndpoint {

	private static final Logger log = LoggerFactory.getLogger(AssetEndpoint.class);

	private final AssetEndpointService service;
	private final AssetUploadEndpointService uploadService;
	private final TagEndpointService tagService;
	private final AssetBinaryEndpointService binaryService;
	private final ReactionEndpointService reactionService;
	private final CommentEndpointService commentService;
	private final DetectionEndpointService detectionService;
	private final EmbeddingEndpointService embeddingService;
	private final TranscriptEndpointService transcriptService;
	private final NodeResultEndpointService nodeResultService;
	private final JsonCompEndpointService jsonCompService;
	private final FingerprintCompEndpointService fingerprintService;
	private final SegmentCompEndpointService segmentService;
	private final TaskEndpointService taskService;
	private final SimilarityEndpointService similarityService;
	private final DedupGroupEndpointService dedupGroupService;
	private final ModelExamples examples;

	@Inject
	public AssetEndpoint(AssetEndpointService service, AssetUploadEndpointService uploadService, TagEndpointService tagService,
		AssetBinaryEndpointService binaryService,
		ReactionEndpointService reactionService,
		CommentEndpointService commentService,
		DetectionEndpointService detectionService,
		EmbeddingEndpointService embeddingService,
		TranscriptEndpointService transcriptService,
		NodeResultEndpointService nodeResultService,
		JsonCompEndpointService jsonCompService,
		FingerprintCompEndpointService fingerprintService,
		SegmentCompEndpointService segmentService,
		TaskEndpointService taskService,
		SimilarityEndpointService similarityService,
		DedupGroupEndpointService dedupGroupService,
		EndpointDependencies deps, ModelExamples examples) {
		super(deps);
		this.service = service;
		this.uploadService = uploadService;
		this.tagService = tagService;
		this.binaryService = binaryService;
		this.reactionService = reactionService;
		this.commentService = commentService;
		this.detectionService = detectionService;
		this.embeddingService = embeddingService;
		this.transcriptService = transcriptService;
		this.nodeResultService = nodeResultService;
		this.jsonCompService = jsonCompService;
		this.fingerprintService = fingerprintService;
		this.segmentService = segmentService;
		this.taskService = taskService;
		this.similarityService = similarityService;
		this.dedupGroupService = dedupGroupService;
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

		// --- Upload route (multipart, literal prefix — registered before :uuid wildcard) ---

		addUploadRoute(basePath() + "/upload",
			"Upload a file to create an asset. Expects a multipart request with one file part named 'file', a required 'libraryUuid' form field "
				+ "and the optional form fields 'origin' and 'poolUuid'. The target library's storage pool decides whether the bytes land on a "
				+ "filesystem or in S3; naming 'poolUuid' overrides that for this upload and additionally requires the READ_ASSET_POOL permission. "
				+ "Answers 201 for new content and 200 when an asset with the same SHA-512 already exists.",
			examples.assetResponseExample(),
			lrc -> {
				uploadService.upload(lrc);
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

		addRoute(basePath() + "/:uuid/tags", PUT,
			"Apply a set of tags to the asset in one request",
			examples.assetTagBulkRequestExample(),
			examples.assetTagBulkResponseExample(),
			lrc -> {
				tagService.bulkTagAsset(lrc, lrc.pathParamAssetId("uuid"));
			});

		addRoute(basePath() + "/:uuid/tags/:tagUuid", DELETE,
			"Remove a tag from an asset, with every placement of it",
			lrc -> {
				tagService.untagAsset(lrc, lrc.pathParamAssetId("uuid"), lrc.pathParamUUID("tagUuid"));
			});

		addRoute(basePath() + "/:uuid/tag-placements/:placementUuid", DELETE,
			"Remove one placement of a tag from an asset, keeping its other placements",
			lrc -> {
				tagService.removeTagPlacement(lrc, lrc.pathParamAssetId("uuid"), lrc.pathParamUUID("placementUuid"));
			});

		// --- TASK (UUID-based sub-resource) ---

		addRoute(basePath() + "/:uuid/tasks", GET,
			"List the tasks assigned to the asset",
			null,
			examples.taskListResponseExample(),
			lrc -> {
				taskService.listAssetTasks(lrc, lrc.pathParamAssetId("uuid"));
			});

		addRoute(basePath() + "/:uuid/tasks/:taskUuid", POST,
			"Assign an existing task to the asset",
			null,
			examples.taskResponseExample(),
			lrc -> {
				taskService.assignAssetTask(lrc, lrc.pathParamAssetId("uuid"), lrc.pathParamUUID("taskUuid"));
			});

		addRoute(basePath() + "/:uuid/tasks/:taskUuid", DELETE,
			"Unassign a task from the asset",
			lrc -> {
				taskService.unassignAssetTask(lrc, lrc.pathParamAssetId("uuid"), lrc.pathParamUUID("taskUuid"));
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

		// --- COMMENT (UUID-based sub-resource) ---

		addRoute(basePath() + "/:uuid/comments", POST,
			"Create a new comment on an asset",
			examples.commentCreateRequestExample(),
			examples.commentResponseExample(),
			lrc -> {
				commentService.createForAsset(lrc, lrc.pathParamUUID("uuid"));
			});

		addRoute(basePath() + "/:uuid/comments", GET,
			"List the comments on an asset",
			null,
			examples.commentListResponseExample(),
			lrc -> {
				commentService.listForAsset(lrc, lrc.pathParamUUID("uuid"));
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

		// --- EMBEDDING (bulk write, the shape a Cortex node needs) ---

		addRoute(basePath() + "/:uuid/embeddings/bulk", POST,
			"Bulk create embeddings on an asset",
			examples.embeddingBulkCreateRequestExample(),
			examples.embeddingBulkResponseExample(),
			lrc -> {
				embeddingService.bulkCreateAssetEmbeddings(lrc, lrc.pathParamAssetId("uuid"));
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

		// --- NODE RESULT LEDGER (UUID-based sub-resource) ---

		addRoute(basePath() + "/:uuid/node-results", POST,
			"Record (upsert) a node processing result for an asset",
			examples.nodeResultCreateRequestExample(),
			examples.nodeResultResponseExample(),
			lrc -> {
				nodeResultService.createAssetNodeResult(lrc, lrc.pathParamUUID("uuid"));
			});

		addRoute(basePath() + "/:uuid/node-results", GET,
			"List node processing results for an asset",
			null,
			examples.nodeResultListResponseExample(),
			lrc -> {
				nodeResultService.listAssetNodeResults(lrc, lrc.pathParamUUID("uuid"));
			});

		addRoute(basePath() + "/:uuid/node-results/:nodeResultUuid", GET,
			"Load a node processing result for an asset",
			lrc -> {
				nodeResultService.loadAssetNodeResult(lrc, lrc.pathParamUUID("uuid"), lrc.pathParamUUID("nodeResultUuid"));
			});

		addRoute(basePath() + "/:uuid/node-results/:nodeResultUuid", DELETE,
			"Delete a node processing result for an asset",
			null,
			examples.deleteResponseExample(),
			lrc -> {
				nodeResultService.deleteAssetNodeResult(lrc, lrc.pathParamUUID("uuid"), lrc.pathParamUUID("nodeResultUuid"));
			});

		// --- GENERIC JSON COMPONENT (asset_json_comp, UUID-based sub-resource) ---

		addRoute(basePath() + "/:uuid/json-comps", POST,
			"Persist (upsert) a generic JSON component result for an asset",
			examples.jsonCompCreateRequestExample(),
			examples.jsonCompResponseExample(),
			lrc -> {
				jsonCompService.createJsonComp(lrc, lrc.pathParamUUID("uuid"));
			});

		addRoute(basePath() + "/:uuid/json-comps", GET,
			"List generic JSON component results for an asset",
			null,
			examples.jsonCompListResponseExample(),
			lrc -> {
				jsonCompService.listJsonComps(lrc, lrc.pathParamUUID("uuid"));
			});

		addRoute(basePath() + "/:uuid/json-comps/:compUuid", GET,
			"Load a generic JSON component result for an asset",
			lrc -> {
				jsonCompService.loadJsonComp(lrc, lrc.pathParamUUID("uuid"), lrc.pathParamUUID("compUuid"));
			});

		addRoute(basePath() + "/:uuid/json-comps/:compUuid", DELETE,
			"Delete a generic JSON component result for an asset",
			null,
			examples.deleteResponseExample(),
			lrc -> {
				jsonCompService.deleteJsonComp(lrc, lrc.pathParamUUID("uuid"), lrc.pathParamUUID("compUuid"));
			});

		// --- FINGERPRINT COMPONENT (asset_fingerprint_comp, UUID-based sub-resource) ---

		addRoute(basePath() + "/:uuid/fingerprints", POST,
			"Persist (upsert) a perceptual fingerprint component for an asset",
			examples.fingerprintCompCreateRequestExample(),
			examples.fingerprintCompResponseExample(),
			lrc -> {
				fingerprintService.createFingerprintComp(lrc, lrc.pathParamUUID("uuid"));
			});

		addRoute(basePath() + "/:uuid/fingerprints", GET,
			"List fingerprint components for an asset",
			null,
			examples.fingerprintCompListResponseExample(),
			lrc -> {
				fingerprintService.listFingerprintComps(lrc, lrc.pathParamUUID("uuid"));
			});

		addRoute(basePath() + "/:uuid/fingerprints/:compUuid", GET,
			"Load a fingerprint component for an asset",
			lrc -> {
				fingerprintService.loadFingerprintComp(lrc, lrc.pathParamUUID("uuid"), lrc.pathParamUUID("compUuid"));
			});

		addRoute(basePath() + "/:uuid/fingerprints/:compUuid", DELETE,
			"Delete a fingerprint component for an asset",
			null,
			examples.deleteResponseExample(),
			lrc -> {
				fingerprintService.deleteFingerprintComp(lrc, lrc.pathParamUUID("uuid"), lrc.pathParamUUID("compUuid"));
			});

		// --- FINGERPRINT SIMILARITY (near-duplicate query over the Lucene k-NN index) ---

		addRoute(basePath() + "/:uuid/similar-assets", GET,
			"Find near-duplicate assets by perceptual fingerprint similarity. Excludes the asset itself.",
			lrc -> {
				similarityService.listSimilarAssets(lrc, lrc.pathParamUUID("uuid"));
			});

		// --- DEDUP REVIEW GROUPS involving this asset (the apply node's entry point) ---

		addRoute(basePath() + "/:uuid/dedup-groups", GET,
			"List the duplicate review groups this asset takes part in, as keep or as duplicate",
			lrc -> {
				dedupGroupService.listAssetDedupGroups(lrc, lrc.pathParamUUID("uuid"));
			});

		// --- SEGMENT COMPONENT (asset_segment_comp: scenes, silence, shots, chapters) ---

		addRoute(basePath() + "/:uuid/segments", POST,
			"Replace the whole set of time-ranged segments for an asset (surplus rows from a shorter re-run are deleted)",
			examples.segmentCompCreateRequestExample(),
			examples.segmentCompListResponseExample(),
			lrc -> {
				segmentService.createSegmentComps(lrc, lrc.pathParamUUID("uuid"));
			});

		addRoute(basePath() + "/:uuid/segments", GET,
			"List segment components for an asset",
			null,
			examples.segmentCompListResponseExample(),
			lrc -> {
				segmentService.listSegmentComps(lrc, lrc.pathParamUUID("uuid"));
			});

		addRoute(basePath() + "/:uuid/segments/:compUuid", GET,
			"Load a segment component for an asset",
			lrc -> {
				segmentService.loadSegmentComp(lrc, lrc.pathParamUUID("uuid"), lrc.pathParamUUID("compUuid"));
			});

		addRoute(basePath() + "/:uuid/segments/:compUuid", DELETE,
			"Delete a segment component for an asset",
			null,
			examples.deleteResponseExample(),
			lrc -> {
				segmentService.deleteSegmentComp(lrc, lrc.pathParamUUID("uuid"), lrc.pathParamUUID("compUuid"));
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
			"Load the primary binary for the asset. An asset holds one binary per library it was imported into; this returns the oldest. Use /binaries for all of them.",
			null,
			examples.binaryResponseExample(),
			lrc -> {
				binaryService.loadByAssetUuid(lrc, lrc.pathParamUUID("uuid"));
			});

		addRoute(basePath() + "/:uuid/binaries", GET,
			"List every binary of the asset - one per library the asset was imported into.",
			null,
			examples.binaryListResponseExample(),
			lrc -> {
				binaryService.listByAssetUuid(lrc, lrc.pathParamUUID("uuid"));
			});

		addRoute(basePath() + "/:uuid/binary", DELETE,
			"Delete the binary for the asset",
			null,
			examples.deleteResponseExample(),
			lrc -> {
				binaryService.deleteByAssetUuid(lrc, lrc.pathParamUUID("uuid"));
			});

		// --- BINARY DATA (raw bytes) ---

		addUploadRoute(basePath() + "/:uuid/binary/data",
			"Upload raw file bytes into the binary for an existing asset. Expects a multipart request with one file part named 'file'. "
				+ "The 'libraryUuid' form field is required when the asset has no binary yet, and when it has more than one - otherwise the "
				+ "server cannot tell which library's binary to replace and answers 400.",
			examples.binaryResponseExample(),
			lrc -> {
				uploadService.uploadForAsset(lrc, lrc.pathParamUUID("uuid"));
			});

		addDownloadRoute(basePath() + "/:uuid/binary/data",
			"Download the raw bytes of the asset's primary binary, from whichever backend holds them. Supports single-range "
				+ "'Range: bytes=' requests, answering 206 with Content-Range, so media can be seeked without refetching.",
			lrc -> {
				binaryService.downloadByAssetUuid(lrc, lrc.pathParamUUID("uuid"));
			});

	}

	public String basePath() {
		return API_V1_PATH + "/assets";
	}

}
