package io.metaloom.cortex.node.source.s3;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.node.source.s3.S3ScanResult.ScanMode;
import io.metaloom.cortex.s3.FakeS3ObjectStore;
import io.metaloom.cortex.s3.event.S3ChangeHint;
import io.metaloom.cortex.s3.event.S3EventBuffer;
import io.metaloom.fs.FileState;

public class S3DifferentialScannerTest {

	private static final String BUCKET = "media";
	private static final String ENDPOINT = "http://minio:9000";
	private static final long HOUR = 60L * 60 * 1000;
	private static final long RECONCILE_MS = 6 * HOUR;

	@TempDir
	Path indexDir;

	private FakeS3ObjectStore store;
	private S3EventBuffer buffer;

	@BeforeEach
	public void setup() {
		store = new FakeS3ObjectStore()
			.put(BUCKET, "2026/a.mp4", "a")
			.put(BUCKET, "2026/b.mp4", "b");
		buffer = new S3EventBuffer();
	}

	private S3DifferentialScanner scanner() {
		return new S3DifferentialScanner(store, new S3ObjectIndexStore(), buffer, indexDir, ENDPOINT, RECONCILE_MS);
	}

	private S3Selection selection(boolean startAfter, boolean useEvents) {
		return new S3Selection(BUCKET, "2026/", Set.of(), Set.of(FileState.NEW, FileState.MODIFIED),
			startAfter, useEvents);
	}

	// --- mode selection ------------------------------------------------------------------

	@Test
	public void testFirstScanIsAlwaysAFullList() throws Exception {
		S3ScanResult result = scanner().scan(selection(true, true), HOUR);

		assertThat(result.mode()).isEqualTo(ScanMode.FULL_LIST);
		assertThat(result.size()).isEqualTo(2);
	}

	@Test
	public void testReconcileIntervalForcesAFullListEvenWhenEventsAreOn() throws Exception {
		S3DifferentialScanner scanner = scanner();
		long start = 10 * HOUR;
		scanner.scan(selection(false, true), start);
		store.put(BUCKET, "2026/c.mp4", "c");

		// No events buffered, but the reconcile window has elapsed - a full listing must happen
		// anyway, which is what recovers objects whose notifications were lost.
		S3ScanResult result = scanner.scan(selection(false, true), start + RECONCILE_MS);

		assertThat(result.mode()).isEqualTo(ScanMode.FULL_LIST);
		assertThat(result.objects()).extracting(r -> r.key()).containsExactly("2026/c.mp4");
	}

	@Test
	public void testWithinTheReconcileWindowEventsAreTrusted() throws Exception {
		S3DifferentialScanner scanner = scanner();
		long start = 10 * HOUR;
		scanner.scan(selection(false, true), start);
		store.resetCounters();

		store.put(BUCKET, "2026/c.mp4", "c");
		buffer.record(S3ChangeHint.created(BUCKET, "2026/c.mp4"));
		S3ScanResult result = scanner.scan(selection(false, true), start + HOUR);

		assertThat(result.mode()).isEqualTo(ScanMode.EVENTS);
		assertThat(result.objects()).extracting(r -> r.key()).containsExactly("2026/c.mp4");
		// The whole point: no listing at all, just one HEAD for the reported key.
		assertThat(store.listCalls).hasValue(0);
		assertThat(store.headCalls).hasValue(1);
	}

	@Test
	public void testEventPathWithNoHintsEmitsNothingAndStillDoesNotList() throws Exception {
		S3DifferentialScanner scanner = scanner();
		long start = 10 * HOUR;
		scanner.scan(selection(false, true), start);
		store.resetCounters();

		S3ScanResult result = scanner.scan(selection(false, true), start + HOUR);

		assertThat(result.mode()).isEqualTo(ScanMode.EVENTS);
		assertThat(result.size()).isZero();
		assertThat(store.listCalls).hasValue(0);
	}

	@Test
	public void testRemovalEventDropsTheObjectFromTheIndex() throws Exception {
		S3Selection withDeletes = new S3Selection(BUCKET, "2026/", Set.of(),
			Set.of(FileState.NEW, FileState.MODIFIED, FileState.DELETED), false, true);
		S3DifferentialScanner scanner = scanner();
		long start = 10 * HOUR;
		scanner.scan(withDeletes, start);

		store.remove(BUCKET, "2026/b.mp4");
		buffer.record(S3ChangeHint.removed(BUCKET, "2026/b.mp4"));
		S3ScanResult result = scanner.scan(withDeletes, start + HOUR);

		assertThat(result.mode()).isEqualTo(ScanMode.EVENTS);
		assertThat(result.objects()).extracting(r -> r.key()).containsExactly("2026/b.mp4");
		assertThat(result.states()).containsEntry("s3://media/2026/b.mp4", FileState.DELETED);
	}

	@Test
	public void testAnObjectCreatedThenDeletedBeforeTheScanIsTreatedAsRemoved() throws Exception {
		S3Selection withDeletes = new S3Selection(BUCKET, "2026/", Set.of(),
			Set.of(FileState.NEW, FileState.DELETED), false, true);
		S3DifferentialScanner scanner = scanner();
		long start = 10 * HOUR;
		scanner.scan(withDeletes, start);

		// A create hint whose object no longer exists - events are replayed and reordered, which
		// is why the scanner HEADs rather than trusting the payload.
		buffer.record(S3ChangeHint.created(BUCKET, "2026/b.mp4"));
		store.remove(BUCKET, "2026/b.mp4");
		S3ScanResult result = scanner.scan(withDeletes, start + HOUR);

		assertThat(result.states()).containsEntry("s3://media/2026/b.mp4", FileState.DELETED);
	}

