package io.metaloom.loom.db.jooq.dao.asset.comp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

import io.metaloom.loom.api.search.HexFingerprint;
import io.metaloom.loom.db.CUDElement;
import io.metaloom.loom.db.model.asset.AssetAudioComp;
import io.metaloom.loom.db.model.asset.AssetComponent;
import io.metaloom.loom.db.model.asset.AssetComponentDao;
import io.metaloom.loom.db.model.asset.AssetDocComp;
import io.metaloom.loom.db.model.asset.AssetFingerprintComp;
import io.metaloom.loom.db.model.asset.AssetGeoComp;
import io.metaloom.loom.db.model.asset.AssetImageComp;
import io.metaloom.loom.db.model.asset.AssetJsonComp;
import io.metaloom.loom.db.model.asset.AssetSegmentComp;
import io.metaloom.loom.db.model.asset.AssetTranscriptComp;
import io.metaloom.loom.db.model.asset.AssetVideoComp;
import io.vertx.core.json.JsonObject;

/**
 * jOOQ implementation of the asset component DAO.
 *
 * <p>
 * The tables are addressed by name rather than through the generated classes, so this file - not the compiler - is where the schema contract lives.
 * Every write goes through {@link #upsert(Table, Map, Field...)}, which issues
 * <code>INSERT ... ON CONFLICT (&lt;identity&gt;) DO UPDATE</code> against the table's unique key. That is what makes a node re-run replace its own
 * row instead of appending a duplicate.
 * </p>
 */
@Singleton
public class AssetComponentDaoImpl implements AssetComponentDao {

	private static final Table<?> GEO_TABLE = DSL.table("asset_geo_comp");
	private static final Table<?> DOC_TABLE = DSL.table("asset_doc_comp");
	private static final Table<?> IMAGE_TABLE = DSL.table("asset_image_comp");
	private static final Table<?> VIDEO_TABLE = DSL.table("asset_video_comp");
	private static final Table<?> AUDIO_TABLE = DSL.table("asset_audio_comp");
	private static final Table<?> TRANSCRIPT_TABLE = DSL.table("asset_transcript_comp");
	private static final Table<?> FINGERPRINT_TABLE = DSL.table("asset_fingerprint_comp");
	private static final Table<?> SEGMENT_TABLE = DSL.table("asset_segment_comp");
	private static final Table<?> JSON_TABLE = DSL.table("asset_json_comp");
	/** Only joined to, never written here: the fingerprint rebuild projection needs the owning asset's content hash. */
	private static final Table<?> ASSET_TABLE = DSL.table("asset");

	// Shared component contract
	private static final Field<UUID> F_UUID = DSL.field("uuid", UUID.class);
	private static final Field<UUID> F_ASSET_UUID = DSL.field("asset_uuid", UUID.class);
	private static final Field<String> F_NODE_KIND = DSL.field("node_kind", String.class);
	private static final Field<String> F_NODE_ID = DSL.field("node_id", String.class);
	private static final Field<String> F_PRODUCER_VERSION = DSL.field("producer_version", String.class);
	private static final Field<UUID> F_RUN_UUID = DSL.field("run_uuid", UUID.class);
	private static final Field<UUID> F_TASK_UUID = DSL.field("task_uuid", UUID.class);
	private static final Field<Float> F_CONFIDENCE = DSL.field("confidence", Float.class);
	private static final Field<JSONB> F_META = DSL.field("meta", SQLDataType.JSONB);
	private static final Field<Instant> F_CREATED = DSL.field("created", Instant.class);
	private static final Field<UUID> F_CREATOR_UUID = DSL.field("creator_uuid", UUID.class);
	private static final Field<Instant> F_EDITED = DSL.field("edited", Instant.class);
	private static final Field<UUID> F_EDITOR_UUID = DSL.field("editor_uuid", UUID.class);

	// Discriminators
	private static final Field<Integer> F_STREAM_INDEX = DSL.field("stream_index", Integer.class);
	private static final Field<Integer> F_PAGE_NUMBER = DSL.field("page_number", Integer.class);
	private static final Field<Long> F_TIME_FROM = DSL.field("time_from", Long.class);
	private static final Field<Long> F_TIME_TO = DSL.field("time_to", Long.class);
	private static final Field<String> F_METHOD = DSL.field("method", String.class);
	private static final Field<String> F_LANG = DSL.field("lang", String.class);
	private static final Field<String> F_ALGORITHM = DSL.field("algorithm", String.class);
	private static final Field<Integer> F_WINDOW_INDEX = DSL.field("window_index", Integer.class);
	private static final Field<String> F_SEGMENT_TYPE = DSL.field("segment_type", String.class);
	private static final Field<Integer> F_SEQ = DSL.field("seq", Integer.class);
	private static final Field<String> F_SCHEMA_TYPE = DSL.field("schema_type", String.class);
	private static final Field<String> F_VARIANT = DSL.field("variant", String.class);

	// Geo
	private static final Field<Double> F_GEO_LON = DSL.field("geo_lon", Double.class);
	private static final Field<Double> F_GEO_LAT = DSL.field("geo_lat", Double.class);
	private static final Field<String> F_GEO_ALIAS = DSL.field("geo_alias", String.class);
	private static final Field<Float> F_ACCURACY_M = DSL.field("accuracy_m", Float.class);

	// Doc
	private static final Field<Integer> F_PAGE_COUNT = DSL.field("page_count", Integer.class);
	private static final Field<String> F_TEXT_LANG = DSL.field("text_lang", String.class);
	private static final Field<String> F_DOC_PLAIN_TEXT = DSL.field("doc_plain_text", String.class);
	private static final Field<Integer> F_DOC_WORD_COUNT = DSL.field("doc_word_count", Integer.class);

