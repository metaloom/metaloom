package io.metaloom.cortex.cloud;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.metaloom.cortex.cloud.gdrive.GoogleExportFormats;

/**
 * An in-memory {@link CloudFileStore} for tests, the counterpart of {@code FakeS3ObjectStore}.
 *
 * <p>The change feed here is <b>real, not stubbed</b>: every mutation appends to a monotonic log and
 * {@link #delta} replays the log after the given cursor. That matters because the delta path is the
 * one thing a mocked store cannot exercise honestly - a stub that simply returns a canned list would
 * pass whether or not the scanner handles moves, removals and cursor advancement correctly.</p>
 *
 * <p>Published in this module's test-jar so the {@code cloud-source} node module reuses it.</p>
 */
public class FakeCloudFileStore implements CloudFileStore {

	/** Every call is counted, so a test can assert that enumeration cost no bytes. */
	public final AtomicInteger listCalls = new AtomicInteger();
	public final AtomicInteger getCalls = new AtomicInteger();
	public final AtomicInteger downloadCalls = new AtomicInteger();
	public final AtomicInteger deltaCalls = new AtomicInteger();

	private record Entry(CloudFileRef ref, byte[] content) {
	}

	private record Change(long seq, String fileId, boolean removed) {
	}

	private final CloudProviderId provider;
	private String accountId = "test-account";

	/** driveId -> fileId -> entry, insertion ordered so listings are reproducible. */
	private final Map<String, Map<String, Entry>> drives = new LinkedHashMap<>();

	private final List<Change> changeLog = new ArrayList<>();
	private long seq;

	private int pageSize = 1000;
	private boolean expireDeltaToken;
	private IOException nextFailure;
	private final AtomicInteger idCounter = new AtomicInteger();

	public FakeCloudFileStore(CloudProviderId provider) {
		this.provider = provider;
	}

	// --- mutation helpers ---------------------------------------------------------------

	/**
	 * @return the generated file id
	 */
	public String putFile(String driveId, String parentId, String name, String content) {
		return put(driveId, parentId, name, content, mimeFor(name), false, null);
	}

	public String putFolder(String driveId, String parentId, String name) {
		return put(driveId, parentId, name, "", GoogleExportFormats.FOLDER_MIME, true, null);
	}

	/**
	 * A Google native document: no size, no downloadable bytes, and only readable via an export.
	 *
	 * @param exportMime the MIME type it would be exported as, or null to model the
	 *                   exportNativeDocs-off case where it is left unreadable
	 */
	public String putNativeDoc(String driveId, String parentId, String name, String exportMime) {
		String fileId = nextId();
		CloudFileRef ref = new CloudFileRef(provider, driveId, fileId, name, parentId,
			"application/vnd.google-apps.document", "v:1", -1, 1000, false, false, exportMime, true);
		store(driveId, fileId, new Entry(ref, "exported".getBytes(StandardCharsets.UTF_8)));
		return fileId;
	}

	private String put(String driveId, String parentId, String name, String content, String mime,
		boolean folder, String exportMime) {
		String fileId = nextId();
		byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
		CloudFileRef ref = new CloudFileRef(provider, driveId, fileId, name, parentId, mime,
			tokenFor(bytes), folder ? -1 : bytes.length, 1000, folder, false, exportMime, true);
		store(driveId, fileId, new Entry(ref, bytes));
		return fileId;
	}

	/** Replace a file's content, which changes its change token. */
	public void update(String driveId, String fileId, String content) {
		Entry existing = require(driveId, fileId);
		byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
		store(driveId, fileId, new Entry(withContent(existing.ref(), tokenFor(bytes), bytes.length), bytes));
	}

	/**
	 * Replace a file's bytes while keeping its change token, modelling a provider that did not
	 * notice. The scanner must then report nothing: the token is the change signal.
	 */
	public void putKeepingToken(String driveId, String fileId, String content) {
		Entry existing = require(driveId, fileId);
		byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
		store(driveId, fileId, new Entry(withContent(existing.ref(), existing.ref().changeToken(), bytes.length), bytes));
	}

	public void move(String driveId, String fileId, String newParentId) {
		Entry existing = require(driveId, fileId);
		store(driveId, fileId, new Entry(withParentAndName(existing.ref(), newParentId, existing.ref().name()),
			existing.content()));
	}

	public void rename(String driveId, String fileId, String newName) {
		Entry existing = require(driveId, fileId);
		store(driveId, fileId, new Entry(withParentAndName(existing.ref(), existing.ref().parentId(), newName),
			existing.content()));
	}

	public void trash(String driveId, String fileId) {
		Entry existing = require(driveId, fileId);
		CloudFileRef ref = existing.ref();
		store(driveId, fileId, new Entry(new CloudFileRef(ref.provider(), ref.driveId(), ref.fileId(), ref.name(),
			ref.parentId(), ref.mimeType(), ref.changeToken(), ref.size(), ref.lastModifiedMillis(), ref.folder(),
			true, ref.exportMimeType(), true), existing.content()));
	}

	public void remove(String driveId, String fileId) {
		Map<String, Entry> drive = drives.get(driveId);
		if (drive != null && drive.remove(fileId) != null) {
			changeLog.add(new Change(++seq, fileId, true));
		}
	}

	// --- test controls ------------------------------------------------------------------

	public FakeCloudFileStore pageSize(int pageSize) {
		this.pageSize = pageSize;
		return this;
	}

	/** Make the next {@link #delta} report its cursor as expired, forcing a full walk. */
	public FakeCloudFileStore expireDeltaToken() {
		this.expireDeltaToken = true;
		return this;
	}

	public FakeCloudFileStore failNextWith(IOException failure) {
		this.nextFailure = failure;
		return this;
	}

