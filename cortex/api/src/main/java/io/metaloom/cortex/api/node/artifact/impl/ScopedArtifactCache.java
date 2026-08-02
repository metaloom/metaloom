package io.metaloom.cortex.api.node.artifact.impl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.node.artifact.Artifact;
import io.metaloom.cortex.api.node.artifact.ArtifactCache;
import io.metaloom.cortex.api.node.artifact.ArtifactException;
import io.metaloom.cortex.api.node.artifact.ArtifactFactory;
import io.metaloom.cortex.api.node.artifact.ArtifactKey;

/**
 * The real {@link ArtifactCache}: one bounded, closeable scope covering one execution over one item in one process.
 *
 * <p>
 * Created by the runner before the first node of a segment and closed after the last. Everything about its behaviour follows from that scope being
 * short and known — see {@link ArtifactCache} for the reasoning; this class is the mechanism.
 * </p>
 *
 * <h2>The memory ceiling</h2>
 *
 * <p>
 * Two different unbounded-growth risks need two different answers. <em>Across</em> a long run the answer is the scope itself: every segment gets a
 * new one and the old one is closed, so a worker that has processed ten thousand items holds exactly as much as a worker that has processed one.
 * <em>Within</em> one segment a node could still publish five two-hundred-megabyte artifacts, so entries carry a weight and the least recently used
 * are dropped once {@link #maxBytes()} is exceeded.
 * </p>
 *
 * <p>
 * A capacity eviction drops the reference and, deliberately, <strong>does not close</strong> the artifact. It cannot know whether the node that
 * fetched it a moment ago is still using it, and closing a native buffer out from under a running node is a segfault, whereas leaving it to the
 * collector is a delay. Closing is confined to the two moments where nobody can still be holding the artifact: {@link #close()} at the end of the
 * scope, and the explicit {@link #invalidate(ArtifactKey)} / rollback paths. An artifact bigger than the whole ceiling is never stored at all —
 * storing it would evict everything else and then be evicted itself by the next insert.
 * </p>
 *
 * <p>
 * Thread-safe. The factory runs while the monitor is held, which is what makes "at most once per key" true for a node that fans its own work out
 * across threads: the second thread waits for the first one's decode instead of starting an identical one.
 * </p>
 */
public final class ScopedArtifactCache implements ArtifactCache {

	private static final Logger log = LoggerFactory.getLogger(ScopedArtifactCache.class);

	/**
	 * The default ceiling for one segment's artifacts.
	 *
	 * <p>
	 * Sized for the case this exists for — a handful of decoded-frame sets over one video — and small enough that a worker running several segments
	 * in parallel is not the reason a container is OOM-killed. Runners take it as a constructor argument so it can be tuned or shrunk in a test.
	 * </p>
	 */
	public static final long DEFAULT_MAX_BYTES = 512L * 1024 * 1024;

	private final String scopeId;
	private final long maxBytes;

