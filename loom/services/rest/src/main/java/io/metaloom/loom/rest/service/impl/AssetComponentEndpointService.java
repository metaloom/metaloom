package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.READ_ASSET;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_ASSET;

import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.db.model.asset.AssetAudioComp;
import io.metaloom.loom.db.model.asset.AssetComponent;
import io.metaloom.loom.db.model.asset.AssetComponentDao;
import io.metaloom.loom.db.model.asset.AssetDocComp;
import io.metaloom.loom.db.model.asset.AssetGeoComp;
import io.metaloom.loom.db.model.asset.AssetImageComp;
import io.metaloom.loom.db.model.asset.AssetJsonComp;
import io.metaloom.loom.db.model.asset.AssetTranscriptComp;
import io.metaloom.loom.db.model.asset.AssetVideoComp;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.asset.AssetComponentCreateRequest;
import io.metaloom.loom.rest.model.asset.AssetComponentListResponse;
import io.metaloom.loom.rest.model.asset.AssetComponentResponse;
import io.metaloom.loom.rest.model.asset.AssetComponentType;
import io.metaloom.loom.rest.model.asset.AssetComponentUpdateRequest;
import io.metaloom.loom.rest.model.asset.info.AudioInfo;
import io.metaloom.loom.rest.model.asset.info.DocumentInfo;
import io.metaloom.loom.rest.model.asset.info.GeoLocationInfo;
import io.metaloom.loom.rest.model.asset.info.ImageInfo;
import io.metaloom.loom.rest.model.asset.info.JsonComponentInfo;
import io.metaloom.loom.rest.model.asset.info.TranscriptInfo;
import io.metaloom.loom.rest.model.asset.info.VideoInfo;
import io.metaloom.loom.rest.service.AbstractEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;

@Singleton
public class AssetComponentEndpointService extends AbstractEndpointService {

	private final AssetComponentDao compDao;

	@Inject
	public AssetComponentEndpointService(AssetComponentDao compDao, LoomModelBuilder modelBuilder, LoomModelValidator validator) {
		super(modelBuilder, validator);
		this.compDao = compDao;
	}

	public void list(LoomRoutingContext lrc, UUID assetUuid) {
		checkPerm(lrc, READ_ASSET, () -> {
			AssetComponentListResponse response = modelBuilder.toComponentList(assetUuid);
			lrc.send(response);
		});
	}

