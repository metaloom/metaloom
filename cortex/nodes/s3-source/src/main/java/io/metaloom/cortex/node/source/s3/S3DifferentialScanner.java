package io.metaloom.cortex.node.source.s3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.node.source.s3.S3ScanResult.ScanMode;
import io.metaloom.cortex.s3.S3ObjectRef;
import io.metaloom.cortex.s3.S3ObjectStore;
import io.metaloom.cortex.s3.S3ObjectStore.S3Listing;
import io.metaloom.cortex.s3.event.S3ChangeHint;
import io.metaloom.cortex.s3.event.S3EventBuffer;
import io.metaloom.fs.FileState;

/**
 * Decides which objects in a bucket/prefix are worth emitting, without downloading anything.
 *
 * <h2>Three paths, one of which is always correct</h2>
 *
 * <ol>
 * <li><b>Full list</b> - paginated {@code ListObjectsV2} diffed against the persisted index.
 * Metadata only, so it costs {@code ceil(N/1000)} requests and no bandwidth. This is the correct
 * one; the other two are accelerators layered on top.</li>
 * <li><b>Resume</b> - {@code startAfter(lastSeenKey)}, valid only for append-only,
 * lexicographically ordered key layouts (date-prefixed keys). It cannot see edits to older keys,
 * so it is opt-in and the reconcile interval still forces periodic full passes.</li>
 * <li><b>Events</b> - drain buffered bucket notifications and HEAD only those keys. No listing at
 * all. Notifications are at-least-once and <em>can be lost</em>, which is exactly why the
 * reconcile interval exists.</li>
 * </ol>
 *
 * <p>Whichever path runs, the index is updated and persisted by the caller, and a full list
 * stamps {@code lastFullScanMillis} - the clock the other two paths are measured against.</p>
 *
 * <h2>On MOVED</h2>
 *
 * <p>Never produced. S3 has no inode, so a rename is a delete plus an add. It could be inferred by
 * matching a removed key against a new key with the same {@code (etag, size)}, but ETags collide
 * across genuinely identical objects - very common in media archives with duplicate uploads - so
 * the inference would invent renames that never happened. {@code MOVED} stays accepted in
 * {@code emitStates} for symmetry with {@code filesystem-source} and is simply never emitted.</p>
 */
public class S3DifferentialScanner {

	private static final Logger log = LoggerFactory.getLogger(S3DifferentialScanner.class);

	private final S3ObjectStore store;
	private final S3ObjectIndexStore indexStore;
	private final S3EventBuffer eventBuffer;
	private final Path indexBaseDir;
	private final String endpointId;
	private final long reconcileIntervalMs;

	/**
	 * @param store               object store
	 * @param indexStore          index persistence
	 * @param eventBuffer         buffered change hints; may be null when events are disabled
	 * @param indexBaseDir        directory holding the per-selection index files
	 * @param endpointId          identifies the S3 endpoint, so the same bucket name on two
	 *                            different servers does not share an index
	 * @param reconcileIntervalMs how long the fast paths may be trusted before a full listing
	 */
	public S3DifferentialScanner(S3ObjectStore store, S3ObjectIndexStore indexStore,
		S3EventBuffer eventBuffer, Path indexBaseDir, String endpointId, long reconcileIntervalMs) {
		if (store == null) {
			throw new IllegalArgumentException("An S3 object store must be provided");
		}
		if (indexBaseDir == null) {
			throw new IllegalArgumentException("An index base directory must be provided");
		}
		this.store = store;
		this.indexStore = indexStore == null ? new S3ObjectIndexStore() : indexStore;
		this.eventBuffer = eventBuffer;
		this.indexBaseDir = indexBaseDir;
		this.endpointId = endpointId == null ? "" : endpointId;
		this.reconcileIntervalMs = reconcileIntervalMs;
	}

	/**
	 * Scan a selection and persist the updated index.
	 *
	 * @param selection what to scan and which states to emit
	 * @param nowMillis current time, injected so tests can drive the reconcile clock
	 * @return the objects to emit
	 * @throws IOException on transport or persistence failure
	 */
	public S3ScanResult scan(S3Selection selection, long nowMillis) throws IOException {
		Path indexFile = indexFileFor(selection);
		S3ObjectIndex index = indexStore.load(indexFile);

		ScanMode mode = chooseMode(selection, index, nowMillis);
		S3ScanResult result = switch (mode) {
			case EVENTS -> scanFromEvents(selection, index);
			case RESUME -> scanByListing(selection, index, index.getLastSeenKey(), false, nowMillis);
			case FULL_LIST -> scanByListing(selection, index, null, true, nowMillis);
		};

		indexStore.store(indexFile, index);
		log.info("Scanned s3://{}/{} via {} - emitting {} of {} indexed object(s)",
			selection.bucket(), selection.prefix() == null ? "" : selection.prefix(),
			mode, result.size(), index.size());
		return result;
	}

	/**
	 * Pick the cheapest path that is still trustworthy.
	 *
	 * <p>Both fast paths are gated on a recent full listing. That single check is what makes lost
	 * notifications and {@code startAfter}'s blind spot survivable: they may skip work, but never
	 * for longer than the reconcile interval.</p>
	 */
	ScanMode chooseMode(S3Selection selection, S3ObjectIndex index, long nowMillis) {
		boolean neverFullyScanned = index.getLastFullScanMillis() <= 0;
		boolean reconcileDue = reconcileIntervalMs > 0
			&& nowMillis - index.getLastFullScanMillis() >= reconcileIntervalMs;

		if (neverFullyScanned || reconcileDue) {
			return ScanMode.FULL_LIST;
		}
		if (selection.useEvents() && eventBuffer != null) {
			if (eventBuffer.isDegraded(selection.bucket())) {
				log.info("Event buffer for bucket '{}' is degraded; falling back to a full listing",
					selection.bucket());
				return ScanMode.FULL_LIST;
			}
			if (eventBuffer.hasHints(selection.bucket(), selection.prefix())) {
				return ScanMode.EVENTS;
			}
			// No hints and events are on: nothing changed since the last run, so a listing would
			// only confirm that. Take the cheap path and emit nothing.
			return ScanMode.EVENTS;
		}
		if (selection.startAfter() && index.getLastSeenKey() != null) {
			return ScanMode.RESUME;
		}
		return ScanMode.FULL_LIST;
	}

