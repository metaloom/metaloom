package io.metaloom.loom.core.endpoint.test;

import static io.metaloom.loom.rest.model.assertj.Assertions.assertThat;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractCRUDEndpointTest;
import io.metaloom.loom.rest.model.cluster.ClusterCreateRequest;
import io.metaloom.loom.rest.model.cluster.ClusterListResponse;
import io.metaloom.loom.rest.model.cluster.ClusterResponse;
import io.metaloom.loom.rest.model.cluster.ClusterUpdateRequest;

public class ClusterEndpointTest extends AbstractCRUDEndpointTest {

	private ClusterResponse createTestCluster(LoomHttpClient client) throws LoomClientException {
		ClusterCreateRequest request = new ClusterCreateRequest();
		request.setName("test-cluster");
		return client.createCluster(request).sync().body();
	}

	@Override
	protected void testRead(LoomHttpClient client) throws LoomClientException {
		ClusterResponse created = createTestCluster(client);
		ClusterResponse cluster = client.loadCluster(created.getUuid()).sync().body();
		assertThat(cluster).isValid();
	}

	@Override
	protected void testCreate(LoomHttpClient client) throws LoomClientException {
		ClusterCreateRequest request = new ClusterCreateRequest();
		request.setName("dummy name");
		ClusterResponse cluster = client.createCluster(request).sync().body();
		assertThat(cluster).isValid();

		ClusterResponse cluster2 = client.loadCluster(cluster.getUuid()).sync().body();
		assertThat(cluster).matches(cluster2);
	}

	@Override
	protected void testDelete(LoomHttpClient client) throws LoomClientException {
		ClusterResponse created = createTestCluster(client);
		client.deleteCluster(created.getUuid()).sync().body();
		expect(404, "Not Found", client.loadCluster(created.getUuid()));
	}

	@Override
	protected void testUpdate(LoomHttpClient client) throws LoomClientException {
		ClusterResponse created = createTestCluster(client);
		ClusterUpdateRequest update = new ClusterUpdateRequest();
		update.setName("updated-name");
		ClusterResponse response = client.updateCluster(created.getUuid(), update).sync().body();
		assertThat(response).isValid();
	}

	@Override
	protected void testReadPage(LoomHttpClient client) throws LoomClientException {
		for (int i = 0; i < 100; i++) {
			ClusterCreateRequest request = new ClusterCreateRequest();
			request.setName("dummy name " + i);
			client.createCluster(request).sync().body();
		}
		ClusterListResponse list = client.listClusters().sync().body();
		assertThat(list).isValid().hasPerPage(25);
	}

	@Override
	protected LoomClientRequest<?> createRequest(LoomHttpClient client) {
		ClusterCreateRequest request = new ClusterCreateRequest();
		request.setName("perm-check");
		return client.createCluster(request);
	}

	@Override
	protected LoomClientRequest<?> loadRequest(LoomHttpClient client) {
		return client.loadCluster(CLUSTER_UUID);
	}

	@Override
	protected LoomClientRequest<?> listRequest(LoomHttpClient client) {
		return client.listClusters();
	}

	@Override
	protected LoomClientRequest<?> deleteRequest(LoomHttpClient client) {
		return client.deleteCluster(CLUSTER_UUID);
	}

}