	@Test
	public void testDegradedBufferFallsBackToAFullListing() throws Exception {
		S3EventBuffer tiny = new S3EventBuffer(1);
		S3DifferentialScanner scanner = new S3DifferentialScanner(store, new S3ObjectIndexStore(), tiny,
			indexDir, ENDPOINT, RECONCILE_MS);
		long start = 10 * HOUR;
		scanner.scan(selection(false, true), start);

		tiny.record(S3ChangeHint.created(BUCKET, "k1"));
		tiny.record(S3ChangeHint.created(BUCKET, "k2"));
		assertThat(tiny.isDegraded(BUCKET)).isTrue();

		S3ScanResult result = scanner.scan(selection(false, true), start + HOUR);

		assertThat(result.mode()).isEqualTo(ScanMode.FULL_LIST);
		// The listing re-established the truth, so the fast path is usable again.
		assertThat(tiny.isDegraded(BUCKET)).isFalse();
	}

	@Test
	public void testResumePathListsOnlyTheTail() throws Exception {
		S3DifferentialScanner scanner = scanner();
		long start = 10 * HOUR;
		scanner.scan(selection(true, false), start);

		store.put(BUCKET, "2026/z-new.mp4", "z");
		S3ScanResult result = scanner.scan(selection(true, false), start + HOUR);

		assertThat(result.mode()).isEqualTo(ScanMode.RESUME);
		assertThat(result.objects()).extracting(r -> r.key()).containsExactly("2026/z-new.mp4");
	}

	@Test
	public void testResumePathCannotSeeEditsToOlderKeys() throws Exception {
		// The documented limitation of startAfter, pinned so it is a known trade-off rather than
		// a surprise: only the reconcile listing recovers this.
		S3DifferentialScanner scanner = scanner();
		long start = 10 * HOUR;
		scanner.scan(selection(true, false), start);

		store.put(BUCKET, "2026/a.mp4", "a-changed");
		S3ScanResult result = scanner.scan(selection(true, false), start + HOUR);

		assertThat(result.mode()).isEqualTo(ScanMode.RESUME);
		assertThat(result.size()).isZero();

		// ... and the reconcile pass catches it.
		S3ScanResult reconciled = scanner.scan(selection(true, false), start + RECONCILE_MS);
		assertThat(reconciled.mode()).isEqualTo(ScanMode.FULL_LIST);
		assertThat(reconciled.objects()).extracting(r -> r.key()).containsExactly("2026/a.mp4");
	}

	// --- index behaviour -----------------------------------------------------------------

	@Test
	public void testIndexRecordsObjectsThatWereFilteredOut() throws Exception {
		// An object excluded by emitStates must still be indexed, or it would look NEW forever.
		S3Selection presentOnly = new S3Selection(BUCKET, "2026/", Set.of(), Set.of(FileState.PRESENT), false, false);
		S3DifferentialScanner scanner = scanner();

		S3ScanResult first = scanner.scan(presentOnly, HOUR);
		assertThat(first.size()).isZero();

		S3ScanResult second = scanner.scan(presentOnly, 2 * HOUR);
		assertThat(second.objects()).extracting(r -> r.key())
			.containsExactlyInAnyOrder("2026/a.mp4", "2026/b.mp4");
	}

	@Test
	public void testIndexIsScopedPerEndpointBucketAndPrefix() {
		S3DifferentialScanner scanner = scanner();
		Path a = scanner.indexFileFor(selection(false, false));
		Path b = scanner.indexFileFor(new S3Selection(BUCKET, "2025/", Set.of(), null, false, false));
		Path c = scanner.indexFileFor(new S3Selection("other", "2026/", Set.of(), null, false, false));

		assertThat(a).isNotEqualTo(b).isNotEqualTo(c);
		assertThat(a.getParent()).isEqualTo(indexDir);
	}

	@Test
	public void testTheSameBucketOnTwoEndpointsDoesNotShareAnIndex() {
		S3DifferentialScanner one = scanner();
		S3DifferentialScanner two = new S3DifferentialScanner(store, new S3ObjectIndexStore(), buffer,
			indexDir, "http://other:9000", RECONCILE_MS);

		assertThat(one.indexFileFor(selection(false, false)))
			.isNotEqualTo(two.indexFileFor(selection(false, false)));
	}

	@Test
	public void testIndexSurvivesAcrossScannerInstances() throws Exception {
		scanner().scan(selection(false, false), HOUR);

		// A fresh scanner (i.e. a restarted worker) must not reprocess the bucket.
		S3ScanResult result = scanner().scan(selection(false, false), 2 * HOUR);

		assertThat(result.size()).isZero();
	}

	@Test
	public void testACorruptIndexIsTreatedAsEmptyRatherThanFailing() throws Exception {
		S3DifferentialScanner scanner = scanner();
		scanner.scan(selection(false, false), HOUR);
		java.nio.file.Files.writeString(scanner.indexFileFor(selection(false, false)), "not avro");

		S3ScanResult result = scanner.scan(selection(false, false), 2 * HOUR);

		assertThat(result.size()).isEqualTo(2);
	}
}
