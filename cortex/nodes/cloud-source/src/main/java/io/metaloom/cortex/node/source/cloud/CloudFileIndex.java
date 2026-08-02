package io.metaloom.cortex.node.source.cloud;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import io.metaloom.cortex.cloud.CloudFileRef;

/**
 * What one selection looked like at the end of the previous scan.
 *
 * <p>Keyed by the provider's <b>file id</b> rather than by a path or a name, which is the whole
 * reason this source can report a rename as {@code MOVED}: the id is stable across both a rename
 * and a re-parent, so the recorded name and parent can be compared against the current ones.</p>
 *
 * <p>Folders are recorded alongside files. They are never emitted, but subtree membership over a
 * drive-wide delta feed is decided by asking "is this parent a folder I already know about", and
 * that question needs them.</p>
 */
public class CloudFileIndex {

	private final Map<String, CloudFileRef> byId = new LinkedHashMap<>();

	private long lastFullScanMillis;

	/** The provider's change cursor as of the last successful scan. */
	private String deltaToken;

	/**
	 * The credential the index was built with. A change means the view of the drive may be
	 * completely different, so the scanner discards the index rather than diffing against it.
	 */
	private String accountId;

	public CloudFileRef get(String fileId) {
		return byId.get(fileId);
	}

	public void put(CloudFileRef ref) {
		byId.put(ref.fileId(), ref);
	}

	public CloudFileRef remove(String fileId) {
		return byId.remove(fileId);
	}

	public boolean contains(String fileId) {
		return byId.containsKey(fileId);
	}

	/**
	 * @param fileId a file id
	 * @return true when the index knows this id and it is a folder
	 */
	public boolean isKnownFolder(String fileId) {
		CloudFileRef ref = byId.get(fileId);
		return ref != null && ref.folder();
	}

	public Collection<CloudFileRef> values() {
		return byId.values();
	}

	public int size() {
		return byId.size();
	}

	public boolean isEmpty() {
		return byId.isEmpty();
	}

	public void clear() {
		byId.clear();
	}

	public long getLastFullScanMillis() {
		return lastFullScanMillis;
	}

	public void setLastFullScanMillis(long lastFullScanMillis) {
		this.lastFullScanMillis = lastFullScanMillis;
	}

	public String getDeltaToken() {
		return deltaToken;
	}

	public void setDeltaToken(String deltaToken) {
		this.deltaToken = deltaToken;
	}

	public String getAccountId() {
		return accountId;
	}

	public void setAccountId(String accountId) {
		this.accountId = accountId;
	}
}
