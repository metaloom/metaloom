package io.metaloom.loom.db.jooq.dao.nodes;

import static io.metaloom.loom.db.jooq.tables.JooqNodeDescriptor.NODE_DESCRIPTOR;
import static io.metaloom.loom.db.jooq.tables.JooqNodeDescriptorInstance.NODE_DESCRIPTOR_INSTANCE;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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
import io.metaloom.loom.db.model.nodes.NodeDescriptorRecord;
import io.metaloom.loom.db.model.nodes.NodeDescriptorRecordDao;

@Singleton
public class NodeDescriptorRecordDaoImpl extends AbstractJooqDao<NodeDescriptorRecord> implements NodeDescriptorRecordDao {

	@Inject
	public NodeDescriptorRecordDaoImpl(DSLContext ctx) {
		super(ctx);
	}

	@Override
	public String getTypeName() {
		return "Node Descriptors";
	}

	@Override
	protected Table<? extends TableRecord<?>> getTable() {
		return NODE_DESCRIPTOR;
	}

	@Override
	protected Class<? extends NodeDescriptorRecord> getPojoClass() {
		return NodeDescriptorRecordImpl.class;
	}

	@Override
	public NodeDescriptorRecord createNodeDescriptor(String nodeId) {
		NodeDescriptorRecord record = new NodeDescriptorRecordImpl();
		record.setNodeId(nodeId);
		record.setSource("ANNOUNCED");
		record.setStatus("ACTIVE");
		// A worker writes these rows, so there is no user to derive the NOT NULL timestamps from.
		Instant now = Instant.now();
		record.setFirstSeen(now);
		record.setLastAnnounced(now);
		record.setCreated(now);
		record.setEdited(now);
		return record;
	}

	@Override
	public NodeDescriptorRecord loadByNodeId(String nodeId) {
		return ctx()
			.selectFrom(NODE_DESCRIPTOR)
			.where(NODE_DESCRIPTOR.NODE_ID.eq(nodeId))
			.fetchOneInto(NodeDescriptorRecordImpl.class);
	}

	@Override
	public Stream<? extends NodeDescriptorRecord> findAll() {
		return loadAll().stream();
	}

	@Override
	public List<? extends NodeDescriptorRecord> loadAll() {
		return ctx()
			.selectFrom(NODE_DESCRIPTOR)
			.fetchInto(NodeDescriptorRecordImpl.class);
	}

	@Override
	public NodeDescriptorRecord upsertByNodeId(NodeDescriptorRecord record) {
		NodeDescriptorRecord existing = loadByNodeId(record.getNodeId());
		if (existing == null) {
			store(record);
			return record;
		}
		// Same node type => same row. first_seen is when this contract was first heard of and must
		// survive a re-announcement, which is the only thing that distinguishes a node that has been
		// around for months from one that appeared this morning.
		record.setUuid(existing.getUuid());
		record.setCreated(existing.getCreated());
		record.setFirstSeen(existing.getFirstSeen());
		if (record.getCreatorUuid() == null) {
			record.setCreatorUuid(existing.getCreatorUuid());
		}
		record.setEdited(Instant.now());
		update(record);
		return record;
	}

	@Override
	public boolean deleteByNodeId(String nodeId) {
		// The claims go with it: they are meaningless once no contract is served for this node type.
		ctx().deleteFrom(NODE_DESCRIPTOR_INSTANCE)
			.where(NODE_DESCRIPTOR_INSTANCE.NODE_ID.eq(nodeId))
			.execute();
		return ctx().deleteFrom(NODE_DESCRIPTOR)
			.where(NODE_DESCRIPTOR.NODE_ID.eq(nodeId))
			.execute() > 0;
	}

	@Override
	public void replaceClaims(UUID instanceUuid, Map<String, String[]> claims) {
		// Wipe and re-insert rather than diff, mirroring the wire format: an announcement is the
		// worker's complete set, so a node missing from it has genuinely gone away.
		ctx().deleteFrom(NODE_DESCRIPTOR_INSTANCE)
			.where(NODE_DESCRIPTOR_INSTANCE.INSTANCE_UUID.eq(instanceUuid))
			.execute();

		if (claims == null || claims.isEmpty()) {
			return;
		}
		LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
		for (Map.Entry<String, String[]> entry : claims.entrySet()) {
			String[] claim = entry.getValue();
			ctx().insertInto(NODE_DESCRIPTOR_INSTANCE,
				NODE_DESCRIPTOR_INSTANCE.NODE_ID,
				NODE_DESCRIPTOR_INSTANCE.INSTANCE_UUID,
				NODE_DESCRIPTOR_INSTANCE.VERSION,
				NODE_DESCRIPTOR_INSTANCE.BODY_HASH,
				NODE_DESCRIPTOR_INSTANCE.LAST_ANNOUNCED)
				.values(entry.getKey(), instanceUuid, claim.length > 0 ? claim[0] : null,
					claim.length > 1 ? claim[1] : "", now)
				.onConflictDoNothing()
				.execute();
		}
	}

	@Override
	public Set<UUID> instancesClaiming(String nodeId) {
		return ctx()
			.select(NODE_DESCRIPTOR_INSTANCE.INSTANCE_UUID)
			.from(NODE_DESCRIPTOR_INSTANCE)
			.where(NODE_DESCRIPTOR_INSTANCE.NODE_ID.eq(nodeId))
			.fetchSet(NODE_DESCRIPTOR_INSTANCE.INSTANCE_UUID);
	}

	@Override
	protected SelectConditionStep<?> applyFilter(SelectConditionStep<?> query, Filter filter) {
		FilterKey key = filter.filterKey();
		if (key == LoomFilterKey.NAME) {
			return query.and(NODE_DESCRIPTOR.NODE_ID.eq(filter.valueStr()));
		}
		return super.applyFilter(query, filter);
	}
}
