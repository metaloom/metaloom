package io.metaloom.loom.db.model.cluster;

import java.util.UUID;

import io.metaloom.loom.db.CRUDDao;
import io.metaloom.loom.db.model.embedding.Embedding;
import io.metaloom.loom.db.model.user.User;

public interface ClusterDao extends CRUDDao<Cluster> {

	default Cluster createCluster(User user, String name, String type) {
		return createCluster(user.getUuid(), name, type);
	}

	Cluster createCluster(UUID userUuid, String name, String type);

	void link(Cluster cluster, Embedding embedding);

	void unlink(Cluster cluster, Embedding embedding);

}
