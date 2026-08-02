package io.metaloom.cortex.node.source.cloud;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.cloud.CloudFileRef;
import io.metaloom.cortex.cloud.CloudFileStore;
import io.metaloom.cortex.cloud.CloudFileStore.CloudPage;

/**
 * Breadth-first walk over a folder subtree, built from one-level {@link CloudFileStore#list} calls.
 *
 * <p>The only place recursion happens. Keeping it out of the stores means both providers implement
 * a single flat listing call, the in-memory fake stays trivial, and the rules that are easy to get
 * wrong - depth limiting, pagination, cycle protection - are written and tested once.</p>
 */
public class CloudFolderWalker {

	private static final Logger log = LoggerFactory.getLogger(CloudFolderWalker.class);

	private final CloudFileStore store;

	public CloudFolderWalker(CloudFileStore store) {
		if (store == null) {
			throw new IllegalArgumentException("A cloud file store must be provided");
		}
		this.store = store;
	}

	/**
	 * Walk the selection and return every item found, folders included.
	 *
	 * <p>Folders are returned rather than swallowed because the index records them: subtree
	 * membership over a drive-wide delta feed is decided by asking whether a parent id is a known
	 * folder, and that answer has to survive between runs.</p>
	 *
	 * @param selection what to walk
	 * @return every item in the subtree, in breadth-first order
	 * @throws IOException on transport or API failure
	 */
	public List<CloudFileRef> walk(CloudSelection selection) throws IOException {
		List<CloudFileRef> found = new ArrayList<>();

		// A Drive shortcut can point back up its own subtree, and a file may legitimately have
		// several parents. Without this guard either turns the walk into an infinite loop.
		Set<String> visitedFolders = new HashSet<>();
		Deque<PendingFolder> queue = new ArrayDeque<>();
		queue.add(new PendingFolder(selection.folderId(), 0));
		if (selection.folderId() != null) {
			visitedFolders.add(selection.folderId());
		}

		while (!queue.isEmpty()) {
			PendingFolder current = queue.removeFirst();
			String pageToken = null;
			do {
				CloudPage page = store.list(selection.driveId(), current.folderId(), pageToken,
					selection.includeTrashed());
				for (CloudFileRef entry : page.entries()) {
					found.add(entry);
					if (entry.folder() && selection.mayDescend(current.depth())
						&& visitedFolders.add(entry.fileId())) {
						queue.addLast(new PendingFolder(entry.fileId(), current.depth() + 1));
					}
				}
				pageToken = page.nextPageToken();
			} while (pageToken != null && !pageToken.isBlank());
		}

		log.debug("Walked {} item(s) under {}/{}", found.size(), selection.driveId(),
			selection.folderId() == null ? "<root>" : selection.folderId());
		return found;
	}

	private record PendingFolder(String folderId, int depth) {
	}
}
