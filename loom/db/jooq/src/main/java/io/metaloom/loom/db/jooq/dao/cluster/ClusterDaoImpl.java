package io.metaloom.loom.db.jooq.dao.cluster;

import static io.metaloom.loom.db.jooq.tables.JooqEmbeddingCluster.EMBEDDING_CLUSTER;

import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.DSLContext;
import org.jooq.Table;
import org.jooq.TableRecord;

import io.metaloom.loom.db.jooq.AbstractJooqDao;
import io.metaloom.loom.db.jooq.tables.JooqCluster;
import io.metaloom.loom.db.model.cluster.Cluster;
import io.metaloom.loom.db.model.cluster.ClusterDao;
import io.metaloom.loom.db.model.embedding.Embedding;

@Singleton
public class ClusterDaoImpl extends AbstractJooqDao<Cluster> implements ClusterDao {

	@Inject
	public ClusterDaoImpl(DSLContext ctx) {
		super(ctx);
	}

	@Override
	public String getTypeName() {
		return "Clusters";
	}

	@Override
	protected Table<? extends TableRecord<?>> getTable() {
		return JooqCluster.CLUSTER;
	}

	@Override
	protected Class<? extends Cluster> getPojoClass() {
		return ClusterImpl.class;
	}

	@Override
	public Cluster createCluster(UUID userUuid, String name, String type) {
		Cluster cluster = new ClusterImpl();
		cluster.setName(name);
		cluster.setType(type);
		setCreatorEditor(cluster, userUuid);
		return cluster;
	}

	@Override
	public void link(Cluster cluster, Embedding embedding) {
		ctx().insertInto(EMBEDDING_CLUSTER,
			EMBEDDING_CLUSTER.CLUSTER_UUID, EMBEDDING_CLUSTER.EMBEDDING_UUID)
			.values(cluster.getUuid(), embedding.getUuid())
			.execute();
	}

	@Override
	public void unlink(Cluster cluster, Embedding embedding) {
		ctx().deleteFrom(EMBEDDING_CLUSTER)
			.where(EMBEDDING_CLUSTER.CLUSTER_UUID.eq(cluster.getUuid())
				.and(EMBEDDING_CLUSTER.EMBEDDING_UUID.eq(embedding.getUuid())))
			.execute();
	}

}
