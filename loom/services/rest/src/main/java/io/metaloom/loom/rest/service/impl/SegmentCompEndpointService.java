package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.READ_ASSET;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_ASSET;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.db.model.asset.AssetComponentDao;
import io.metaloom.loom.db.model.asset.AssetSegmentComp;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.segmentcomp.SegmentCompCreateRequest;
import io.metaloom.loom.rest.model.segmentcomp.SegmentCompListResponse;
import io.metaloom.loom.rest.model.segmentcomp.SegmentCompResponse;
import io.metaloom.loom.rest.model.segmentcomp.SegmentEntry;
import io.metaloom.loom.rest.service.AbstractEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;

/**
 * Endpoint service for the time-ranged segment component ({@code asset_segment_comp}): scenes, silence, shots, chapters.
 *
 * <p>
 * The create route is a whole-set replace for {@code (asset, node_kind, segment_type)}: a re-run that produces fewer segments deletes the surplus.
 * </p>
 */
@Singleton
public class SegmentCompEndpointService extends AbstractEndpointService {

	private final AssetComponentDao compDao;

	@Inject
	public SegmentCompEndpointService(AssetComponentDao compDao, LoomModelBuilder modelBuilder, LoomModelValidator validator) {
		super(modelBuilder, validator);
		this.compDao = compDao;
	}

	public void createSegmentComps(LoomRoutingContext lrc, UUID assetUuid) {
		checkPerm(lrc, UPDATE_ASSET, () -> {
			SegmentCompCreateRequest request = lrc.requestBody(SegmentCompCreateRequest.class);
			validator.validate(request);

			UUID userUuid = lrc.userUuid();
			String nodeKind = request.getNodeKind();
			String segmentType = request.getSegmentType();

			List<AssetSegmentComp> comps = new ArrayList<>();
			int seq = 0;
			for (SegmentEntry entry : request.getSegments()) {
				AssetSegmentComp comp = compDao.createSegmentComp(userUuid, assetUuid, nodeKind);
				comp.setSegmentType(segmentType);
				// seq assigned from list position so a caller never has to number segments by hand.
				comp.setSeq(seq++);
				comp.setTimeFrom(entry.getTimeFrom());
				comp.setTimeTo(entry.getTimeTo());
				comp.setTitle(entry.getTitle());
				comp.setScore(entry.getScore());
				if (request.getProducerVersion() != null) {
					comp.setProducerVersion(request.getProducerVersion());
				}
				comps.add(comp);
			}
			// Whole-set replace: upserts seq 0..N-1 and deletes surplus rows from a shorter re-run.
			List<AssetSegmentComp> stored = compDao.replaceSegmentComps(assetUuid, nodeKind, segmentType, comps);
			SegmentCompListResponse response = modelBuilder.toSegmentCompList(stored);
			lrc.send(response, 201);
		});
	}

	public void listSegmentComps(LoomRoutingContext lrc, UUID assetUuid) {
		checkPerm(lrc, READ_ASSET, () -> {
			List<AssetSegmentComp> comps = compDao.loadSegmentComps(assetUuid);
			SegmentCompListResponse response = modelBuilder.toSegmentCompList(comps);
			lrc.send(response);
		});
	}

	public void loadSegmentComp(LoomRoutingContext lrc, UUID assetUuid, UUID compUuid) {
		checkPerm(lrc, READ_ASSET, () -> {
			AssetSegmentComp comp = compDao.loadSegmentComp(compUuid);
			if (comp == null || !assetUuid.equals(comp.getAssetUuid())) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Segment component not found.");
			}
			SegmentCompResponse response = modelBuilder.toSegmentCompResponse(comp);
			lrc.send(response);
		});
	}

	public void deleteSegmentComp(LoomRoutingContext lrc, UUID assetUuid, UUID compUuid) {
		checkPerm(lrc, UPDATE_ASSET, () -> {
			AssetSegmentComp comp = compDao.loadSegmentComp(compUuid);
			if (comp == null || !assetUuid.equals(comp.getAssetUuid())) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Segment component not found.");
			}
			compDao.deleteSegmentComp(compUuid);
			lrc.sendNoContent();
		});
	}

}
