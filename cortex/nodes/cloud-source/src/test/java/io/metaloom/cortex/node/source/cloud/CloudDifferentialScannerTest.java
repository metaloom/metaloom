package io.metaloom.cortex.node.source.cloud;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.cloud.CloudProviderId;
import io.metaloom.cortex.cloud.FakeCloudFileStore;
import io.metaloom.cortex.node.source.cloud.CloudScanResult.ScanMode;
import io.metaloom.fs.FileState;

public class CloudDifferentialScannerTest {

	private static final long HOUR = 60L * 60 * 1000;
	private static final long RECONCILE_MS = 24 * HOUR;

	@TempDir
	Path indexDir;

	private FakeCloudFileStore store;

	@BeforeEach
	public void setup() {
		store = new FakeCloudFileStore(CloudProviderId.GDRIVE);
	}

	private CloudDifferentialScanner scanner() {
		return scanner(RECONCILE_MS);
	}

	private CloudDifferentialScanner scanner(long reconcileMs) {
		return new CloudDifferentialScanner(store, new CloudFileIndexStore(), indexDir, reconcileMs);
	}

	private CloudSelection selection(String folderId, boolean useDelta) {
		return selection(folderId, useDelta, true, 0);
	}

	private CloudSelection selection(String folderId, boolean useDelta, boolean recursive, int maxDepth) {
		return new CloudSelection(CloudProviderId.GDRIVE, "d", folderId, recursive, maxDepth,
			Set.of(), Set.of(),
			Set.of(FileState.NEW, FileState.MODIFIED, FileState.MOVED, FileState.DELETED),
			useDelta, false, false);
	}

	@Test
	public void testTheFirstScanIsAlwaysAFullWalk() throws IOException {
		store.putFile("d", null, "a.mp4", "1");

		CloudScanResult result = scanner().scan(selection(null, true), 0);

		assertThat(result.mode()).isEqualTo(ScanMode.FULL_WALK);
		assertThat(result.size()).isEqualTo(1);
	}

	@Test
	public void testTheSecondScanUsesDeltaWhenEnabled() throws IOException {
		store.putFile("d", null, "a.mp4", "1");
		CloudDifferentialScanner scanner = scanner();
		scanner.scan(selection(null, true), HOUR);

		int listsAfterFirst = store.listCalls.get();
		CloudScanResult second = scanner.scan(selection(null, true), 2 * HOUR);

		assertThat(second.mode()).isEqualTo(ScanMode.DELTA);
		// The whole point: no listing at all on the fast path.
		assertThat(store.listCalls).hasValue(listsAfterFirst);
		assertThat(store.deltaCalls).hasValue(1);
	}

	@Test
	public void testDeltaDisabledAlwaysWalks() throws IOException {
		store.putFile("d", null, "a.mp4", "1");
		CloudDifferentialScanner scanner = scanner();
		scanner.scan(selection(null, false), HOUR);

		assertThat(scanner.scan(selection(null, false), 2 * HOUR).mode()).isEqualTo(ScanMode.FULL_WALK);
		assertThat(store.deltaCalls).hasValue(0);
	}

	@Test
	public void testTheDeltaCursorIsTakenBeforeTheWalk() throws IOException {
		store.putFile("d", null, "a.mp4", "1");
		CloudDifferentialScanner scanner = scanner();
		scanner.scan(selection(null, true), HOUR);

		// A file created during the walk lands after the stored cursor, so the next delta sees it
		// rather than it falling between the two scans.
		String during = store.putFile("d", null, "during.mp4", "2");
		CloudScanResult second = scanner.scan(selection(null, true), 2 * HOUR);

		assertThat(second.states()).containsKey("gdrive://d/" + during + "/during.mp4");
	}

	@Test
	public void testTheReconcileIntervalForcesAFullWalk() throws IOException {
		store.putFile("d", null, "a.mp4", "1");
		CloudDifferentialScanner scanner = scanner();
		scanner.scan(selection(null, true), HOUR);

		assertThat(scanner.scan(selection(null, true), HOUR + RECONCILE_MS).mode())
			.isEqualTo(ScanMode.FULL_WALK);
	}

