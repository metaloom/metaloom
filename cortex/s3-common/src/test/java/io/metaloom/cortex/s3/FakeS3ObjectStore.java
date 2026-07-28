package io.metaloom.cortex.s3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory {@link S3ObjectStore} for tests.
 *
 * <p>Published through this module's test-jar so the {@code s3-source} node's tests use the same
 * fake, which keeps MinIO confined to the integration tests.</p>
 *
 * <p>Paginates at {@link #pageSize} so continuation-token handling is genuinely exercised rather
 * than always taking the single-page path, and counts calls so tests can assert that the event
 * fast path really avoided a listing.</p>
 */
public class FakeS3ObjectStore implements S3ObjectStore {

	/** bucket -> key -> bytes */
	private final Map<String, Map<String, byte[]>> buckets = new LinkedHashMap<>();
	/** bucket -> key -> etag override */
	private final Map<String, Map<String, String>> etags = new LinkedHashMap<>();

	private int pageSize = 1000;

	/** bucket -> key -> content type recorded at upload */
	private final Map<String, Map<String, String>> contentTypes = new LinkedHashMap<>();

	public final AtomicInteger listCalls = new AtomicInteger();
	public final AtomicInteger headCalls = new AtomicInteger();
	public final AtomicInteger downloadCalls = new AtomicInteger();
	public final AtomicInteger uploadCalls = new AtomicInteger();

	private IOException failNextWith;
	private IOException failUploadWith;

	public FakeS3ObjectStore put(String bucket, String key, String content) {
		buckets.computeIfAbsent(bucket, b -> new LinkedHashMap<>())
			.put(key, content.getBytes(StandardCharsets.UTF_8));
		// Default etag derives from the content, so "same content -> same etag" holds like S3.
		etags.computeIfAbsent(bucket, b -> new LinkedHashMap<>())
			.put(key, Integer.toHexString(content.hashCode()));
		return this;
	}

	/** Change the bytes without changing the etag - used to prove etag drives change detection. */
	public FakeS3ObjectStore putKeepingEtag(String bucket, String key, String content) {
		String etag = etags.getOrDefault(bucket, Map.of()).get(key);
		put(bucket, key, content);
		if (etag != null) {
			etags.get(bucket).put(key, etag);
		}
		return this;
	}

	public FakeS3ObjectStore etag(String bucket, String key, String etag) {
		etags.computeIfAbsent(bucket, b -> new LinkedHashMap<>()).put(key, etag);
		return this;
	}

	public FakeS3ObjectStore remove(String bucket, String key) {
		Map<String, byte[]> objects = buckets.get(bucket);
		if (objects != null) {
			objects.remove(key);
		}
		Map<String, String> tags = etags.get(bucket);
		if (tags != null) {
			tags.remove(key);
		}
		return this;
	}

	public FakeS3ObjectStore pageSize(int pageSize) {
		this.pageSize = pageSize;
		return this;
	}

	public FakeS3ObjectStore failNextWith(IOException e) {
		this.failNextWith = e;
		return this;
	}

	public void resetCounters() {
		listCalls.set(0);
		headCalls.set(0);
		downloadCalls.set(0);
		uploadCalls.set(0);
	}

	/** Fail only the next upload, leaving list/head/download working. */
	public FakeS3ObjectStore failUploadWith(IOException e) {
		this.failUploadWith = e;
		return this;
	}

	/**
	 * @return the stored bytes, or null when the object does not exist
	 */
	public byte[] bytes(String bucket, String key) {
		return buckets.getOrDefault(bucket, Map.of()).get(key);
	}

	/**
	 * @return the content type recorded at upload, or null
	 */
	public String contentTypeOf(String bucket, String key) {
		return contentTypes.getOrDefault(bucket, Map.of()).get(key);
	}

	/**
	 * @return every key in the bucket, in insertion order
	 */
	public Set<String> keys(String bucket) {
		return new LinkedHashSet<>(buckets.getOrDefault(bucket, Map.of()).keySet());
	}

	private void maybeFail() throws IOException {
		if (failNextWith != null) {
			IOException e = failNextWith;
			failNextWith = null;
			throw e;
		}
	}

	private S3ObjectRef refFor(String bucket, String key) {
		byte[] content = buckets.getOrDefault(bucket, Map.of()).get(key);
		if (content == null) {
			return null;
		}
		return new S3ObjectRef(bucket, key, etags.getOrDefault(bucket, Map.of()).get(key),
			content.length, 0L);
	}

	@Override
	public S3Listing list(String bucket, String prefix, String continuationToken, String startAfter) throws IOException {
		listCalls.incrementAndGet();
		maybeFail();

		List<String> keys = new ArrayList<>(buckets.getOrDefault(bucket, Map.of()).keySet());
		keys.sort(Comparator.naturalOrder());
		if (prefix != null && !prefix.isBlank()) {
			keys.removeIf(key -> !key.startsWith(prefix));
		}
		// A continuation token here is simply the key to resume after; S3 ignores startAfter
		// once a token is present, and the fake follows that.
		String resumeAfter = continuationToken != null ? continuationToken : startAfter;
		if (resumeAfter != null && !resumeAfter.isBlank()) {
			final String after = resumeAfter;
			keys.removeIf(key -> key.compareTo(after) <= 0);
		}

		List<S3ObjectRef> page = new ArrayList<>();
		for (int i = 0; i < keys.size() && i < pageSize; i++) {
			page.add(refFor(bucket, keys.get(i)));
		}
		String next = keys.size() > pageSize ? page.get(page.size() - 1).key() : null;
		return new S3Listing(page, next);
	}

	@Override
	public S3ObjectRef head(String bucket, String key) throws IOException {
		headCalls.incrementAndGet();
		maybeFail();
		return refFor(bucket, key);
	}

	@Override
	public S3ObjectRef upload(String bucket, String key, Path source, String contentType) throws IOException {
		uploadCalls.incrementAndGet();
		if (failUploadWith != null) {
			IOException e = failUploadWith;
			failUploadWith = null;
			throw e;
		}
		maybeFail();
		byte[] content = Files.readAllBytes(source);
		buckets.computeIfAbsent(bucket, b -> new LinkedHashMap<>()).put(key, content);
		// Arrays.hashCode over the bytes, not String.hashCode: keeps the "same content -> same
		// etag" property that put(...) relies on, for content that is not text.
		etags.computeIfAbsent(bucket, b -> new LinkedHashMap<>())
			.put(key, Integer.toHexString(Arrays.hashCode(content)));
		contentTypes.computeIfAbsent(bucket, b -> new LinkedHashMap<>()).put(key, contentType);
		return new S3ObjectRef(bucket, key, etags.get(bucket).get(key), content.length, 0L);
	}

	@Override
	public void download(String bucket, String key, Path target) throws IOException {
		downloadCalls.incrementAndGet();
		maybeFail();
		byte[] content = buckets.getOrDefault(bucket, Map.of()).get(key);
		if (content == null) {
			throw new IOException("No such object: s3://" + bucket + "/" + key);
		}
		Files.write(target, content);
	}
}
