package io.metaloom.loom.db.model.asset;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import io.metaloom.loom.db.Dao;

/**
 * DAO for managing asset components (geo, doc, image, video, audio, transcript, fingerprint, segment, json).
 *
 * <p>
 * Each component type offers the same four shapes:
 * </p>
 * <ul>
 * <li><code>createXComp(user, asset, nodeKind)</code> - build a transient component</li>
 * <li><code>storeXComp(comp)</code> - INSERT. Throws on a duplicate identity; use the upsert unless you know the row is new.</li>
 * <li><code>upsertXComp(comp)</code> - INSERT ... ON CONFLICT DO UPDATE on the component's unique key. <b>This is what a node sync path must
 * call</b>: retries and re-deliveries are inevitable, and a node must leave exactly one row per identity.</li>
 * <li><code>loadXComps(asset)</code> / <code>loadXComp(uuid)</code> / <code>deleteXComp(uuid)</code> / <code>updateXComp(comp)</code></li>
 * </ul>
 */
public interface AssetComponentDao extends Dao {

	// Geo
	AssetGeoComp createGeoComp(UUID userUuid, UUID assetUuid, String nodeKind);

	List<AssetGeoComp> loadGeoComps(UUID assetUuid);

	void deleteGeoComp(UUID uuid);

	AssetGeoComp loadGeoComp(UUID uuid);

	/**
	 * Load the geo component with the given identity, or null when it does not exist.
	 */
	AssetGeoComp loadGeoComp(UUID assetUuid, String nodeKind, String method, long timeFrom);

	void storeGeoComp(AssetGeoComp comp);

	/**
	 * Insert or replace the component keyed by (asset, node kind, method, time from).
	 */
	AssetGeoComp upsertGeoComp(AssetGeoComp comp);

	AssetGeoComp updateGeoComp(AssetGeoComp comp);

	// Doc
	AssetDocComp createDocComp(UUID userUuid, UUID assetUuid, String nodeKind);

	List<AssetDocComp> loadDocComps(UUID assetUuid);

	void deleteDocComp(UUID uuid);

	AssetDocComp loadDocComp(UUID uuid);

	/**
	 * Load the document component with the given identity, or null when it does not exist.
	 */
	AssetDocComp loadDocComp(UUID assetUuid, String nodeKind, int pageNumber);

	void storeDocComp(AssetDocComp comp);

	/**
	 * Insert or replace the component keyed by (asset, node kind, page number).
	 */
	AssetDocComp upsertDocComp(AssetDocComp comp);

	AssetDocComp updateDocComp(AssetDocComp comp);

	// Image
	AssetImageComp createImageComp(UUID userUuid, UUID assetUuid, String nodeKind);

	List<AssetImageComp> loadImageComps(UUID assetUuid);

	void deleteImageComp(UUID uuid);

	AssetImageComp loadImageComp(UUID uuid);

	/**
	 * Load the image component with the given identity, or null when it does not exist.
	 */
	AssetImageComp loadImageComp(UUID assetUuid, String nodeKind, int streamIndex);

	void storeImageComp(AssetImageComp comp);

	/**
	 * Insert or replace the component keyed by (asset, node kind, stream index).
	 */
	AssetImageComp upsertImageComp(AssetImageComp comp);

	AssetImageComp updateImageComp(AssetImageComp comp);

	// Video
	AssetVideoComp createVideoComp(UUID userUuid, UUID assetUuid, String nodeKind);

	List<AssetVideoComp> loadVideoComps(UUID assetUuid);

	void deleteVideoComp(UUID uuid);

	AssetVideoComp loadVideoComp(UUID uuid);

	/**
	 * Load the video component with the given identity, or null when it does not exist.
	 */
	AssetVideoComp loadVideoComp(UUID assetUuid, String nodeKind, int streamIndex);

	void storeVideoComp(AssetVideoComp comp);

	/**
	 * Insert or replace the component keyed by (asset, node kind, stream index).
	 */
	AssetVideoComp upsertVideoComp(AssetVideoComp comp);

	AssetVideoComp updateVideoComp(AssetVideoComp comp);

	// Audio
	AssetAudioComp createAudioComp(UUID userUuid, UUID assetUuid, String nodeKind);

	List<AssetAudioComp> loadAudioComps(UUID assetUuid);

	void deleteAudioComp(UUID uuid);

	AssetAudioComp loadAudioComp(UUID uuid);

	/**
	 * Load the audio component with the given identity, or null when it does not exist.
	 */
	AssetAudioComp loadAudioComp(UUID assetUuid, String nodeKind, int streamIndex);

	void storeAudioComp(AssetAudioComp comp);

	/**
	 * Insert or replace the component keyed by (asset, node kind, stream index).
	 */
	AssetAudioComp upsertAudioComp(AssetAudioComp comp);

	AssetAudioComp updateAudioComp(AssetAudioComp comp);

	// Transcript
	AssetTranscriptComp createTranscriptComp(UUID userUuid, UUID assetUuid, String nodeKind);

	List<AssetTranscriptComp> loadTranscriptComps(UUID assetUuid);

	void deleteTranscriptComp(UUID uuid);

	AssetTranscriptComp loadTranscriptComp(UUID uuid);

