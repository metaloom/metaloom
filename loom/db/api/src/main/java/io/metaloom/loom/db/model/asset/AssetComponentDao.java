package io.metaloom.loom.db.model.asset;

import java.util.List;
import java.util.UUID;

import io.metaloom.loom.db.Dao;

/**
 * DAO for managing asset components (geo, doc, image, video, audio, transcript, json).
 */
public interface AssetComponentDao extends Dao {

	// Geo
	AssetGeoComp createGeoComp(UUID userUuid, UUID assetUuid, String source);

	List<AssetGeoComp> loadGeoComps(UUID assetUuid);

	void deleteGeoComp(UUID uuid);

	AssetGeoComp loadGeoComp(UUID uuid);

	void storeGeoComp(AssetGeoComp comp);

	AssetGeoComp updateGeoComp(AssetGeoComp comp);

	// Doc
	AssetDocComp createDocComp(UUID userUuid, UUID assetUuid, String source);

	List<AssetDocComp> loadDocComps(UUID assetUuid);

	void deleteDocComp(UUID uuid);

	AssetDocComp loadDocComp(UUID uuid);

	void storeDocComp(AssetDocComp comp);

	AssetDocComp updateDocComp(AssetDocComp comp);

	// Image
	AssetImageComp createImageComp(UUID userUuid, UUID assetUuid, String source);

	List<AssetImageComp> loadImageComps(UUID assetUuid);

	void deleteImageComp(UUID uuid);

	AssetImageComp loadImageComp(UUID uuid);

	void storeImageComp(AssetImageComp comp);

	AssetImageComp updateImageComp(AssetImageComp comp);

	// Video
	AssetVideoComp createVideoComp(UUID userUuid, UUID assetUuid, String source);

	List<AssetVideoComp> loadVideoComps(UUID assetUuid);

	void deleteVideoComp(UUID uuid);

	AssetVideoComp loadVideoComp(UUID uuid);

	void storeVideoComp(AssetVideoComp comp);

	AssetVideoComp updateVideoComp(AssetVideoComp comp);

	// Audio
	AssetAudioComp createAudioComp(UUID userUuid, UUID assetUuid, String source);

	List<AssetAudioComp> loadAudioComps(UUID assetUuid);

	void deleteAudioComp(UUID uuid);

	AssetAudioComp loadAudioComp(UUID uuid);

	void storeAudioComp(AssetAudioComp comp);

	AssetAudioComp updateAudioComp(AssetAudioComp comp);

	// Transcript
	AssetTranscriptComp createTranscriptComp(UUID userUuid, UUID assetUuid, String source);

	List<AssetTranscriptComp> loadTranscriptComps(UUID assetUuid);

	void deleteTranscriptComp(UUID uuid);

	AssetTranscriptComp loadTranscriptComp(UUID uuid);

	void storeTranscriptComp(AssetTranscriptComp comp);

	AssetTranscriptComp updateTranscriptComp(AssetTranscriptComp comp);

	// Json
	AssetJsonComp createJsonComp(UUID userUuid, UUID assetUuid, String source);

	List<AssetJsonComp> loadJsonComps(UUID assetUuid);

	void deleteJsonComp(UUID uuid);

	AssetJsonComp loadJsonComp(UUID uuid);

	void storeJsonComp(AssetJsonComp comp);

	AssetJsonComp updateJsonComp(AssetJsonComp comp);
}
