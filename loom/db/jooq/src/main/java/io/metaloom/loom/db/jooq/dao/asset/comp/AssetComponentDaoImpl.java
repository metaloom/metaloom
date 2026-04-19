package io.metaloom.loom.db.jooq.dao.asset.comp;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

import io.metaloom.loom.db.CUDElement;
import io.metaloom.loom.db.model.asset.AssetAudioComp;
import io.metaloom.loom.db.model.asset.AssetComponent;
import io.metaloom.loom.db.model.asset.AssetComponentDao;
import io.metaloom.loom.db.model.asset.AssetDocComp;
import io.metaloom.loom.db.model.asset.AssetGeoComp;
import io.metaloom.loom.db.model.asset.AssetImageComp;
import io.metaloom.loom.db.model.asset.AssetJsonComp;
import io.metaloom.loom.db.model.asset.AssetTranscriptComp;
import io.metaloom.loom.db.model.asset.AssetVideoComp;

@Singleton
public class AssetComponentDaoImpl implements AssetComponentDao {

private static final Table<?> GEO_TABLE = DSL.table("asset_geo_comp");
private static final Table<?> DOC_TABLE = DSL.table("asset_doc_comp");
private static final Table<?> IMAGE_TABLE = DSL.table("asset_image_comp");
private static final Table<?> VIDEO_TABLE = DSL.table("asset_video_comp");
private static final Table<?> AUDIO_TABLE = DSL.table("asset_audio_comp");
private static final Table<?> TRANSCRIPT_TABLE = DSL.table("asset_transcript_comp");
private static final Table<?> JSON_TABLE = DSL.table("asset_json_comp");

private static final Field<UUID> F_UUID = DSL.field("uuid", UUID.class);
private static final Field<UUID> F_ASSET_UUID = DSL.field("asset_uuid", UUID.class);
private static final Field<String> F_SOURCE = DSL.field("source", String.class);
private static final Field<Instant> F_CREATED = DSL.field("created", Instant.class);
private static final Field<UUID> F_CREATOR_UUID = DSL.field("creator_uuid", UUID.class);
private static final Field<Instant> F_EDITED = DSL.field("edited", Instant.class);
private static final Field<UUID> F_EDITOR_UUID = DSL.field("editor_uuid", UUID.class);

// Geo fields
private static final Field<Double> F_GEO_LON = DSL.field("geo_lon", Double.class);
private static final Field<Double> F_GEO_LAT = DSL.field("geo_lat", Double.class);
private static final Field<String> F_GEO_ALIAS = DSL.field("geo_alias", String.class);

// Doc fields
private static final Field<String> F_DOC_PLAIN_TEXT = DSL.field("doc_plain_text", String.class);
private static final Field<Integer> F_DOC_WORD_COUNT = DSL.field("doc_word_count", Integer.class);

// Image fields
private static final Field<String> F_IMAGE_DOMINANT_COLOR = DSL.field("image_dominant_color", String.class);
private static final Field<Integer> F_MEDIA_WIDTH = DSL.field("media_width", Integer.class);
private static final Field<Integer> F_MEDIA_HEIGHT = DSL.field("media_height", Integer.class);

// Video fields
private static final Field<Long> F_MEDIA_DURATION = DSL.field("media_duration", Long.class);
private static final Field<Integer> F_VIDEO_BITRATE = DSL.field("video_bitrate", Integer.class);
private static final Field<String> F_VIDEO_ENCODING = DSL.field("video_encoding", String.class);

// Audio fields
private static final Field<Integer> F_AUDIO_BPM = DSL.field("audio_bpm", Integer.class);
private static final Field<Integer> F_AUDIO_SAMPLING_RATE = DSL.field("audio_sampling_rate", Integer.class);
private static final Field<Integer> F_AUDIO_CHANNELS = DSL.field("audio_channels", Integer.class);
private static final Field<Integer> F_AUDIO_BITRATE = DSL.field("audio_bitrate", Integer.class);
private static final Field<String> F_AUDIO_ENCODING = DSL.field("audio_encoding", String.class);

// Transcript fields
private static final Field<String> F_LANG = DSL.field("lang", String.class);
private static final Field<String> F_TRANSCRIPT_TEXT = DSL.field("transcript_text", String.class);
private static final Field<Integer> F_DURATION = DSL.field("duration", Integer.class);
private static final Field<String> F_MODEL = DSL.field("model", String.class);
private static final Field<String> F_TRANSCRIPT_JSON = DSL.field("transcript_json", SQLDataType.VARCHAR);

// Json fields
private static final Field<String> F_SCHEMA_TYPE = DSL.field("schema_type", String.class);
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
ctx.deleteFrom(GEO_TABLE).execute();
ctx.deleteFrom(DOC_TABLE).execute();
ctx.deleteFrom(IMAGE_TABLE).execute();
ctx.deleteFrom(VIDEO_TABLE).execute();
ctx.deleteFrom(AUDIO_TABLE).execute();
ctx.deleteFrom(TRANSCRIPT_TABLE).execute();
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
total += ctx.fetchCount(JSON_TABLE);
return total;
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

@SuppressWarnings({ "unchecked", "rawtypes" })
private UUID insertAndReturnUuid(Table<?> table, Map<Field<?>, Object> values) {
return ctx.insertInto(table)
.set((Map) values)
.returning(F_UUID)
.fetchOne(F_UUID);
}

@SuppressWarnings({ "unchecked", "rawtypes" })
private void doUpdate(Table<?> table, UUID uuid, Map<Field<?>, Object> values) {
ctx.update(table)
.set((Map) values)
.where(F_UUID.eq(uuid))
.execute();
}

private void doDelete(Table<?> table, UUID uuid) {
ctx.deleteFrom(table).where(F_UUID.eq(uuid)).execute();
}

private <T> T doLoad(Table<?> table, UUID uuid, Class<T> clazz) {
return ctx.select(DSL.asterisk())
.from(table)
.where(F_UUID.eq(uuid))
.fetchOneInto(clazz);
}

private <T> List<T> doLoadByAsset(Table<?> table, UUID assetUuid, Class<T> clazz) {
return ctx.select(DSL.asterisk())
.from(table)
.where(F_ASSET_UUID.eq(assetUuid))
.fetchInto(clazz);
}

private Map<Field<?>, Object> baseValues(AssetComponent<?> comp) {
Map<Field<?>, Object> values = new HashMap<>();
values.put(F_ASSET_UUID, comp.getAssetUuid());
values.put(F_SOURCE, comp.getSource());
values.put(F_CREATED, comp.getCreated());
values.put(F_CREATOR_UUID, comp.getCreatorUuid());
values.put(F_EDITED, comp.getEdited());
values.put(F_EDITOR_UUID, comp.getEditorUuid());
return values;
}

// ---- Geo ----

@Override
public AssetGeoComp createGeoComp(UUID userUuid, UUID assetUuid, String source) {
AssetGeoCompImpl comp = new AssetGeoCompImpl();
comp.setAssetUuid(assetUuid);
comp.setSource(source);
setCreatorEditor(userUuid, comp);
return comp;
}

@Override
public void storeGeoComp(AssetGeoComp comp) {
Map<Field<?>, Object> values = baseValues(comp);
values.put(F_GEO_LON, comp.getGeoLon());
values.put(F_GEO_LAT, comp.getGeoLat());
values.put(F_GEO_ALIAS, comp.getGeoAlias());
UUID uuid = insertAndReturnUuid(GEO_TABLE, values);
comp.setUuid(uuid);
}

@Override
public List<AssetGeoComp> loadGeoComps(UUID assetUuid) {
return doLoadByAsset(GEO_TABLE, assetUuid, AssetGeoCompImpl.class).stream()
.map(e -> (AssetGeoComp) e).toList();
}

@Override
public AssetGeoComp loadGeoComp(UUID uuid) {
return doLoad(GEO_TABLE, uuid, AssetGeoCompImpl.class);
}

@Override
public void deleteGeoComp(UUID uuid) {
doDelete(GEO_TABLE, uuid);
}

@Override
public AssetGeoComp updateGeoComp(AssetGeoComp comp) {
Map<Field<?>, Object> values = baseValues(comp);
values.put(F_GEO_LON, comp.getGeoLon());
values.put(F_GEO_LAT, comp.getGeoLat());
values.put(F_GEO_ALIAS, comp.getGeoAlias());
doUpdate(GEO_TABLE, comp.getUuid(), values);
return comp;
}

// ---- Doc ----

@Override
public AssetDocComp createDocComp(UUID userUuid, UUID assetUuid, String source) {
AssetDocCompImpl comp = new AssetDocCompImpl();
comp.setAssetUuid(assetUuid);
comp.setSource(source);
setCreatorEditor(userUuid, comp);
return comp;
}

@Override
public void storeDocComp(AssetDocComp comp) {
Map<Field<?>, Object> values = baseValues(comp);
values.put(F_DOC_PLAIN_TEXT, comp.getDocPlainText());
values.put(F_DOC_WORD_COUNT, comp.getDocWordCount());
UUID uuid = insertAndReturnUuid(DOC_TABLE, values);
comp.setUuid(uuid);
}

@Override
public List<AssetDocComp> loadDocComps(UUID assetUuid) {
return doLoadByAsset(DOC_TABLE, assetUuid, AssetDocCompImpl.class).stream()
.map(e -> (AssetDocComp) e).toList();
}

@Override
public AssetDocComp loadDocComp(UUID uuid) {
return doLoad(DOC_TABLE, uuid, AssetDocCompImpl.class);
}

@Override
public void deleteDocComp(UUID uuid) {
doDelete(DOC_TABLE, uuid);
}

@Override
public AssetDocComp updateDocComp(AssetDocComp comp) {
Map<Field<?>, Object> values = baseValues(comp);
values.put(F_DOC_PLAIN_TEXT, comp.getDocPlainText());
values.put(F_DOC_WORD_COUNT, comp.getDocWordCount());
doUpdate(DOC_TABLE, comp.getUuid(), values);
return comp;
}

// ---- Image ----

@Override
public AssetImageComp createImageComp(UUID userUuid, UUID assetUuid, String source) {
AssetImageCompImpl comp = new AssetImageCompImpl();
comp.setAssetUuid(assetUuid);
comp.setSource(source);
setCreatorEditor(userUuid, comp);
return comp;
}

@Override
public void storeImageComp(AssetImageComp comp) {
Map<Field<?>, Object> values = baseValues(comp);
values.put(F_IMAGE_DOMINANT_COLOR, comp.getImageDominantColor());
values.put(F_MEDIA_WIDTH, comp.getMediaWidth());
values.put(F_MEDIA_HEIGHT, comp.getMediaHeight());
UUID uuid = insertAndReturnUuid(IMAGE_TABLE, values);
comp.setUuid(uuid);
}

@Override
public List<AssetImageComp> loadImageComps(UUID assetUuid) {
return doLoadByAsset(IMAGE_TABLE, assetUuid, AssetImageCompImpl.class).stream()
.map(e -> (AssetImageComp) e).toList();
}

@Override
public AssetImageComp loadImageComp(UUID uuid) {
return doLoad(IMAGE_TABLE, uuid, AssetImageCompImpl.class);
}

@Override
public void deleteImageComp(UUID uuid) {
doDelete(IMAGE_TABLE, uuid);
}

@Override
public AssetImageComp updateImageComp(AssetImageComp comp) {
Map<Field<?>, Object> values = baseValues(comp);
values.put(F_IMAGE_DOMINANT_COLOR, comp.getImageDominantColor());
values.put(F_MEDIA_WIDTH, comp.getMediaWidth());
values.put(F_MEDIA_HEIGHT, comp.getMediaHeight());
doUpdate(IMAGE_TABLE, comp.getUuid(), values);
return comp;
}

// ---- Video ----

@Override
public AssetVideoComp createVideoComp(UUID userUuid, UUID assetUuid, String source) {
AssetVideoCompImpl comp = new AssetVideoCompImpl();
comp.setAssetUuid(assetUuid);
comp.setSource(source);
setCreatorEditor(userUuid, comp);
return comp;
}

@Override
public void storeVideoComp(AssetVideoComp comp) {
Map<Field<?>, Object> values = baseValues(comp);
values.put(F_MEDIA_WIDTH, comp.getMediaWidth());
values.put(F_MEDIA_HEIGHT, comp.getMediaHeight());
values.put(F_MEDIA_DURATION, comp.getMediaDuration());
values.put(F_VIDEO_BITRATE, comp.getVideoBitrate());
values.put(F_VIDEO_ENCODING, comp.getVideoEncoding());
UUID uuid = insertAndReturnUuid(VIDEO_TABLE, values);
comp.setUuid(uuid);
}

@Override
public List<AssetVideoComp> loadVideoComps(UUID assetUuid) {
return doLoadByAsset(VIDEO_TABLE, assetUuid, AssetVideoCompImpl.class).stream()
.map(e -> (AssetVideoComp) e).toList();
}

@Override
public AssetVideoComp loadVideoComp(UUID uuid) {
return doLoad(VIDEO_TABLE, uuid, AssetVideoCompImpl.class);
}

@Override
public void deleteVideoComp(UUID uuid) {
doDelete(VIDEO_TABLE, uuid);
}

@Override
public AssetVideoComp updateVideoComp(AssetVideoComp comp) {
Map<Field<?>, Object> values = baseValues(comp);
values.put(F_MEDIA_WIDTH, comp.getMediaWidth());
values.put(F_MEDIA_HEIGHT, comp.getMediaHeight());
values.put(F_MEDIA_DURATION, comp.getMediaDuration());
values.put(F_VIDEO_BITRATE, comp.getVideoBitrate());
values.put(F_VIDEO_ENCODING, comp.getVideoEncoding());
doUpdate(VIDEO_TABLE, comp.getUuid(), values);
return comp;
}

// ---- Audio ----

@Override
public AssetAudioComp createAudioComp(UUID userUuid, UUID assetUuid, String source) {
AssetAudioCompImpl comp = new AssetAudioCompImpl();
comp.setAssetUuid(assetUuid);
comp.setSource(source);
setCreatorEditor(userUuid, comp);
return comp;
}

@Override
public void storeAudioComp(AssetAudioComp comp) {
Map<Field<?>, Object> values = baseValues(comp);
values.put(F_AUDIO_BPM, comp.getAudioBpm());
values.put(F_AUDIO_SAMPLING_RATE, comp.getAudioSamplingRate());
values.put(F_AUDIO_CHANNELS, comp.getAudioChannels());
values.put(F_AUDIO_BITRATE, comp.getAudioBitrate());
values.put(F_AUDIO_ENCODING, comp.getAudioEncoding());
values.put(F_MEDIA_DURATION, comp.getMediaDuration());
UUID uuid = insertAndReturnUuid(AUDIO_TABLE, values);
comp.setUuid(uuid);
}

@Override
public List<AssetAudioComp> loadAudioComps(UUID assetUuid) {
return doLoadByAsset(AUDIO_TABLE, assetUuid, AssetAudioCompImpl.class).stream()
.map(e -> (AssetAudioComp) e).toList();
}

@Override
public AssetAudioComp loadAudioComp(UUID uuid) {
return doLoad(AUDIO_TABLE, uuid, AssetAudioCompImpl.class);
}

@Override
public void deleteAudioComp(UUID uuid) {
doDelete(AUDIO_TABLE, uuid);
}

@Override
public AssetAudioComp updateAudioComp(AssetAudioComp comp) {
Map<Field<?>, Object> values = baseValues(comp);
values.put(F_AUDIO_BPM, comp.getAudioBpm());
values.put(F_AUDIO_SAMPLING_RATE, comp.getAudioSamplingRate());
values.put(F_AUDIO_CHANNELS, comp.getAudioChannels());
values.put(F_AUDIO_BITRATE, comp.getAudioBitrate());
values.put(F_AUDIO_ENCODING, comp.getAudioEncoding());
values.put(F_MEDIA_DURATION, comp.getMediaDuration());
doUpdate(AUDIO_TABLE, comp.getUuid(), values);
return comp;
}

// ---- Transcript ----

@Override
public AssetTranscriptComp createTranscriptComp(UUID userUuid, UUID assetUuid, String source) {
AssetTranscriptCompImpl comp = new AssetTranscriptCompImpl();
comp.setAssetUuid(assetUuid);
comp.setSource(source);
setCreatorEditor(userUuid, comp);
return comp;
}

@Override
public void storeTranscriptComp(AssetTranscriptComp comp) {
Map<Field<?>, Object> values = baseValues(comp);
values.put(F_LANG, comp.getLang());
values.put(F_TRANSCRIPT_TEXT, comp.getTranscriptText());
values.put(F_DURATION, comp.getDuration());
values.put(F_MODEL, comp.getModel());
values.put(F_TRANSCRIPT_JSON, comp.getTranscriptJson() != null ? comp.getTranscriptJson().encode() : null);
UUID uuid = insertAndReturnUuid(TRANSCRIPT_TABLE, values);
comp.setUuid(uuid);
}

@Override
public List<AssetTranscriptComp> loadTranscriptComps(UUID assetUuid) {
return doLoadByAsset(TRANSCRIPT_TABLE, assetUuid, AssetTranscriptCompImpl.class).stream()
.map(e -> (AssetTranscriptComp) e).toList();
}

@Override
public AssetTranscriptComp loadTranscriptComp(UUID uuid) {
return doLoad(TRANSCRIPT_TABLE, uuid, AssetTranscriptCompImpl.class);
}

@Override
public void deleteTranscriptComp(UUID uuid) {
doDelete(TRANSCRIPT_TABLE, uuid);
}

@Override
public AssetTranscriptComp updateTranscriptComp(AssetTranscriptComp comp) {
Map<Field<?>, Object> values = baseValues(comp);
values.put(F_LANG, comp.getLang());
values.put(F_TRANSCRIPT_TEXT, comp.getTranscriptText());
values.put(F_DURATION, comp.getDuration());
values.put(F_MODEL, comp.getModel());
values.put(F_TRANSCRIPT_JSON, comp.getTranscriptJson() != null ? comp.getTranscriptJson().encode() : null);
doUpdate(TRANSCRIPT_TABLE, comp.getUuid(), values);
return comp;
}
// ---- Json ----

@Override
public AssetJsonComp createJsonComp(UUID userUuid, UUID assetUuid, String source) {
AssetJsonCompImpl comp = new AssetJsonCompImpl();
comp.setAssetUuid(assetUuid);
comp.setSource(source);
setCreatorEditor(userUuid, comp);
return comp;
}

@Override
public void storeJsonComp(AssetJsonComp comp) {
Map<Field<?>, Object> values = baseValues(comp);
values.put(F_SCHEMA_TYPE, comp.getSchemaType());
values.put(F_DATA, comp.getData() != null ? JSONB.jsonb(comp.getData().encode()) : null);
UUID uuid = insertAndReturnUuid(JSON_TABLE, values);
comp.setUuid(uuid);
}

@Override
public List<AssetJsonComp> loadJsonComps(UUID assetUuid) {
return ctx.select(DSL.asterisk())
	.from(JSON_TABLE)
	.where(F_ASSET_UUID.eq(assetUuid))
	.fetch(this::mapJsonComp);
}

@Override
public AssetJsonComp loadJsonComp(UUID uuid) {
return ctx.select(DSL.asterisk())
	.from(JSON_TABLE)
	.where(F_UUID.eq(uuid))
	.fetchOne(this::mapJsonComp);
}

@Override
public void deleteJsonComp(UUID uuid) {
doDelete(JSON_TABLE, uuid);
}

@Override
public AssetJsonComp updateJsonComp(AssetJsonComp comp) {
Map<Field<?>, Object> values = baseValues(comp);
values.put(F_SCHEMA_TYPE, comp.getSchemaType());
values.put(F_DATA, comp.getData() != null ? JSONB.jsonb(comp.getData().encode()) : null);
doUpdate(JSON_TABLE, comp.getUuid(), values);
return comp;
}

private AssetJsonComp mapJsonComp(org.jooq.Record r) {
AssetJsonCompImpl comp = new AssetJsonCompImpl();
comp.setUuid(r.get(F_UUID));
comp.setAssetUuid(r.get(F_ASSET_UUID));
comp.setSource(r.get(F_SOURCE));
comp.setCreated(toInstant(r.get("created")));
comp.setCreatorUuid(r.get(F_CREATOR_UUID));
comp.setEdited(toInstant(r.get("edited")));
comp.setEditorUuid(r.get(F_EDITOR_UUID));
comp.setSchemaType(r.get(F_SCHEMA_TYPE));
JSONB jsonb = r.get(F_DATA);
if (jsonb != null) {
	comp.setData(new io.vertx.core.json.JsonObject(jsonb.data()));
}
return comp;
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
