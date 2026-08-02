package io.metaloom.cortex.node.source.cloud;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.metaloom.cortex.cloud.CloudFileRef;
import io.metaloom.cortex.cloud.CloudProviderId;
import io.metaloom.cortex.cloud.FakeCloudFileStore;
import io.metaloom.fs.FileState;

public class CloudFolderWalkerTest {

	private FakeCloudFileStore store;

	@BeforeEach
	public void setup() {
		store = new FakeCloudFileStore(CloudProviderId.GDRIVE);
	}

	private CloudSelection selection(String folderId, boolean recursive, int maxDepth) {
		return new CloudSelection(CloudProviderId.GDRIVE, "d", folderId, recursive, maxDepth,
			Set.of(), Set.of(), Set.of(FileState.NEW), false, false, false);
	}

	private List<String> names(List<CloudFileRef> refs) {
		return refs.stream().map(CloudFileRef::name).toList();
	}

	@Test
	public void testFlattensASubtree() throws IOException {
		String folder = store.putFolder("d", null, "Videos");
		store.putFile("d", null, "top.mp4", "1");
		store.putFile("d", folder, "nested.mp4", "2");

		List<CloudFileRef> found = new CloudFolderWalker(store).walk(selection(null, true, 0));

		// Folders come back too: the index needs them to answer subtree membership later.
		assertThat(names(found)).containsExactlyInAnyOrder("Videos", "top.mp4", "nested.mp4");
	}

	@Test
	public void testNonRecursiveReturnsOneLevel() throws IOException {
		String folder = store.putFolder("d", null, "Videos");
		store.putFile("d", null, "top.mp4", "1");
		store.putFile("d", folder, "nested.mp4", "2");

		List<CloudFileRef> found = new CloudFolderWalker(store).walk(selection(null, false, 0));

		assertThat(names(found)).containsExactlyInAnyOrder("Videos", "top.mp4");
	}

	@Test
	public void testRespectsMaxDepth() throws IOException {
		String level1 = store.putFolder("d", null, "L1");
		String level2 = store.putFolder("d", level1, "L2");
		store.putFile("d", level1, "shallow.mp4", "1");
		store.putFile("d", level2, "deep.mp4", "2");

		List<CloudFileRef> found = new CloudFolderWalker(store).walk(selection(null, true, 1));

		assertThat(names(found)).contains("shallow.mp4", "L2").doesNotContain("deep.mp4");
	}

	@Test
	public void testStartsFromTheSelectedFolder() throws IOException {
		String watched = store.putFolder("d", null, "Watched");
		store.putFile("d", null, "outside.mp4", "1");
		store.putFile("d", watched, "inside.mp4", "2");

		List<CloudFileRef> found = new CloudFolderWalker(store).walk(selection(watched, true, 0));

		assertThat(names(found)).containsExactly("inside.mp4");
	}

	@Test
	public void testFollowsPageTokens() throws IOException {
		for (int i = 0; i < 9; i++) {
			store.putFile("d", null, "clip" + i + ".mp4", "x");
		}
		store.pageSize(2);

		List<CloudFileRef> found = new CloudFolderWalker(store).walk(selection(null, true, 0));

		assertThat(found).hasSize(9);
		assertThat(store.listCalls.get()).isGreaterThan(1);
	}

	@Test
	public void testACycleDoesNotHangTheWalk() throws IOException {
		// A Drive shortcut can point back up its own subtree; without the visited guard this walk
		// never terminates.
		String a = store.putFolder("d", null, "A");
		String b = store.putFolder("d", a, "B");
		store.move("d", a, b);

		List<CloudFileRef> found = new CloudFolderWalker(store).walk(selection(null, true, 0));

		assertThat(found).isNotNull();
	}

	@Test
	public void testTrashedItemsAreExcludedUnlessRequested() throws IOException {
		String file = store.putFile("d", null, "a.mp4", "1");
		store.trash("d", file);

		CloudSelection excluding = selection(null, true, 0);
		assertThat(new CloudFolderWalker(store).walk(excluding)).isEmpty();

		CloudSelection including = new CloudSelection(CloudProviderId.GDRIVE, "d", null, true, 0,
			Set.of(), Set.of(), Set.of(FileState.NEW), false, true, false);
		assertThat(new CloudFolderWalker(store).walk(including)).hasSize(1);
	}
}
