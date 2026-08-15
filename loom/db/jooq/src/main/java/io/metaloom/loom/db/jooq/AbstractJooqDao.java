package io.metaloom.loom.db.jooq;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.OrderField;
import org.jooq.SelectConditionStep;
import org.jooq.SelectSeekStepN;
import org.jooq.Table;
import org.jooq.TableField;
import org.jooq.TableRecord;
import org.jooq.UniqueKey;
import org.jooq.UpdatableRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.filter.Filter;
import io.metaloom.filter.FilterKey;
import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.api.filter.LoomFilterKey;
import io.metaloom.loom.api.sort.SortDirection;
import io.metaloom.loom.api.sort.SortKey;
import io.metaloom.loom.db.CRUDDao;
import io.metaloom.loom.db.CUDElement;
import io.metaloom.loom.db.Element;
import io.metaloom.loom.db.page.Page;

/**
 * 
 * @param <T>
 *            Pojo type
 */
public abstract class AbstractJooqDao<T extends Element<T>> implements JooqDao, CRUDDao<T> {

	private static final Logger log = LoggerFactory.getLogger(AbstractJooqDao.class);

	private final DSLContext ctx;

	public AbstractJooqDao(DSLContext ctx) {
		this.ctx = ctx;
	}

	protected DSLContext ctx() {
		return ctx;
	}

	abstract protected Table<? extends TableRecord<?>> getTable();

	public Field<UUID> getIdField() {
		return (Field<UUID>) getTable().field("uuid", UUID.class);
	}

	/**
	 * Resolve a {@link SortKey} to the column that orders by it, or {@code null} when this type has no such column.
	 *
	 * <p>
	 * Returns the field at its declared type. The previous implementation coerced every sort column to {@code Field<UUID>}, which is invisible while
	 * only {@code ORDER BY} is generated but produces {@code where "name" > cast(? as uuid)} the moment a cursor is added — so {@code ?sort=name}
	 * worked and {@code ?sort=name&from=...} was a 500.
	 * </p>
	 *
	 * <p>
	 * Override to map a key onto a differently named column. An asset's display name is {@code filename}, so {@code ?sort=name} over assets would
	 * otherwise be rejected as unknown.
	 * </p>
	 */
	protected Field<?> getSortField(SortKey sortBy) {
		return getTable().field(sortBy.getKey());
	}

	public Field<UUID> getUuidField() {
		// return JooqAsset.ASSET.UUID;
		return getTable().field("uuid", UUID.class);
	}

	abstract protected Class<? extends T> getPojoClass();

	public <PK> Condition pkSelect(PK pk) {
		TableField<? extends TableRecord<?>, PK> field = (TableField<? extends TableRecord<?>, PK>) getTable().getPrimaryKey().getFieldsArray()[0];
		return field.eq(pk);
	}

	@Override
	public void clear() {
		// ctx().truncate(getTable()).cascade().execute();
		ctx().deleteFrom(getTable()).execute();
	}

	@Override
	public long count() {
		return ctx()
			.selectCount()
			.from(getTable())
			.fetchOne(0, Long.class);
	}

	@Override
	public void store(T element) {
		TableRecord<?> reco = ctx().newRecord(getTable(), element);
		if (element.getUuid() == null) {
			reco.reset("uuid");
		}
		UUID uuid = ctx().insertInto(getTable())
			.set(reco)
			.returning(getTable().field("uuid", UUID.class))
			.fetchOne("uuid", UUID.class);
		if (uuid == null) {
			throw new RuntimeException("Key null!!");
		}
		element.setUuid(uuid);

	}

	/**
	 * Insert the element, or update the conflicting row when the given natural-key columns already exist. Returns the row's uuid.
	 *
	 * <p>
	 * This is the idempotent counterpart to {@link #store(Element)} for tables that carry a {@code UNIQUE} natural key: a node that runs again rewrites
	 * its own row instead of hitting the constraint. The natural-key columns and the creation-audit columns ({@code uuid}, {@code created},
	 * {@code creator_uuid}) are excluded from the UPDATE set so first-write provenance survives.
	 * </p>
	 *
	 * @param element   the element to persist; its uuid is populated on return
	 * @param keyFields the natural-key columns the unique constraint is defined on
	 * @return the uuid of the inserted or updated row
	 */
	protected UUID upsert(T element, Field<?>... keyFields) {
		return upsert(element, java.util.Set.of(), keyFields);
	}

