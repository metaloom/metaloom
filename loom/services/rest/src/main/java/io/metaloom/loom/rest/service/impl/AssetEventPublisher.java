package io.metaloom.loom.rest.service.impl;

import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

/**
 * Publishes asset lifecycle events onto the Vert.x event bus so that other components (e.g. the pipeline auto-trigger) can react to them without the
 * asset endpoints having to know about them.
 */
@Singleton
public class AssetEventPublisher {

	/**
	 * Event bus address on which a JSON payload {@code {assetUuid, mimeType}} is published after an asset has been fully created.
	 */
	public static final String ASSET_CREATED_ADDRESS = "loom.asset.created";

	public static final String FIELD_ASSET_UUID = "assetUuid";

	public static final String FIELD_MIME_TYPE = "mimeType";

	private final Vertx vertx;

	@Inject
	public AssetEventPublisher(Vertx vertx) {
		this.vertx = vertx;
	}

	/**
	 * Publish an {@code asset.created} event for the given asset.
	 *
	 * @param assetUuid
	 *            the created asset
	 * @param mimeType
	 *            the asset mime type (used for pipeline matching); may be {@code null}
	 */
	public void publishCreated(UUID assetUuid, String mimeType) {
		JsonObject payload = new JsonObject().put(FIELD_ASSET_UUID, assetUuid.toString());
		if (mimeType != null) {
			payload.put(FIELD_MIME_TYPE, mimeType);
		}
		vertx.eventBus().publish(ASSET_CREATED_ADDRESS, payload);
	}
}