	// Image / video
	private static final Field<String> F_IMAGE_DOMINANT_COLOR = DSL.field("image_dominant_color", String.class);
	private static final Field<String> F_IMAGE_ENCODING = DSL.field("image_encoding", String.class);
	private static final Field<Integer> F_MEDIA_WIDTH = DSL.field("media_width", Integer.class);
	private static final Field<Integer> F_MEDIA_HEIGHT = DSL.field("media_height", Integer.class);
	private static final Field<Integer> F_ORIENTATION = DSL.field("orientation", Integer.class);
	private static final Field<Integer> F_BIT_DEPTH = DSL.field("bit_depth", Integer.class);
	private static final Field<Float> F_BLURRINESS = DSL.field("blurriness", Float.class);
	private static final Field<Long> F_MEDIA_DURATION = DSL.field("media_duration", Long.class);
	private static final Field<Integer> F_VIDEO_BITRATE = DSL.field("video_bitrate", Integer.class);
	private static final Field<String> F_VIDEO_ENCODING = DSL.field("video_encoding", String.class);
	private static final Field<Float> F_FPS = DSL.field("fps", Float.class);
	private static final Field<Long> F_FRAME_COUNT = DSL.field("frame_count", Long.class);
	private static final Field<Integer> F_ROTATION = DSL.field("rotation", Integer.class);

	// Audio
	private static final Field<String> F_TRACK_TITLE = DSL.field("track_title", String.class);
	private static final Field<Boolean> F_IS_DEFAULT = DSL.field("is_default", Boolean.class);
	private static final Field<Integer> F_AUDIO_BPM = DSL.field("audio_bpm", Integer.class);
	private static final Field<Integer> F_AUDIO_SAMPLING_RATE = DSL.field("audio_sampling_rate", Integer.class);
	private static final Field<Integer> F_AUDIO_CHANNELS = DSL.field("audio_channels", Integer.class);
	private static final Field<Integer> F_AUDIO_BITRATE = DSL.field("audio_bitrate", Integer.class);
	private static final Field<String> F_AUDIO_ENCODING = DSL.field("audio_encoding", String.class);

	// Transcript
	private static final Field<UUID> F_AUDIO_COMP_UUID = DSL.field("audio_comp_uuid", UUID.class);
	private static final Field<String> F_TRANSCRIPT_TEXT = DSL.field("transcript_text", String.class);
	private static final Field<Long> F_DURATION = DSL.field("duration", Long.class);
	private static final Field<Integer> F_WORD_COUNT = DSL.field("word_count", Integer.class);
	private static final Field<String> F_MODEL = DSL.field("model", String.class);
	private static final Field<JSONB> F_TRANSCRIPT_JSON = DSL.field("transcript_json", SQLDataType.JSONB);

	// Fingerprint / segment
	private static final Field<String> F_FINGERPRINT = DSL.field("fingerprint", String.class);
	private static final Field<String> F_TITLE = DSL.field("title", String.class);
	private static final Field<Float> F_SCORE = DSL.field("score", Float.class);

	// Json
	private static final Field<JSONB> F_DATA = DSL.field("data", SQLDataType.JSONB);

	private final DSLContext ctx;

	@Inject
	public AssetComponentDaoImpl(DSLContext ctx) {
		this.ctx = ctx;
	}

	@Override
	public String getTypeName() {
		return "AssetComponents";
	}

	@Override
	public void clear() {
		ctx.deleteFrom(TRANSCRIPT_TABLE).execute();
		ctx.deleteFrom(GEO_TABLE).execute();
		ctx.deleteFrom(DOC_TABLE).execute();
		ctx.deleteFrom(IMAGE_TABLE).execute();
		ctx.deleteFrom(VIDEO_TABLE).execute();
		ctx.deleteFrom(AUDIO_TABLE).execute();
		ctx.deleteFrom(FINGERPRINT_TABLE).execute();
		ctx.deleteFrom(SEGMENT_TABLE).execute();
		ctx.deleteFrom(JSON_TABLE).execute();
	}

	@Override
	public long count() {
		long total = 0;
		total += ctx.fetchCount(GEO_TABLE);
		total += ctx.fetchCount(DOC_TABLE);
		total += ctx.fetchCount(IMAGE_TABLE);
		total += ctx.fetchCount(VIDEO_TABLE);
		total += ctx.fetchCount(AUDIO_TABLE);
		total += ctx.fetchCount(TRANSCRIPT_TABLE);
		total += ctx.fetchCount(FINGERPRINT_TABLE);
		total += ctx.fetchCount(SEGMENT_TABLE);
		total += ctx.fetchCount(JSON_TABLE);
		return total;
	}

	// ---- generic plumbing ----

	private <T extends AssetComponent<T>> T init(AbstractAssetCompImpl<T> comp, UUID userUuid, UUID assetUuid, String nodeKind) {
		comp.setAssetUuid(assetUuid);
		comp.setNodeKind(nodeKind);
		setCreatorEditor(userUuid, comp);
		return comp.self();
	}

	private void setCreatorEditor(UUID userUuid, Object comp) {
		if (comp instanceof CUDElement) {
			CUDElement<?> el = (CUDElement<?>) comp;
			el.setCreatorUuid(userUuid);
			el.setEditorUuid(userUuid);
			el.setCreated(Instant.now());
			el.setEdited(Instant.now());
		}
	}

	/**
	 * Values shared by every component: the owning asset, the producer provenance and the audit columns.
	 */
	private Map<Field<?>, Object> baseValues(AssetComponent<?> comp) {
		Map<Field<?>, Object> values = new LinkedHashMap<>();
		values.put(F_ASSET_UUID, comp.getAssetUuid());
		values.put(F_NODE_KIND, comp.getNodeKind());
		values.put(F_NODE_ID, comp.getNodeId());
		values.put(F_PRODUCER_VERSION, comp.getProducerVersion() == null ? "" : comp.getProducerVersion());
		values.put(F_RUN_UUID, comp.getRunUuid());
		values.put(F_TASK_UUID, comp.getTaskUuid());
		values.put(F_CONFIDENCE, comp.getConfidence());
		values.put(F_META, toJsonb(comp.getMeta()));
		values.put(F_CREATED, comp.getCreated());
		values.put(F_CREATOR_UUID, comp.getCreatorUuid());
		values.put(F_EDITED, comp.getEdited());
		values.put(F_EDITOR_UUID, comp.getEditorUuid());
		return values;
	}

	@SuppressWarnings("rawtypes")
	private UUID insertAndReturnUuid(Table<?> table, Map<Field<?>, Object> values) {
		return ctx.insertInto(table)
			.set((Map) values)
			.returning(F_UUID)
			.fetchOne(F_UUID);
	}