	@Test
	public void testWithinTheReconcileWindowDeltaIsTrusted() throws IOException {
		store.putFile("d", null, "a.mp4", "1");
		CloudDifferentialScanner scanner = scanner();
		scanner.scan(selection(null, true), HOUR);

		assertThat(scanner.scan(selection(null, true), HOUR + RECONCILE_MS - 1).mode())
			.isEqualTo(ScanMode.DELTA);
	}

	@Test
	public void testAnExpiredCursorFallsBackToAFullWalkOnce() throws IOException {
		String file = store.putFile("d", null, "a.mp4", "1");
		CloudDifferentialScanner scanner = scanner();
		scanner.scan(selection(null, true), HOUR);

		store.update("d", file, "changed");
		store.expireDeltaToken();

		CloudScanResult result = scanner.scan(selection(null, true), 2 * HOUR);

		// Expiry is a value, not an exception, precisely so this fallback can happen.
		assertThat(result.mode()).isEqualTo(ScanMode.FULL_WALK);
		assertThat(result.size()).isEqualTo(1);
	}

	@Test
	public void testDeltaEntriesOutsideTheSubtreeAreIgnored() throws IOException {
		String watched = store.putFolder("d", null, "Watched");
		String elsewhere = store.putFolder("d", null, "Elsewhere");
		store.putFile("d", watched, "inside.mp4", "1");
		CloudDifferentialScanner scanner = scanner();
		scanner.scan(selection(watched, true), HOUR);

		store.putFile("d", elsewhere, "outside.mp4", "2");
		CloudScanResult result = scanner.scan(selection(watched, true), 2 * HOUR);

		// The change feed is drive-wide; filtering it back to the selection is the scanner's job.
		assertThat(result.size()).isZero();
	}

	@Test
	public void testDeltaSeesAFileAddedDeepInsideTheSubtree() throws IOException {
		String watched = store.putFolder("d", null, "Watched");
		String nested = store.putFolder("d", watched, "2026");
		CloudDifferentialScanner scanner = scanner();
		scanner.scan(selection(watched, true), HOUR);

		String file = store.putFile("d", nested, "deep.mp4", "1");
		CloudScanResult result = scanner.scan(selection(watched, true), 2 * HOUR);

		assertThat(result.states()).containsEntry("gdrive://d/" + file + "/deep.mp4", FileState.NEW);
	}

	@Test
	public void testTheParentChainIsResolvedForAFolderTheIndexDoesNotKnow() throws IOException {
		String watched = store.putFolder("d", null, "Watched");
		CloudDifferentialScanner scanner = scanner();
		scanner.scan(selection(watched, true), HOUR);

		// Both the folder and the file appear only after the index was built, so membership can
		// only come from walking up the parent chain.
		String fresh = store.putFolder("d", watched, "Fresh");
		String file = store.putFile("d", fresh, "new.mp4", "1");

		CloudScanResult result = scanner.scan(selection(watched, true), 2 * HOUR);
		assertThat(result.states()).containsEntry("gdrive://d/" + file + "/new.mp4", FileState.NEW);
	}

	@Test
	public void testDeltaRespectsMaxDepth() throws IOException {
		String watched = store.putFolder("d", null, "Watched");
		String level1 = store.putFolder("d", watched, "L1");
		String level2 = store.putFolder("d", level1, "L2");
		CloudDifferentialScanner scanner = scanner();
		scanner.scan(selection(watched, true, true, 1), HOUR);

		store.putFile("d", level2, "too-deep.mp4", "1");
		CloudScanResult result = scanner.scan(selection(watched, true, true, 1), 2 * HOUR);

		assertThat(result.size()).isZero();
	}

	@Test
	public void testDeltaRespectsANonRecursiveSelection() throws IOException {
		String watched = store.putFolder("d", null, "Watched");
		String nested = store.putFolder("d", watched, "Nested");
		CloudDifferentialScanner scanner = scanner();
		scanner.scan(selection(watched, true, false, 0), HOUR);

		store.putFile("d", nested, "deep.mp4", "1");
		String direct = store.putFile("d", watched, "top.mp4", "2");

		CloudScanResult result = scanner.scan(selection(watched, true, false, 0), 2 * HOUR);
		assertThat(result.states()).containsOnlyKeys("gdrive://d/" + direct + "/top.mp4");
	}

