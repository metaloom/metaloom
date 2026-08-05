package io.metaloom.loom.db.jooq.dao.tag;

import static io.metaloom.loom.db.jooq.tables.JooqAnnotationTag.ANNOTATION_TAG;
import static io.metaloom.loom.db.jooq.tables.JooqTag.TAG;
import static io.metaloom.loom.db.jooq.tables.JooqTagAsset.TAG_ASSET;
import static io.metaloom.loom.db.jooq.tables.JooqTagUserMeta.TAG_USER_META;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.DSLContext;
import org.jooq.SelectConditionStep;
import org.jooq.Table;
import org.jooq.TableRecord;
import org.jooq.impl.DSL;

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
	public UUID resolveOrCreateAssetTag(AssetTag tag) {
		return resolveOrCreate(ctx(), tag);
	}

	/**
	 * The body of {@link #resolveOrCreateAssetTag(AssetTag)}, parameterized by the {@link DSLContext} to run on.
	 *
	 * <p>
	 * Every statement of a bulk call has to run on the transaction's context rather than the DAO's: {@link #ctx()} takes its own connection from the
	 * pool, so a call made through it inside {@code transaction(...)} would commit separately and read the uncommitted rows of the transaction not at
	 * all.
	 * </p>
	 */
	private UUID resolveOrCreate(DSLContext tx, AssetTag tag) {
		Objects.requireNonNull(tag, "A tag must be provided");

		TableRecord<?> reco = tx.newRecord(getTable(), tag);
		if (tag.getUuid() == null) {
			reco.reset("uuid");
		}

		// INSERT ... ON CONFLICT (name, collection) DO UPDATE, because tags are global: the second asset
		// to receive "blurry" must land on the first asset's tag row instead of violating the unique index.
		//
		// The update set is deliberately not the whole record. jOOQ marks every mapped field as changed -
		// nulls included - so writing them all back would wipe the meta, rating and colour of a tag someone
		// else curated. coalesce(excluded, current) writes only what this call actually supplied and leaves
		// the rest, along with the original creator and creation timestamp, untouched.
		UUID uuid = tx.insertInto(TAG)
			.set(reco)
			.onConflict(TAG.NAME, TAG.COLLECTION)
			.doUpdate()
			.set(TAG.META, DSL.coalesce(DSL.excluded(TAG.META), TAG.META))
			.set(TAG.RATING, DSL.coalesce(DSL.excluded(TAG.RATING), TAG.RATING))
			.set(TAG.COLOR, DSL.coalesce(DSL.excluded(TAG.COLOR), TAG.COLOR))
			.returning(TAG.UUID)
			.fetchOne(TAG.UUID);
		if (uuid == null) {
			throw new RuntimeException("Key null!!");
		}
		tag.setUuid(uuid);

		// Read the persisted row back so the caller reports what the tag *is* rather than what this call
		// proposed - an existing tag keeps its meta, colour and creation audit, and a response built from
		// the transient pojo would show none of it. One extra select per tag write; a bulk route would
		// resolve the whole set server-side in one statement.
		Tag persisted = tx.select(getTable())
			.from(getTable())
			.where(getIdField().eq(uuid))
			.fetchOneInto(getPojoClass());
		if (persisted != null) {
			tag.setName(persisted.getName());
			tag.setCollection(persisted.getCollection());
			tag.setColor(persisted.getColor());
			tag.setMeta(persisted.getMeta());
			tag.setCreated(persisted.getCreated());
			tag.setCreatorUuid(persisted.getCreatorUuid());
			tag.setEdited(persisted.getEdited());
			tag.setEditorUuid(persisted.getEditorUuid());
		}
		return uuid;
	}

	@Override
	public void tagAsset(AssetTag tag, Asset asset) {
		DaoUtils.requireUuid(tag, "tag");
		DaoUtils.requireUuid(asset, "asset");
		attach(ctx(), tag, asset.getUuid());
	}

	@Override
	public AssetTagBulkResult bulkTagAsset(Asset asset, List<AssetTag> tags, Collection<UUID> withdraw, String withdrawNodeId) {
		DaoUtils.requireUuid(asset, "asset");
		Objects.requireNonNull(tags, "The tag list must be provided - pass an empty list to only withdraw");

		UUID assetUuid = asset.getUuid();
		List<UUID> toWithdraw = withdraw == null ? List.of() : List.copyOf(withdraw);

		// One transaction for the whole set. Not an optimization detail: a writer that attaches five tags
		// and withdraws two must not be able to leave an asset carrying three of the five because the
		// process died halfway, and a reader must never observe the intermediate state.
		return ctx().transactionResult(cfg -> {
			DSLContext tx = cfg.dsl();

			for (AssetTag tag : tags) {
				resolveOrCreate(tx, tag);
				attach(tx, tag, assetUuid);
			}

			// Detached in one statement rather than one per tag. The tags themselves survive - they are
			// global objects which other assets may carry; only this asset's placements go.
			//
			// 🔴 A writer that names itself may only withdraw its own placements. Since V2.71 a tag can sit
			// on an asset several times, so "remove tag X" would otherwise take a person's placement of the
			// same name along with the node's. A caller with no node id - a person - removes them all,
			// which is what an untag means when a human asks for it.
			int withdrawn = 0;
			if (!toWithdraw.isEmpty()) {
				var delete = tx.deleteFrom(TAG_ASSET)
					.where(TAG_ASSET.ASSET_UUID.eq(assetUuid))
					.and(TAG_ASSET.TAG_UUID.in(toWithdraw));
				if (withdrawNodeId != null) {
					delete = delete.and(TAG_ASSET.NODE_ID.eq(withdrawNodeId));
				}
				withdrawn = delete.execute();
			}
			return new AssetTagBulkResult(tags, withdrawn);
		});
	}

	/**
	 * Attach one tag to one asset at one place, on the given context.
	 *
	 * <p>
	 * The region (time + area) and the provenance belong to the tag&lt;-&gt;asset relationship rather than to the tag, and are thus stored on the join
	 * row. Since V2.71 that row has its own identity, so the same tag may be placed on one asset repeatedly - one placement per face, per timecode.
	 * </p>
	 *
	 * <p>
	 * Upserted on <code>tag_asset_placement_key</code>, which is <code>(tag, asset, time_from, time_to, areaStartX, areaStartY)</code> with
	 * <code>NULLS NOT DISTINCT</code>: re-tagging the same place is the no-op it always was, while a different place is a new row. The box extent
	 * (<code>areaWidth</code>/<code>areaHeight</code>) sits outside the key, so a corrected box updates the placement instead of duplicating it.
	 * </p>
	 *
	 * <p>
	 * 🔴 <strong>The first author keeps the row.</strong> The update carries a {@code WHERE node_id IS NOT DISTINCT FROM excluded.node_id}, so a write
	 * only ever rewrites a placement made by the same writer. A node attaching a tag a person already placed leaves that row untouched - it stays
	 * {@code node_kind = 'manual'} and keeps its timestamps - which matters because the node's own reconciliation later deletes by {@code node_id}.
	 * Without the guard, tagging would quietly transfer authorship and a subsequent reconcile would delete somebody's curation.
	 * </p>
	 */
	private void attach(DSLContext tx, AssetTag tag, UUID assetUuid) {
		DaoUtils.requireUuid(tag, "tag");
		String nodeKind = tag.getNodeKind() == null ? AssetTag.MANUAL_NODE_KIND : tag.getNodeKind();
		String producerVersion = tag.getProducerVersion() == null ? "" : tag.getProducerVersion();

		UUID placementUuid = tx.insertInto(TAG_ASSET)
			.set(TAG_ASSET.TAG_UUID, tag.getUuid())
			.set(TAG_ASSET.ASSET_UUID, assetUuid)
			.set(TAG_ASSET.TIME_FROM, toInt(tag.getTimeFrom()))
			.set(TAG_ASSET.TIME_TO, toInt(tag.getTimeTo()))
			.set(TAG_ASSET.AREASTARTX, tag.getAreaStartX())
			.set(TAG_ASSET.AREASTARTY, tag.getAreaStartY())
			.set(TAG_ASSET.AREAWIDTH, tag.getAreaWidth())
			.set(TAG_ASSET.AREAHEIGHT, tag.getAreaHeight())
			.set(TAG_ASSET.NODE_KIND, nodeKind)
			.set(TAG_ASSET.NODE_ID, tag.getNodeId())
			.set(TAG_ASSET.PRODUCER_VERSION, producerVersion)
			.set(TAG_ASSET.CONFIDENCE, tag.getConfidence())
			.set(TAG_ASSET.CREATOR_UUID, tag.getAttachedBy())
			.onConflict(TAG_ASSET.TAG_UUID, TAG_ASSET.ASSET_UUID,
				TAG_ASSET.TIME_FROM, TAG_ASSET.TIME_TO, TAG_ASSET.AREASTARTX, TAG_ASSET.AREASTARTY)
			.doUpdate()
			.set(TAG_ASSET.AREAWIDTH, tag.getAreaWidth())
			.set(TAG_ASSET.AREAHEIGHT, tag.getAreaHeight())
			.set(TAG_ASSET.PRODUCER_VERSION, producerVersion)
			.set(TAG_ASSET.CONFIDENCE, tag.getConfidence())
			.where(TAG_ASSET.NODE_ID.isNotDistinctFrom(DSL.excluded(TAG_ASSET.NODE_ID)))
			.returning(TAG_ASSET.UUID)
			.fetchOne(TAG_ASSET.UUID);

		if (placementUuid == null) {
			// The WHERE above suppressed the update, so nothing was returned: the placement exists and
			// belongs to somebody else. Read it back rather than reporting a half-populated pojo - the tag
			// *is* on the asset, which is what the caller asked for, and it is the other writer's row.
			tag.setPlacementUuid(tx.select(TAG_ASSET.UUID)
				.from(TAG_ASSET)
				.where(TAG_ASSET.TAG_UUID.eq(tag.getUuid()))
				.and(TAG_ASSET.ASSET_UUID.eq(assetUuid))
				.and(TAG_ASSET.TIME_FROM.isNotDistinctFrom(toInt(tag.getTimeFrom())))
				.and(TAG_ASSET.TIME_TO.isNotDistinctFrom(toInt(tag.getTimeTo())))
				.and(TAG_ASSET.AREASTARTX.isNotDistinctFrom(tag.getAreaStartX()))
				.and(TAG_ASSET.AREASTARTY.isNotDistinctFrom(tag.getAreaStartY()))
				.fetchOne(TAG_ASSET.UUID));
		} else {
			tag.setPlacementUuid(placementUuid);
			tag.setNodeKind(nodeKind);
			tag.setProducerVersion(producerVersion);
		}
	}

	private static Integer toInt(Long value) {
		return value == null ? null : value.intValue();
	}

	@Override
	public List<AssetTag> assetTags(Asset asset) {
		DaoUtils.requireUuid(asset, "asset");

		// Select the tag columns plus the placement columns from the join row so the returned AssetTag
		// pojos carry the region and the provenance of the relationship. Using an explicit flat column
		// list (rather than select(getTable())) ensures fetchInto maps every column by name.
		//
		// 🔴 Three of the join columns must be aliased: tag and tag_asset both have uuid, created and
		// creator_uuid, and fetchInto maps by name - unaliased, the placement would overwrite the tag's
		// own identity and audit fields with the join row's.
		return ctx().select(TAG.asterisk(),
			TAG_ASSET.TIME_FROM, TAG_ASSET.TIME_TO,
			TAG_ASSET.AREASTARTX, TAG_ASSET.AREASTARTY,
			TAG_ASSET.AREAWIDTH, TAG_ASSET.AREAHEIGHT,
			TAG_ASSET.UUID.as("placement_uuid"),
			TAG_ASSET.NODE_KIND, TAG_ASSET.NODE_ID, TAG_ASSET.PRODUCER_VERSION, TAG_ASSET.CONFIDENCE,
			TAG_ASSET.CREATED.as("attached"),
			TAG_ASSET.CREATOR_UUID.as("attached_by"))
			.from(TAG)
			.join(TAG_ASSET)
			.on(TAG.UUID.eq(TAG_ASSET.TAG_UUID))
			.where(TAG_ASSET.ASSET_UUID.eq(asset.getUuid()))
			.orderBy(TAG_ASSET.CREATED)
			.fetchInto(AssetTagImpl.class);
	}

	@Override
	public void untagAsset(Tag tag, Asset asset) {
		DaoUtils.requireUuid(tag, "tag");
		DaoUtils.requireUuid(asset, "asset");

		// Every placement of this tag on this asset. A tag placed on three faces is removed from all
		// three - "remove this tag from this picture" is what the caller asked for. Removing one face
		// alone is removePlacement().
		ctx().deleteFrom(TAG_ASSET)
			.where(TAG_ASSET.TAG_UUID.eq(tag.getUuid())
				.and(TAG_ASSET.ASSET_UUID.eq(asset.getUuid())))
			.execute();
	}

	@Override
	public boolean removePlacement(Asset asset, UUID placementUuid) {
		DaoUtils.requireUuid(asset, "asset");
		Objects.requireNonNull(placementUuid, "The placement uuid must be provided");

		// Scoped by asset as well as by placement: the uuid alone would let a caller who may edit one
		// asset detach a tag from another.
		return ctx().deleteFrom(TAG_ASSET)
			.where(TAG_ASSET.UUID.eq(placementUuid))
			.and(TAG_ASSET.ASSET_UUID.eq(asset.getUuid()))
			.execute() > 0;
	}

	@Override
	public List<AssetTag> assetTagsByNode(Asset asset, String nodeId) {
		DaoUtils.requireUuid(asset, "asset");
		Objects.requireNonNull(nodeId, "The node id must be provided");

		return assetTags(asset).stream()
			.filter(tag -> nodeId.equals(tag.getNodeId()))
			.toList();
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
	public void storeUserRating(UUID tagUuid, UUID userUuid, int rating) {
		Objects.requireNonNull(tagUuid, "A tag uuid must be provided");
		Objects.requireNonNull(userUuid, "A user uuid must be provided");

		// tag_user_meta has a composite (tag_uuid, user_uuid) primary key and no uuid column, so we
		// upsert on the key instead of using the base store() impl.
		ctx().insertInto(TAG_USER_META)
			.set(TAG_USER_META.TAG_UUID, tagUuid)
			.set(TAG_USER_META.USER_UUID, userUuid)
			.set(TAG_USER_META.RATING, rating)
			.onConflict(TAG_USER_META.TAG_UUID, TAG_USER_META.USER_UUID)
			.doUpdate()
			.set(TAG_USER_META.RATING, rating)
			.execute();
	}

	@Override
	public Integer readUserRating(UUID tagUuid, UUID userUuid) {
		Objects.requireNonNull(tagUuid, "A tag uuid must be provided");
		Objects.requireNonNull(userUuid, "A user uuid must be provided");

		return ctx().select(TAG_USER_META.RATING)
			.from(TAG_USER_META)
			.where(TAG_USER_META.TAG_UUID.eq(tagUuid)
				.and(TAG_USER_META.USER_UUID.eq(userUuid)))
			.fetchOne(TAG_USER_META.RATING);
	}

	@Override
	public void deleteUserRating(UUID tagUuid, UUID userUuid) {
		Objects.requireNonNull(tagUuid, "A tag uuid must be provided");
		Objects.requireNonNull(userUuid, "A user uuid must be provided");

		ctx().deleteFrom(TAG_USER_META)
			.where(TAG_USER_META.TAG_UUID.eq(tagUuid)
				.and(TAG_USER_META.USER_UUID.eq(userUuid)))
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