	/**
	 * Insert the component, or replace the existing row with the same identity.
	 *
	 * <p>
	 * The creation audit columns are left untouched on conflict: the row keeps the timestamp of the first time this node produced it, while everything
	 * the node computed - including the producer version - is overwritten.
	 * </p>
	 */
	@SuppressWarnings("rawtypes")
	private UUID upsert(Table<?> table, Map<Field<?>, Object> values, Field<?>... keyFields) {
		Map<Field<?>, Object> updates = new LinkedHashMap<>(values);
		for (Field<?> key : keyFields) {
			updates.remove(key);
		}
		updates.remove(F_CREATED);
		updates.remove(F_CREATOR_UUID);
		return ctx.insertInto(table)
			.set((Map) values)
			.onConflict(keyFields)
			.doUpdate()
			.set((Map) updates)
			.returning(F_UUID)
			.fetchOne(F_UUID);
	}

	@SuppressWarnings("rawtypes")
	private void doUpdate(Table<?> table, UUID uuid, Map<Field<?>, Object> values) {
		ctx.update(table)
			.set((Map) values)
			.where(F_UUID.eq(uuid))
			.execute();
	}

	private void doDelete(Table<?> table, UUID uuid) {
		ctx.deleteFrom(table).where(F_UUID.eq(uuid)).execute();
	}

	private <T> T doLoad(Table<?> table, UUID uuid, Function<Record, T> mapper) {
		return ctx.select(DSL.asterisk())
			.from(table)
			.where(F_UUID.eq(uuid))
			.fetchOne(mapper::apply);
	}

	private <T> T doLoadOne(Table<?> table, Condition condition, Function<Record, T> mapper) {
		return ctx.select(DSL.asterisk())
			.from(table)
			.where(condition)
			.fetchOne(mapper::apply);
	}

	private <T> List<T> doLoadByAsset(Table<?> table, UUID assetUuid, Function<Record, T> mapper) {
		return ctx.select(DSL.asterisk())
			.from(table)
			.where(F_ASSET_UUID.eq(assetUuid))
			.fetch(mapper::apply);
	}

	/**
	 * Read back the identity the database assigned and the defaults it filled in.
	 */
	private void readBase(Record r, AbstractAssetCompImpl<?> comp) {
		comp.setUuid(r.get(F_UUID));
		comp.setAssetUuid(r.get(F_ASSET_UUID));
		comp.setNodeKind(r.get(F_NODE_KIND));
		comp.setNodeId(r.get(F_NODE_ID));
		comp.setProducerVersion(r.get(F_PRODUCER_VERSION));
		comp.setRunUuid(r.get(F_RUN_UUID));
		comp.setTaskUuid(r.get(F_TASK_UUID));
		comp.setConfidence(r.get(F_CONFIDENCE));
		comp.setMeta(toJsonObject(r.get(F_META)));
		comp.setCreated(toInstant(r.get("created")));
		comp.setCreatorUuid(r.get(F_CREATOR_UUID));
		comp.setEdited(toInstant(r.get("edited")));
		comp.setEditorUuid(r.get(F_EDITOR_UUID));
	}

	private Condition identity(UUID assetUuid, String nodeKind) {
		return F_ASSET_UUID.eq(assetUuid).and(F_NODE_KIND.eq(nodeKind));
	}

	// ---- Geo ----

	@Override
	public AssetGeoComp createGeoComp(UUID userUuid, UUID assetUuid, String nodeKind) {
		return init(new AssetGeoCompImpl(), userUuid, assetUuid, nodeKind);
	}

	private Map<Field<?>, Object> geoValues(AssetGeoComp comp) {
		Map<Field<?>, Object> values = baseValues(comp);
		values.put(F_METHOD, comp.getMethod() == null ? "" : comp.getMethod());
		values.put(F_TIME_FROM, comp.getTimeFrom());
		values.put(F_GEO_LON, comp.getGeoLon());
		values.put(F_GEO_LAT, comp.getGeoLat());
		values.put(F_GEO_ALIAS, comp.getGeoAlias());
		values.put(F_ACCURACY_M, comp.getAccuracyM());
		return values;
	}

	@Override
	public void storeGeoComp(AssetGeoComp comp) {
		comp.setUuid(insertAndReturnUuid(GEO_TABLE, geoValues(comp)));
	}

	@Override
	public AssetGeoComp upsertGeoComp(AssetGeoComp comp) {
		comp.setUuid(upsert(GEO_TABLE, geoValues(comp), F_ASSET_UUID, F_NODE_KIND, F_METHOD, F_TIME_FROM));
		return comp;
	}

	@Override
	public List<AssetGeoComp> loadGeoComps(UUID assetUuid) {
		return doLoadByAsset(GEO_TABLE, assetUuid, this::mapGeoComp);
	}

	@Override
	public AssetGeoComp loadGeoComp(UUID uuid) {
		return doLoad(GEO_TABLE, uuid, this::mapGeoComp);
	}

	@Override
	public AssetGeoComp loadGeoComp(UUID assetUuid, String nodeKind, String method, long timeFrom) {
		return doLoadOne(GEO_TABLE, identity(assetUuid, nodeKind)
			.and(F_METHOD.eq(method == null ? "" : method))
			.and(F_TIME_FROM.eq(timeFrom)), this::mapGeoComp);
	}

	@Override
	public void deleteGeoComp(UUID uuid) {
		doDelete(GEO_TABLE, uuid);
	}

	@Override
	public AssetGeoComp updateGeoComp(AssetGeoComp comp) {
		doUpdate(GEO_TABLE, comp.getUuid(), geoValues(comp));
		return comp;
	}

	private AssetGeoComp mapGeoComp(Record r) {
		AssetGeoCompImpl comp = new AssetGeoCompImpl();
		readBase(r, comp);
		comp.setMethod(r.get(F_METHOD));
		comp.setTimeFrom(longOrZero(r.get(F_TIME_FROM)));
		// geo_lon/geo_lat are SQL decimals, so the driver hands back BigDecimal - ask for the
		// conversion by name rather than casting through the Double-typed field reference.
		comp.setGeoLon(r.get("geo_lon", Double.class));
		comp.setGeoLat(r.get("geo_lat", Double.class));
		comp.setGeoAlias(r.get(F_GEO_ALIAS));
		comp.setAccuracyM(r.get(F_ACCURACY_M));
		return comp;
	}

