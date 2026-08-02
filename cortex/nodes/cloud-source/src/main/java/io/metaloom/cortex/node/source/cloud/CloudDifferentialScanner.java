package io.metaloom.cortex.node.source.cloud;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.cloud.CloudFileRef;
import io.metaloom.cortex.cloud.CloudFileStore;
import io.metaloom.cortex.cloud.CloudFileStore.CloudChange;
import io.metaloom.cortex.cloud.CloudFileStore.CloudDelta;
import io.metaloom.cortex.node.source.cloud.CloudScanResult.ScanMode;
import io.metaloom.fs.FileState;

/**
 * Works out what changed in a cloud selection since the previous run.
 *
 * <h2>Two modes, not three</h2>
 *
 * <p>{@code s3-source} needs three strategies because object storage offers no change feed: a full
 * listing, a lexicographic resume, and best-effort bucket notifications. A cloud drive offers a
 * real one, so there are only two paths here and S3's {@code startAfter} resume is deliberately not
 * ported - Drive and Graph have no ordered key space to resume from, and delta is strictly
 * better.</p>
 *
 * <ul>
 * <li>{@link ScanMode#FULL_WALK} walks the selected subtree and diffs it against the index. The
 * delta cursor is taken <em>before</em> the walk starts, so anything changing during a long walk is
 * caught by the next run rather than falling between the two.</li>
 * <li>{@link ScanMode#DELTA} reads the provider's change feed since the stored cursor. One request
 * for a drive of any size.</li>
 * </ul>
 *
 * <h2>The one genuine approximation</h2>
 *
 * <p>Both providers' change feeds are <b>drive-wide</b>, and a delta entry names only the item's
 * immediate parent. Deciding whether a changed file lies inside the selected folder subtree
 * therefore means walking up the parent chain, which this class does for at most
 * {@link #PARENT_LOOKUP_DEPTH} hops, memoised per scan and answered from the index wherever
 * possible.</p>
 *
 * <p>When the chain cannot be resolved within that budget the file is treated as <em>outside</em>
 * the selection. That is the conservative direction - it can delay a file, never invent one - and
 * it is repaired by the periodic full walk. This, and only this, is why
 * {@code reconcileIntervalMs} exists at all: the delta feed itself is a provider guarantee about
 * content, unlike an S3 bucket notification which can simply be lost.</p>
 *
 * <h2>MOVED is real here</h2>
 *
 * <p>A cloud file id survives a rename and a re-parent, and the index records both the name and the
 * parent, so a move is genuinely distinguishable from a delete plus an add - which S3 cannot do.
 * Two selection-scoped refinements go with it: a file that <em>left</em> the subtree is reported
 * {@code DELETED} rather than {@code MOVED}, because from the pipeline's point of view it is gone;
 * and a file that <em>entered</em> it is {@code NEW}, even though its id existed elsewhere in the
 * drive all along.</p>
 */
public class CloudDifferentialScanner {

	private static final Logger log = LoggerFactory.getLogger(CloudDifferentialScanner.class);

	/**
	 * How far up a parent chain a delta entry is followed before giving up.
	 *
	 * <p>Bounded because each unresolved hop is an API call, and a drive-wide feed can carry many
	 * entries that have nothing to do with the selection. Eight levels covers any realistic media
	 * hierarchy.</p>
	 */
	static final int PARENT_LOOKUP_DEPTH = 8;

	private final CloudFileStore store;
	private final CloudFileIndexStore indexStore;
	private final CloudFolderWalker walker;
	private final Path indexBaseDir;
	private final long reconcileIntervalMs;

	public CloudDifferentialScanner(CloudFileStore store, CloudFileIndexStore indexStore, Path indexBaseDir,
		long reconcileIntervalMs) {
		if (store == null) {
			throw new IllegalArgumentException("A cloud file store must be provided");
		}
		if (indexStore == null) {
			throw new IllegalArgumentException("An index store must be provided");
		}
		if (indexBaseDir == null) {
			throw new IllegalArgumentException("An index base directory must be provided");
		}
		this.store = store;
		this.indexStore = indexStore;
		this.walker = new CloudFolderWalker(store);
		this.indexBaseDir = indexBaseDir;
		this.reconcileIntervalMs = reconcileIntervalMs;
	}

