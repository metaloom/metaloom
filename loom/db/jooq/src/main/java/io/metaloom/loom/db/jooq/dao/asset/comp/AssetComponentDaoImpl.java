package io.metaloom.loom.db.jooq.dao.asset.comp;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.TableRecord;
import org.jooq.UpdatableRecord;
import org.jooq.impl.DSL;

import io.metaloom.loom.db.model.asset.AssetAudioComp;
import io.metaloom.loom.db.model.asset.AssetComponentDao;
import io.metaloom.loom.db.model.asset.AssetDocComp;
import io.metaloom.loom.db.model.asset.AssetGeoComp;
import io.metaloom.loom.db.model.asset.AssetImageComp;
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

	private static final Field<UUID> UUID_FIELD = DSL.field("uuid", UUID.class);
	private static final Field<UUID> ASSET_UUID_FIELD = DSL.field("asset_uuid", UUID.class);

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
		return total;
	}

	private void setCreatorEditor(UUID userUuid, Object comp) {
		if (comp instanceof io.metaloom.loom.db.CUDElement) {
			io.metaloom.loom.db.CUDElement<?> el = (io.metaloom.loom.db.CUDElement<?>) comp;
			el.setCreatorUuid(userUuid);
			el.setEditorUuid(userUuid);
			el.setCreated(Instant.now());
			el.setEdited(Instant.now());
		}
	}

	@SuppressWarnings("unchecked")
	private <T> UUID storeAndReturnUuid(Table<?> table, T element) {
		TableRecord<?> reco = (TableRecord<?>) ctx.newRecord(table, element);
		reco.reset("uuid");
		UUID uuid = ctx.insertInto(table)
			.set(reco)
			.returning(DSL.field("uuid", UUID.class))
			.fetchOne("uuid", UUID.class);
		return uuid;
	}

	private <T> void doUpdate(Table<?> table, T element) {
		UpdatableRecord<?> reco = (UpdatableRecord<?>) ctx.newRecord(table, element);
		ctx.executeUpdate(reco);
	}

	private void doDelete(Table<?> table, UUID uuid) {
		ctx.deleteFrom(table).where(UUID_FIELD.eq(uuid)).execute();
	}

	private <T> T doLoad(Table<?> table, UUID uuid, Class<T> clazz) {
		return ctx.select(table.fields())
			.from(table)
			.where(UUID_FIELD.eq(uuid))
			.fetchOneInto(clazz);
	}

	private <T> List<T> doLoadByAsset(Table<?> table, UUID assetUuid, Class<T> clazz) {
		return ctx.select(table.fields())
			.from(table)
			.where(ASSET_UUID_FIELD.eq(assetUuid))
			.fetchInto(clazz);
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
		UUID uuid = storeAndReturnUuid(GEO_TABLE, comp);
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
		doUpdate(GEO_TABLE, comp);
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
		UUID uuid = storeAndReturnUuid(DOC_TABLE, comp);
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
		doUpdate(DOC_TABLE, comp);
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
		UUID uuid = storeAndReturnUuid(IMAGE_TABLE, comp);
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
		doUpdate(IMAGE_TABLE, comp);
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
		UUID uuid = storeAndReturnUuid(VIDEO_TABLE, comp);
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
		doUpdate(VIDEO_TABLE, comp);
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
		UUID uuid = storeAndReturnUuid(AUDIO_TABLE, comp);
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
		doUpdate(AUDIO_TABLE, comp);
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
		UUID uuid = storeAndReturnUuid(TRANSCRIPT_TABLE, comp);
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
		doUpdate(TRANSCRIPT_TABLE, comp);
		return comp;
	}
}
