package io.metaloom.cortex.pipeline.api;

import io.reactivex.rxjava3.core.Flowable;

/**
 * A pair of {@link Flowable}s representing the PASS and REJECT branches produced
 * by a partitioning (filter) node. Downstream nodes subscribe to the branch that
 * matches their {@link io.metaloom.cortex.pipeline.api.filter.FilterBranch} declaration.
 *
 * <p>Both branches share the same upstream subscription — items are evaluated once
 * and routed to the appropriate branch.</p>
 *
 * @param <T> the element type
 */
public class PartitionedFlowable<T> {

	private final Flowable<T> pass;
	private final Flowable<T> reject;

	public PartitionedFlowable(Flowable<T> pass, Flowable<T> reject) {
		this.pass = pass;
		this.reject = reject;
	}

	/**
	 * Items that passed the filter condition.
	 */
	public Flowable<T> pass() {
		return pass;
	}

	/**
	 * Items that were rejected by the filter condition.
	 */
	public Flowable<T> reject() {
		return reject;
	}
}
