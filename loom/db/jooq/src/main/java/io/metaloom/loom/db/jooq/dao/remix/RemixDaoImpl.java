package io.metaloom.loom.db.jooq.dao.remix;

import static io.metaloom.loom.db.jooq.tables.JooqAsset.ASSET;
import static io.metaloom.loom.db.jooq.tables.JooqRemix.REMIX;
import static io.metaloom.loom.db.jooq.tables.JooqRemixMember.REMIX_MEMBER;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SelectConditionStep;
import org.jooq.Table;
import org.jooq.TableRecord;
import org.jooq.impl.DSL;

import io.metaloom.loom.db.jooq.AbstractJooqDao;
import io.metaloom.loom.db.model.remix.Remix;
import io.metaloom.loom.db.model.remix.RemixDao;
import io.metaloom.loom.db.model.remix.RemixMember;
import io.metaloom.loom.db.model.remix.RemixRole;
import io.metaloom.loom.db.page.Page;

@Singleton
public class RemixDaoImpl extends AbstractJooqDao<Remix> implements RemixDao {

	@Inject
	public RemixDaoImpl(DSLContext ctx) {
		super(ctx);
	}

	@Override
	public String getTypeName() {
		return "Remixes";
	}

	@Override
	protected Table<? extends TableRecord<?>> getTable() {
		return REMIX;
	}

	@Override
	protected Class<? extends Remix> getPojoClass() {
		return RemixImpl.class;
	}

	@Override
	public Remix createRemix(UUID userUuid, String name) {
		Remix remix = new RemixImpl();
		remix.setName(name);
		setCreatorEditor(remix, userUuid);
		return remix;
	}

	@Override
	public void linkAsset(UUID remixUuid, UUID assetUuid, RemixRole role, Integer ordinal, UUID actorUuid) {
		Objects.requireNonNull(remixUuid, "The remix uuid must be provided");
		Objects.requireNonNull(assetUuid, "The asset uuid must be provided");
		RemixRole effective = role == null ? RemixRole.DERIVED : role;

		// ON CONFLICT DO UPDATE rather than DO NOTHING: re-submitting a selection that already
		// overlaps the remix is the normal case from the UI's "combine" action, and the caller means
		// "this is the membership now", not "leave whatever was there". created/creator_uuid are left
		// out of the update set so first-add provenance survives a re-add.
		ctx().insertInto(REMIX_MEMBER,
			REMIX_MEMBER.REMIX_UUID, REMIX_MEMBER.ASSET_UUID, REMIX_MEMBER.ROLE, REMIX_MEMBER.ORDINAL,
			REMIX_MEMBER.CREATOR_UUID, REMIX_MEMBER.EDITOR_UUID)
			.values(remixUuid, assetUuid, effective.name(), ordinal, actorUuid, actorUuid)
			.onConflict(REMIX_MEMBER.REMIX_UUID, REMIX_MEMBER.ASSET_UUID)
			.doUpdate()
			.set(REMIX_MEMBER.ROLE, effective.name())
			.set(REMIX_MEMBER.ORDINAL, ordinal)
			.set(REMIX_MEMBER.EDITOR_UUID, actorUuid)
			.set(REMIX_MEMBER.EDITED, LocalDateTime.now())
			.execute();

		// Keep the denormalised pointer in step when a SOURCE is linked directly. Adding a second
		// SOURCE is rejected by remix_member_single_source before this line is reached, so there is no
		// window in which the pointer names one asset while two members claim the role.
		if (effective == RemixRole.SOURCE) {
			ctx().update(REMIX)
				.set(REMIX.SOURCE_ASSET_UUID, assetUuid)
				.where(REMIX.UUID.eq(remixUuid))
				.execute();
		}
	}

	@Override
	public void unlinkAsset(UUID remixUuid, UUID assetUuid) {
		Objects.requireNonNull(remixUuid, "The remix uuid must be provided");
		Objects.requireNonNull(assetUuid, "The asset uuid must be provided");

		ctx().transaction(cfg -> {
			DSLContext tx = cfg.dsl();
			tx.deleteFrom(REMIX_MEMBER)
				.where(REMIX_MEMBER.REMIX_UUID.eq(remixUuid)
					.and(REMIX_MEMBER.ASSET_UUID.eq(assetUuid)))
				.execute();

			// The pointer must not outlive the membership it mirrors: a remix naming a source that is
			// no longer a member would render a card for an asset the remix does not contain.
			tx.update(REMIX)
				.setNull(REMIX.SOURCE_ASSET_UUID)
				.where(REMIX.UUID.eq(remixUuid)
					.and(REMIX.SOURCE_ASSET_UUID.eq(assetUuid)))
				.execute();
		});
	}

	@Override
	public boolean containsAsset(UUID remixUuid, UUID assetUuid) {
		Objects.requireNonNull(remixUuid, "The remix uuid must be provided");
		Objects.requireNonNull(assetUuid, "The asset uuid must be provided");
		return ctx().fetchExists(ctx()
			.selectOne()
			.from(REMIX_MEMBER)
			.where(REMIX_MEMBER.REMIX_UUID.eq(remixUuid)
				.and(REMIX_MEMBER.ASSET_UUID.eq(assetUuid))));
	}

	@Override
	public long countAssets(UUID remixUuid) {
		Objects.requireNonNull(remixUuid, "The remix uuid must be provided");
		return ctx().fetchCount(REMIX_MEMBER, REMIX_MEMBER.REMIX_UUID.eq(remixUuid));
	}

