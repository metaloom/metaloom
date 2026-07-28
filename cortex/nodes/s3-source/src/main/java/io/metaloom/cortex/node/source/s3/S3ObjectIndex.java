package io.metaloom.cortex.node.source.s3;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import io.metaloom.cortex.s3.S3ObjectRef;

/**
 * What the previous scan saw, keyed by object key.
 *
 * <p>Keyed by <em>key</em> rather than by content, unlike the filesystem index which keys on
 * {@code (st_dev, st_ino)}. S3 has no inode, so there is no identity that survives a rename -
 * which is why this index cannot report {@code MOVED} and reports a rename as a delete plus an
 * add. See {@link S3DifferentialScanner} for why inferring moves from matching ETags was
 * rejected.</p>
 *
 * <p>Also records the highest key seen, which is what the {@code startAfter} resume path
 * continues from.</p>
 */
public class S3ObjectIndex {

	private final Map<String, S3ObjectRef> byKey = new LinkedHashMap<>();

	private long lastFullScanMillis;
	private String lastSeenKey;

	public void put(S3ObjectRef ref) {
		byKey.put(ref.key(), ref);
		if (lastSeenKey == null || ref.key().compareTo(lastSeenKey) > 0) {
			lastSeenKey = ref.key();
		}
	}

	public S3ObjectRef get(String key) {
		return byKey.get(key);
	}

	public boolean contains(String key) {
		return byKey.containsKey(key);
	}

	public S3ObjectRef remove(String key) {
		return byKey.remove(key);
	}

	public Collection<S3ObjectRef> values() {
		return byKey.values();
	}

	public int size() {
		return byKey.size();
	}

	public boolean isEmpty() {
		return byKey.isEmpty();
	}

	/**
	 * @return epoch millis of the last completed full listing, or 0 when there has never been one
	 */
	public long getLastFullScanMillis() {
		return lastFullScanMillis;
	}

	public void setLastFullScanMillis(long lastFullScanMillis) {
		this.lastFullScanMillis = lastFullScanMillis;
	}

	/**
	 * @return the highest key seen so far, or null when the index is empty
	 */
	public String getLastSeenKey() {
		return lastSeenKey;
	}

	public void setLastSeenKey(String lastSeenKey) {
		this.lastSeenKey = lastSeenKey;
	}
}