	/**
	 * Scan a selection and return what should flow downstream.
	 *
	 * @param selection what to scan
	 * @param nowMillis the current time, injected so reconcile behaviour is testable
	 * @return the emitted files with their diff states
	 * @throws IOException on transport or API failure
	 */
	public CloudScanResult scan(CloudSelection selection, long nowMillis) throws IOException {
		Path indexFile = indexFileFor(selection);
		CloudFileIndex index = indexStore.load(indexFile);

		String accountId = store.accountId();
		if (index.getAccountId() != null && !index.getAccountId().equals(accountId)) {
			// A different credential sees a different subset of the drive, so diffing against the
			// old index would report other people's files as deleted. Start over.
			log.info("The credential for {} changed ({} -> {}); discarding the scan index",
				selection.provider().displayName(), index.getAccountId(), accountId);
			index = new CloudFileIndex();
		}
		index.setAccountId(accountId);

		ScanMode mode = chooseMode(selection, index, nowMillis);
		CloudScanResult result = mode == ScanMode.DELTA
			? scanFromDelta(selection, index)
			: fullWalk(selection, index, nowMillis);

		if (result == null) {
			// The delta cursor had expired; fall back once, which is the whole point of reporting
			// expiry as a value rather than throwing.
			log.info("Falling back to a full walk of {}/{}", selection.driveId(),
				selection.folderId() == null ? "<root>" : selection.folderId());
			result = fullWalk(selection, index, nowMillis);
		}

		indexStore.store(indexFile, index);
		log.info("Scanned {} {}/{} via {} - emitting {} of {} indexed item(s)",
			selection.provider().displayName(), selection.driveId(),
			selection.folderId() == null ? "<root>" : selection.folderId(),
			result.mode(), result.size(), index.size());
		return result;
	}

	/**
	 * Pick the cheapest correct strategy.
	 *
	 * @param selection  what is being scanned
	 * @param index      the loaded index
	 * @param nowMillis  the current time
	 * @return the mode to run
	 */
	ScanMode chooseMode(CloudSelection selection, CloudFileIndex index, long nowMillis) {
		if (index.getLastFullScanMillis() <= 0) {
			return ScanMode.FULL_WALK;
		}
		if (reconcileIntervalMs > 0 && nowMillis - index.getLastFullScanMillis() >= reconcileIntervalMs) {
			return ScanMode.FULL_WALK;
		}
		if (!selection.useDelta()) {
			return ScanMode.FULL_WALK;
		}
		if (index.getDeltaToken() == null || index.getDeltaToken().isBlank()) {
			return ScanMode.FULL_WALK;
		}
		return ScanMode.DELTA;
	}

	// --- full walk ----------------------------------------------------------------------

	private CloudScanResult fullWalk(CloudSelection selection, CloudFileIndex index, long nowMillis)
		throws IOException {

		// Taken first, deliberately: a change made while the walk is running lands after this
		// cursor and is therefore picked up next run, rather than being missed by both.
		String cursor = null;
		try {
			cursor = store.startDeltaToken(selection.driveId());
		} catch (IOException e) {
			// Not fatal - it only costs the next run its fast path.
			log.warn("Could not obtain a change cursor for {}; the next run will walk again",
				selection.driveId(), e);
		}

		CloudScanResult result = new CloudScanResult(ScanMode.FULL_WALK);
		List<CloudFileRef> walked = walker.walk(selection);

		Set<String> seen = new HashSet<>();
		List<CloudFileRef> survivors = new ArrayList<>();
		for (CloudFileRef ref : walked) {
			seen.add(ref.fileId());
			classify(ref, index, selection, result);
			survivors.add(ref);
		}

		// Anything the index knew and the walk did not see is gone from the selection - deleted,
		// trashed, or moved out. All three are DELETED as far as the pipeline is concerned.
		for (CloudFileRef indexed : List.copyOf(index.values())) {
			if (!seen.contains(indexed.fileId())) {
				if (!indexed.folder() && selection.accepts(indexed) && selection.emits(FileState.DELETED)) {
					result.add(indexed, FileState.DELETED);
				}
				index.remove(indexed.fileId());
			}
		}

		// Rebuild rather than merge: an index that keeps stale entries would keep reporting files
		// that have left the selection.
		index.clear();
		for (CloudFileRef ref : survivors) {
			index.put(ref);
		}
		index.setLastFullScanMillis(nowMillis);
		index.setDeltaToken(cursor);
		return result;
	}

	// --- delta --------------------------------------------------------------------------

	/**
	 * @return the scan result, or null when the stored cursor has expired and the caller must fall
	 *         back to a full walk
	 */
	private CloudScanResult scanFromDelta(CloudSelection selection, CloudFileIndex index) throws IOException {
		CloudDelta delta = store.delta(selection.driveId(), index.getDeltaToken(), selection.includeTrashed());
		if (delta.tokenExpired()) {
			return null;
		}

		CloudScanResult result = new CloudScanResult(ScanMode.DELTA);
		Map<String, Integer> hopMemo = new HashMap<>();
		Map<String, CloudFileRef> folderCache = new HashMap<>();

		for (CloudChange change : delta.changes()) {
			if (change.removed()) {
				CloudFileRef indexed = index.remove(change.fileId());
				if (indexed != null && !indexed.folder()
					&& selection.accepts(indexed) && selection.emits(FileState.DELETED)) {
					result.add(indexed, FileState.DELETED);
				}
				continue;
			}

			CloudFileRef ref = change.file();
			if (ref == null) {
				continue;
			}
			if (!ref.trashed() || selection.includeTrashed()) {
				if (isInside(ref, selection, index, hopMemo, folderCache)) {
					classify(ref, index, selection, result);
					continue;
				}
			}

			// Outside the selection now. If we had it, it left - which is a deletion from the
			// pipeline's point of view, not a move.
			CloudFileRef indexed = index.remove(ref.fileId());
			if (indexed != null && !indexed.folder()
				&& selection.accepts(indexed) && selection.emits(FileState.DELETED)) {
				result.add(indexed, FileState.DELETED);
			}
		}

		if (delta.nextToken() != null && !delta.nextToken().isBlank()) {
			index.setDeltaToken(delta.nextToken());
		}
		return result;
	}