	/**
	 * Load the transcript component with the given identity, or null when it does not exist.
	 */
	AssetTranscriptComp loadTranscriptComp(UUID assetUuid, String nodeKind, int streamIndex, String lang);

	void storeTranscriptComp(AssetTranscriptComp comp);

	/**
	 * Insert or replace the component keyed by (asset, node kind, stream index, language).
	 */
	AssetTranscriptComp upsertTranscriptComp(AssetTranscriptComp comp);

	AssetTranscriptComp updateTranscriptComp(AssetTranscriptComp comp);

	// Fingerprint
	AssetFingerprintComp createFingerprintComp(UUID userUuid, UUID assetUuid, String nodeKind);

	List<AssetFingerprintComp> loadFingerprintComps(UUID assetUuid);

	void deleteFingerprintComp(UUID uuid);

	AssetFingerprintComp loadFingerprintComp(UUID uuid);

	/**
	 * Load the fingerprint component with the given identity, or null when it does not exist.
	 */
	AssetFingerprintComp loadFingerprintComp(UUID assetUuid, String nodeKind, String algorithm, int sectorIndex);

	void storeFingerprintComp(AssetFingerprintComp comp);

	/**
	 * Insert or replace the component keyed by (asset, node kind, algorithm, sector index).
	 */
	AssetFingerprintComp upsertFingerprintComp(AssetFingerprintComp comp);

	AssetFingerprintComp updateFingerprintComp(AssetFingerprintComp comp);

	/**
	 * Find every fingerprint component that carries the given value - the dedup lookup. Served by the (algorithm, fingerprint) index.
	 */
	List<AssetFingerprintComp> findByFingerprint(String algorithm, String fingerprint);

	/**
	 * Every fingerprint component recorded for one algorithm.
	 *
	 * <p>
	 * Feeds the full rebuild of the derived similarity index (spec/features/search/LUCENE_PLAN.md §4): the Lucene k-NN index is a cache of this table
	 * and is reconstructed from exactly this query.
	 * </p>
	 */
	List<AssetFingerprintComp> findByAlgorithm(String algorithm);

	/**
	 * The same rows as {@link #findByAlgorithm(String)}, streamed.
	 *
	 * <p>
	 * The similarity index takes a {@link java.util.stream.Stream} for its rebuild precisely so a corpus larger than memory can be walked; loading the
	 * list first defeats that. Use this for the rebuild and the list only when the whole set is genuinely wanted at once. The caller must close the
	 * stream.
	 * </p>
	 */
	Stream<AssetFingerprintComp> streamByAlgorithm(String algorithm);

	/** How many fingerprint components exist for one algorithm - the total a rebuild reports progress against. */
	long countByAlgorithm(String algorithm);

	/** Which of the given assets still have a fingerprint component for this algorithm. The batched test behind the index orphan sweep. */
	Set<UUID> filterExistingFingerprintAssets(String algorithm, Collection<UUID> assetUuids);

	// Segment
	AssetSegmentComp createSegmentComp(UUID userUuid, UUID assetUuid, String nodeKind);

	List<AssetSegmentComp> loadSegmentComps(UUID assetUuid);

	/**
	 * Load the segments of one kind, ordered by their sequence number.
	 */
	List<AssetSegmentComp> loadSegmentComps(UUID assetUuid, String nodeKind, String segmentType);

	void deleteSegmentComp(UUID uuid);

	AssetSegmentComp loadSegmentComp(UUID uuid);

	void storeSegmentComp(AssetSegmentComp comp);

	AssetSegmentComp upsertSegmentComp(AssetSegmentComp comp);

	AssetSegmentComp updateSegmentComp(AssetSegmentComp comp);

	/**
	 * Replace a whole set of segments.
	 *
	 * <p>
	 * Segments are the one component type whose replace-in-place is not a single statement: a re-run that produces fewer segments than the previous
	 * run would otherwise leave the surplus behind. This upserts seq 0..N-1 and deletes everything beyond N for the given
	 * (asset, node kind, segment type).
	 * </p>
	 *
	 * @param assetUuid   the asset
	 * @param nodeKind    the producing node kind
	 * @param segmentType the segment kind being replaced
	 * @param comps       the new segments, in order; their seq is assigned from their position
	 */
	List<AssetSegmentComp> replaceSegmentComps(UUID assetUuid, String nodeKind, String segmentType, List<AssetSegmentComp> comps);

	// Json
	AssetJsonComp createJsonComp(UUID userUuid, UUID assetUuid, String nodeKind);

	List<AssetJsonComp> loadJsonComps(UUID assetUuid);

	void deleteJsonComp(UUID uuid);

	AssetJsonComp loadJsonComp(UUID uuid);

	/**
	 * Load the JSON component with the given identity, or null when it does not exist.
	 */
	AssetJsonComp loadJsonComp(UUID assetUuid, String nodeKind, String schemaType, String variant);

	void storeJsonComp(AssetJsonComp comp);

	/**
	 * Insert or replace the component keyed by (asset, node kind, schema type, variant).
	 */
	AssetJsonComp upsertJsonComp(AssetJsonComp comp);

	AssetJsonComp updateJsonComp(AssetJsonComp comp);
}