	/**
	 * Insert or replace the component identified by its type, node kind and discriminators.
	 *
	 * <p>
	 * This is an <b>upsert</b>, not a plain insert: every component table carries a
	 * {@code UNIQUE (asset_uuid, node_kind, &lt;discriminators&gt;)} key, so a Cortex node re-run - or
	 * any retried delivery - would otherwise fail on the second POST. The discriminators arrive on
	 * {@link AssetComponentCreateRequest}: {@code method}/{@code timeFrom} for geo,
	 * {@code streamIndex} for the media types, {@code pageNumber} for documents, and the schema type
	 * plus variant for JSON.
	 * </p>
	 */
	public void create(LoomRoutingContext lrc, UUID assetUuid) {
		checkPerm(lrc, UPDATE_ASSET, () -> {
			AssetComponentCreateRequest request = lrc.requestBody(AssetComponentCreateRequest.class);
			validator.validate(request);

			UUID userUuid = lrc.userUuid();
			AssetComponentType type = request.getType();
			String source = request.getSource();

			AssetComponentResponse response;
			switch (type) {
			case GEO: {
				AssetGeoComp comp = compDao.createGeoComp(userUuid, assetUuid, source);
				setProvenance(comp, request);
				comp.setMethod(request.getMethod() == null ? "" : request.getMethod());
				comp.setTimeFrom(request.getTimeFrom() == null ? 0L : request.getTimeFrom());
				GeoLocationInfo info = request.getGeo();
				if (info != null) {
					comp.setGeoLon(info.getLon());
					comp.setGeoLat(info.getLat());
					comp.setGeoAlias(info.getAlias());
					comp.setAccuracyM(info.getAccuracyM());
				}
				compDao.upsertGeoComp(comp);
				response = modelBuilder.toResponse(comp);
				break;
			}
			case IMAGE: {
				AssetImageComp comp = compDao.createImageComp(userUuid, assetUuid, source);
				setProvenance(comp, request);
				comp.setStreamIndex(streamIndex(request));
				ImageInfo info = request.getImage();
				if (info != null) {
					comp.setImageDominantColor(info.getDominantColor());
					comp.setMediaWidth(info.getWidth());
					comp.setMediaHeight(info.getHeight());
					comp.setOrientation(info.getOrientation());
					comp.setBitDepth(info.getBitDepth());
					comp.setImageEncoding(info.getEncoding());
				}
				compDao.upsertImageComp(comp);
				response = modelBuilder.toResponse(comp);
				break;
			}
			case VIDEO: {
				AssetVideoComp comp = compDao.createVideoComp(userUuid, assetUuid, source);
				setProvenance(comp, request);
				comp.setStreamIndex(streamIndex(request));
				VideoInfo info = request.getVideo();
				if (info != null) {
					comp.setVideoBitrate(info.getBitrate());
					comp.setVideoEncoding(info.getEncoding());
					comp.setMediaWidth(info.getWidth());
					comp.setMediaHeight(info.getHeight());
					comp.setMediaDuration(info.getDuration());
					comp.setFps(info.getFps());
					comp.setFrameCount(info.getFrameCount());
					comp.setRotation(info.getRotation());
				}
				compDao.upsertVideoComp(comp);
				response = modelBuilder.toResponse(comp);
				break;
			}
			case AUDIO: {
				AssetAudioComp comp = compDao.createAudioComp(userUuid, assetUuid, source);
				setProvenance(comp, request);
				comp.setStreamIndex(streamIndex(request));
				AudioInfo info = request.getAudio();
				if (info != null) {
					comp.setAudioBpm(info.getBpm());
					comp.setAudioBitrate(info.getBitrate());
					comp.setAudioChannels(info.getChannels());
					comp.setAudioEncoding(info.getEncoding());
					comp.setAudioSamplingRate(info.getSamplingRate());
					comp.setMediaDuration(info.getDuration());
					comp.setLang(info.getLang());
					comp.setTrackTitle(info.getTrackTitle());
					comp.setIsDefault(info.getIsDefault());
				}
				compDao.upsertAudioComp(comp);
				response = modelBuilder.toResponse(comp);
				break;
			}
			case DOC: {
				AssetDocComp comp = compDao.createDocComp(userUuid, assetUuid, source);
				setProvenance(comp, request);
				comp.setPageNumber(request.getPageNumber() == null ? 0 : request.getPageNumber());
				DocumentInfo info = request.getDocument();
				if (info != null) {
					comp.setDocPlainText(info.getPlainText());
					comp.setDocWordCount(info.getWordCount() != null ? info.getWordCount().intValue() : null);
					comp.setPageCount(info.getPageCount());
					comp.setTextLang(info.getTextLang());
				}
				compDao.upsertDocComp(comp);
				response = modelBuilder.toResponse(comp);
				break;
			}
			case TRANSCRIPT: {
				AssetTranscriptComp comp = compDao.createTranscriptComp(userUuid, assetUuid, source);
				setProvenance(comp, request);
				comp.setStreamIndex(streamIndex(request));
				TranscriptInfo info = request.getTranscript();
				if (info != null) {
					comp.setLang(info.getLang());
					comp.setTranscriptText(info.getTranscriptText());
					comp.setDuration(info.getDuration());
					comp.setModel(info.getModel());
					comp.setTranscriptJson(info.getTranscriptJson());
				}
				compDao.upsertTranscriptComp(comp);
				response = modelBuilder.toResponse(comp);
				break;
			}
			case JSON: {
				AssetJsonComp comp = compDao.createJsonComp(userUuid, assetUuid, source);
				setProvenance(comp, request);
				JsonComponentInfo info = request.getJson();
				if (info != null) {
					comp.setSchemaType(info.getSchemaType());
					comp.setVariant(info.getVariant() == null ? "" : info.getVariant());
					comp.setData(info.getData());
				}
				compDao.upsertJsonComp(comp);
				response = modelBuilder.toResponse(comp);
				break;
			}
			default:
				throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST, "Unknown component type: " + type);
			}
			lrc.send(response, 201);
		});
	}

	/**
	 * Apply the fields every component table shares - the producer provenance the shared component contract defines.
	 */
	private void setProvenance(AssetComponent<?> comp, AssetComponentCreateRequest request) {
		comp.setNodeId(request.getNodeId());
		comp.setProducerVersion(request.getProducerVersion() == null ? "" : request.getProducerVersion());
		comp.setConfidence(request.getConfidence());
		if (request.getMeta() != null) {
			comp.setMeta(request.getMeta());
		}
	}

	private int streamIndex(AssetComponentCreateRequest request) {
		return request.getStreamIndex() == null ? 0 : request.getStreamIndex();
	}

	public void load(LoomRoutingContext lrc, UUID assetUuid, UUID compUuid) {
		checkPerm(lrc, READ_ASSET, () -> {
			AssetComponentResponse response = findComponent(compUuid);
			if (response == null) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Component not found");
			}
			lrc.send(response);
		});
	}

	public void update(LoomRoutingContext lrc, UUID assetUuid, UUID compUuid) {
		checkPerm(lrc, UPDATE_ASSET, () -> {
			AssetComponentUpdateRequest request = lrc.requestBody(AssetComponentUpdateRequest.class);
			validator.validate(request);

			UUID userUuid = lrc.userUuid();
			AssetComponentResponse response = updateComponent(compUuid, request, userUuid);
			if (response == null) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Component not found");
			}
			lrc.send(response);
		});
	}

	public void delete(LoomRoutingContext lrc, UUID assetUuid, UUID compUuid) {
		checkPerm(lrc, UPDATE_ASSET, () -> {
			if (!deleteComponent(compUuid)) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Component not found");
			}
			lrc.sendNoContent();
		});
	}

	private AssetComponentResponse findComponent(UUID compUuid) {
		AssetGeoComp geo = compDao.loadGeoComp(compUuid);
		if (geo != null) {
			return modelBuilder.toResponse(geo);
		}
		AssetImageComp image = compDao.loadImageComp(compUuid);
		if (image != null) {
			return modelBuilder.toResponse(image);
		}
		AssetVideoComp video = compDao.loadVideoComp(compUuid);
		if (video != null) {
			return modelBuilder.toResponse(video);
		}
		AssetAudioComp audio = compDao.loadAudioComp(compUuid);
		if (audio != null) {
			return modelBuilder.toResponse(audio);
		}
		AssetDocComp doc = compDao.loadDocComp(compUuid);
		if (doc != null) {
			return modelBuilder.toResponse(doc);
		}
		AssetTranscriptComp transcript = compDao.loadTranscriptComp(compUuid);
		if (transcript != null) {
			return modelBuilder.toResponse(transcript);
		}
		AssetJsonComp json = compDao.loadJsonComp(compUuid);
		if (json != null) {
			return modelBuilder.toResponse(json);
		}
		return null;
	}

	private AssetComponentResponse updateComponent(UUID compUuid, AssetComponentUpdateRequest request, UUID userUuid) {
		AssetGeoComp geo = compDao.loadGeoComp(compUuid);
		if (geo != null) {
			update(request::getSource, geo::setNodeKind);
			GeoLocationInfo info = request.getGeo();
			if (info != null) {
				update(info::getLon, geo::setGeoLon);
				update(info::getLat, geo::setGeoLat);
				update(info::getAlias, geo::setGeoAlias);
			}
			setEditor(geo, userUuid);
			compDao.updateGeoComp(geo);
			return modelBuilder.toResponse(geo);
		}
		AssetImageComp image = compDao.loadImageComp(compUuid);
		if (image != null) {
			update(request::getSource, image::setNodeKind);
			ImageInfo info = request.getImage();
			if (info != null) {
				update(info::getDominantColor, image::setImageDominantColor);
				update(info::getWidth, image::setMediaWidth);
				update(info::getHeight, image::setMediaHeight);
			}
			setEditor(image, userUuid);
			compDao.updateImageComp(image);
			return modelBuilder.toResponse(image);
		}
		AssetVideoComp video = compDao.loadVideoComp(compUuid);
		if (video != null) {
			update(request::getSource, video::setNodeKind);
			VideoInfo info = request.getVideo();
			if (info != null) {
				update(info::getBitrate, video::setVideoBitrate);
				update(info::getEncoding, video::setVideoEncoding);
				update(info::getWidth, video::setMediaWidth);
				update(info::getHeight, video::setMediaHeight);
				update(info::getDuration, video::setMediaDuration);
			}
			setEditor(video, userUuid);
			compDao.updateVideoComp(video);
			return modelBuilder.toResponse(video);
		}
		AssetAudioComp audio = compDao.loadAudioComp(compUuid);
		if (audio != null) {
			update(request::getSource, audio::setNodeKind);
			AudioInfo info = request.getAudio();
			if (info != null) {
				update(info::getBpm, audio::setAudioBpm);
				update(info::getBitrate, audio::setAudioBitrate);
				update(info::getChannels, audio::setAudioChannels);
				update(info::getEncoding, audio::setAudioEncoding);
				update(info::getSamplingRate, audio::setAudioSamplingRate);
				update(info::getDuration, audio::setMediaDuration);
			}
			setEditor(audio, userUuid);
			compDao.updateAudioComp(audio);
			return modelBuilder.toResponse(audio);
		}
		AssetDocComp doc = compDao.loadDocComp(compUuid);
		if (doc != null) {
			update(request::getSource, doc::setNodeKind);
			DocumentInfo info = request.getDocument();
			if (info != null) {
				update(info::getPlainText, doc::setDocPlainText);
				if (info.getWordCount() != null) {
					doc.setDocWordCount(info.getWordCount().intValue());
				}
			}
			setEditor(doc, userUuid);
			compDao.updateDocComp(doc);
			return modelBuilder.toResponse(doc);
		}
		AssetTranscriptComp transcript = compDao.loadTranscriptComp(compUuid);
		if (transcript != null) {
			update(request::getSource, transcript::setNodeKind);
			TranscriptInfo info = request.getTranscript();
			if (info != null) {
				update(info::getLang, transcript::setLang);
				update(info::getTranscriptText, transcript::setTranscriptText);
				update(info::getDuration, transcript::setDuration);
				update(info::getModel, transcript::setModel);
				update(info::getTranscriptJson, transcript::setTranscriptJson);
			}
			setEditor(transcript, userUuid);
			compDao.updateTranscriptComp(transcript);
			return modelBuilder.toResponse(transcript);
		}
		AssetJsonComp json = compDao.loadJsonComp(compUuid);
		if (json != null) {
			update(request::getSource, json::setNodeKind);
			JsonComponentInfo info = request.getJson();
			if (info != null) {
				update(info::getSchemaType, json::setSchemaType);
				update(info::getData, json::setData);
			}
			setEditor(json, userUuid);
			compDao.updateJsonComp(json);
			return modelBuilder.toResponse(json);
		}
		return null;
	}

	private boolean deleteComponent(UUID compUuid) {
		if (compDao.loadGeoComp(compUuid) != null) {
			compDao.deleteGeoComp(compUuid);
			return true;
		}
		if (compDao.loadImageComp(compUuid) != null) {
			compDao.deleteImageComp(compUuid);
			return true;
		}
		if (compDao.loadVideoComp(compUuid) != null) {
			compDao.deleteVideoComp(compUuid);
			return true;
		}
		if (compDao.loadAudioComp(compUuid) != null) {
			compDao.deleteAudioComp(compUuid);
			return true;
		}
		if (compDao.loadDocComp(compUuid) != null) {
			compDao.deleteDocComp(compUuid);
			return true;
		}
		if (compDao.loadTranscriptComp(compUuid) != null) {
			compDao.deleteTranscriptComp(compUuid);
			return true;
		}
		if (compDao.loadJsonComp(compUuid) != null) {
			compDao.deleteJsonComp(compUuid);
			return true;
		}
		return false;
	}
}
