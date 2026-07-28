package io.metaloom.cortex.s3.event;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Worker-wide buffer of change hints delivered by bucket notifications.
 *
 * <p>Events arrive continuously; a pipeline run is a discrete, finite thing. This buffer is what
 * reconciles the two: transports fill it as events land, and a run drains it instead of listing
 * the bucket. Doing it this way keeps {@code stream()} a cold, finite {@code Flowable} and leaves
 * the SOURCE_TASK contract untouched.</p>
 *
 * <h2>Overflow is visible, not silent</h2>
 *
 * <p>Past {@code maxBufferedKeys} the buffer marks the bucket <b>degraded</b> and stops accepting
 * hints for it. A degraded bucket forces the next run onto a full listing, which is correct;
 * quietly dropping hints would instead make objects invisible until something else happened to
 * touch them.</p>
 */
@Singleton
public class S3EventBuffer {

	private static final Logger log = LoggerFactory.getLogger(S3EventBuffer.class);

	/** bucket -&gt; key -&gt; hint. A later hint for the same key supersedes an earlier one. */
	private final Map<String, Map<String, S3ChangeHint>> hints = new ConcurrentHashMap<>();
	private final Set<String> degraded = ConcurrentHashMap.newKeySet();

	private final int maxBufferedKeys;

	@Inject
	public S3EventBuffer() {
		this(io.metaloom.cortex.api.option.S3EventOptions.DEFAULT_MAX_BUFFERED_KEYS);
	}

	public S3EventBuffer(int maxBufferedKeys) {
		this.maxBufferedKeys = maxBufferedKeys <= 0
			? io.metaloom.cortex.api.option.S3EventOptions.DEFAULT_MAX_BUFFERED_KEYS
			: maxBufferedKeys;
	}

	/**
	 * Record a change hint.
	 *
	 * @param hint the hint
	 */
	public void record(S3ChangeHint hint) {
		if (hint == null) {
			return;
		}
		if (degraded.contains(hint.bucket())) {
			// Already over budget; the next run does a full listing anyway, so buffering more
			// would only grow memory for no benefit.
			return;
		}
		Map<String, S3ChangeHint> forBucket = hints.computeIfAbsent(hint.bucket(),
			b -> new ConcurrentHashMap<>());
		if (forBucket.size() >= maxBufferedKeys && !forBucket.containsKey(hint.key())) {
			degraded.add(hint.bucket());
			forBucket.clear();
			log.warn("S3 event buffer for bucket '{}' exceeded {} keys; the next run will fall back "
				+ "to a full listing rather than drop hints", hint.bucket(), maxBufferedKeys);
			return;
		}
		forBucket.put(hint.key(), hint);
	}

	/**
	 * Whether a run may take the event fast path for this selection.
	 *
	 * @param bucket bucket
	 * @param prefix key prefix, may be null or empty
	 * @return true when there is at least one hint and the bucket is not degraded
	 */
	public boolean hasHints(String bucket, String prefix) {
		if (isDegraded(bucket)) {
			return false;
		}
		Map<String, S3ChangeHint> forBucket = hints.get(bucket);
		if (forBucket == null || forBucket.isEmpty()) {
			return false;
		}
		return forBucket.keySet().stream().anyMatch(key -> matchesPrefix(key, prefix));
	}

	/**
	 * Take every hint matching the selection, removing it from the buffer.
	 *
	 * <p>Removal happens here rather than after the run succeeds. That makes a crash mid-run lose
	 * the hints, which the periodic reconcile listing then recovers - the same at-least-once
	 * bargain the rest of the pipeline already makes.</p>
	 *
	 * @param bucket bucket
	 * @param prefix key prefix, may be null or empty
	 * @return the drained hints, never null
	 */
	public List<S3ChangeHint> drain(String bucket, String prefix) {
		Map<String, S3ChangeHint> forBucket = hints.get(bucket);
		if (forBucket == null || forBucket.isEmpty()) {
			return List.of();
		}
		List<S3ChangeHint> drained = new ArrayList<>();
		// Snapshot the keys first: removing while iterating a concurrent map is legal but makes
		// what was taken ambiguous, and the caller needs an exact set.
		for (String key : new LinkedHashMap<>(forBucket).keySet()) {
			if (!matchesPrefix(key, prefix)) {
				continue;
			}
			S3ChangeHint hint = forBucket.remove(key);
			if (hint != null) {
				drained.add(hint);
			}
		}
		return drained;
	}

	/**
	 * @param bucket bucket
	 * @return true when the buffer overflowed and a full listing is required
	 */
	public boolean isDegraded(String bucket) {
		return degraded.contains(bucket);
	}

	/**
	 * Clear the degraded flag. Called once a full listing has re-established the truth.
	 *
	 * @param bucket bucket
	 */
	public void clearDegraded(String bucket) {
		degraded.remove(bucket);
	}

	/**
	 * @param bucket bucket
	 * @return number of buffered hints
	 */
	public int size(String bucket) {
		Map<String, S3ChangeHint> forBucket = hints.get(bucket);
		return forBucket == null ? 0 : forBucket.size();
	}

	/** Test/reset hook. */
	public void clear() {
		hints.clear();
		degraded.clear();
	}

	private static boolean matchesPrefix(String key, String prefix) {
		return prefix == null || prefix.isBlank() || key.startsWith(prefix);
	}
}
