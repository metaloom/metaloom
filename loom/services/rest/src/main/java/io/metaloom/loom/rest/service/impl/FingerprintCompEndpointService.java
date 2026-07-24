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
import io.metaloom.loom.db.model.asset.AssetFingerprintComp;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.fingerprintcomp.FingerprintCompCreateRequest;
import io.metaloom.loom.rest.model.fingerprintcomp.FingerprintCompListResponse;
import io.metaloom.loom.rest.model.fingerprintcomp.FingerprintCompResponse;
import io.metaloom.loom.rest.service.AbstractEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;

/**
 * Endpoint service for the perceptual fingerprint component ({@code asset_fingerprint_comp}).
 *
 * <p>
 * A cortex node posts its computed fingerprint tagged with {@code node_kind}/{@code algorithm}/{@code sector_index}; re-posting the same key upserts
 * the single row.
 * </p>
 */
@Singleton
public class FingerprintCompEndpointService extends AbstractEndpointService {

	private final AssetComponentDao compDao;

	@Inject
	public FingerprintCompEndpointService(AssetComponentDao compDao, LoomModelBuilder modelBuilder, LoomModelValidator validator) {
		super(modelBuilder, validator);
		this.compDao = compDao;
	}

	public void createFingerprintComp(LoomRoutingContext lrc, UUID assetUuid) {
		checkPerm(lrc, UPDATE_ASSET, () -> {
			FingerprintCompCreateRequest request = lrc.requestBody(FingerprintCompCreateRequest.class);
			validator.validate(request);

			UUID userUuid = lrc.userUuid();
			AssetFingerprintComp comp = compDao.createFingerprintComp(userUuid, assetUuid, request.getNodeKind());
			// algorithm and sector_index are part of the component identity and must be set before the upsert so the natural key matches.
			comp.setAlgorithm(request.getAlgorithm());
			comp.setSectorIndex(request.getSectorIndex());
			comp.setFingerprint(request.getFingerprint());
			comp.setTimeFrom(request.getTimeFrom());
			comp.setTimeTo(request.getTimeTo());
			if (request.getProducerVersion() != null) {
				comp.setProducerVersion(request.getProducerVersion());
			}
			// Upsert on (asset_uuid, node_kind, algorithm, sector_index): re-running the fingerprint node replaces its own row.
			AssetFingerprintComp stored = compDao.upsertFingerprintComp(comp);
			FingerprintCompResponse response = modelBuilder.toFingerprintCompResponse(stored);
			lrc.send(response, 201);
		});
	}

	public void listFingerprintComps(LoomRoutingContext lrc, UUID assetUuid) {
		checkPerm(lrc, READ_ASSET, () -> {
			List<AssetFingerprintComp> comps = compDao.loadFingerprintComps(assetUuid);
			FingerprintCompListResponse response = modelBuilder.toFingerprintCompList(comps);
			lrc.send(response);
		});
	}

	public void loadFingerprintComp(LoomRoutingContext lrc, UUID assetUuid, UUID compUuid) {
		checkPerm(lrc, READ_ASSET, () -> {
			AssetFingerprintComp comp = compDao.loadFingerprintComp(compUuid);
			if (comp == null || !assetUuid.equals(comp.getAssetUuid())) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Fingerprint component not found.");
			}
			FingerprintCompResponse response = modelBuilder.toFingerprintCompResponse(comp);
			lrc.send(response);
		});
	}

	public void deleteFingerprintComp(LoomRoutingContext lrc, UUID assetUuid, UUID compUuid) {
		checkPerm(lrc, UPDATE_ASSET, () -> {
			AssetFingerprintComp comp = compDao.loadFingerprintComp(compUuid);
			if (comp == null || !assetUuid.equals(comp.getAssetUuid())) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Fingerprint component not found.");
			}
			compDao.deleteFingerprintComp(compUuid);
			lrc.sendNoContent();
		});
	}

}
