package io.metaloom.loom.db.jooq.dao.cortex;

import static io.metaloom.loom.db.jooq.tables.JooqCortexInstance.CORTEX_INSTANCE;
import static io.metaloom.loom.db.jooq.tables.JooqCortexInstanceNodeKind.CORTEX_INSTANCE_NODE_KIND;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.DSLContext;
import org.jooq.SelectConditionStep;
import org.jooq.Table;
import org.jooq.TableRecord;

import io.metaloom.filter.Filter;
import io.metaloom.filter.FilterKey;
import io.metaloom.loom.api.filter.LoomFilterKey;
import io.metaloom.loom.db.jooq.AbstractJooqDao;
import io.metaloom.loom.db.jooq.tables.JooqCortexInstance;
import io.metaloom.loom.db.model.cortex.CortexInstance;
import io.metaloom.loom.db.model.cortex.CortexInstanceDao;

@Singleton
public class CortexInstanceDaoImpl extends AbstractJooqDao<CortexInstance> implements CortexInstanceDao {

	private static final String WHITELIST = "WHITELIST";
	private static final String BLACKLIST = "BLACKLIST";

	@Inject
	public CortexInstanceDaoImpl(DSLContext ctx) {
		super(ctx);
	}

	@Override
	public String getTypeName() {
		return "Cortex Instances";
	}

	@Override
	protected Table<? extends TableRecord<?>> getTable() {
		return JooqCortexInstance.CORTEX_INSTANCE;
	}

	@Override
	protected Class<? extends CortexInstance> getPojoClass() {
		return CortexInstanceImpl.class;
	}

	@Override
	public CortexInstance createCortexInstance(String nodeId, String name) {
		CortexInstance instance = new CortexInstanceImpl();
		instance.setNodeId(nodeId);
		instance.setName(name);
		// A worker-registered row has no user, so we set the NOT NULL timestamps here
		// rather than via setCreatorEditor (which would also require a user).
		Instant now = Instant.now();
		instance.setFirstRegistered(now);
		instance.setLastSeen(now);
		instance.setCreated(now);
		instance.setEdited(now);
		return instance;
	}

	@Override
	public CortexInstance load(UUID uuid) {
		CortexInstance instance = super.load(uuid);
		loadNodeKinds(instance);
		return instance;
	}

	@Override
	public CortexInstance loadByNodeId(String nodeId) {
		CortexInstance instance = ctx()
			.selectFrom(CORTEX_INSTANCE)
			.where(CORTEX_INSTANCE.NODE_ID.eq(nodeId))
			.fetchOneInto(CortexInstanceImpl.class);
		loadNodeKinds(instance);
		return instance;
	}

	@Override
	public Stream<? extends CortexInstance> findAll() {
		List<CortexInstanceImpl> list = ctx()
			.selectFrom(CORTEX_INSTANCE)
			.fetchInto(CortexInstanceImpl.class);
		list.forEach(this::loadNodeKinds);
		return list.stream();
	}

	@Override
	public void store(CortexInstance element) {
		super.store(element);
		storeNodeKinds(element);
	}

	@Override
	public CortexInstance update(CortexInstance element) {
		super.update(element);
		// Child rows are not diffed: wipe and re-insert to match the element's sets.
		deleteNodeKinds(element.getUuid());
		storeNodeKinds(element);
		return element;
	}

	@Override
	public CortexInstance upsertByNodeId(CortexInstance instance) {
		CortexInstance existing = loadByNodeId(instance.getNodeId());
		if (existing == null) {
			store(instance);
		} else {
			// Same node id => same row. Preserve the immutable-ish origin fields and update.
			instance.setUuid(existing.getUuid());
			instance.setCreated(existing.getCreated());
			instance.setFirstRegistered(existing.getFirstRegistered());
			if (instance.getCreatorUuid() == null) {
				instance.setCreatorUuid(existing.getCreatorUuid());
			}
			instance.setEdited(Instant.now());
			update(instance);
		}
		return instance;
	}

	private void loadNodeKinds(CortexInstance instance) {
		if (instance == null) {
			return;
		}
		instance.setNodeWhitelist(fetchKinds(instance.getUuid(), WHITELIST));
		instance.setNodeBlacklist(fetchKinds(instance.getUuid(), BLACKLIST));
	}

	private Set<String> fetchKinds(UUID instanceUuid, String list) {
		return ctx()
			.select(CORTEX_INSTANCE_NODE_KIND.NODE_KIND)
			.from(CORTEX_INSTANCE_NODE_KIND)
			.where(CORTEX_INSTANCE_NODE_KIND.INSTANCE_UUID.eq(instanceUuid)
				.and(CORTEX_INSTANCE_NODE_KIND.LIST.eq(list)))
			.fetchSet(CORTEX_INSTANCE_NODE_KIND.NODE_KIND);
	}

	private void storeNodeKinds(CortexInstance instance) {
		writeKinds(instance.getUuid(), instance.getNodeWhitelist(), WHITELIST);
		writeKinds(instance.getUuid(), instance.getNodeBlacklist(), BLACKLIST);
	}

	private void writeKinds(UUID instanceUuid, Set<String> kinds, String list) {
		if (kinds == null || kinds.isEmpty()) {
			return;
		}
		for (String kind : kinds) {
			ctx()
				.insertInto(CORTEX_INSTANCE_NODE_KIND,
					CORTEX_INSTANCE_NODE_KIND.INSTANCE_UUID, CORTEX_INSTANCE_NODE_KIND.NODE_KIND, CORTEX_INSTANCE_NODE_KIND.LIST)
				.values(instanceUuid, kind, list)
				.onConflictDoNothing()
				.execute();
		}
	}

	private void deleteNodeKinds(UUID instanceUuid) {
		ctx()
			.deleteFrom(CORTEX_INSTANCE_NODE_KIND)
			.where(CORTEX_INSTANCE_NODE_KIND.INSTANCE_UUID.eq(instanceUuid))
			.execute();
	}

	@Override
	protected SelectConditionStep<?> applyFilter(SelectConditionStep<?> query, Filter filter) {
		FilterKey key = filter.filterKey();
		if (key == LoomFilterKey.NAME) {
			return query.and(CORTEX_INSTANCE.NAME.eq(filter.valueStr()));
		}
		return super.applyFilter(query, filter);
	}

}