	/** Access-ordered, so the eldest entry is genuinely the least recently <em>used</em> and not merely the first published. */
	private final Map<ArtifactKey<?>, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);

	/** Keys whose factory is running right now, so a factory that asks for its own key fails clearly instead of recursing until the stack ends. */
	private final Set<ArtifactKey<?>> inProgress = new HashSet<>();

	private long retained;
	private boolean closed;
	private ScopedPublication current;

	private record Entry(Object value, long weightBytes) {
	}

	/**
	 * @param scopeId
	 *            what this scope covers — the run item id — used only in log lines, so that a leak is traceable to an item
	 * @param maxBytes
	 *            the ceiling to evict against; must be positive
	 */
	public ScopedArtifactCache(String scopeId, long maxBytes) {
		if (maxBytes <= 0) {
			throw new IllegalArgumentException("An artifact scope needs a positive ceiling, got " + maxBytes);
		}
		this.scopeId = scopeId;
		this.maxBytes = maxBytes;
	}

	public ScopedArtifactCache(String scopeId) {
		this(scopeId, DEFAULT_MAX_BYTES);
	}

	@Override
	public synchronized <T> T get(ArtifactKey<T> key, ArtifactFactory<T> factory) {
		checkOpen();
		Entry existing = entries.get(key);
		if (existing != null) {
			return key.cast(existing.value());
		}
		if (!inProgress.add(key)) {
			throw new ArtifactException("Artifact " + key + " is being produced by the factory that just asked for it");
		}
		Artifact<T> artifact;
		try {
			artifact = factory.create();
		} catch (RuntimeException e) {
			// Nothing is stored, so the next node asking for this key gets a clean attempt
			// rather than whatever the failed one had managed to build.
			throw e;
		} catch (Exception e) {
			throw new ArtifactException("Could not produce artifact " + key + " for " + scopeId, e);
		} finally {
			inProgress.remove(key);
		}
		if (artifact == null) {
			throw new ArtifactException("Factory for artifact " + key + " returned null");
		}
		store(key, artifact);
		return artifact.value();
	}

	private void store(ArtifactKey<?> key, Artifact<?> artifact) {
		long weight = artifact.weightBytes();
		if (weight > maxBytes) {
			// Keeping it would evict every other entry and then be evicted itself by the next
			// insert - all of the cost of caching and none of the benefit.
			log.debug("Not retaining artifact {} for {}: {} bytes exceeds the {} byte scope ceiling", key, scopeId, weight, maxBytes);
			return;
		}
		evictUntilItFits(weight);
		entries.put(key, new Entry(artifact.value(), weight));
		retained += weight;
		if (current != null) {
			current.published(key);
		}
	}

	private void evictUntilItFits(long incoming) {
		Iterator<Map.Entry<ArtifactKey<?>, Entry>> it = entries.entrySet().iterator();
		while (retained + incoming > maxBytes && it.hasNext()) {
			Map.Entry<ArtifactKey<?>, Entry> eldest = it.next();
			it.remove();
			retained -= eldest.getValue().weightBytes();
			if (current != null) {
				current.forget(eldest.getKey());
			}
			// Dropped, not closed - see the class javadoc. A node fetched this a moment ago and
			// may still be reading it.
			log.debug("Evicted artifact {} from scope {} to make room for {} bytes", eldest.getKey(), scopeId, incoming);
		}
	}

	@Override
	public synchronized <T> Optional<T> peek(ArtifactKey<T> key) {
		checkOpen();
		Entry entry = entries.get(key);
		return entry == null ? Optional.empty() : Optional.ofNullable(key.cast(entry.value()));
	}

	@Override
	public synchronized void invalidate(ArtifactKey<?> key) {
		checkOpen();
		removeAndClose(key);
	}

	private void removeAndClose(ArtifactKey<?> key) {
		Entry removed = entries.remove(key);
		if (removed == null) {
			return;
		}
		retained -= removed.weightBytes();
		closeQuietly(key, removed.value());
	}

	@Override
	public synchronized long retainedBytes() {
		return retained;
	}

	@Override
	public long maxBytes() {
		return maxBytes;
	}

	@Override
	public synchronized Publication beginPublication() {
		checkOpen();
		if (current != null) {
			throw new IllegalStateException("A publication window is already open on scope " + scopeId
				+ "; the runner opens one per node and the nodes of a segment run in sequence");
		}
		current = new ScopedPublication();
		return current;
	}

	@Override
	public synchronized void close() {
		if (closed) {
			return;
		}
		closed = true;
		current = null;
		// Reverse publication order: an artifact built on top of an earlier one is released
		// before the thing it was built from.
		List<Map.Entry<ArtifactKey<?>, Entry>> all = new ArrayList<>(entries.entrySet());
		entries.clear();
		retained = 0;
		for (int i = all.size() - 1; i >= 0; i--) {
			closeQuietly(all.get(i).getKey(), all.get(i).getValue().value());
		}
	}

	private void closeQuietly(ArtifactKey<?> key, Object value) {
		if (!(value instanceof AutoCloseable closeable)) {
			return;
		}
		try {
			closeable.close();
		} catch (Exception e) {
			// One artifact that will not release must not stop the rest from being released.
			log.warn("Could not close artifact {} of scope {}", key, scopeId, e);
		}
	}

	private void checkOpen() {
		if (closed) {
			throw new IllegalStateException("Artifact scope " + scopeId + " is closed. A node kept it past the segment it belongs to; "
				+ "an artifact scope is valid only for the execution that created it.");
		}
	}

	/**
	 * Records what one node published so a failure can take it back out again.
	 */
	private final class ScopedPublication implements Publication {

		private final List<ArtifactKey<?>> added = new ArrayList<>(2);
		private boolean committed;

		void published(ArtifactKey<?> key) {
			added.add(key);
		}

		void forget(ArtifactKey<?> key) {
			// Already evicted for capacity, so there is nothing left to roll back.
			added.remove(key);
		}

		@Override
		public void commit() {
			committed = true;
		}

		@Override
		public void close() {
			synchronized (ScopedArtifactCache.this) {
				if (current != this) {
					return;
				}
				current = null;
				if (committed || closed) {
					return;
				}
				for (ArtifactKey<?> key : added) {
					log.debug("Discarding artifact {} published by a node that then failed, in scope {}", key, scopeId);
					removeAndClose(key);
				}
			}
		}
	}
}
