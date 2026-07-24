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
import org.jooq.SelectConditionStep;
import org.jooq.SelectSeekStep1;
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

	private Field<UUID> getField(SortKey sortBy) {
		return getTable().field(sortBy.getKey(), UUID.class);
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
	@SuppressWarnings({ "unchecked", "rawtypes" })
	protected UUID upsert(T element, Field<?>... keyFields) {
		TableRecord<?> reco = ctx().newRecord(getTable(), element);
		if (element.getUuid() == null) {
			reco.reset("uuid");
		}
		java.util.Set<String> excluded = new java.util.HashSet<>(List.of("uuid", "created", "creator_uuid"));
		for (Field<?> key : keyFields) {
			excluded.add(key.getName());
		}
		java.util.Map<Field<?>, Object> updates = new java.util.LinkedHashMap<>();
		for (Field<?> field : reco.fields()) {
			if (reco.changed(field) && !excluded.contains(field.getName())) {
				updates.put(field, reco.get(field));
			}
		}
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

		// Sorting
		SelectSeekStep1<?, UUID> query2;
		if (sortBy == null) {
			query2 = query.orderBy(getIdField());
		} else {
			Field<UUID> field = getField(sortBy);
			if (field == null) {
				// The caller named a column this type does not have - a bad request, not an
				// internal error. Mirrors applyFilter below, which rejects unknown filter keys
				// the same way.
				throw new LoomRestException(400, LoomRestErrorCode.BAD_QUERY_PARAMS,
					"Unknown sort field " + sortBy.getKey() + " for " + getTypeName());
			}
			if (sortDirection == SortDirection.DESCENDING) {
				query2 = query.orderBy(field.desc());
			} else {
				query2 = query.orderBy(field.asc());
			}
		}

		// Seeking
		if (fromId != null) {
			query2.seek(fromId);
		}
		List<T> list = query2
			.limit(pageSize)
			.fetchStreamInto(getPojoClass())
			.collect(Collectors.toList());
		return new Page<>(pageSize, list);

	}

	protected SelectConditionStep<?> applyFilter(SelectConditionStep<?> query, Filter filter) {
		FilterKey key = filter.filterKey();
		if (key == LoomFilterKey.UUID) {
			return query.and(getTable().field("uuid", UUID.class).eq(UUID.fromString(filter.valueStr())));
		}
		throw new LoomRestException(400, LoomRestErrorCode.BAD_FILTER_KEY, "Unknown filter field " + key.id() + " for " + getTypeName());
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
