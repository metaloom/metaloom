package io.metaloom.loom.rest.model.cluster;

import java.util.ArrayList;
import java.util.List;

import io.metaloom.loom.rest.model.RestResponseModel;

/**
 * The clusters stored by a bulk write, in request order.
 *
 * <p>
 * Order matters: the caller pairs each returned uuid with the proposal it sent, exactly as the detection bulk response does.
 * </p>
 */
public class ClusterBulkResponse implements RestResponseModel<ClusterBulkResponse> {

	private List<ClusterResponse> clusters = new ArrayList<>();

	private int total;

	private int created;

	private int failed;

	private int pruned;

	public List<ClusterResponse> getClusters() {
		return clusters;
	}

	public ClusterBulkResponse setClusters(List<ClusterResponse> clusters) {
		this.clusters = clusters;
		return this;
	}

	public ClusterBulkResponse add(ClusterResponse cluster) {
		this.clusters.add(cluster);
		return this;
	}

	public int getTotal() {
		return total;
	}

	public ClusterBulkResponse setTotal(int total) {
		this.total = total;
		return this;
	}

	public int getCreated() {
		return created;
	}

	public ClusterBulkResponse setCreated(int created) {
		this.created = created;
		return this;
	}

	public int getFailed() {
		return failed;
	}

	public ClusterBulkResponse setFailed(int failed) {
		this.failed = failed;
		return this;
	}

	/** How many of the producer's stale pending clusters were retired by this write. */
	public int getPruned() {
		return pruned;
	}

	public ClusterBulkResponse setPruned(int pruned) {
		this.pruned = pruned;
		return this;
	}

	@Override
	public ClusterBulkResponse self() {
		return this;
	}

}