	// ---- Doc ----

	@Override
	public AssetDocComp createDocComp(UUID userUuid, UUID assetUuid, String nodeKind) {
		return init(new AssetDocCompImpl(), userUuid, assetUuid, nodeKind);
	}

	private Map<Field<?>, Object> docValues(AssetDocComp comp) {
		Map<Field<?>, Object> values = baseValues(comp);
		values.put(F_PAGE_NUMBER, comp.getPageNumber());
		values.put(F_PAGE_COUNT, comp.getPageCount());
		values.put(F_TEXT_LANG, comp.getTextLang());
		values.put(F_DOC_PLAIN_TEXT, comp.getDocPlainText());
		values.put(F_DOC_WORD_COUNT, comp.getDocWordCount());
		return values;
	}

	@Override
	public void storeDocComp(AssetDocComp comp) {
		comp.setUuid(insertAndReturnUuid(DOC_TABLE, docValues(comp)));
	}

	@Override
	public AssetDocComp upsertDocComp(AssetDocComp comp) {
		comp.setUuid(upsert(DOC_TABLE, docValues(comp), F_ASSET_UUID, F_NODE_KIND, F_PAGE_NUMBER));
		return comp;
	}

	@Override
	public List<AssetDocComp> loadDocComps(UUID assetUuid) {
		return doLoadByAsset(DOC_TABLE, assetUuid, this::mapDocComp);
	}

	@Override
	public AssetDocComp loadDocComp(UUID uuid) {
		return doLoad(DOC_TABLE, uuid, this::mapDocComp);
	}

	@Override
	public AssetDocComp loadDocComp(UUID assetUuid, String nodeKind, int pageNumber) {
		return doLoadOne(DOC_TABLE, identity(assetUuid, nodeKind).and(F_PAGE_NUMBER.eq(pageNumber)), this::mapDocComp);
	}

	@Override
	public void deleteDocComp(UUID uuid) {
		doDelete(DOC_TABLE, uuid);
	}

	@Override
	public AssetDocComp updateDocComp(AssetDocComp comp) {
		doUpdate(DOC_TABLE, comp.getUuid(), docValues(comp));
		return comp;
	}

	private AssetDocComp mapDocComp(Record r) {
		AssetDocCompImpl comp = new AssetDocCompImpl();
		readBase(r, comp);
		comp.setPageNumber(intOrZero(r.get(F_PAGE_NUMBER)));
		comp.setPageCount(r.get(F_PAGE_COUNT));
		comp.setTextLang(r.get(F_TEXT_LANG));
		comp.setDocPlainText(r.get(F_DOC_PLAIN_TEXT));
		comp.setDocWordCount(r.get(F_DOC_WORD_COUNT));
		return comp;
	}

	// ---- Image ----

	@Override
	public AssetImageComp createImageComp(UUID userUuid, UUID assetUuid, String nodeKind) {
		return init(new AssetImageCompImpl(), userUuid, assetUuid, nodeKind);
	}

	private Map<Field<?>, Object> imageValues(AssetImageComp comp) {
		Map<Field<?>, Object> values = baseValues(comp);
		values.put(F_STREAM_INDEX, comp.getStreamIndex());
		values.put(F_IMAGE_DOMINANT_COLOR, comp.getImageDominantColor());
		values.put(F_IMAGE_ENCODING, comp.getImageEncoding());
		values.put(F_MEDIA_WIDTH, comp.getMediaWidth());
		values.put(F_MEDIA_HEIGHT, comp.getMediaHeight());
		values.put(F_ORIENTATION, comp.getOrientation());
		values.put(F_BIT_DEPTH, comp.getBitDepth());
		values.put(F_BLURRINESS, comp.getBlurriness());
		return values;
	}

	@Override
	public void storeImageComp(AssetImageComp comp) {
		comp.setUuid(insertAndReturnUuid(IMAGE_TABLE, imageValues(comp)));
	}

	@Override
	public AssetImageComp upsertImageComp(AssetImageComp comp) {
		comp.setUuid(upsert(IMAGE_TABLE, imageValues(comp), F_ASSET_UUID, F_NODE_KIND, F_STREAM_INDEX));
		return comp;
	}

	@Override
	public List<AssetImageComp> loadImageComps(UUID assetUuid) {
		return doLoadByAsset(IMAGE_TABLE, assetUuid, this::mapImageComp);
	}

	@Override
	public AssetImageComp loadImageComp(UUID uuid) {
		return doLoad(IMAGE_TABLE, uuid, this::mapImageComp);
	}

	@Override
	public AssetImageComp loadImageComp(UUID assetUuid, String nodeKind, int streamIndex) {
		return doLoadOne(IMAGE_TABLE, identity(assetUuid, nodeKind).and(F_STREAM_INDEX.eq(streamIndex)), this::mapImageComp);
	}

	@Override
	public void deleteImageComp(UUID uuid) {
		doDelete(IMAGE_TABLE, uuid);
	}

	@Override
	public AssetImageComp updateImageComp(AssetImageComp comp) {
		doUpdate(IMAGE_TABLE, comp.getUuid(), imageValues(comp));
		return comp;
	}

	private AssetImageComp mapImageComp(Record r) {
		AssetImageCompImpl comp = new AssetImageCompImpl();
		readBase(r, comp);
		comp.setStreamIndex(intOrZero(r.get(F_STREAM_INDEX)));
		comp.setImageDominantColor(r.get(F_IMAGE_DOMINANT_COLOR));
		comp.setImageEncoding(r.get(F_IMAGE_ENCODING));
		comp.setMediaWidth(r.get(F_MEDIA_WIDTH));
		comp.setMediaHeight(r.get(F_MEDIA_HEIGHT));
		comp.setOrientation(r.get(F_ORIENTATION));
		comp.setBitDepth(r.get(F_BIT_DEPTH));
		comp.setBlurriness(r.get(F_BLURRINESS));
		return comp;
	}

	// ---- Video ----

