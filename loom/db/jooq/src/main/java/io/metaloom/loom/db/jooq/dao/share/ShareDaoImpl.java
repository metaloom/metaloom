package io.metaloom.loom.db.jooq.dao.share;

import static io.metaloom.loom.db.jooq.tables.JooqShare.SHARE;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.DSLContext;
import org.jooq.SelectConditionStep;
import org.jooq.Table;
import org.jooq.TableRecord;
import org.jooq.impl.DSL;

import io.metaloom.loom.db.jooq.AbstractJooqDao;
import io.metaloom.loom.db.model.share.Share;
import io.metaloom.loom.db.model.share.ShareDao;
import io.metaloom.loom.db.model.share.ShareTargetType;
import io.metaloom.loom.db.page.Page;

@Singleton
public class ShareDaoImpl extends AbstractJooqDao<Share> implements ShareDao {

	@Inject
	public ShareDaoImpl(DSLContext ctx) {
		super(ctx);
	}

	@Override
	public String getTypeName() {
		return "Shares";
	}

	@Override
	protected Table<? extends TableRecord<?>> getTable() {
		return SHARE;
	}

	@Override
	protected Class<? extends Share> getPojoClass() {
		return ShareImpl.class;
	}

	@Override
	public Share createAssetShare(UUID userUuid, UUID assetUuid, String slug) {
		Share share = newShare(userUuid, slug);
		share.setTargetType(ShareTargetType.ASSET);
		share.setAssetUuid(assetUuid);
		return share;
	}

	@Override
	public Share createCollectionShare(UUID userUuid, UUID collectionUuid, String slug) {
		Share share = newShare(userUuid, slug);
		share.setTargetType(ShareTargetType.COLLECTION);
		share.setCollectionUuid(collectionUuid);
		return share;
	}

	/**
	 * The defaults a new share carries before the caller overrides any of them.
	 *
	 * <p>
	 * Set here rather than relying on the column defaults because the row is round-tripped through the response before it is ever read back: a null
	 * {@code allowDownload} on the way out would render as an unchecked box in the dialog that created it.
	 * </p>
	 */
	private Share newShare(UUID userUuid, String slug) {
		Share share = new ShareImpl();
		share.setSlug(slug);
		share.setAllowDownload(true);
		share.setShowMetadata(true);
		share.setAllowComments(false);
		share.setAllowReactions(false);
		share.setAllowAnnotations(false);
		share.setViewCount(0);
		setCreatorEditor(share, userUuid);
		return share;
	}

	@Override
	public Share loadBySlug(String slug) {
		if (slug == null) {
			return null;
		}
		return ctx()
			.select(SHARE.fields())
			.from(SHARE)
			.where(SHARE.SLUG.eq(slug))
			.fetchOneInto(getPojoClass());
	}

	@Override
	public boolean slugExists(String slug) {
		if (slug == null) {
			return false;
		}
		return ctx().fetchExists(ctx().selectOne().from(SHARE).where(SHARE.SLUG.eq(slug)));
	}

	@Override
	public Page<Share> loadPageByAsset(UUID assetUuid, UUID fromId, int pageSize) {
		SelectConditionStep<?> query = ctx()
			.select(getTable())
			.from(getTable())
			.where(SHARE.ASSET_UUID.eq(assetUuid));
		return loadPage(query, fromId, pageSize, null, null, null);
	}

	@Override
	public Page<Share> loadPageByCollection(UUID collectionUuid, UUID fromId, int pageSize) {
		SelectConditionStep<?> query = ctx()
			.select(getTable())
			.from(getTable())
			.where(SHARE.COLLECTION_UUID.eq(collectionUuid));
		return loadPage(query, fromId, pageSize, null, null, null);
	}

	@Override
	public void recordVisit(UUID shareUuid, String visitorName) {
		LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
		// One statement, not a read-modify-write. Two visitors opening a link in the same second must not lose a
		// count between them, and COALESCE is what makes "the first visit names the share" hold under concurrency
		// as well as in sequence - the second UPDATE finds a non-null name and leaves it alone.
		ctx().update(SHARE)
			.set(SHARE.VISITOR_NAME, DSL.coalesce(SHARE.VISITOR_NAME, DSL.val(visitorName)))
			.set(SHARE.FIRST_VISITED_AT, DSL.coalesce(SHARE.FIRST_VISITED_AT, DSL.val(now)))
			.set(SHARE.LAST_VIEWED_AT, now)
			.set(SHARE.VIEW_COUNT, SHARE.VIEW_COUNT.plus(1))
			.where(SHARE.UUID.eq(shareUuid))
			.execute();
	}
}