	public FakeCloudFileStore accountId(String accountId) {
		this.accountId = accountId;
		return this;
	}

	public CloudFileRef peek(String driveId, String fileId) {
		Entry entry = drives.getOrDefault(driveId, Map.of()).get(fileId);
		return entry == null ? null : entry.ref();
	}

	// --- CloudFileStore -----------------------------------------------------------------

	@Override
	public CloudProviderId provider() {
		return provider;
	}

	@Override
	public String accountId() {
		return accountId;
	}

	@Override
	public String resolveDriveId(String configuredDriveId) throws IOException {
		if (configuredDriveId != null && !configuredDriveId.isBlank()) {
			return configuredDriveId;
		}
		if (provider == CloudProviderId.GDRIVE) {
			return CloudUri.MY_DRIVE;
		}
		throw new IOException("OneDrive needs a drive id");
	}

	@Override
	public CloudPage list(String driveId, String folderId, String pageToken, boolean includeTrashed) throws IOException {
		listCalls.incrementAndGet();
		failIfArmed();

		List<CloudFileRef> matching = new ArrayList<>();
		for (Entry entry : drives.getOrDefault(driveId, Map.of()).values()) {
			CloudFileRef ref = entry.ref();
			boolean atRoot = folderId == null || folderId.isBlank();
			boolean inFolder = atRoot ? ref.parentId() == null : folderId.equals(ref.parentId());
			if (inFolder && (includeTrashed || !ref.trashed())) {
				matching.add(ref);
			}
		}

		int offset = pageToken == null || pageToken.isBlank() ? 0 : Integer.parseInt(pageToken);
		int end = Math.min(offset + pageSize, matching.size());
		List<CloudFileRef> page = matching.subList(offset, end);
		String next = end < matching.size() ? String.valueOf(end) : null;
		return new CloudPage(page, next);
	}

	@Override
	public CloudFileRef get(String driveId, String fileId) throws IOException {
		getCalls.incrementAndGet();
		failIfArmed();
		Entry entry = drives.getOrDefault(driveId, Map.of()).get(fileId);
		return entry == null ? null : entry.ref();
	}

	@Override
	public String startDeltaToken(String driveId) {
		return String.valueOf(seq);
	}

	@Override
	public CloudDelta delta(String driveId, String token, boolean includeTrashed) throws IOException {
		deltaCalls.incrementAndGet();
		failIfArmed();
		if (expireDeltaToken) {
			expireDeltaToken = false;
			return CloudDelta.expired();
		}

		long since = token == null || token.isBlank() ? 0 : Long.parseLong(token);
		// Collapse repeats: a file touched twice reports its final state once, exactly as both real
		// feeds behave.
		Map<String, Change> latest = new LinkedHashMap<>();
		for (Change change : changeLog) {
			if (change.seq() > since) {
				latest.put(change.fileId(), change);
			}
		}

		List<CloudChange> changes = new ArrayList<>();
		for (Change change : latest.values()) {
			CloudFileRef ref = peek(driveId, change.fileId());
			if (change.removed() || ref == null) {
				changes.add(CloudChange.removed(change.fileId()));
			} else if (ref.trashed() && !includeTrashed) {
				changes.add(CloudChange.removed(change.fileId()));
			} else {
				changes.add(CloudChange.changed(ref));
			}
		}
		return new CloudDelta(changes, String.valueOf(seq), false);
	}

	@Override
	public void download(CloudFileRef ref, Path target) throws IOException {
		downloadCalls.incrementAndGet();
		failIfArmed();
		Entry entry = drives.getOrDefault(ref.driveId(), Map.of()).get(ref.fileId());
		if (entry == null) {
			throw new IOException("No such file: " + ref.reference());
		}
		Files.write(target, entry.content());
	}

	// --- internals ----------------------------------------------------------------------

	private void store(String driveId, String fileId, Entry entry) {
		drives.computeIfAbsent(driveId, key -> new LinkedHashMap<>()).put(fileId, entry);
		changeLog.add(new Change(++seq, fileId, false));
	}

	private Entry require(String driveId, String fileId) {
		Entry entry = drives.getOrDefault(driveId, Map.of()).get(fileId);
		if (entry == null) {
			throw new IllegalArgumentException("No such file in the fake store: " + driveId + "/" + fileId);
		}
		return entry;
	}

	private void failIfArmed() throws IOException {
		if (nextFailure != null) {
			IOException failure = nextFailure;
			nextFailure = null;
			throw failure;
		}
	}

	private String nextId() {
		return "id-" + idCounter.incrementAndGet();
	}

	private static CloudFileRef withContent(CloudFileRef ref, String token, long size) {
		return new CloudFileRef(ref.provider(), ref.driveId(), ref.fileId(), ref.name(), ref.parentId(),
			ref.mimeType(), token, size, ref.lastModifiedMillis(), ref.folder(), ref.trashed(),
			ref.exportMimeType(), true);
	}

	private static CloudFileRef withParentAndName(CloudFileRef ref, String parentId, String name) {
		return new CloudFileRef(ref.provider(), ref.driveId(), ref.fileId(), name, parentId,
			ref.mimeType(), ref.changeToken(), ref.size(), ref.lastModifiedMillis(), ref.folder(),
			ref.trashed(), ref.exportMimeType(), true);
	}

	private static String tokenFor(byte[] content) {
		return "md5:" + Integer.toHexString(java.util.Arrays.hashCode(content));
	}

	private static String mimeFor(String name) {
		String lower = name.toLowerCase();
		if (lower.endsWith(".mp4")) {
			return "video/mp4";
		}
		if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
			return "image/jpeg";
		}
		if (lower.endsWith(".txt")) {
			return "text/plain";
		}
		return "application/octet-stream";
	}
}