	@Override
	public AssetVideoComp createVideoComp(UUID userUuid, UUID assetUuid, String nodeKind) {
		return init(new AssetVideoCompImpl(), userUuid, assetUuid, nodeKind);
	}

	private Map<Field<?>, Object> videoValues(AssetVideoComp comp) {
		Map<Field<?>, Object> values = baseValues(comp);
		values.put(F_STREAM_INDEX, comp.getStreamIndex());
		values.put(F_MEDIA_WIDTH, comp.getMediaWidth());
		values.put(F_MEDIA_HEIGHT, comp.getMediaHeight());
		values.put(F_MEDIA_DURATION, comp.getMediaDuration());
		values.put(F_VIDEO_BITRATE, comp.getVideoBitrate());
		values.put(F_VIDEO_ENCODING, comp.getVideoEncoding());
		values.put(F_FPS, comp.getFps());
		values.put(F_FRAME_COUNT, comp.getFrameCount());
		values.put(F_ROTATION, comp.getRotation());
		values.put(F_BLURRINESS, comp.getBlurriness());
		return values;
	}

	@Override
	public void storeVideoComp(AssetVideoComp comp) {
		comp.setUuid(insertAndReturnUuid(VIDEO_TABLE, videoValues(comp)));
	}

	@Override
	public AssetVideoComp upsertVideoComp(AssetVideoComp comp) {
		comp.setUuid(upsert(VIDEO_TABLE, videoValues(comp), F_ASSET_UUID, F_NODE_KIND, F_STREAM_INDEX));
		return comp;
	}

	@Override
	public List<AssetVideoComp> loadVideoComps(UUID assetUuid) {
		return doLoadByAsset(VIDEO_TABLE, assetUuid, this::mapVideoComp);
	}

	@Override
	public AssetVideoComp loadVideoComp(UUID uuid) {
		return doLoad(VIDEO_TABLE, uuid, this::mapVideoComp);
	}

	@Override
	public AssetVideoComp loadVideoComp(UUID assetUuid, String nodeKind, int streamIndex) {
		return doLoadOne(VIDEO_TABLE, identity(assetUuid, nodeKind).and(F_STREAM_INDEX.eq(streamIndex)), this::mapVideoComp);
	}

	@Override
	public void deleteVideoComp(UUID uuid) {
		doDelete(VIDEO_TABLE, uuid);
	}

	@Override
	public AssetVideoComp updateVideoComp(AssetVideoComp comp) {
		doUpdate(VIDEO_TABLE, comp.getUuid(), videoValues(comp));
		return comp;
	}

	private AssetVideoComp mapVideoComp(Record r) {
		AssetVideoCompImpl comp = new AssetVideoCompImpl();
		readBase(r, comp);
		comp.setStreamIndex(intOrZero(r.get(F_STREAM_INDEX)));
		comp.setMediaWidth(r.get(F_MEDIA_WIDTH));
		comp.setMediaHeight(r.get(F_MEDIA_HEIGHT));
		comp.setMediaDuration(r.get(F_MEDIA_DURATION));
		comp.setVideoBitrate(r.get(F_VIDEO_BITRATE));
		comp.setVideoEncoding(r.get(F_VIDEO_ENCODING));
		comp.setFps(r.get(F_FPS));
		comp.setFrameCount(r.get(F_FRAME_COUNT));
		comp.setRotation(r.get(F_ROTATION));
		comp.setBlurriness(r.get(F_BLURRINESS));
		return comp;
	}

	// ---- Audio ----

	@Override
	public AssetAudioComp createAudioComp(UUID userUuid, UUID assetUuid, String nodeKind) {
		return init(new AssetAudioCompImpl(), userUuid, assetUuid, nodeKind);
	}

	private Map<Field<?>, Object> audioValues(AssetAudioComp comp) {
		Map<Field<?>, Object> values = baseValues(comp);
		values.put(F_STREAM_INDEX, comp.getStreamIndex());
		values.put(F_LANG, comp.getLang());
		values.put(F_TRACK_TITLE, comp.getTrackTitle());
		values.put(F_IS_DEFAULT, comp.getIsDefault());
		values.put(F_AUDIO_BPM, comp.getAudioBpm());
		values.put(F_AUDIO_SAMPLING_RATE, comp.getAudioSamplingRate());
		values.put(F_AUDIO_CHANNELS, comp.getAudioChannels());
		values.put(F_AUDIO_BITRATE, comp.getAudioBitrate());
		values.put(F_AUDIO_ENCODING, comp.getAudioEncoding());
		values.put(F_MEDIA_DURATION, comp.getMediaDuration());
		return values;
	}

	@Override
	public void storeAudioComp(AssetAudioComp comp) {
		comp.setUuid(insertAndReturnUuid(AUDIO_TABLE, audioValues(comp)));
	}

	@Override
	public AssetAudioComp upsertAudioComp(AssetAudioComp comp) {
		comp.setUuid(upsert(AUDIO_TABLE, audioValues(comp), F_ASSET_UUID, F_NODE_KIND, F_STREAM_INDEX));
		return comp;
	}

	@Override
	public List<AssetAudioComp> loadAudioComps(UUID assetUuid) {
		return doLoadByAsset(AUDIO_TABLE, assetUuid, this::mapAudioComp);
	}

	@Override
	public AssetAudioComp loadAudioComp(UUID uuid) {
		return doLoad(AUDIO_TABLE, uuid, this::mapAudioComp);
	}

	@Override
	public AssetAudioComp loadAudioComp(UUID assetUuid, String nodeKind, int streamIndex) {
		return doLoadOne(AUDIO_TABLE, identity(assetUuid, nodeKind).and(F_STREAM_INDEX.eq(streamIndex)), this::mapAudioComp);
	}

	@Override
	public void deleteAudioComp(UUID uuid) {
		doDelete(AUDIO_TABLE, uuid);
	}

	@Override
	public AssetAudioComp updateAudioComp(AssetAudioComp comp) {
		doUpdate(AUDIO_TABLE, comp.getUuid(), audioValues(comp));
		return comp;
	}

