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
import io.metaloom.loom.api.asset.AssetId;
import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.api.sort.SortDirection;
import io.metaloom.loom.api.sort.SortKey;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.asset.AssetDao;
import io.metaloom.loom.db.model.detection.Detection;
import io.metaloom.loom.db.model.detection.DetectionDao;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.detection.DetectionBulkCreateRequest;
import io.metaloom.loom.rest.model.detection.DetectionBulkResponse;
import io.metaloom.loom.rest.model.detection.DetectionCreateRequest;
import io.metaloom.loom.rest.model.detection.DetectionListResponse;
import io.metaloom.loom.rest.model.detection.DetectionResponse;
import io.metaloom.loom.rest.model.detection.DetectionUpdateRequest;
import io.metaloom.loom.rest.parameter.FilterParameters;
import io.metaloom.loom.rest.parameter.PagingParameters;
import io.metaloom.loom.rest.parameter.SortParameters;
import io.metaloom.loom.rest.service.AbstractEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;

@Singleton
public class DetectionEndpointService extends AbstractEndpointService {

	private static final Logger log = LoggerFactory.getLogger(DetectionEndpointService.class);

	private final DetectionDao dao;

	private final AssetDao assetDao;

	@Inject
	public DetectionEndpointService(DetectionDao detectionDao, AssetDao assetDao, LoomModelBuilder modelBuilder, LoomModelValidator validator) {
		super(modelBuilder, validator);
		this.dao = detectionDao;
		this.assetDao = assetDao;
	}

	public DetectionDao dao() {
		return dao;
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

	// --- Private helpers ---

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
