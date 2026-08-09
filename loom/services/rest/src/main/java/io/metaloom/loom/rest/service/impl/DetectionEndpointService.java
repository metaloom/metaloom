package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.CREATE_DETECTION;
import static io.metaloom.loom.db.model.perm.Permission.DELETE_DETECTION;
import static io.metaloom.loom.db.model.perm.Permission.READ_DETECTION;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_DETECTION;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.filter.Filter;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;

import io.metaloom.loom.api.asset.AssetId;
import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.api.sort.SortDirection;
import io.metaloom.loom.api.sort.SortKey;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.asset.AssetDao;
import io.metaloom.loom.db.model.attachment.Attachment;
import io.metaloom.loom.db.model.attachment.AttachmentDao;
import io.metaloom.loom.db.model.detection.Detection;
import io.metaloom.loom.db.model.detection.DetectionDao;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.db.model.review.ReviewStatus;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.detection.DetectionBulkCreateRequest;
import io.metaloom.loom.rest.model.detection.DetectionBulkResponse;
import io.metaloom.loom.rest.model.detection.DetectionBulkReviewRequest;
import io.metaloom.loom.rest.model.detection.DetectionConfirmRequest;
import io.metaloom.loom.rest.model.detection.DetectionCreateRequest;
import io.metaloom.loom.rest.model.detection.DetectionReviewItem;
import io.metaloom.loom.rest.model.detection.DetectionListResponse;
import io.metaloom.loom.rest.model.detection.DetectionResponse;
import io.metaloom.loom.rest.model.detection.DetectionUpdateRequest;
import io.metaloom.loom.rest.parameter.FilterParameters;
import io.metaloom.loom.rest.parameter.PagingParameters;
import io.metaloom.loom.rest.parameter.SortParameters;
import io.metaloom.loom.rest.service.AbstractEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.metaloom.loom.storage.BinaryStorage;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RequestBody;

@Singleton
public class DetectionEndpointService extends AbstractEndpointService {

	private static final Logger log = LoggerFactory.getLogger(DetectionEndpointService.class);

	private final DetectionDao dao;

	private final AssetDao assetDao;

	private final AttachmentDao attachmentDao;

	private final BinaryStorageResolver storageResolver;

	@Inject
	public DetectionEndpointService(DetectionDao detectionDao, AssetDao assetDao, AttachmentDao attachmentDao,
		BinaryStorageResolver storageResolver, LoomModelBuilder modelBuilder, LoomModelValidator validator) {
		super(modelBuilder, validator);
		this.dao = detectionDao;
		this.assetDao = assetDao;
		this.attachmentDao = attachmentDao;
		this.storageResolver = storageResolver;
	}

	public DetectionDao dao() {
		return dao;
	}