	private AssetAudioComp mapAudioComp(Record r) {
		AssetAudioCompImpl comp = new AssetAudioCompImpl();
		readBase(r, comp);
		comp.setStreamIndex(intOrZero(r.get(F_STREAM_INDEX)));
		comp.setLang(r.get(F_LANG));
		comp.setTrackTitle(r.get(F_TRACK_TITLE));
		comp.setIsDefault(r.get(F_IS_DEFAULT));
		comp.setAudioBpm(r.get(F_AUDIO_BPM));
		comp.setAudioSamplingRate(r.get(F_AUDIO_SAMPLING_RATE));
		comp.setAudioChannels(r.get(F_AUDIO_CHANNELS));
		comp.setAudioBitrate(r.get(F_AUDIO_BITRATE));
		comp.setAudioEncoding(r.get(F_AUDIO_ENCODING));
		comp.setMediaDuration(r.get(F_MEDIA_DURATION));
		return comp;
	}

	// ---- Transcript ----

	@Override
	public AssetTranscriptComp createTranscriptComp(UUID userUuid, UUID assetUuid, String nodeKind) {
		return init(new AssetTranscriptCompImpl(), userUuid, assetUuid, nodeKind);
	}

	private Map<Field<?>, Object> transcriptValues(AssetTranscriptComp comp) {
		Map<Field<?>, Object> values = baseValues(comp);
		values.put(F_STREAM_INDEX, comp.getStreamIndex());
		values.put(F_LANG, comp.getLang() == null ? "" : comp.getLang());
		values.put(F_AUDIO_COMP_UUID, comp.getAudioCompUuid());
		values.put(F_MODEL, comp.getModel());
		values.put(F_TRANSCRIPT_TEXT, comp.getTranscriptText());
		values.put(F_DURATION, comp.getDuration());
		values.put(F_WORD_COUNT, comp.getWordCount());
		values.put(F_TRANSCRIPT_JSON, toJsonb(comp.getTranscriptJson()));
		return values;
	}

	@Override
	public void storeTranscriptComp(AssetTranscriptComp comp) {
		comp.setUuid(insertAndReturnUuid(TRANSCRIPT_TABLE, transcriptValues(comp)));
	}

	@Override
	public AssetTranscriptComp upsertTranscriptComp(AssetTranscriptComp comp) {
		comp.setUuid(upsert(TRANSCRIPT_TABLE, transcriptValues(comp), F_ASSET_UUID, F_NODE_KIND, F_STREAM_INDEX, F_LANG));
		return comp;
	}

	@Override
	public List<AssetTranscriptComp> loadTranscriptComps(UUID assetUuid) {
		return doLoadByAsset(TRANSCRIPT_TABLE, assetUuid, this::mapTranscriptComp);
	}

	@Override
	public AssetTranscriptComp loadTranscriptComp(UUID uuid) {
		return doLoad(TRANSCRIPT_TABLE, uuid, this::mapTranscriptComp);
	}

	@Override
	public AssetTranscriptComp loadTranscriptComp(UUID assetUuid, String nodeKind, int streamIndex, String lang) {
		return doLoadOne(TRANSCRIPT_TABLE, identity(assetUuid, nodeKind)
			.and(F_STREAM_INDEX.eq(streamIndex))
			.and(F_LANG.eq(lang == null ? "" : lang)), this::mapTranscriptComp);
	}

	@Override
	public void deleteTranscriptComp(UUID uuid) {
		doDelete(TRANSCRIPT_TABLE, uuid);
	}

	@Override
	public AssetTranscriptComp updateTranscriptComp(AssetTranscriptComp comp) {
		doUpdate(TRANSCRIPT_TABLE, comp.getUuid(), transcriptValues(comp));
		return comp;
	}

	private AssetTranscriptComp mapTranscriptComp(Record r) {
		AssetTranscriptCompImpl comp = new AssetTranscriptCompImpl();
		readBase(r, comp);
		comp.setStreamIndex(intOrZero(r.get(F_STREAM_INDEX)));
		comp.setLang(r.get(F_LANG));
		comp.setAudioCompUuid(r.get(F_AUDIO_COMP_UUID));
		comp.setModel(r.get(F_MODEL));
		comp.setTranscriptText(r.get(F_TRANSCRIPT_TEXT));
		comp.setDuration(r.get(F_DURATION));
		comp.setWordCount(r.get(F_WORD_COUNT));
		comp.setTranscriptJson(toJsonObject(r.get(F_TRANSCRIPT_JSON)));
		return comp;
	}

	// ---- Fingerprint ----

	@Override
	public AssetFingerprintComp createFingerprintComp(UUID userUuid, UUID assetUuid, String nodeKind) {
		return init(new AssetFingerprintCompImpl(), userUuid, assetUuid, nodeKind);
	}

	private Map<Field<?>, Object> fingerprintValues(AssetFingerprintComp comp) {
		Map<Field<?>, Object> values = baseValues(comp);
		values.put(F_ALGORITHM, comp.getAlgorithm());
		values.put(F_WINDOW_INDEX, comp.getWindowIndex());
		values.put(F_TIME_FROM, comp.getTimeFrom());
		values.put(F_TIME_TO, comp.getTimeTo());
		values.put(F_FINGERPRINT, comp.getFingerprint());
		return values;
	}

	@Override
	public void storeFingerprintComp(AssetFingerprintComp comp) {
		comp.setUuid(insertAndReturnUuid(FINGERPRINT_TABLE, fingerprintValues(comp)));
	}

	@Override
	public AssetFingerprintComp upsertFingerprintComp(AssetFingerprintComp comp) {
		comp.setUuid(upsert(FINGERPRINT_TABLE, fingerprintValues(comp), F_ASSET_UUID, F_NODE_KIND, F_ALGORITHM, F_WINDOW_INDEX));
		return comp;
	}

	@Override
	public List<AssetFingerprintComp> loadFingerprintComps(UUID assetUuid) {
		return doLoadByAsset(FINGERPRINT_TABLE, assetUuid, this::mapFingerprintComp);
	}

	@Override
	public AssetFingerprintComp loadFingerprintComp(UUID uuid) {
		return doLoad(FINGERPRINT_TABLE, uuid, this::mapFingerprintComp);
	}

