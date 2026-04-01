package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.CREATE_CLUSTER;
import static io.metaloom.loom.db.model.perm.Permission.DELETE_CLUSTER;
import static io.metaloom.loom.db.model.perm.Permission.READ_CLUSTER;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_CLUSTER;

import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.cluster.Cluster;
import io.metaloom.loom.db.model.cluster.ClusterDao;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.cluster.ClusterCreateRequest;
import io.metaloom.loom.rest.model.cluster.ClusterUpdateRequest;
import io.metaloom.loom.rest.service.AbstractCRUDEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;

@Singleton
public class ClusterEndpointService extends AbstractCRUDEndpointService<ClusterDao, Cluster> {

	@Inject
	public ClusterEndpointService(ClusterDao clusterDao, DaoCollection daos, LoomModelBuilder modelBuilder, LoomModelValidator validator) {
		super(clusterDao, daos, modelBuilder, validator);
	}

	@Override
	public void delete(LoomRoutingContext lrc, UUID id) {
		delete(lrc, DELETE_CLUSTER, id);
	}

	@Override
	public void list(LoomRoutingContext lrc) {
		list(lrc, READ_CLUSTER, modelBuilder::toClusterList);
	}

	@Override
	public void load(LoomRoutingContext lrc, UUID id) {
		load(lrc, READ_CLUSTER, () -> {
			return dao().load(id);
		}, modelBuilder::toResponse);
	}

	@Override
	public void create(LoomRoutingContext lrc) {
		create(lrc, CREATE_CLUSTER, () -> {
			ClusterCreateRequest request = lrc.requestBody(ClusterCreateRequest.class);
			validator.validate(request);

			String name = null;
			UUID userUuid = lrc.userUuid();
			String type = null;
			return dao().createCluster(userUuid, name, type);
		}, modelBuilder::toResponse);
	}

	@Override
	public void update(LoomRoutingContext lrc, UUID id) {
		update(lrc, UPDATE_CLUSTER, () -> {
			ClusterUpdateRequest request = lrc.requestBody(ClusterUpdateRequest.class);
			validator.validate(request);

			UUID userUuid = lrc.userUuid();
			Cluster cluster = dao().load(id);
			update(request::getName, cluster::setName);
			update(request::getType, cluster::setType);
			setEditor(cluster, userUuid);
			return cluster;
		}, modelBuilder::toResponse);
	}

}
