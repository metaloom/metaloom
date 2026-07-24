package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.READ_ASSET;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_ASSET;

import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.db.model.asset.AssetComponentDao;
import io.metaloom.loom.db.model.asset.AssetJsonComp;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompCreateRequest;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompListResponse;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompResponse;
import io.metaloom.loom.rest.service.AbstractEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.vertx.core.json.JsonObject;

/**
 * Endpoint service for the generic JSON component sink ({@code asset_json_comp}).
 *
 * <p>
 * This is the lightweight, customer-facing way for a cortex node to persist its result: post an opaque {@code data} payload tagged with
 * {@code node_kind}/{@code schema_type}/{@code variant} and it lands in {@code asset_json_comp} without a dedicated typed component table. Re-posting
 * the same key upserts the single row.
 * </p>
 */
@Singleton
public class JsonCompEndpointService extends AbstractEndpointService {

	private final AssetComponentDao compDao;

	@Inject
	public JsonCompEndpointService(AssetComponentDao compDao, LoomModelBuilder modelBuilder, LoomModelValidator validator) {
		super(modelBuilder, validator);
		this.compDao = compDao;
	}

	public void createJsonComp(LoomRoutingContext lrc, UUID assetUuid) {
		checkPerm(lrc, UPDATE_ASSET, () -> {
			JsonCompCreateRequest request = lrc.requestBody(JsonCompCreateRequest.class);
			validator.validate(request);

			UUID userUuid = lrc.userUuid();
			AssetJsonComp comp = compDao.createJsonComp(userUuid, assetUuid, request.getNodeKind());
			comp.setSchemaType(request.getSchemaType());
			comp.setVariant(request.getVariant() == null ? "" : request.getVariant());
			comp.setData(request.getData() == null ? new JsonObject() : request.getData());
			if (request.getProducerVersion() != null) {
				comp.setProducerVersion(request.getProducerVersion());
			}
			// Upsert on (asset_uuid, node_kind, schema_type, variant): re-running a node rewrites its single component row.
			AssetJsonComp stored = compDao.upsertJsonComp(comp);
			JsonCompResponse response = modelBuilder.toJsonCompResponse(stored);
			lrc.send(response, 201);
		});
	}

	public void listJsonComps(LoomRoutingContext lrc, UUID assetUuid) {
		checkPerm(lrc, READ_ASSET, () -> {
			List<AssetJsonComp> comps = compDao.loadJsonComps(assetUuid);
			JsonCompListResponse response = modelBuilder.toJsonCompList(comps);
			lrc.send(response);
		});
	}

	public void loadJsonComp(LoomRoutingContext lrc, UUID assetUuid, UUID compUuid) {
		checkPerm(lrc, READ_ASSET, () -> {
			AssetJsonComp comp = compDao.loadJsonComp(compUuid);
			if (comp == null || !assetUuid.equals(comp.getAssetUuid())) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Json component not found.");
			}
			JsonCompResponse response = modelBuilder.toJsonCompResponse(comp);
			lrc.send(response);
		});
	}

	public void deleteJsonComp(LoomRoutingContext lrc, UUID assetUuid, UUID compUuid) {
		checkPerm(lrc, UPDATE_ASSET, () -> {
			AssetJsonComp comp = compDao.loadJsonComp(compUuid);
			if (comp == null || !assetUuid.equals(comp.getAssetUuid())) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Json component not found.");
			}
			compDao.deleteJsonComp(compUuid);
			lrc.sendNoContent();
		});
	}

}