	@Override
	public AssetFingerprintComp loadFingerprintComp(UUID assetUuid, String nodeKind, String algorithm, int windowIndex) {
		return doLoadOne(FINGERPRINT_TABLE, identity(assetUuid, nodeKind)
			.and(F_ALGORITHM.eq(algorithm))
			.and(F_WINDOW_INDEX.eq(windowIndex)), this::mapFingerprintComp);
	}

	@Override
	public void deleteFingerprintComp(UUID uuid) {
		doDelete(FINGERPRINT_TABLE, uuid);
	}

	@Override
	public AssetFingerprintComp updateFingerprintComp(AssetFingerprintComp comp) {
		doUpdate(FINGERPRINT_TABLE, comp.getUuid(), fingerprintValues(comp));
		return comp;
	}

	@Override
	public List<AssetFingerprintComp> findByFingerprint(String algorithm, String fingerprint) {
		return ctx.select(DSL.asterisk())
			.from(FINGERPRINT_TABLE)
			.where(F_ALGORITHM.eq(algorithm).and(F_FINGERPRINT.eq(fingerprint)))
			.fetch(this::mapFingerprintComp);
	}

	@Override
	public List<AssetFingerprintComp> findByAlgorithm(String algorithm) {
		return ctx.select(DSL.asterisk())
			.from(FINGERPRINT_TABLE)
			.where(F_ALGORITHM.eq(algorithm))
			.fetch(this::mapFingerprintComp);
	}

	@Override
	public Stream<AssetFingerprintComp> streamByAlgorithm(String algorithm) {
		return ctx.select(DSL.asterisk())
			.from(FINGERPRINT_TABLE)
			.where(F_ALGORITHM.eq(algorithm))
			// Ordered so a rebuild is reproducible and a resumed one is comparable to its predecessor.
			.orderBy(FINGERPRINT_TABLE.field("uuid", UUID.class).asc())
			.fetchStream()
			.map(this::mapFingerprintComp);
	}

	@Override
	public Stream<HexFingerprint> streamHexFingerprintsByAlgorithm(String algorithm) {
		// Qualified fields: the component table and asset share uuid/created/meta column names, so an unqualified projection would be ambiguous.
		Field<UUID> compUuid = DSL.field(DSL.name("asset_fingerprint_comp", "uuid"), UUID.class);
		Field<UUID> compAssetUuid = DSL.field(DSL.name("asset_fingerprint_comp", "asset_uuid"), UUID.class);
		Field<String> compAlgorithm = DSL.field(DSL.name("asset_fingerprint_comp", "algorithm"), String.class);
		Field<String> compFingerprint = DSL.field(DSL.name("asset_fingerprint_comp", "fingerprint"), String.class);
		Field<UUID> assetUuid = DSL.field(DSL.name("asset", "uuid"), UUID.class);
		Field<String> assetSha512 = DSL.field(DSL.name("asset", "sha512sum"), String.class);
		// Inner join: asset_uuid is a NOT NULL foreign key, so no fingerprint row is lost by it.
		return ctx.select(compAssetUuid, compAlgorithm, compFingerprint, assetSha512)
			.from(FINGERPRINT_TABLE)
			.join(ASSET_TABLE).on(assetUuid.eq(compAssetUuid))
			.where(compAlgorithm.eq(algorithm))
			// Ordered so a rebuild is reproducible and a resumed one is comparable to its predecessor.
			.orderBy(compUuid.asc())
			.fetchStream()
			.map(r -> new HexFingerprint(r.get(compAssetUuid), r.get(assetSha512), r.get(compAlgorithm), r.get(compFingerprint)));
	}

	@Override
	public long countByAlgorithm(String algorithm) {
		Integer count = ctx.selectCount()
			.from(FINGERPRINT_TABLE)
			.where(F_ALGORITHM.eq(algorithm))
			.fetchOne(0, Integer.class);
		return count == null ? 0 : count;
	}

	@Override
	public Set<UUID> filterExistingFingerprintAssets(String algorithm, Collection<UUID> assetUuids) {
		if (assetUuids == null || assetUuids.isEmpty()) {
			return Set.of();
		}
		Field<UUID> assetField = FINGERPRINT_TABLE.field("asset_uuid", UUID.class);
		return Set.copyOf(ctx.selectDistinct(assetField)
			.from(FINGERPRINT_TABLE)
			.where(F_ALGORITHM.eq(algorithm).and(assetField.in(assetUuids)))
			.fetchInto(UUID.class));
	}

	private AssetFingerprintComp mapFingerprintComp(Record r) {
		AssetFingerprintCompImpl comp = new AssetFingerprintCompImpl();
		readBase(r, comp);
		comp.setAlgorithm(r.get(F_ALGORITHM));
		comp.setWindowIndex(intOrZero(r.get(F_WINDOW_INDEX)));
		comp.setTimeFrom(r.get(F_TIME_FROM));
		comp.setTimeTo(r.get(F_TIME_TO));
		comp.setFingerprint(r.get(F_FINGERPRINT));
		return comp;
	}

	// ---- Segment ----

	@Override
	public AssetSegmentComp createSegmentComp(UUID userUuid, UUID assetUuid, String nodeKind) {
		return init(new AssetSegmentCompImpl(), userUuid, assetUuid, nodeKind);
	}

	private Map<Field<?>, Object> segmentValues(AssetSegmentComp comp) {
		Map<Field<?>, Object> values = baseValues(comp);
		values.put(F_SEGMENT_TYPE, comp.getSegmentType());
		values.put(F_SEQ, comp.getSeq());
		values.put(F_TIME_FROM, comp.getTimeFrom());
		values.put(F_TIME_TO, comp.getTimeTo());
		values.put(F_TITLE, comp.getTitle());
		values.put(F_SCORE, comp.getScore());
		return values;
	}

	@Override
	public void storeSegmentComp(AssetSegmentComp comp) {
		comp.setUuid(insertAndReturnUuid(SEGMENT_TABLE, segmentValues(comp)));
	}

	@Override
	public AssetSegmentComp upsertSegmentComp(AssetSegmentComp comp) {
		comp.setUuid(upsert(SEGMENT_TABLE, segmentValues(comp), F_ASSET_UUID, F_NODE_KIND, F_SEGMENT_TYPE, F_SEQ));
		return comp;
	}