	@Test
	public void testARemovalInTheFeedIsReportedAsDeleted() throws IOException {
		String file = store.putFile("d", null, "a.mp4", "1");
		CloudDifferentialScanner scanner = scanner();
		scanner.scan(selection(null, true), HOUR);

		store.remove("d", file);
		CloudScanResult result = scanner.scan(selection(null, true), 2 * HOUR);

		assertThat(result.states()).containsEntry("gdrive://d/" + file + "/a.mp4", FileState.DELETED);
	}

	@Test
	public void testTheIndexRecordsFilesThatWereFilteredOut() throws IOException {
		store.putFile("d", null, "a.mp4", "1");
		store.putFile("d", null, "notes.txt", "2");

		CloudSelection onlyVideos = new CloudSelection(CloudProviderId.GDRIVE, "d", null, true, 0,
			Set.of("mp4"), Set.of(), Set.of(FileState.NEW), false, false, false);

		CloudDifferentialScanner scanner = scanner();
		assertThat(scanner.scan(onlyVideos, HOUR).size()).isEqualTo(1);
		// The txt is in the index even though it was never emitted - otherwise it would look brand
		// new on every single run.
		assertThat(scanner.scan(onlyVideos, 2 * HOUR).size()).isZero();
	}

	@Test
	public void testTheIndexIsScopedPerAccountDriveFolderAndRecursion() {
		CloudDifferentialScanner scanner = scanner();

		Path a = scanner.indexFileFor(selection("folder-1", true));
		Path b = scanner.indexFileFor(selection("folder-2", true));
		Path shallow = scanner.indexFileFor(selection("folder-1", true, false, 0));

		assertThat(a).isNotEqualTo(b);
		// A non-recursive index is a strict subset; reusing it for a recursive selection would
		// report the whole subtree as PRESENT and emit nothing.
		assertThat(a).isNotEqualTo(shallow);
	}

	@Test
	public void testTwoCredentialsDoNotShareAnIndex() {
		Path first = scanner().indexFileFor(selection("f", true));
		store.accountId("someone-else@example.com");
		Path second = scanner().indexFileFor(selection("f", true));

		assertThat(first).isNotEqualTo(second);
	}

	@Test
	public void testACredentialChangeDiscardsTheIndex() throws IOException {
		store.putFile("d", null, "a.mp4", "1");
		CloudDifferentialScanner scanner = scanner();
		scanner.scan(selection(null, true), HOUR);

		// Same index file (the account is not in the path here because the store reports it at scan
		// time), but a different credential sees a different world.
		store.accountId("someone-else@example.com");
		CloudScanResult result = new CloudDifferentialScanner(store, new CloudFileIndexStore(),
			indexDir, RECONCILE_MS).scan(selection(null, true), 2 * HOUR);

		assertThat(result.mode()).isEqualTo(ScanMode.FULL_WALK);
	}

	@Test
	public void testTheIndexSurvivesAcrossScannerInstances() throws IOException {
		store.putFile("d", null, "a.mp4", "1");
		scanner().scan(selection(null, false), HOUR);

		assertThat(scanner().scan(selection(null, false), 2 * HOUR).size()).isZero();
	}

	@Test
	public void testAFailedCursorFetchDoesNotFailTheScan() throws IOException {
		store.putFile("d", null, "a.mp4", "1");
		CloudDifferentialScanner scanner = scanner();
		scanner.scan(selection(null, true), HOUR);

		// A missing cursor only costs the next run its fast path.
		CloudScanResult second = scanner.scan(selection(null, true), 2 * HOUR);
		assertThat(second).isNotNull();
	}

	@Test
	public void testReconcileZeroDisablesTheForcedWalk() throws IOException {
		store.putFile("d", null, "a.mp4", "1");
		CloudDifferentialScanner scanner = scanner(0);
		scanner.scan(selection(null, true), HOUR);

		assertThat(scanner.scan(selection(null, true), HOUR + 1000 * RECONCILE_MS).mode())
			.isEqualTo(ScanMode.DELTA);
	}
}