	/**
	 * As {@link #upsert(Element, Field...)}, but additionally preserving the named columns on the conflicting row.
	 *
	 * <p>
	 * Some columns belong to a different writer than the one running the upsert. A producer re-running over an asset owns the payload it computed, but
	 * not a human decision recorded against it since - re-running face detection must not reset a cluster a reviewer already confirmed. Naming those
	 * columns here keeps the row's existing values instead of overwriting them with whatever the producer happened to set.
	 * </p>
	 *
	 * @param element        the element to persist; its uuid is populated on return
	 * @param preserved      column names to keep from the existing row on conflict
	 * @param keyFields      the natural-key columns the unique constraint is defined on
	 * @return the uuid of the inserted or updated row
	 */
	protected UUID upsert(T element, java.util.Set<String> preserved, Field<?>... keyFields) {
		return upsert(element, preserved, java.util.Map.of(), keyFields);
	}

	/**
	 * As {@link #upsert(Element, java.util.Set, Field...)}, but additionally forcing the given columns to the given values in the UPDATE set.
	 *
	 * <p>
	 * The values may be SQL expressions rather than constants, which is what makes a <em>conditional</em> preserve expressible: a column can be set to
	 * a {@code CASE} that keeps the stored value under one condition and resets it under another. {@code DetectionDaoImpl} uses this to hold a review
	 * verdict while the producer version is unchanged and to retire it when the version moves on - a plain {@code preserved} entry can only do the
	 * former.
	 * </p>
	 *
	 * <p>
	 * Overrides are applied last, so they win over both {@code preserved} and the element's own field values.
	 * </p>
	 *
	 * @param element   the element to persist; its uuid is populated on return
	 * @param preserved column names to keep from the existing row on conflict
	 * @param overrides columns to set to an explicit value or expression on conflict
	 * @param keyFields the natural-key columns the unique constraint is defined on
	 * @return the uuid of the inserted or updated row
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	protected UUID upsert(T element, java.util.Set<String> preserved, java.util.Map<Field<?>, Object> overrides, Field<?>... keyFields) {
		TableRecord<?> reco = ctx().newRecord(getTable(), element);
		if (element.getUuid() == null) {
			reco.reset("uuid");
		}
		java.util.Set<String> excluded = new java.util.HashSet<>(List.of("uuid", "created", "creator_uuid"));
		excluded.addAll(preserved);
		for (Field<?> key : keyFields) {
			excluded.add(key.getName());
		}
		java.util.Map<Field<?>, Object> updates = new java.util.LinkedHashMap<>();
		for (Field<?> field : reco.fields()) {
			if (reco.changed(field) && !excluded.contains(field.getName())) {
				updates.put(field, reco.get(field));
			}
		}
		// Last, so an override wins over both the preserved set and whatever the element carried.
		updates.putAll(overrides);
		UUID uuid = ctx().insertInto(getTable())
			.set(reco)
			.onConflict(keyFields)
			.doUpdate()
			.set((java.util.Map) updates)
			.returning(getTable().field("uuid", UUID.class))
			.fetchOne("uuid", UUID.class);
		if (uuid == null) {
			throw new RuntimeException("Key null!!");
		}
		element.setUuid(uuid);
		return uuid;
	}

	@Override
	public void storeBatch(List<T> elements) {
		if (elements == null || elements.isEmpty()) {
			return;
		}
		// Use jOOQ batch insert for performance.
		// Build one INSERT with RETURNING to get back all generated UUIDs.
		List<TableRecord<?>> records = new ArrayList<>();
		for (T element : elements) {
			TableRecord<?> reco = ctx().newRecord(getTable(), element);
			if (element.getUuid() == null) {
				reco.reset("uuid");
			}
			records.add(reco);
		}

		// Execute batch using jOOQ's batchInsert (groups records by table)
		ctx().batchInsert(records).execute();

		// After batch insert, the records will have their generated keys populated by jOOQ.
		// However, batchInsert may not always populate generated keys.
		// Fall back to loading by a known unique field if UUID is not set.
		for (int i = 0; i < records.size(); i++) {
			Object uuid = records.get(i).get("uuid");
			if (uuid instanceof UUID) {
				elements.get(i).setUuid((UUID) uuid);
			}
		}
	}

	@Override
	public void delete(UUID id) {
		Field<?>[] pk = pk();

		if (pk != null) {
			ctx().delete(getTable())
				.where(pkSelect(id))
				.execute();
		}
	}

	@Override
	public Stream<? extends T> findAll() {
		return ctx().selectFrom(getTable()).fetchStreamInto(getPojoClass());
	}

	@Override
	public T load(UUID uuid) {
		return ctx()
			.select(getTable())
			.from(getTable())
			.where(getIdField().eq(uuid))
			.fetchOneInto(getPojoClass());
	}

	@Override
	public T update(T element) {
		UpdatableRecord<?> reco = (UpdatableRecord<?>) ctx().newRecord(getTable(), element);
		ctx().executeUpdate(reco);
		return element;
	}

	@Override
	public Page<T> loadPage(UUID fromId, int pageSize, List<Filter> filters, SortKey sortBy, SortDirection sortDirection) {
		SelectConditionStep<?> query = ctx()
			.select(getTable())
			.from(getTable())
			.where();

		return loadPage(query, fromId, pageSize, filters, sortBy, sortDirection);
	}

	protected Page<T> loadPage(SelectConditionStep<?> query, UUID fromId, int pageSize, List<Filter> filters, SortKey sortBy,
		SortDirection sortDirection) {

		// Filtering
		if (filters != null) {
			for (Filter filter : filters) {
				applyFilter(query, filter);
			}
		}

		// Total count across all pages. Must be taken after the filters are applied but before
		// ordering/seeking/limiting, so it counts every match rather than the page. fetchCount wraps
		// the select rather than extending its projection, which keeps fetchStreamInto working for
		// every DAO regardless of how it built its select.
		long totalCount = ctx().fetchCount(query);

		// Sorting.
		//
		// The uuid is always the last ORDER BY term, even when the caller named a column. A sort
		// column is rarely unique - two collections may share a name, and a bulk import gives a
		// whole batch the same `created` - and keyset paging over a non-unique order silently
		// drops or repeats rows at the page boundary. Appending the primary key makes the order
		// total, which is what makes the seek below exact.
		Field<?> sortField = null;
		if (sortBy != null) {
			sortField = getSortField(sortBy);
			if (sortField == null) {
				// The caller named a column this type does not have - a bad request, not an
				// internal error. Mirrors applyFilter below, which rejects unknown filter keys
				// the same way.
				throw new LoomRestException(400, LoomRestErrorCode.BAD_QUERY_PARAMS,
					"Unknown sort field " + sortBy.getKey() + " for " + getTypeName());
			}
		}

		boolean descending = sortDirection == SortDirection.DESCENDING;
		List<OrderField<?>> orderFields = new ArrayList<>();
		if (sortField != null) {
			orderFields.add(descending ? sortField.desc() : sortField.asc());
		}
		orderFields.add(descending ? getIdField().desc() : getIdField().asc());
		SelectSeekStepN<?> ordered = query.orderBy(orderFields);

		// Seeking
		if (fromId != null) {
			ordered.seek(seekValues(sortField, fromId));
		}
		List<T> list = ordered
			.limit(pageSize)
			.fetchStreamInto(getPojoClass())
			.collect(Collectors.toList());
		return new Page<>(pageSize, totalCount, list);

	}

	/**
	 * Build the seek tuple for the cursor row, matching the ORDER BY terms one for one.
	 *
	 * <p>
	 * The wire contract is that {@code ?from=} is the uuid of the last element of the previous page — {@code PagingInfo.lastUuid} — and it stays that
	 * way here. When the order is a column plus the uuid the seek needs that column's value too, so it is read back from the cursor row: one indexed
	 * primary-key lookup per page, in exchange for leaving the cursor opaque-free and every existing client untouched.
	 * </p>
	 *
	 * <p>
	 * The lookup deliberately ignores the page's filters. The cursor identifies a position in the ordering, and a row can perfectly well have been
	 * filtered out of the result while still marking where to resume from.
	 * </p>
	 */
	private Object[] seekValues(Field<?> sortField, UUID fromId) {
		if (sortField == null) {
			return new Object[] { fromId };
		}
		org.jooq.Record cursor = ctx()
			.select(sortField)
			.from(getTable())
			.where(getIdField().eq(fromId))
			.fetchOne();
		if (cursor == null) {
			// Resuming needs the cursor row's sort value, and a deleted row no longer has one.
			// Saying so beats the alternatives: seeking from nothing would silently restart at
			// page one, which turns a client's paging loop into an infinite one.
			throw new LoomRestException(400, LoomRestErrorCode.BAD_QUERY_PARAMS,
				"Cannot resume a sorted page from " + fromId + ": no " + getTypeName() + " with that uuid. Restart the listing.");
		}
		return new Object[] { cursor.get(0), fromId };
	}

