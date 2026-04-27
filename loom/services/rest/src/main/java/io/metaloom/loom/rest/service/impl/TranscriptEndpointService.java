package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.READ_ASSET;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_ASSET;

import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.db.model.asset.AssetComponentDao;
import io.metaloom.loom.db.model.asset.AssetTranscriptComp;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.transcript.TranscriptCreateRequest;
import io.metaloom.loom.rest.model.transcript.TranscriptListResponse;
import io.metaloom.loom.rest.model.transcript.TranscriptResponse;
import io.metaloom.loom.rest.model.transcript.TranscriptUpdateRequest;
import io.metaloom.loom.rest.service.AbstractEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;

@Singleton
public class TranscriptEndpointService extends AbstractEndpointService {

	private static final Logger log = LoggerFactory.getLogger(TranscriptEndpointService.class);

	private final AssetComponentDao compDao;

	@Inject
	public TranscriptEndpointService(AssetComponentDao compDao, LoomModelBuilder modelBuilder, LoomModelValidator validator) {
		super(modelBuilder, validator);
		this.compDao = compDao;
	}

	public void createAssetTranscript(LoomRoutingContext lrc, UUID assetUuid) {
		checkPerm(lrc, UPDATE_ASSET, () -> {
			TranscriptCreateRequest request = lrc.requestBody(TranscriptCreateRequest.class);
			validator.validate(request);

			UUID userUuid = lrc.userUuid();
			String source = request.getSource();

			AssetTranscriptComp comp = compDao.createTranscriptComp(userUuid, assetUuid, source);
			if (request.getLang() != null) {
				comp.setLang(request.getLang());
			}
			if (request.getTranscriptText() != null) {
				comp.setTranscriptText(request.getTranscriptText());
			}
			if (request.getDuration() != null) {
				comp.setDuration(request.getDuration());
			}
			if (request.getModel() != null) {
				comp.setModel(request.getModel());
			}
			if (request.getTranscriptJson() != null) {
				comp.setTranscriptJson(request.getTranscriptJson());
			}
			compDao.storeTranscriptComp(comp);
			TranscriptResponse response = modelBuilder.toTranscriptResponse(comp);
			lrc.send(response, 201);
		});
	}

	public void listAssetTranscripts(LoomRoutingContext lrc, UUID assetUuid) {
		checkPerm(lrc, READ_ASSET, () -> {
			List<AssetTranscriptComp> comps = compDao.loadTranscriptComps(assetUuid);
			TranscriptListResponse response = modelBuilder.toTranscriptList(comps);
			lrc.send(response);
		});
	}

	public void loadAssetTranscript(LoomRoutingContext lrc, UUID assetUuid, UUID transcriptUuid) {
		checkPerm(lrc, READ_ASSET, () -> {
			AssetTranscriptComp comp = compDao.loadTranscriptComp(transcriptUuid);
			if (comp == null || !assetUuid.equals(comp.getAssetUuid())) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Transcript not found.");
			}
			TranscriptResponse response = modelBuilder.toTranscriptResponse(comp);
			lrc.send(response);
		});
	}

	public void updateAssetTranscript(LoomRoutingContext lrc, UUID assetUuid, UUID transcriptUuid) {
		checkPerm(lrc, UPDATE_ASSET, () -> {
			TranscriptUpdateRequest request = lrc.requestBody(TranscriptUpdateRequest.class);
			validator.validate(request);

			AssetTranscriptComp comp = compDao.loadTranscriptComp(transcriptUuid);
			if (comp == null || !assetUuid.equals(comp.getAssetUuid())) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Transcript not found.");
			}
			if (request.getSource() != null) {
				comp.setSource(request.getSource());
			}
			if (request.getLang() != null) {
				comp.setLang(request.getLang());
			}
			if (request.getTranscriptText() != null) {
				comp.setTranscriptText(request.getTranscriptText());
			}
			if (request.getDuration() != null) {
				comp.setDuration(request.getDuration());
			}
			if (request.getModel() != null) {
				comp.setModel(request.getModel());
			}
			if (request.getTranscriptJson() != null) {
				comp.setTranscriptJson(request.getTranscriptJson());
			}
			UUID userUuid = lrc.userUuid();
			setEditor(comp, userUuid);
			compDao.updateTranscriptComp(comp);
			TranscriptResponse response = modelBuilder.toTranscriptResponse(comp);
			lrc.send(response);
		});
	}

	public void deleteAssetTranscript(LoomRoutingContext lrc, UUID assetUuid, UUID transcriptUuid) {
		checkPerm(lrc, UPDATE_ASSET, () -> {
			AssetTranscriptComp comp = compDao.loadTranscriptComp(transcriptUuid);
			if (comp == null || !assetUuid.equals(comp.getAssetUuid())) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Transcript not found.");
			}
			compDao.deleteTranscriptComp(transcriptUuid);
			lrc.sendNoContent();
		});
	}

}