	/**
	 * Whether a changed item lies inside the selected subtree.
	 *
	 * <p>See the class javadoc: this is the design's one approximation, bounded by
	 * {@link #PARENT_LOOKUP_DEPTH} and repaired by the reconcile walk.</p>
	 */
	private boolean isInside(CloudFileRef ref, CloudSelection selection, CloudFileIndex index,
		Map<String, Integer> hopMemo, Map<String, CloudFileRef> folderCache) {

		int maxHops = !selection.recursive()
			? 1
			: selection.maxDepth() == 0 ? Integer.MAX_VALUE : selection.maxDepth() + 1;

		int parentHops = hopsToSelectionRoot(ref.parentId(), selection, index, hopMemo, folderCache, 0);
		if (parentHops < 0) {
			return false;
		}
		int hops = parentHops + 1;
		return hops <= maxHops;
	}

	/**
	 * Hops from a folder up to the selection root, or -1 when it is not below it.
	 *
	 * @return 0 when the folder <em>is</em> the selection root
	 */
	private int hopsToSelectionRoot(String folderId, CloudSelection selection, CloudFileIndex index,
		Map<String, Integer> memo, Map<String, CloudFileRef> folderCache, int depth) {

		if (folderId == null) {
			// Reached the drive root. That is the selection root only when no folder was selected.
			return selection.folderId() == null ? 0 : -1;
		}
		if (folderId.equals(selection.folderId())) {
			return 0;
		}
		if (depth >= PARENT_LOOKUP_DEPTH) {
			log.debug("Gave up resolving the parent chain of folder {} after {} hops; the reconcile "
				+ "walk will pick up anything missed", folderId, depth);
			return -1;
		}
		Integer cached = memo.get(folderId);
		if (cached != null) {
			return cached;
		}

		CloudFileRef parent = index.get(folderId);
		if (parent == null) {
			parent = folderCache.get(folderId);
		}
		if (parent == null) {
			try {
				parent = store.get(selection.driveId(), folderId);
			} catch (IOException e) {
				log.debug("Could not read folder {} while resolving a parent chain", folderId, e);
			}
			if (parent != null) {
				folderCache.put(folderId, parent);
			}
		}

		int result;
		if (parent == null) {
			result = -1;
		} else {
			int above = hopsToSelectionRoot(parent.parentId(), selection, index, memo, folderCache, depth + 1);
			result = above < 0 ? -1 : above + 1;
		}
		memo.put(folderId, result);
		return result;
	}

	// --- classification -----------------------------------------------------------------

	/**
	 * Decide what a file's state is relative to the index, record it in the index either way, and
	 * emit it when the selection asks for that state.
	 *
	 * <p>The index tracks everything the selection contains regardless of what is emitted -
	 * otherwise a file filtered out by {@code emitStates} today would look brand new tomorrow.</p>
	 */
	private void classify(CloudFileRef ref, CloudFileIndex index, CloudSelection selection, CloudScanResult result) {
		CloudFileRef indexed = index.get(ref.fileId());

		FileState state;
		if (indexed == null) {
			state = FileState.NEW;
		} else if (ref.differsFrom(indexed.changeToken(), indexed.size())) {
			// Content wins over position: a file that was edited and moved in the same interval is
			// MODIFIED, because that is what a downstream node has to react to.
			state = FileState.MODIFIED;
		} else if (ref.movedFrom(indexed.parentId(), indexed.name())) {
			state = FileState.MOVED;
		} else {
			state = FileState.PRESENT;
		}

		index.put(ref);

		if (selection.accepts(ref) && selection.emits(state)) {
			result.add(ref, state);
		}
	}

	// --- index location -----------------------------------------------------------------

	/**
	 * Where the index for a selection lives.
	 *
	 * <p>The credential scopes it for the same reason an S3 endpoint scopes the object index: two
	 * credentials see different subsets of one drive, so sharing an index between them corrupts
	 * both. {@code recursive} and {@code maxDepth} are in the key because a shallow index is a
	 * strict subset - reusing it for a deeper selection would report the whole subtree as
	 * {@code PRESENT} and emit nothing.</p>
	 *
	 * @param selection the selection
	 * @return the index file path
	 */
	public Path indexFileFor(CloudSelection selection) {
		String canonical = selection.provider().scheme()
			+ "/" + store.accountId()
			+ "/" + selection.driveId()
			+ "/" + (selection.folderId() == null ? "" : selection.folderId())
			+ "/" + (selection.recursive() ? "r" : "1")
			+ "/" + selection.maxDepth();
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
