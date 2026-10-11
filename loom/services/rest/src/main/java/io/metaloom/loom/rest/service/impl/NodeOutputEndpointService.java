package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.READ_ASSET;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_ASSET;

import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.api.asset.AssetId;
import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.asset.AssetDao;
import io.metaloom.loom.db.model.asset.AssetNodeOutput;
import io.metaloom.loom.db.model.asset.AssetNodeOutputDao;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.nodeoutput.NodeOutputCreateRequest;
import io.metaloom.loom.rest.model.nodeoutput.NodeOutputLookupResponse;
import io.metaloom.loom.rest.service.AbstractEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;

/**
 * Endpoint service for node output reuse ({@code asset_node_output}, V2.116).
 *
 * <p>
 * A node asks {@link #lookup} before it starts work and replays the stored output on a hit; after it computed, it stores what it emitted with
 * {@link #createAssetNodeOutput}. The lookup returns the asset too, so the check costs a node nothing over the asset load it made anyway.
 * </p>
 */
@Singleton
public class NodeOutputEndpointService extends AbstractEndpointService {

	private final AssetNodeOutputDao outputDao;
	private final AssetDao assetDao;

	@Inject
	public NodeOutputEndpointService(AssetNodeOutputDao outputDao, AssetDao assetDao, LoomModelBuilder modelBuilder,
		LoomModelValidator validator) {
		super(modelBuilder, validator);
		this.outputDao = outputDao;
		this.assetDao = assetDao;
	}

	/**
	 * Answer "has this node already computed this, with these settings?" together with the asset itself.
	 */
	public void lookup(LoomRoutingContext lrc, AssetId assetId, String nodeKind, String reuseKey) {
		checkPerm(lrc, READ_ASSET, () -> {
			Asset asset = loadAsset(assetId);
			AssetNodeOutput output = outputDao.loadByKey(asset.getUuid(), nodeKind, reuseKey);
			NodeOutputLookupResponse response = new NodeOutputLookupResponse()
				.setAsset(modelBuilder.toResponse(asset))
				.setOutput(output == null ? null : modelBuilder.toNodeOutputResponse(output));
			lrc.send(response);
		});
	}

	public void createAssetNodeOutput(LoomRoutingContext lrc, UUID assetUuid) {
		checkPerm(lrc, UPDATE_ASSET, () -> {
			NodeOutputCreateRequest request = lrc.requestBody(NodeOutputCreateRequest.class);
			validator.validate(request);
			// The foreign key would refuse an unknown asset too, but as a 500; this names it.
			loadAsset(AssetId.assetId(assetUuid));
			AssetNodeOutput stored = outputDao.upsert(assetUuid, request.getNodeKind(), request.getReuseKey(), request.getNodeId(),
				request.getProducerVersion(), request.getState(), request.getReason(), request.getOutputs(),
				request.getRunUuid() == null ? null : UUID.fromString(request.getRunUuid()));
			lrc.send(modelBuilder.toNodeOutputResponse(stored), 201);
		});
	}

	public void listAssetNodeOutputs(LoomRoutingContext lrc, UUID assetUuid) {
		checkPerm(lrc, READ_ASSET, () -> {
			loadAsset(AssetId.assetId(assetUuid));
			List<AssetNodeOutput> outputs = outputDao.listByAsset(assetUuid, nodeKindFilter(lrc));
			lrc.send(modelBuilder.toNodeOutputList(outputs));
		});
	}

	/**
	 * Forget stored outputs, so the next run of those nodes computes again. The typed results the nodes wrote are untouched.
	 */
	public void deleteAssetNodeOutputs(LoomRoutingContext lrc, UUID assetUuid) {
		checkPerm(lrc, UPDATE_ASSET, () -> {
			loadAsset(AssetId.assetId(assetUuid));
			outputDao.deleteByAsset(assetUuid, nodeKindFilter(lrc));
			lrc.sendNoContent();
		});
	}

	private Asset loadAsset(AssetId assetId) {
		Asset asset = assetId.isUUID() ? assetDao.load(assetId.uuid()) : assetDao.loadBySHA512(assetId.hashsum());
		if (asset == null) {
			throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Asset not found.");
		}
		return asset;
	}

	private static String nodeKindFilter(LoomRoutingContext lrc) {
		List<String> values = lrc.queryParam("nodeKind");
		if (values == null || values.isEmpty() || values.get(0) == null || values.get(0).isBlank()) {
			return null;
		}
		return values.get(0);
	}

}
