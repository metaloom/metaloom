package io.metaloom.cortex.s3;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Narrow seam over object storage.
 *
 * <p>Deliberately small, and deliberately bucket-agnostic: one instance per worker serves every
 * bucket the deployment can reach. Keeping it this thin is what lets the node's unit tests run
 * against an in-memory fake and confine MinIO to the integration tests - the same shape as the
 * other injectable client seams in cortex ({@code TtsClient}, {@code SentimentClient},
 * {@code VlmChatClient}).</p>
 */
public interface S3ObjectStore extends AutoCloseable {

	/**
	 * One page of a listing.
	 *
	 * @param objects               the page's objects, never null
	 * @param nextContinuationToken token for the next page, or null when this was the last page
	 */
	record S3Listing(List<S3ObjectRef> objects, String nextContinuationToken) {

		public boolean hasMore() {
			return nextContinuationToken != null && !nextContinuationToken.isBlank();
		}
	}

	/**
	 * List one page of objects.
	 *
	 * @param bucket            bucket to list
	 * @param prefix            key prefix, may be null or empty for the whole bucket
	 * @param continuationToken token from the previous page, or null for the first page
	 * @param startAfter        start listing strictly after this key, or null. Only honoured on
	 *                          the first page (S3 ignores it once a continuation token is given)
	 * @return the page
	 * @throws IOException on transport or protocol failure
	 */
	S3Listing list(String bucket, String prefix, String continuationToken, String startAfter) throws IOException;

	/**
	 * Fetch the metadata of a single object.
	 *
	 * @param bucket bucket
	 * @param key    object key
	 * @return the object, or null when it does not exist
	 * @throws IOException on transport or protocol failure
	 */
	S3ObjectRef head(String bucket, String key) throws IOException;

	/**
	 * Download an object's bytes to a local file. The target's parent directory is expected to
	 * exist; the target is overwritten when present.
	 *
	 * @param bucket bucket
	 * @param key    object key
	 * @param target local destination
	 * @throws IOException on transport failure or when the object does not exist
	 */
	void download(String bucket, String key, Path target) throws IOException;

	/**
	 * Upload a local file as an object, creating it or replacing whatever is at that key.
	 *
	 * <p>Deliberately not a {@code default} method: both implementations live in this repository,
	 * and a store that silently cannot upload would be a lie the compiler should catch.</p>
	 *
	 * @param bucket      bucket
	 * @param key         object key
	 * @param source      local file; must exist and be a regular file
	 * @param contentType content type to store, or null to let the store decide
	 * @return the stored object, whose etag and size come from the store rather than being assumed
	 * @throws IOException on transport failure, or when the source is missing or unreadable
	 */
	S3ObjectRef upload(String bucket, String key, Path source, String contentType) throws IOException;

	@Override
	default void close() {
		// Most implementations hold a client that needs closing; a fake does not.
	}
}
