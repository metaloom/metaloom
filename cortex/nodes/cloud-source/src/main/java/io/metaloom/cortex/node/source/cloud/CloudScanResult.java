package io.metaloom.cortex.node.source.cloud;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.metaloom.cortex.cloud.CloudFileRef;
import io.metaloom.fs.FileState;

/**
 * The files a scan decided are worth emitting, each with the state that made it interesting.
 *
 * <p>Ordered: callers hand this straight to a {@code Flowable}, and a stable order makes runs
 * reproducible and test assertions meaningful.</p>
 */
public class CloudScanResult {

	/** Which path the scanner took, recorded so callers (and tests) can assert on it. */
	public enum ScanMode {
		/** Walked the selected folder subtree and diffed it against the index. */
		FULL_WALK,
		/** Read the provider's change feed since the stored cursor; no walking at all. */
		DELTA
	}

	private final List<CloudFileRef> files = new ArrayList<>();
	private final Map<String, FileState> states = new LinkedHashMap<>();
	private final ScanMode mode;

	public CloudScanResult(ScanMode mode) {
		this.mode = mode;
	}

	public void add(CloudFileRef ref, FileState state) {
		files.add(ref);
		states.put(ref.reference(), state);
	}

	public List<CloudFileRef> files() {
		return files;
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
		return files.size();
	}
}
