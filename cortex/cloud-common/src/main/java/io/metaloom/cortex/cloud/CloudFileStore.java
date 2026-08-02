package io.metaloom.cortex.cloud;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * The seam between cortex and a cloud drive.
 *
 * <p>Deliberately narrow, and deliberately <em>one level deep</em>: implementations list the direct
 * children of a folder and never recurse. Recursion lives in the scanner's folder walker, which
 * keeps both stores small, makes the in-memory fake trivial, and means the walking rules
 * (depth limits, cycle guards) are tested once rather than twice.</p>
 *
 * <h2>What the two providers do not share</h2>
 * <ul>
 * <li><b>Drive addressing.</b> Google treats an absent drive id as "My Drive"; Microsoft app-only
 * tokens have no {@code /me} at all and require a concrete drive. {@link #resolveDriveId(String)}
 * is where that difference is resolved or refused.</li>
 * <li><b>Pagination.</b> Google returns a {@code pageToken}, Microsoft an absolute
 * {@code @odata.nextLink}. The token here is opaque, so Graph simply stores the whole link in it.</li>
 * <li><b>Delta.</b> Both feeds are <em>drive-wide</em>, not subtree-scoped. Restricting them to the
 * selected folder is the scanner's job, and is the one genuine approximation in the design.</li>
 * <li><b>Export.</b> Google native documents have no bytes; {@link CloudFileRef#exportMimeType()}
 * is non-null for them and {@link #download} must use the export endpoint.</li>
 * </ul>
 */
public interface CloudFileStore extends AutoCloseable {

	/**
	 * @return which cloud this store speaks to
	 */
	CloudProviderId provider();

	/**
	 * Stable identity of the credential this store speaks with.
	 *
	 * <p>Scopes the persisted scan index the way an S3 endpoint scopes the object index: two
	 * credentials see different subsets of the same drive, so sharing one index between them would
	 * corrupt both. Google returns the service account's {@code client_email} or the OAuth client
	 * id; Microsoft returns {@code <tenantId>/<clientId>}.</p>
	 *
	 * @return an identity string, never null
	 */
	String accountId();

	/**
	 * Work out which drive to operate on.
	 *
	 * @param configuredDriveId the drive id from the node definition or worker default; may be null
	 * @return the drive id to use. Google returns {@link CloudUri#MY_DRIVE} when nothing was given
	 * @throws IOException when the provider cannot operate without an explicit drive - which is the
	 *         case for Microsoft app-only credentials, and must name the flag to set
	 */
	String resolveDriveId(String configuredDriveId) throws IOException;

	/**
	 * One page of a folder listing.
	 *
	 * @param entries       the items on this page, folders included
	 * @param nextPageToken opaque cursor for the following page, or null when this was the last
	 */
	record CloudPage(List<CloudFileRef> entries, String nextPageToken) {

		public CloudPage {
			entries = entries == null ? List.of() : List.copyOf(entries);
		}

		public boolean hasMore() {
			return nextPageToken != null && !nextPageToken.isBlank();
		}

		public static CloudPage last(List<CloudFileRef> entries) {
			return new CloudPage(entries, null);
		}
	}

	/**
	 * List the direct children of a folder.
	 *
	 * <p>Folders are returned alongside files, with {@link CloudFileRef#folder()} set, so the
	 * walker can descend without a second call shape.</p>
	 *
	 * @param driveId        the drive
	 * @param folderId       folder id; null or blank means the drive root
	 * @param pageToken      cursor from a previous page, or null to start
	 * @param includeTrashed whether to include trashed items
	 * @return one page of children
	 * @throws IOException on transport or API failure
	 */
	CloudPage list(String driveId, String folderId, String pageToken, boolean includeTrashed) throws IOException;

	/**
	 * Read the metadata of a single item - the HEAD analogue.
	 *
	 * @param driveId the drive
	 * @param fileId  the item
	 * @return the item, or null when it does not exist
	 * @throws IOException on transport or API failure
	 */
	CloudFileRef get(String driveId, String fileId) throws IOException;

	/**
	 * A cursor pointing at "now".
	 *
	 * <p>Taken <em>before</em> a full walk, so that anything changing during the walk is caught by
	 * the next delta rather than falling between the two.</p>
	 *
	 * @param driveId the drive
	 * @return an opaque delta cursor
	 * @throws IOException on transport or API failure
	 */
	String startDeltaToken(String driveId) throws IOException;

	/**
	 * A single entry in a change feed.
	 *
	 * @param fileId  the item that changed
	 * @param file    its current state, or null when it was removed
	 * @param removed whether the item is gone (deleted, trashed or moved out of scope)
	 */
	record CloudChange(String fileId, CloudFileRef file, boolean removed) {

		public static CloudChange removed(String fileId) {
			return new CloudChange(fileId, null, true);
		}

		public static CloudChange changed(CloudFileRef file) {
			return new CloudChange(file.fileId(), file, false);
		}
	}

	/**
	 * The drained result of a change feed.
	 *
	 * @param changes      every change since the cursor, in feed order
	 * @param nextToken    the cursor to store for the next run
	 * @param tokenExpired true when the provider refused the cursor (Google answers 404 on a stale
	 *                     page token, Microsoft {@code 410 Gone} with {@code resyncRequired}). The
	 *                     caller must fall back to a full walk
	 */
	record CloudDelta(List<CloudChange> changes, String nextToken, boolean tokenExpired) {

		public CloudDelta {
			changes = changes == null ? List.of() : List.copyOf(changes);
		}

		public static CloudDelta expired() {
			return new CloudDelta(List.of(), null, true);
		}
	}

	/**
	 * Drain the whole change feed, following its pages internally.
	 *
	 * @param driveId        the drive
	 * @param token          the cursor stored by a previous scan
	 * @param includeTrashed whether trashed items should be reported as changes rather than removals
	 * @return every change since the cursor, plus the new cursor
	 * @throws IOException on transport or API failure
	 */
	CloudDelta delta(String driveId, String token, boolean includeTrashed) throws IOException;

	/**
	 * Stream an item's bytes into a local file.
	 *
	 * <p>The target's parent directory exists and the target may be overwritten. When
	 * {@link CloudFileRef#requiresExport()} the store must use the provider's export endpoint,
	 * because the item has no bytes of its own.</p>
	 *
	 * @param ref    the item
	 * @param target where to write
	 * @throws IOException on transport or API failure
	 */
	void download(CloudFileRef ref, Path target) throws IOException;

	@Override
	default void close() {
	}
}
