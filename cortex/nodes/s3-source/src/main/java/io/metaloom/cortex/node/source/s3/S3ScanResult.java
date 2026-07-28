package io.metaloom.cortex.node.source.s3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.metaloom.cortex.s3.S3ObjectRef;
import io.metaloom.fs.FileState;

/**
 * The objects a scan decided are worth emitting, each with the state that made it interesting.
 *
 * <p>Ordered: callers hand this straight to a {@code Flowable}, and a stable order makes runs
 * reproducible and test assertions meaningful.</p>
 */
public class S3ScanResult {

	private final List<S3ObjectRef> objects = new ArrayList<>();
	private final Map<String, FileState> states = new LinkedHashMap<>();
	private final ScanMode mode;

	/** Which path the scanner took, recorded so callers (and tests) can assert on it. */
	public enum ScanMode {
		/** Full paginated listing of the bucket/prefix. */
		FULL_LIST,
		/** Listing resumed after the last seen key. */
		RESUME,
		/** Buffered bucket notifications; no listing at all. */
		EVENTS
	}

	public S3ScanResult(ScanMode mode) {
		this.mode = mode;
	}

	public void add(S3ObjectRef ref, FileState state) {
		objects.add(ref);
		states.put(ref.reference(), state);
	}

	public List<S3ObjectRef> objects() {
		return objects;
	}

	/**
	 * @return emitted reference -&gt; diff state
	 */
	public Map<String, FileState> states() {
		return states;
	}

	public ScanMode mode() {
		return mode;
	}

	public int size() {
		return objects.size();
	}
}
