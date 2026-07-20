package io.metaloom.loom.db.jooq.dao.tag;

import static io.metaloom.loom.db.jooq.tables.JooqAnnotationTag.ANNOTATION_TAG;
import static io.metaloom.loom.db.jooq.tables.JooqTag.TAG;
import static io.metaloom.loom.db.jooq.tables.JooqTagAsset.TAG_ASSET;

import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.DSLContext;
import org.jooq.SelectConditionStep;
import org.jooq.Table;
import org.jooq.TableRecord;

import io.metaloom.filter.Filter;
import io.metaloom.filter.FilterKey;
import io.metaloom.loom.api.filter.LoomFilterKey;
import io.metaloom.loom.db.DaoUtils;
import io.metaloom.loom.db.jooq.AbstractJooqDao;
import io.metaloom.loom.db.jooq.tables.JooqTag;
import io.metaloom.loom.db.model.annotation.Annotation;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.tag.AssetTag;
import io.metaloom.loom.db.model.tag.Tag;
import io.metaloom.loom.db.model.tag.TagDao;

@Singleton
public class TagDaoImpl extends AbstractJooqDao<Tag> implements TagDao {

	@Inject
	public TagDaoImpl(DSLContext ctx) {
		super(ctx);
	}

	@Override
	public String getTypeName() {
		return "Tags";
	}

	@Override
	protected Table<? extends TableRecord<?>> getTable() {
		return JooqTag.TAG;
	}

	@Override
	protected Class<? extends Tag> getPojoClass() {
		return TagImpl.class;
	}

	@Override
	public Tag createTag(UUID userUuid, String name, String collection) {
		Tag tag = new TagImpl();
		tag.setName(name);
		tag.setCollection(collection);
		setCreatorEditor(tag, userUuid);
		return tag;
	}

	@Override
	public AssetTag createAssetTag(UUID userUuid, String name, String collection) {
		AssetTag tag = new AssetTagImpl();
		tag.setName(name);
		tag.setCollection(collection);
		setCreatorEditor(tag, userUuid);
		return tag;
	}

	@Override
	public void tagAsset(AssetTag tag, Asset asset) {
		DaoUtils.requireUuid(tag, "tag");
		DaoUtils.requireUuid(asset, "asset");

		// The region (time + area) belongs to the tag<->asset relationship and is thus stored on the join row.
		ctx().insertInto(TAG_ASSET)
			.set(TAG_ASSET.TAG_UUID, tag.getUuid())
			.set(TAG_ASSET.ASSET_UUID, asset.getUuid())
			.set(TAG_ASSET.TIME_FROM, toInt(tag.getTimeFrom()))
			.set(TAG_ASSET.TIME_TO, toInt(tag.getTimeTo()))
			.set(TAG_ASSET.AREASTARTX, tag.getAreaStartX())
			.set(TAG_ASSET.AREASTARTY, tag.getAreaStartY())
			.set(TAG_ASSET.AREAWIDTH, tag.getAreaWidth())
			.set(TAG_ASSET.AREAHEIGHT, tag.getAreaHeight())
			.execute();
	}

	private static Integer toInt(Long value) {
		return value == null ? null : value.intValue();
	}

	@Override
	public List<AssetTag> assetTags(Asset asset) {
		DaoUtils.requireUuid(asset, "asset");

		// Select the tag columns plus the region columns from the join row so the returned
		// AssetTag pojos carry the time/area of the relationship. Using an explicit flat column
		// list (rather than select(getTable())) ensures fetchInto maps every column by name.
		return ctx().select(TAG.asterisk(),
			TAG_ASSET.TIME_FROM, TAG_ASSET.TIME_TO,
			TAG_ASSET.AREASTARTX, TAG_ASSET.AREASTARTY,
			TAG_ASSET.AREAWIDTH, TAG_ASSET.AREAHEIGHT)
			.from(TAG)
			.join(TAG_ASSET)
			.on(TAG.UUID.eq(TAG_ASSET.TAG_UUID))
			.where(TAG_ASSET.ASSET_UUID.eq(asset.getUuid()))
			.fetchInto(AssetTagImpl.class);
	}

	@Override
	public void untagAsset(Tag tag, Asset asset) {
		DaoUtils.requireUuid(tag, "tag");
		DaoUtils.requireUuid(asset, "asset");

		ctx().deleteFrom(TAG_ASSET)
			.where(TAG_ASSET.TAG_UUID.eq(tag.getUuid())
				.and(TAG_ASSET.ASSET_UUID.eq(asset.getUuid())))
			.execute();
	}

	@Override
	public void tagAnnotation(Tag tag, Annotation annotation) {
		DaoUtils.requireUuid(tag, "tag");
		DaoUtils.requireUuid(annotation, "annotation");

		ctx().insertInto(ANNOTATION_TAG,
			ANNOTATION_TAG.TAG_UUID, ANNOTATION_TAG.ANNOTATION_UUID)
			.values(tag.getUuid(), annotation.getUuid())
			.execute();
	}

	@Override
	public void untagAnnotation(Tag tag, Annotation annotation) {
		DaoUtils.requireUuid(tag, "tag");
		DaoUtils.requireUuid(annotation, "annotation");

		ctx().deleteFrom(ANNOTATION_TAG)
			.where(ANNOTATION_TAG.TAG_UUID.eq(tag.getUuid())
				.and(ANNOTATION_TAG.ANNOTATION_UUID.eq(annotation.getUuid())))
			.execute();
	}

	@Override
	protected SelectConditionStep<?> applyFilter(SelectConditionStep<?> query, Filter filter) {
		FilterKey key = filter.filterKey();
		if (key == LoomFilterKey.NAME) {
			return query.and(TAG.NAME.eq(filter.valueStr()));
		}
		if (key == LoomFilterKey.COLLECTION) {
			return query.and(TAG.COLLECTION.eq(filter.valueStr()));
		}
		return super.applyFilter(query, filter);
	}

}