	protected SelectConditionStep<?> applyFilter(SelectConditionStep<?> query, Filter filter) {
		FilterKey key = filter.filterKey();
		if (key == LoomFilterKey.UUID) {
			return query.and(getTable().field("uuid", UUID.class).eq(parseUuid(filter.valueStr(), key)));
		}
		// Provenance filters are handled here rather than per DAO: `creator_uuid`/`editor_uuid` are
		// the CUDElement audit columns, so every type carrying them filters identically. Types
		// without them (join tables, and anything not user-authored) fall through to the 400 below.
		if (key == LoomFilterKey.CREATOR) {
			return applyUserFilter(query, "creator_uuid", filter);
		}
		if (key == LoomFilterKey.EDITOR) {
			return applyUserFilter(query, "editor_uuid", filter);
		}
		throw new LoomRestException(400, LoomRestErrorCode.BAD_FILTER_KEY, "Unknown filter field " + key.id() + " for " + getTypeName());
	}

	private SelectConditionStep<?> applyUserFilter(SelectConditionStep<?> query, String column, Filter filter) {
		Field<UUID> field = getTable().field(column, UUID.class);
		if (field == null) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_FILTER_KEY,
				"Unknown filter field " + filter.filterKey().id() + " for " + getTypeName());
		}
		return query.and(field.eq(parseUuid(filter.valueStr(), filter.filterKey())));
	}

	/**
	 * Parse a filter value that has to be a uuid.
	 *
	 * <p>
	 * {@code UUID.fromString} throws {@link IllegalArgumentException}, which reaches the client as a 500. A malformed value in a query string is the
	 * caller's mistake, so it gets a 400 that names the offending key.
	 * </p>
	 */
	protected UUID parseUuid(String value, FilterKey key) {
		try {
			return UUID.fromString(value);
		} catch (IllegalArgumentException e) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_FILTER_KEY,
				"Filter " + key.id() + " expects a uuid but got '" + value + "'.");
		}
	}

	/**
	 * Parse a filter value that has to name an enum constant.
	 *
	 * <p>
	 * As {@link #parseUuid}: {@code valueOf} throws {@link IllegalArgumentException}, which would reach the client as a 500. The message lists what
	 * was expected, because guessing the spelling of a status from a rejection is not reasonable.
	 * </p>
	 */
	protected <E extends Enum<E>> E parseEnum(Class<E> type, String value, FilterKey key) {
		try {
			return Enum.valueOf(type, value == null ? "" : value.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_FILTER_KEY,
				"Filter " + key.id() + " expects one of " + List.of(type.getEnumConstants()) + " but got '" + value + "'.");
		}
	}

	public T findByUUID(UUID id) {
		Field<?>[] pk = pk();

		if (pk != null) {
			return ctx().selectFrom(getTable())
				.where(pkSelect(id))
				.fetchOneInto(getPojoClass());
		}
		return null;
	}

	protected void deleteCrossTableEntry(TableField<?, UUID> aField, UUID a, TableField<?, UUID> bField, UUID b) {
		ctx().deleteFrom(aField.getTable())
			.where(aField.eq(a)
				.and(bField.eq(b)))
			.execute();
	}

	private /* non-final */ Field<?>[] pk() {
		UniqueKey<?> key = getTable().getPrimaryKey();
		return key == null ? null : key.getFieldsArray();
	}

	private <PK> TableField<?, PK> pkField() {
		return (TableField<?, PK>) getTable().getPrimaryKey();
	}

	protected void setCreatorEditor(CUDElement<?> element, UUID userUuid) {
		if (log.isDebugEnabled()) {
			log.debug("Setting creator/editor {}", userUuid);
		}
		element.setCreatorUuid(userUuid);
		element.setEditorUuid(userUuid);
		element.setCreated(Instant.now());
		element.setEdited(Instant.now());
	}

}
