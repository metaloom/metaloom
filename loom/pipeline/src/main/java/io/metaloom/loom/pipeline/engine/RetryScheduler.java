package io.metaloom.loom.pipeline.engine;

/**
 * Decides <em>when</em> a retry happens.
 *
 * <p>The engine knows a node should be attempted again; it deliberately does not
 * know how to wait. Retrying a failing node in a tight loop turns one broken worker
 * or one unavailable dependency into a stampede, so the delay is real - but a
 * blocking sleep inside the engine would stall every other item, because all
 * mutating entry points are serialised on the engine's monitor.</p>
 *
 * <p>This seam lets production hand the wait to a Vert.x timer while tests run
 * retries immediately and stay deterministic.</p>
 */
@FunctionalInterface
public interface RetryScheduler {

	/**
	 * Arrange for the retry to happen.
	 *
	 * @param delayMs how long to wait; may be 0
	 * @param retry   the action that re-dispatches; must not be run on the calling
	 *                thread while the caller holds the engine monitor unless
	 *                {@code delayMs} is 0
	 */
	void schedule(long delayMs, Runnable retry);

	/**
	 * Retry at once, ignoring the delay.
	 *
	 * <p>Fine for tests, and the honest default: an implementation that silently
	 * dropped retries would be far worse than one that is merely impatient.</p>
	 */
	RetryScheduler IMMEDIATE = (delayMs, retry) -> retry.run();

}