	@Override
	public Page<Remix> loadPageByAsset(UUID assetUuid, UUID fromId, int pageSize) {
		Objects.requireNonNull(assetUuid, "The asset uuid must be provided");
		SelectConditionStep<?> query = ctx()
			.select(getTable())
			.from(getTable())
			.join(REMIX_MEMBER).on(REMIX_MEMBER.REMIX_UUID.eq(REMIX.UUID))
			.where(REMIX_MEMBER.ASSET_UUID.eq(assetUuid));

		return loadPage(query, fromId, pageSize, null, null, null);
	}

	@Override
	public Page<RemixMember> loadMembers(UUID remixUuid, UUID fromId, int pageSize) {
		Objects.requireNonNull(remixUuid, "The remix uuid must be provided");
		long totalCount = countAssets(remixUuid);

		Condition condition = REMIX_MEMBER.REMIX_UUID.eq(remixUuid);
		if (fromId != null) {
			// Keyset seek: continue strictly after the cursor row in (created, uuid) ascending order.
			// A cursor whose row is gone - the member was removed between pages - yields no rows rather
			// than silently restarting from the top and repeating the first page.
			LocalDateTime fromCreated = ctx().select(REMIX_MEMBER.CREATED)
				.from(REMIX_MEMBER)
				.where(REMIX_MEMBER.UUID.eq(fromId))
				.fetchOne(REMIX_MEMBER.CREATED);
			if (fromCreated == null) {
				return new Page<>(pageSize, totalCount, List.<RemixMember> of());
			}
			condition = condition.and(DSL.row(REMIX_MEMBER.CREATED, REMIX_MEMBER.UUID).gt(DSL.row(fromCreated, fromId)));
		}

		// Explicit projection rather than select(REMIX_MEMBER, ASSET): uuid, created, creator_uuid,
		// edited and editor_uuid exist on both tables, so a whole-table select would give the mapper
		// two candidates per name. The asset side is aliased for the same reason.
		List<RemixMember> list = ctx()
			.select(REMIX_MEMBER.UUID, REMIX_MEMBER.REMIX_UUID, REMIX_MEMBER.ASSET_UUID,
				REMIX_MEMBER.ROLE, REMIX_MEMBER.ORDINAL, REMIX_MEMBER.CREATED, REMIX_MEMBER.CREATOR_UUID,
				ASSET.FILENAME.as("asset_filename"),
				ASSET.MIME_TYPE.as("asset_mime_type"),
				ASSET.SHA512SUM.as("asset_sha512sum"),
				ASSET.SIZE.as("asset_size"))
			.from(REMIX_MEMBER)
			.join(ASSET).on(ASSET.UUID.eq(REMIX_MEMBER.ASSET_UUID))
			.where(condition)
			.orderBy(REMIX_MEMBER.CREATED.asc(), REMIX_MEMBER.UUID.asc())
			.limit(pageSize)
			.fetch(RemixDaoImpl::mapMember);

		return new Page<>(pageSize, totalCount, list);
	}

	/**
	 * Map one joined row. Written out rather than left to {@code fetchInto}, because the role column is
	 * a varchar on the database side and a {@link RemixRole} on this one, and because the asset fields
	 * are aliased.
	 */
	private static RemixMember mapMember(Record record) {
		return new RemixMemberImpl()
			.setUuid(record.get(REMIX_MEMBER.UUID))
			.setRemixUuid(record.get(REMIX_MEMBER.REMIX_UUID))
			.setAssetUuid(record.get(REMIX_MEMBER.ASSET_UUID))
			.setRole(RemixRole.parse(record.get(REMIX_MEMBER.ROLE)))
			.setOrdinal(record.get(REMIX_MEMBER.ORDINAL))
			.setCreated(record.get(REMIX_MEMBER.CREATED) == null ? null : record.get(REMIX_MEMBER.CREATED).toInstant(java.time.ZoneOffset.UTC))
			.setCreatorUuid(record.get(REMIX_MEMBER.CREATOR_UUID))
			.setFilename(record.get("asset_filename", String.class))
			.setMimeType(record.get("asset_mime_type", String.class))
			.setSha512sum(record.get("asset_sha512sum", String.class))
			.setSize(record.get("asset_size", Long.class));
	}

	@Override
	public void setSource(UUID remixUuid, UUID assetUuid) {
		Objects.requireNonNull(remixUuid, "The remix uuid must be provided");

		ctx().transaction(cfg -> {
			DSLContext tx = cfg.dsl();

			// Demote first. remix_member_single_source is a unique index, so promoting before demoting
			// would collide with the incumbent inside the same transaction.
			tx.update(REMIX_MEMBER)
				.set(REMIX_MEMBER.ROLE, RemixRole.DERIVED.name())
				.where(REMIX_MEMBER.REMIX_UUID.eq(remixUuid)
					.and(REMIX_MEMBER.ROLE.eq(RemixRole.SOURCE.name())))
				.execute();

			if (assetUuid == null) {
				tx.update(REMIX).setNull(REMIX.SOURCE_ASSET_UUID).where(REMIX.UUID.eq(remixUuid)).execute();
				return;
			}

			int promoted = tx.update(REMIX_MEMBER)
				.set(REMIX_MEMBER.ROLE, RemixRole.SOURCE.name())
				.where(REMIX_MEMBER.REMIX_UUID.eq(remixUuid)
					.and(REMIX_MEMBER.ASSET_UUID.eq(assetUuid)))
				.execute();
			if (promoted == 0) {
				// Rolls the demotion back with it. Setting a source that is not a member would
				// otherwise leave the remix with no source at all, which is a worse outcome than
				// refusing the call.
				throw new IllegalArgumentException(
					"Asset " + assetUuid + " is not a member of remix " + remixUuid + " and cannot be its source");
			}

			tx.update(REMIX)
				.set(REMIX.SOURCE_ASSET_UUID, assetUuid)
				.where(REMIX.UUID.eq(remixUuid))
				.execute();
		});
	}

}
