package io.metaloom.loom.core.endpoint.test;

import static io.metaloom.loom.rest.model.assertj.Assertions.assertThat;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractCRUDEndpointTest;
import io.metaloom.loom.rest.model.cluster.ClusterCreateRequest;
import io.metaloom.loom.rest.model.cluster.ClusterListResponse;
import io.metaloom.loom.rest.model.cluster.ClusterResponse;
import io.metaloom.loom.rest.model.cluster.ClusterUpdateRequest;

public class ClusterEndpointTest extends AbstractCRUDEndpointTest {

	@Override
	protected void testRead(LoomHttpClient client) throws LoomClientException {
		ClusterResponse cluster = client.loadCluster(CLUSTER_UUID).sync();
		assertThat(cluster).isValid();
	}

	@Override
	protected void testCreate(LoomHttpClient client) throws LoomClientException {
		ClusterCreateRequest request = new ClusterCreateRequest();
		request.setName("dummy name");
		ClusterResponse cluster = client.createCluster(request).sync();
		assertThat(cluster).isValid();

		ClusterResponse cluster2 = client.loadCluster(cluster.getUuid()).sync();
		assertThat(cluster).matches(cluster2);
	}

	@Override
	protected void testDelete(LoomHttpClient client) throws LoomClientException {
		client.deleteCluster(CLUSTER_UUID).sync();
		expect(404, "Not Found", client.loadCluster(CLUSTER_UUID));
	}

	@Override
	protected void testUpdate(LoomHttpClient client) throws LoomClientException {
		ClusterUpdateRequest update = new ClusterUpdateRequest();
		update.setName("updated-name");
		ClusterResponse response = client.updateCluster(CLUSTER_UUID, update).sync();
		assertThat(response).isValid();
	}

	@Override
	protected void testReadPage(LoomHttpClient client) throws LoomClientException {
		for (int i = 0; i < 100; i++) {
			ClusterCreateRequest request = new ClusterCreateRequest();
			request.setName("dummy name " + i);
			client.createCluster(request).sync();
		}
		ClusterListResponse list = client.listClusters().sync();
		assertThat(list).isValid().hasSize(25).hasPerPage(25);
	}

}
