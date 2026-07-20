package io.metaloom.loom.rest.service.impl;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.pipeline.Pipeline;
import io.metaloom.loom.db.model.pipeline.PipelineVersion;
import io.metaloom.loom.rest.model.pipeline.PipelineRunResponse;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

/**
 * Listens for {@code asset.created} events and, when a pipeline is configured to auto-process the asset's mime type, triggers an asset-scoped run.
 *
 * <p>
 * The event bus consumer is registered once at startup via {@link #register()}. Matching and dispatch run on a worker thread because the DAO and
 * dispatch calls are blocking.
 * </p>
 *
 * @see PipelineMatcher
 * @see AssetEventPublisher
 */
@Singleton
public class AssetPipelineTrigger {

	private static final Logger log = LoggerFactory.getLogger(AssetPipelineTrigger.class);

	private final Vertx vertx;
	private final DaoCollection daos;
	private final PipelineEndpointService pipelineService;

	@Inject
	public AssetPipelineTrigger(Vertx vertx, DaoCollection daos, PipelineEndpointService pipelineService) {
		this.vertx = vertx;
		this.daos = daos;
		this.pipelineService = pipelineService;
	}

	/**
	 * Register the event bus consumer. Must be called once during startup.
	 */
	public void register() {
		vertx.eventBus().<JsonObject>consumer(AssetEventPublisher.ASSET_CREATED_ADDRESS, msg -> onAssetCreated(msg.body()));
		log.info("Registered asset-created pipeline trigger on '{}'", AssetEventPublisher.ASSET_CREATED_ADDRESS);
	}

	private void onAssetCreated(JsonObject body) {
		if (body == null) {
			return;
		}
		vertx.<Void>executeBlocking(() -> {
			handle(body);
			return null;
		}).onFailure(err -> log.error("Failed to auto-trigger pipeline for asset event {}", body, err));
	}

	private void handle(JsonObject body) {
		String assetUuidStr = body.getString(AssetEventPublisher.FIELD_ASSET_UUID);
		String mimeType = body.getString(AssetEventPublisher.FIELD_MIME_TYPE);
		if (assetUuidStr == null) {
			return;
		}
		UUID assetUuid = UUID.fromString(assetUuidStr);

		Optional<PipelineVersion> match = PipelineMatcher.selectForMimeType(latestVersions(), mimeType);
		if (match.isEmpty()) {
			log.debug("No matching pipeline for asset {} ({})", assetUuid, mimeType);
			return;
		}

		PipelineVersion version = match.get();
		Asset asset = daos.assetDao().load(assetUuid);
		UUID userUuid = asset != null ? asset.getCreatorUuid() : null;

		PipelineRunResponse response = pipelineService.runForAsset(version.getPipelineUuid(), assetUuid, userUuid);
		log.info("Auto-triggered pipeline {} for asset {} ({}): dispatched={} ({})",
			version.getPipelineUuid(), assetUuid, mimeType, response.isDispatched(), response.getMessage());
	}

	/**
	 * Load the latest version of every pipeline in a single batch, so matching does not do an N+1 lookup.
	 */
	private List<PipelineVersion> latestVersions() {
		List<UUID> versionUuids = daos.pipelineDao().findAll()
			.map(Pipeline::getLatestVersionUuid)
			.filter(Objects::nonNull)
			.collect(Collectors.toList());
		if (versionUuids.isEmpty()) {
			return List.of();
		}
		return daos.pipelineVersionDao().loadByUuids(versionUuids);
	}
}