	/**
	 * Fast path: only the keys the bucket told us about, confirmed with a HEAD each.
	 */
	private S3ScanResult scanFromEvents(S3Selection selection, S3ObjectIndex index) throws IOException {
		S3ScanResult result = new S3ScanResult(ScanMode.EVENTS);
		List<S3ChangeHint> drained = eventBuffer.drain(selection.bucket(), selection.prefix());
		if (drained.isEmpty()) {
			return result;
		}

		for (S3ChangeHint hint : drained) {
			if (!selection.accepts(hint.key())) {
				continue;
			}
			if (hint.removed()) {
				S3ObjectRef removed = index.remove(hint.key());
				if (removed != null && selection.emits(FileState.DELETED)) {
					result.add(removed, FileState.DELETED);
				}
				continue;
			}

			// The hint carries an etag, but events can be reordered or replayed, so ask the
			// bucket what is true now rather than trusting the payload.
			S3ObjectRef current = store.head(hint.bucket(), hint.key());
			if (current == null) {
				// Created then deleted before we looked. Treat as a removal.
				S3ObjectRef removed = index.remove(hint.key());
				if (removed != null && selection.emits(FileState.DELETED)) {
					result.add(removed, FileState.DELETED);
				}
				continue;
			}
			classify(current, index, selection, result);
		}
		return result;
	}

	/**
	 * List the selection and diff it. When {@code full} is set, keys absent from the listing are
	 * treated as deleted and the reconcile clock is stamped.
	 */
	private S3ScanResult scanByListing(S3Selection selection, S3ObjectIndex index, String startAfter,
		boolean full, long nowMillis) throws IOException {

		S3ScanResult result = new S3ScanResult(full ? ScanMode.FULL_LIST : ScanMode.RESUME);
		Set<String> seen = full ? new LinkedHashSet<>() : null;

		String continuationToken = null;
		int pages = 0;
		do {
			S3Listing page = store.list(selection.bucket(), selection.prefix(), continuationToken, startAfter);
			pages++;
			for (S3ObjectRef object : page.objects()) {
				if (full) {
					seen.add(object.key());
				}
				if (!selection.accepts(object.key())) {
					continue;
				}
				classify(object, index, selection, result);
			}
			continuationToken = page.nextContinuationToken();
		} while (continuationToken != null && !continuationToken.isBlank());

		if (full) {
			collectDeletions(index, selection, seen, result);
			index.setLastFullScanMillis(nowMillis);
			if (eventBuffer != null) {
				// The listing re-established the truth, so whatever the buffer missed no longer
				// matters and the bucket can use the fast path again.
				eventBuffer.clearDegraded(selection.bucket());
				eventBuffer.drain(selection.bucket(), selection.prefix());
			}
		}
		log.debug("Listed {} page(s) of s3://{}/{}", pages, selection.bucket(),
			selection.prefix() == null ? "" : selection.prefix());
		return result;
	}

	/** NEW when unknown, MODIFIED when the change token or size moved, PRESENT otherwise. */
	private void classify(S3ObjectRef object, S3ObjectIndex index, S3Selection selection, S3ScanResult result) {
		S3ObjectRef indexed = index.get(object.key());
		FileState state;
		if (indexed == null) {
			state = FileState.NEW;
		} else if (object.differsFrom(indexed.etag(), indexed.size())) {
			state = FileState.MODIFIED;
		} else {
			state = FileState.PRESENT;
		}

		// The index records what the bucket holds, regardless of what is emitted. Recording only
		// emitted objects would make an object that is filtered out today look NEW forever.
		index.put(object);

		if (selection.emits(state)) {
			result.add(object, state);
		}
	}

	/** Anything indexed but absent from a full listing is gone. */
	private void collectDeletions(S3ObjectIndex index, S3Selection selection, Set<String> seen, S3ScanResult result) {
		List<S3ObjectRef> gone = new ArrayList<>();
		for (S3ObjectRef indexed : index.values()) {
			if (!seen.contains(indexed.key())) {
				gone.add(indexed);
			}
		}
		for (S3ObjectRef indexed : gone) {
			index.remove(indexed.key());
			if (selection.accepts(indexed.key()) && selection.emits(FileState.DELETED)) {
				result.add(indexed, FileState.DELETED);
			}
		}
	}

	/**
	 * The index file for a selection.
	 *
	 * <p>Keyed on endpoint + bucket + prefix, mirroring how {@code FilesystemSourceNode} keys on
	 * the canonical root path. Including the endpoint is what stops the same bucket name on two
	 * different MinIO servers from sharing - and corrupting - one index.</p>
	 *
	 * @param selection the selection
	 * @return the index file path
	 */
	public Path indexFileFor(S3Selection selection) {
		String canonical = endpointId + "/" + selection.bucket() + "/"
			+ (selection.prefix() == null ? "" : selection.prefix());
		return indexBaseDir.resolve(sha256Hex(canonical) + ".avro");
	}

	static String sha256Hex(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			// SHA-256 is a required algorithm on every JRE.
			throw new IllegalStateException("SHA-256 is not available", e);
		}
	}
}
