package io.metaloom.cortex.cloud;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.metaloom.cortex.cloud.CloudFileStore.CloudChange;
import io.metaloom.cortex.cloud.CloudFileStore.CloudDelta;

/**
 * The fake's change feed is real rather than canned, and the scanner tests lean on that heavily -
 * so the fake itself needs a few tests, exactly as {@code FakeS3ObjectStore} has.
 */
public class FakeCloudFileStoreTest {

	private FakeCloudFileStore store;

	@BeforeEach
	public void setup() {
		store = new FakeCloudFileStore(CloudProviderId.GDRIVE);
	}

	@Test
	public void testDeltaReportsOnlyChangesAfterTheCursor() throws IOException {
		store.putFile("d", null, "a.mp4", "x");
		String cursor = store.startDeltaToken("d");
		String second = store.putFile("d", null, "b.mp4", "y");

		CloudDelta delta = store.delta("d", cursor, false);

		assertThat(delta.changes()).hasSize(1);
		assertThat(delta.changes().get(0).fileId()).isEqualTo(second);
	}

	@Test
	public void testDeltaReportsAMove() throws IOException {
		String folder = store.putFolder("d", null, "Videos");
		String file = store.putFile("d", null, "a.mp4", "x");
		String cursor = store.startDeltaToken("d");

		store.move("d", file, folder);
		CloudDelta delta = store.delta("d", cursor, false);

		assertThat(delta.changes()).hasSize(1);
		assertThat(delta.changes().get(0).file().parentId()).isEqualTo(folder);
	}

	@Test
	public void testRepeatedTouchesCollapseToTheFinalState() throws IOException {
		String file = store.putFile("d", null, "a.mp4", "one");
		String cursor = store.startDeltaToken("d");

		store.update("d", file, "two");
		store.rename("d", file, "b.mp4");

		CloudDelta delta = store.delta("d", cursor, false);
		assertThat(delta.changes()).hasSize(1);
		assertThat(delta.changes().get(0).file().name()).isEqualTo("b.mp4");
	}

	@Test
	public void testARemovalIsReportedAsRemoved() throws IOException {
		String file = store.putFile("d", null, "a.mp4", "x");
		String cursor = store.startDeltaToken("d");

		store.remove("d", file);
		CloudChange change = store.delta("d", cursor, false).changes().get(0);

		assertThat(change.removed()).isTrue();
		assertThat(change.file()).isNull();
	}

	@Test
	public void testATrashedFileReadsAsRemovedUnlessRequested() throws IOException {
		String file = store.putFile("d", null, "a.mp4", "x");
		String cursor = store.startDeltaToken("d");
		store.trash("d", file);

		assertThat(store.delta("d", cursor, false).changes().get(0).removed()).isTrue();
		assertThat(store.delta("d", cursor, true).changes().get(0).removed()).isFalse();
	}

	@Test
	public void testExpireDeltaTokenIsHonouredOnce() throws IOException {
		store.putFile("d", null, "a.mp4", "x");
		store.expireDeltaToken();

		assertThat(store.delta("d", "0", false).tokenExpired()).isTrue();
		assertThat(store.delta("d", "0", false).tokenExpired()).isFalse();
	}

	@Test
	public void testPaginationEmitsMultiplePages() throws IOException {
		for (int i = 0; i < 5; i++) {
			store.putFile("d", null, "clip" + i + ".mp4", "x");
		}
		store.pageSize(2);

		int seen = 0;
		String token = null;
		do {
			var page = store.list("d", null, token, false);
			seen += page.entries().size();
			token = page.nextPageToken();
		} while (token != null);

		assertThat(seen).isEqualTo(5);
		assertThat(store.listCalls).hasValue(3);
	}

	@Test
	public void testListingIsScopedToTheFolder() throws IOException {
		String folder = store.putFolder("d", null, "Videos");
		store.putFile("d", null, "root.mp4", "x");
		store.putFile("d", folder, "nested.mp4", "y");

		assertThat(store.list("d", folder, null, false).entries()).hasSize(1);
		// The root listing sees the file and the folder itself.
		assertThat(store.list("d", null, null, false).entries()).hasSize(2);
	}

	@Test
	public void testPutKeepingTokenChangesBytesButNotTheChangeToken() throws IOException {
		String file = store.putFile("d", null, "a.mp4", "one");
		String before = store.peek("d", file).changeToken();

		store.putKeepingToken("d", file, "different bytes");

		assertThat(store.peek("d", file).changeToken()).isEqualTo(before);
	}

	@Test
	public void testTheProviderDecidesWhetherADriveIdIsOptional() throws IOException {
		assertThat(new FakeCloudFileStore(CloudProviderId.GDRIVE).resolveDriveId(null))
			.isEqualTo(CloudUri.MY_DRIVE);
		org.assertj.core.api.Assertions
			.assertThatThrownBy(() -> new FakeCloudFileStore(CloudProviderId.ONEDRIVE).resolveDriveId(null))
			.isInstanceOf(IOException.class);
	}
}