	/**
	 * {@code GET /api/v1/assets/:uuid/detections/:detectionUuid/crop} - the cropped face, as an image.
	 *
	 * <p>
	 * Served from the deployment's own storage. Face crops are biometric data, and the review UI previously stood them in with portraits fetched from
	 * a third-party avatar service - which leaked detection uuids to that service and showed the reviewer somebody else's face.
	 * </p>
	 *
	 * <p>
	 * The bytes are written by the producing node from the crop it already cut to compute the embedding, rather than re-derived here. That is not only
	 * cheaper: the server container ships without any native imaging libraries, so it cannot decode a video frame at all, and re-cropping would work
	 * for stills and silently fail for every video.
	 * </p>
	 *
	 * <p>
	 * A crop is a pure function of an immutable row, so it is safe to cache hard.
	 * </p>
	 */
	public void loadDetectionCrop(LoomRoutingContext lrc, AssetId assetId, UUID detectionUuid) {
		checkPerm(lrc, Permission.READ_DETECTION, () -> {
			UUID assetUuid = resolveAssetUuid(assetId);
			Detection detection = dao.load(detectionUuid);
			if (detection == null || assetUuid == null || !assetUuid.equals(detection.getAssetUuid())) {
				// A detection belonging to another asset is answered as missing rather than as forbidden: the
				// pairing is part of the address, and confirming that the uuid exists elsewhere leaks it.
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Detection not found.");
			}

			Attachment crop = attachmentDao.findFaceCrop(detectionUuid, null);
			if (crop == null || crop.getSha512sum() == null) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND,
					"No face crop has been stored for this detection. Crops are written by the face-detection node when it runs.");
			}

			BinaryStorage storage = storageResolver.forPool(crop.getPoolUuid());
			String locator = storage.locatorFor(crop.getSha512sum());
			if (!storage.exists(locator)) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND,
					"The face crop's bytes are missing in " + storage.describe() + ".");
			}

			HttpServerResponse response = lrc.routingContext().response();
			String etag = "\"" + detectionUuid + "-" + crop.getSha512sum().toString().substring(0, 16) + "\"";
			if (etag.equals(lrc.routingContext().request().getHeader(HttpHeaders.IF_NONE_MATCH))) {
				response.setStatusCode(304).end();
				return;
			}
			response.putHeader(HttpHeaders.CONTENT_TYPE, crop.getMimeType() == null ? "image/jpeg" : crop.getMimeType());
			response.putHeader(HttpHeaders.ETAG, etag);
			// private: a face crop is not something a shared cache should hold.
			response.putHeader(HttpHeaders.CACHE_CONTROL, "private, max-age=86400, immutable");

			Optional<Path> local = storage.localPath(locator);
			if (local.isPresent()) {
				response.sendFile(local.get().toString());
				return;
			}
			long size = storage.size(locator);
			if (size >= 0) {
				response.putHeader(HttpHeaders.CONTENT_LENGTH, String.valueOf(size));
			} else {
				// Vert.x refuses a write with neither a Content-Length nor chunked encoding.
				response.setChunked(true);
			}
			try (InputStream in = storage.read(locator, 0, -1)) {
				byte[] buffer = new byte[64 * 1024];
				int read;
				while ((read = in.read(buffer)) > 0) {
					response.write(Buffer.buffer(java.util.Arrays.copyOf(buffer, read)));
				}
				response.end();
			} catch (Exception e) {
				log.error("Failed to stream the face crop for detection {}", detectionUuid, e);
				if (!response.headWritten()) {
					throw new LoomRestException(500, LoomRestErrorCode.INTERNAL_ERROR, "Could not read the face crop.");
				}
			}
		});
	}

	// --- Helper methods ---

	protected void create(LoomRoutingContext lrc, Permission permission, DetectionCreator creator) {
		checkPerm(lrc, permission, () -> {
			DetectionCreateRequest request = lrc.requestBody(DetectionCreateRequest.class);
			validator.validate(request);

			UUID userUuid = lrc.userUuid();
			String type = request.getType();
			Detection element = creator.createDetection(userUuid, type);
			applyCreateFields(request, element);
			dao().store(element);
			DetectionResponse response = modelBuilder.toResponse(element);
			lrc.send(response, 201);
		});
	}

	protected void delete(LoomRoutingContext lrc, Permission permission, Supplier<Detection> loader) {
		checkPerm(lrc, permission, () -> {
			Detection detection = loader.get();
			if (detection == null) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Detection not found.");
			} else {
				dao().delete(detection);
				lrc.sendNoContent();
			}
		});
	}

	protected void load(LoomRoutingContext lrc, Permission permission, Supplier<Detection> loader) {
		checkPerm(lrc, permission, () -> {
			Detection detection = loader.get();
			if (detection == null) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Detection not found");
			}
			DetectionResponse response = modelBuilder.toResponse(detection);
			lrc.send(response);
		});
	}

	protected void update(LoomRoutingContext lrc, Permission permission, Supplier<Detection> loader) {
		checkPerm(lrc, permission, () -> {
			DetectionUpdateRequest request = lrc.requestBody(DetectionUpdateRequest.class);
			validator.validate(request);

			Detection detection = loader.get();
			if (detection == null) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Detection not found");
			}
			applyUpdateFields(request, detection);
			UUID userUuid = lrc.userUuid();
			setEditor(detection, userUuid);
			dao().update(detection);
			DetectionResponse response = modelBuilder.toResponse(detection);
			lrc.send(response, 200);
		});
	}

	protected void list(LoomRoutingContext lrc, Permission permission, DetectionPageLoader loader) {
		checkPerm(lrc, permission, () -> {
			PagingParameters pagingParameters = lrc.pagingParams();
			FilterParameters filterParameters = lrc.filterParams();
			SortParameters sortParameters = lrc.sortParams();
			UUID from = pagingParameters.from();
			int limit = pagingParameters.limit();
			if (log.isDebugEnabled()) {
				log.debug("Loading page from {} limit: {}", from, limit);
			}
			Page<Detection> page = loader.loadPage(from, limit, filterParameters.filters(), sortParameters.sortBy(), sortParameters.sortOrder());
			DetectionListResponse response = modelBuilder.toDetectionList(page);
			lrc.send(response);
		});
	}

	// --- Asset Detection CRUD ---

	public void createAssetDetection(LoomRoutingContext lrc, AssetId assetId) {
		UUID assetUuid = resolveAssetUuid(assetId);
		create(lrc, CREATE_DETECTION, (userUuid, type) -> dao().createDetection(userUuid, type).setAssetUuid(assetUuid));
	}

	public void deleteAssetDetection(LoomRoutingContext lrc, AssetId assetId, UUID detectionUuid) {
		delete(lrc, DELETE_DETECTION, () -> dao().loadAssetDetection(assetId, detectionUuid));
	}

	public void listAssetDetections(LoomRoutingContext lrc, AssetId assetId) {
		list(lrc, READ_DETECTION,
			(fromId, pageSize, filters, sortBy, sortDirection) -> dao().loadPageForAsset(assetId, fromId, pageSize, filters, sortBy, sortDirection));
	}

	public void loadAssetDetection(LoomRoutingContext lrc, AssetId assetId, UUID detectionUuid) {
		load(lrc, READ_DETECTION, () -> dao().loadAssetDetection(assetId, detectionUuid));
	}

	public void updateAssetDetection(LoomRoutingContext lrc, AssetId assetId, UUID detectionUuid) {
		update(lrc, UPDATE_DETECTION, () -> dao().loadAssetDetection(assetId, detectionUuid));
	}

	// --- Bulk ---

	public void bulkCreateAssetDetections(LoomRoutingContext lrc, AssetId assetId) {
		checkPerm(lrc, CREATE_DETECTION, () -> {
			UUID assetUuid = resolveAssetUuid(assetId);
			DetectionBulkCreateRequest request = lrc.requestBody(DetectionBulkCreateRequest.class);
			List<DetectionCreateRequest> items = request.getDetections();
			UUID userUuid = lrc.userUuid();

			DetectionBulkResponse response = new DetectionBulkResponse();
			response.setTotal(items.size());
			int created = 0;
			int failed = 0;

			List<Detection> detections = new ArrayList<>();
			int index = 0;
			for (DetectionCreateRequest itemRequest : items) {
				try {
					validator.validate(itemRequest);
					String type = itemRequest.getType();
					Detection detection = dao().createDetection(userUuid, type);
					detection.setAssetUuid(assetUuid);
					applyCreateFields(itemRequest, detection);
					// When the caller does not number its detections, assign the index from batch position so each row has a distinct natural key
					// (asset, node_kind, frame_number, detection_index) and a node re-run upserts rather than duplicates.
					if (itemRequest.getDetectionIndex() == null) {
						detection.setDetectionIndex(index);
					}
					detections.add(detection);
				} catch (Exception e) {
					log.warn("Bulk create detection item failed: {}", e.getMessage());
					failed++;
				}
				index++;
			}

			// Upsert each on its natural key so re-running the producing node replaces its own rows instead of appending duplicates.
			for (Detection detection : detections) {
				dao().upsertDetection(detection);
				response.add(modelBuilder.toResponse(detection));
				created++;
			}

			response.setCreated(created);
			response.setFailed(failed);
			lrc.send(response, 201);
		});
	}

	// --- Review ---

	/**
	 * {@code POST /api/v1/assets/:uuid/detections/:detectionUuid/confirm} - the producer found something real here.
	 *
	 * <p>
	 * An RPC-style sub-resource rather than a field write, matching the cluster review routes. The verdict is not an editable property: it stamps a
	 * reviewer and a time, and it must not be reachable through the generic update path where a client could set it without either.
	 * </p>
	 *
	 * <p>
	 * The body is optional. Supplying {@code correctedLabel} is still a confirmation - the box was right, only the class was wrong - and the original
	 * label is kept alongside it.
	 * </p>
	 */
	public void confirmAssetDetection(LoomRoutingContext lrc, AssetId assetId, UUID detectionUuid) {
		review(lrc, assetId, detectionUuid, ReviewStatus.CONFIRMED, () -> optionalCorrectedLabel(lrc));
	}

	/**
	 * Read {@code correctedLabel} from a body that may legitimately not be there.
	 *
	 * <p>
	 * The emptiness check is on the raw buffer rather than a try/catch around the parse, so that "no body" and "malformed body" stay distinguishable:
	 * swallowing a parse failure here would silently drop a correction the reviewer typed.
	 * </p>
	 */
	private static String optionalCorrectedLabel(LoomRoutingContext lrc) {
		RequestBody body = lrc.routingContext().body();
		if (body == null || body.buffer() == null || body.buffer().length() == 0) {
			return null;
		}
		DetectionConfirmRequest request = lrc.requestBody(DetectionConfirmRequest.class);
		return request == null ? null : request.getCorrectedLabel();
	}

	/**
	 * {@code POST /api/v1/assets/:uuid/detections/:detectionUuid/reject} - a false positive.
	 *
	 * <p>
	 * The row is kept rather than deleted. A rejected detection is the record that the producer was wrong here, which is what makes it useful as
	 * training feedback and what stops the next run from presenting the same box again as new.
	 * </p>
	 */
	public void rejectAssetDetection(LoomRoutingContext lrc, AssetId assetId, UUID detectionUuid) {
		review(lrc, assetId, detectionUuid, ReviewStatus.REJECTED, () -> null);
	}

	private void review(LoomRoutingContext lrc, AssetId assetId, UUID detectionUuid, String status, Supplier<String> correctedLabel) {
		checkPerm(lrc, UPDATE_DETECTION, () -> {
			Detection detection = dao().loadAssetDetection(assetId, detectionUuid);
			if (detection == null) {
				// A detection belonging to another asset is answered as missing rather than as forbidden: the pairing is part of the address.
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Detection not found.");
			}
			Detection reviewed = dao().updateReview(detectionUuid, status, correctedLabel.get(), lrc.userUuid());
			lrc.send(modelBuilder.toResponse(reviewed), 200);
		});
	}

	/**
	 * {@code POST /api/v1/assets/:uuid/detections/review-bulk} - every verdict from one pass over an asset.
	 *
	 * <p>
	 * Reports {@code {total, created, failed}} like the bulk-create route it sits next to. A bad item is counted and skipped rather than failing the
	 * batch: losing twenty good decisions because the twenty-first named a stale uuid would be worse than reporting the one that did not apply.
	 * </p>
	 */
	public void bulkReviewAssetDetections(LoomRoutingContext lrc, AssetId assetId) {
		checkPerm(lrc, UPDATE_DETECTION, () -> {
			DetectionBulkReviewRequest request = lrc.requestBody(DetectionBulkReviewRequest.class);
			List<DetectionReviewItem> items = request.getReviews() == null ? List.of() : request.getReviews();
			UUID userUuid = lrc.userUuid();

			DetectionBulkResponse response = new DetectionBulkResponse();
			response.setTotal(items.size());
			int reviewed = 0;
			int failed = 0;

			for (DetectionReviewItem item : items) {
				try {
					UUID detectionUuid = UUID.fromString(item.getUuid());
					String status = requireStatus(item.getStatus());
					if (dao().loadAssetDetection(assetId, detectionUuid) == null) {
						throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Detection not found.");
					}
					Detection updated = dao().updateReview(detectionUuid, status, item.getCorrectedLabel(), userUuid);
					response.add(modelBuilder.toResponse(updated));
					reviewed++;
				} catch (Exception e) {
					log.warn("Bulk review item failed: {}", e.getMessage());
					failed++;
				}
			}

			// "created" is the count of rows the batch wrote, which for a review batch is the count it decided. The field name is the bulk-create
			// route's; keeping it means one response shape for every bulk endpoint rather than a near-duplicate that differs by one word.
			response.setCreated(reviewed);
			response.setFailed(failed);
			lrc.send(response, 200);
		});
	}

	/**
	 * {@code GET /api/v1/detections?status=PENDING&type=objectdetection} - the cross-asset review queue.
	 *
	 * <p>
	 * The filters are explicit query parameters rather than the generic filter machinery: the queue is the one question this top-level collection
	 * exists to answer, and an explicit parameter is what keeps it visible in the OpenAPI document and simple in every client.
	 * </p>
	 */
	public void listDetections(LoomRoutingContext lrc) {
		checkPerm(lrc, READ_DETECTION, () -> {
			String status = optionalStatus(firstQueryParam(lrc, "status"));
			String type = blankToNull(firstQueryParam(lrc, "type"));

			// pagingParams(), never lrc.pageSize() - the latter reads a path parameter and would ignore ?limit= entirely.
			PagingParameters paging = lrc.pagingParams();
			Page<Detection> page = dao().loadPage(status, type, paging.from(), paging.limit());
			lrc.send(modelBuilder.toDetectionList(page));
		});
	}

	// --- Private helpers ---

	private static String firstQueryParam(LoomRoutingContext lrc, String name) {
		List<String> values = lrc.queryParam(name);
		return values == null || values.isEmpty() ? null : values.get(0);
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	/** Null or blank means "any status", which is a valid filter. */
	private static String optionalStatus(String raw) {
		String status = blankToNull(raw);
		return status == null ? null : requireStatus(status);
	}

	/** Unlike {@link #optionalStatus(String)}, a missing verdict is a bad request - a review has to say something. */
	private static String requireStatus(String status) {
		if (!ReviewStatus.isValid(status)) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST, "The status must be one of PENDING, CONFIRMED, REJECTED.");
		}
		return status;
	}

	private UUID resolveAssetUuid(AssetId assetId) {
		if (assetId.isUUID()) {
			return assetId.uuid();
		} else {
			Asset asset = assetDao.loadBySHA512(assetId.hashsum());
			return asset.getUuid();
		}
	}

	private void applyCreateFields(DetectionCreateRequest request, Detection detection) {
		// Node-produced detections carry their own provenance and identity discriminators; manual detections leave these unset (node_kind stays
		// "manual", detection_index defaults to 0).
		if (request.getNodeKind() != null) {
			detection.setNodeKind(request.getNodeKind());
		}
		if (request.getProducerVersion() != null) {
			detection.setProducerVersion(request.getProducerVersion());
		}
		if (request.getLabel() != null) {
			detection.setLabel(request.getLabel());
		}
		if (request.getDetectionIndex() != null) {
			detection.setDetectionIndex(request.getDetectionIndex());
		}
		if (request.getFrameNumber() != null) {
			detection.setFrameNumber(request.getFrameNumber());
		}
		if (request.getBboxX() != null) {
			detection.setBboxX(request.getBboxX());
		}
		if (request.getBboxY() != null) {
			detection.setBboxY(request.getBboxY());
		}
		if (request.getBboxWidth() != null) {
			detection.setBboxWidth(request.getBboxWidth());
		}
		if (request.getBboxHeight() != null) {
			detection.setBboxHeight(request.getBboxHeight());
		}
		if (request.getConfidence() != null) {
			detection.setConfidence(request.getConfidence());
		}
		if (request.getMeta() != null) {
			detection.setMeta(request.getMeta());
		}
	}

	private void applyUpdateFields(DetectionUpdateRequest request, Detection detection) {
		if (request.getType() != null) {
			detection.setType(request.getType());
		}
		if (request.getFrameNumber() != null) {
			detection.setFrameNumber(request.getFrameNumber());
		}
		if (request.getBboxX() != null) {
			detection.setBboxX(request.getBboxX());
		}
		if (request.getBboxY() != null) {
			detection.setBboxY(request.getBboxY());
		}
		if (request.getBboxWidth() != null) {
			detection.setBboxWidth(request.getBboxWidth());
		}
		if (request.getBboxHeight() != null) {
			detection.setBboxHeight(request.getBboxHeight());
		}
		if (request.getConfidence() != null) {
			detection.setConfidence(request.getConfidence());
		}
		if (request.getMeta() != null) {
			detection.setMeta(request.getMeta());
		}
	}

	@FunctionalInterface
	interface DetectionCreator {
		Detection createDetection(UUID userUuid, String type);
	}

	@FunctionalInterface
	interface DetectionPageLoader {
		Page<Detection> loadPage(UUID fromId, int pageSize, List<Filter> filters, SortKey sortBy, SortDirection sortDirection);
	}

}