	@Override
	public List<AssetSegmentComp> loadSegmentComps(UUID assetUuid) {
		return doLoadByAsset(SEGMENT_TABLE, assetUuid, this::mapSegmentComp);
	}

	@Override
	public List<AssetSegmentComp> loadSegmentComps(UUID assetUuid, String nodeKind, String segmentType) {
		return ctx.select(DSL.asterisk())
			.from(SEGMENT_TABLE)
			.where(identity(assetUuid, nodeKind).and(F_SEGMENT_TYPE.eq(segmentType)))
			.orderBy(F_SEQ.asc())
			.fetch(this::mapSegmentComp);
	}

	@Override
	public AssetSegmentComp loadSegmentComp(UUID uuid) {
		return doLoad(SEGMENT_TABLE, uuid, this::mapSegmentComp);
	}

	@Override
	public void deleteSegmentComp(UUID uuid) {
		doDelete(SEGMENT_TABLE, uuid);
	}

	@Override
	public AssetSegmentComp updateSegmentComp(AssetSegmentComp comp) {
		doUpdate(SEGMENT_TABLE, comp.getUuid(), segmentValues(comp));
		return comp;
	}

	@Override
	public List<AssetSegmentComp> replaceSegmentComps(UUID assetUuid, String nodeKind, String segmentType, List<AssetSegmentComp> comps) {
		List<AssetSegmentComp> stored = new ArrayList<>(comps.size());
		int seq = 0;
		for (AssetSegmentComp comp : comps) {
			comp.setAssetUuid(assetUuid);
			comp.setNodeKind(nodeKind);
			comp.setSegmentType(segmentType);
			comp.setSeq(seq++);
			stored.add(upsertSegmentComp(comp));
		}
		// A shorter run must not leave the tail of the previous one behind.
		ctx.deleteFrom(SEGMENT_TABLE)
			.where(identity(assetUuid, nodeKind)
				.and(F_SEGMENT_TYPE.eq(segmentType))
				.and(F_SEQ.ge(seq)))
			.execute();
		return stored;
	}

	private AssetSegmentComp mapSegmentComp(Record r) {
		AssetSegmentCompImpl comp = new AssetSegmentCompImpl();
		readBase(r, comp);
		comp.setSegmentType(r.get(F_SEGMENT_TYPE));
		comp.setSeq(intOrZero(r.get(F_SEQ)));
		comp.setTimeFrom(longOrZero(r.get(F_TIME_FROM)));
		comp.setTimeTo(longOrZero(r.get(F_TIME_TO)));
		comp.setTitle(r.get(F_TITLE));
		comp.setScore(r.get(F_SCORE));
		return comp;
	}

	// ---- Json ----

	@Override
	public AssetJsonComp createJsonComp(UUID userUuid, UUID assetUuid, String nodeKind) {
		return init(new AssetJsonCompImpl(), userUuid, assetUuid, nodeKind);
	}

	private Map<Field<?>, Object> jsonValues(AssetJsonComp comp) {
		Map<Field<?>, Object> values = baseValues(comp);
		values.put(F_SCHEMA_TYPE, comp.getSchemaType());
		values.put(F_VARIANT, comp.getVariant() == null ? "" : comp.getVariant());
		values.put(F_DATA, comp.getData() == null ? JSONB.jsonb("{}") : toJsonb(comp.getData()));
		return values;
	}

	@Override
	public void storeJsonComp(AssetJsonComp comp) {
		comp.setUuid(insertAndReturnUuid(JSON_TABLE, jsonValues(comp)));
	}

	@Override
	public AssetJsonComp upsertJsonComp(AssetJsonComp comp) {
		comp.setUuid(upsert(JSON_TABLE, jsonValues(comp), F_ASSET_UUID, F_NODE_KIND, F_SCHEMA_TYPE, F_VARIANT));
		return comp;
	}

	@Override
	public List<AssetJsonComp> loadJsonComps(UUID assetUuid) {
		return doLoadByAsset(JSON_TABLE, assetUuid, this::mapJsonComp);
	}

	@Override
	public AssetJsonComp loadJsonComp(UUID uuid) {
		return doLoad(JSON_TABLE, uuid, this::mapJsonComp);
	}

	@Override
	public AssetJsonComp loadJsonComp(UUID assetUuid, String nodeKind, String schemaType, String variant) {
		return doLoadOne(JSON_TABLE, identity(assetUuid, nodeKind)
			.and(F_SCHEMA_TYPE.eq(schemaType))
			.and(F_VARIANT.eq(variant == null ? "" : variant)), this::mapJsonComp);
	}

	@Override
	public void deleteJsonComp(UUID uuid) {
		doDelete(JSON_TABLE, uuid);
	}

	@Override
	public AssetJsonComp updateJsonComp(AssetJsonComp comp) {
		doUpdate(JSON_TABLE, comp.getUuid(), jsonValues(comp));
		return comp;
	}

	private AssetJsonComp mapJsonComp(Record r) {
		AssetJsonCompImpl comp = new AssetJsonCompImpl();
		readBase(r, comp);
		comp.setSchemaType(r.get(F_SCHEMA_TYPE));
		comp.setVariant(r.get(F_VARIANT));
		comp.setData(toJsonObject(r.get(F_DATA)));
		return comp;
	}

	// ---- conversions ----

	private static JSONB toJsonb(JsonObject json) {
		return json == null ? null : JSONB.jsonb(json.encode());
	}

	private static JsonObject toJsonObject(JSONB jsonb) {
		return jsonb == null ? null : new JsonObject(jsonb.data());
	}

	private static int intOrZero(Integer value) {
		return value == null ? 0 : value;
	}

	private static long longOrZero(Long value) {
		return value == null ? 0L : value;
	}

	private Instant toInstant(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof Instant i) {
			return i;
		}
		if (value instanceof java.sql.Timestamp ts) {
			return ts.toInstant();
		}
		if (value instanceof java.time.LocalDateTime ldt) {
			return ldt.atZone(java.time.ZoneOffset.UTC).toInstant();
		}
		throw new IllegalArgumentException("Cannot convert " + value.getClass() + " to Instant");
	}
}
