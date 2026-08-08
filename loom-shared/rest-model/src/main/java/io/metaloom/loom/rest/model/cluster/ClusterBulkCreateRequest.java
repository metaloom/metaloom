package io.metaloom.loom.rest.model.cluster;

import java.util.ArrayList;
import java.util.List;

import io.metaloom.loom.rest.model.RestRequestModel;

/**
 * Every cluster a producer found in one asset, written in one call.
 *
 * <p>
 * The whole set travels together because it is one statement about the asset - "these are the subjects in it". Sending them one at a time would leave
 * the asset in states that were never true, and gives the server no way to retire proposals the producer no longer makes.
 * </p>
 */
public class ClusterBulkCreateRequest implements RestRequestModel {

	private List<ClusterCreateItem> clusters = new ArrayList<>();

	/**
	 * Nullable rather than a primitive {@code boolean} so that "absent" is distinguishable from "false", and absent means the default (prune).
	 *
	 * <p>
	 * The generated Python client is transcribed by a line scanner that cannot see Java field initialisers, so a primitive with a {@code true}
	 * initialiser would come out as {@code False} on the other side and silently turn pruning off for every Python caller.
	 * </p>
	 */
	private Boolean pruneStale;

	public List<ClusterCreateItem> getClusters() {
		return clusters;
	}

	public ClusterBulkCreateRequest setClusters(List<ClusterCreateItem> clusters) {
		this.clusters = clusters;
		return this;
	}

	public ClusterBulkCreateRequest add(ClusterCreateItem cluster) {
		this.clusters.add(cluster);
		return this;
	}

	/**
	 * Whether to retire this producer's still-pending clusters for the asset that are absent from this request. Defaults to true.
	 *
	 * <p>
	 * A re-run that now finds two people where it once found three would otherwise strand the third forever. Clusters a human has already confirmed or
	 * rejected are never retired, whatever this is set to - the producer owns its proposals, not the verdicts on them.
	 * </p>
	 */
	public Boolean getPruneStale() {
		return pruneStale;
	}

	public ClusterBulkCreateRequest setPruneStale(Boolean pruneStale) {
		this.pruneStale = pruneStale;
		return this;
	}

	/** Whether to prune, resolving an absent flag to the default. */
	public boolean pruneStaleOrDefault() {
		return pruneStale == null || pruneStale;
	}

}
