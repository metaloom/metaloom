package io.metaloom.loom.vector.lucene;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.loom.api.search.VectorHit;
import io.metaloom.loom.api.search.VectorQuery;
import io.metaloom.loom.api.search.VectorRecord;
import io.metaloom.loom.api.search.VectorSpace;

public class LuceneVectorIndexTest {

	private static final String TYPE = "face";
	private static final String MODEL = "inspireface-r18";
	private static final int DIM = 512;
	private static final float THRESHOLD = 0.10f;

	private static final VectorSpace SPACE = new VectorSpace(TYPE, MODEL, DIM);

	private LuceneVectorIndex index;

	private float[] base;
	private float[] near;
	private float[] far;

	@BeforeEach
	public void setup(@TempDir Path dir) {
		index = new LuceneVectorIndex(dir.resolve("index"));
		base = filled(DIM, 0.1f);
		near = filled(DIM, 0.1f);
		near[0] = 0.1001f; // almost identical -> high k-NN score
		far = filled(DIM, 0.9f); // very different -> score below threshold
	}

	@AfterEach
	public void teardown() {
		if (index != null) {
			index.close();
		}
	}

	@Test
	public void shouldReturnNearNeighbourAboveThresholdAndDropDissimilar() {
		assertThat(index.isAvailable()).isTrue();
		assertThat(index.providerName()).isEqualTo("lucene");

		VectorRecord nearRecord = record(SPACE, base);
		VectorRecord farRecord = record(SPACE, far);
		index.indexAll(List.of(nearRecord, farRecord));

		List<VectorHit> hits = index.query(new VectorQuery(SPACE, near, 10, THRESHOLD));
		assertThat(hits).extracting(VectorHit::embeddingUuid)
			.contains(nearRecord.embeddingUuid())
			.doesNotContain(farRecord.embeddingUuid());
	}

	@Test
	public void shouldCarryTheDetectionUuidOntoTheHit() {
		// A face hit is only useful if it resolves back to the box it came from - "this asset matches"
		// is a much weaker answer than "this face, in this frame, matches".
		UUID detectionUuid = UUID.randomUUID();
		VectorRecord stored = new VectorRecord(UUID.randomUUID(), UUID.randomUUID(), detectionUuid, SPACE, base);
		index.index(stored);
		index.commit();

		List<VectorHit> hits = index.query(new VectorQuery(SPACE, near, 10, THRESHOLD));
		assertThat(hits).hasSize(1);
		assertThat(hits.get(0).detectionUuid()).isEqualTo(detectionUuid);
		assertThat(hits.get(0).assetUuid()).isEqualTo(stored.assetUuid());
	}

	@Test
	public void shouldNeverAnswerAcrossModels() {
		// The whole point of the (type, model, dimensions) key. Two models produce vectors of the same
		// length and mean entirely different things by them; a distance between them is a plausible
		// number with no meaning, and returning one would be a silent, unnoticeable wrong answer.
		VectorSpace otherModel = new VectorSpace(TYPE, "some-newer-model", DIM);
		VectorRecord oldModel = record(SPACE, base);
		VectorRecord newModel = record(otherModel, base);
		index.indexAll(List.of(oldModel, newModel));

		assertThat(index.query(new VectorQuery(SPACE, near, 10, THRESHOLD)))
			.extracting(VectorHit::embeddingUuid)
			.containsExactly(oldModel.embeddingUuid());

		assertThat(index.query(new VectorQuery(otherModel, near, 10, THRESHOLD)))
			.extracting(VectorHit::embeddingUuid)
			.containsExactly(newModel.embeddingUuid());
	}

	@Test
	public void shouldNeverAnswerAcrossTypes() {
		VectorSpace clip = new VectorSpace("clip", MODEL, DIM);
		VectorRecord faceRecord = record(SPACE, base);
		index.indexAll(List.of(faceRecord, record(clip, base)));

		assertThat(index.query(new VectorQuery(SPACE, near, 10, THRESHOLD)))
			.extracting(VectorHit::embeddingUuid)
			.containsExactly(faceRecord.embeddingUuid());
	}

	@Test
	public void shouldHoldTwoDimensionsInOneIndex() {
		// A model change that also changes the vector length must not need a second index. Lucene fixes
		// the dimension per field name, so the length is part of the field - both live here at once.
		VectorSpace small = new VectorSpace(TYPE, "tiny-model", 128);
		VectorRecord bigRecord = record(SPACE, base);
		VectorRecord smallRecord = record(small, filled(128, 0.1f));
		index.indexAll(List.of(bigRecord, smallRecord));

		assertThat(index.query(new VectorQuery(SPACE, near, 10, THRESHOLD)))
			.extracting(VectorHit::embeddingUuid)
			.containsExactly(bigRecord.embeddingUuid());
		assertThat(index.query(new VectorQuery(small, filled(128, 0.1f), 10, THRESHOLD)))
			.extracting(VectorHit::embeddingUuid)
			.containsExactly(smallRecord.embeddingUuid());
	}

	@Test
	public void shouldUpsertRatherThanDuplicateOnReindex() {
		UUID embeddingUuid = UUID.randomUUID();
		UUID assetUuid = UUID.randomUUID();
		index.index(new VectorRecord(embeddingUuid, assetUuid, null, SPACE, far));
		index.index(new VectorRecord(embeddingUuid, assetUuid, null, SPACE, base));
		index.commit();

		// Re-running the node rewrote the row rather than adding a second copy, and the query sees the
		// NEW vector: the old one scored below the threshold and would have been dropped entirely.
		List<VectorHit> hits = index.query(new VectorQuery(SPACE, near, 10, THRESHOLD));
		assertThat(hits).hasSize(1);
		assertThat(hits.get(0).embeddingUuid()).isEqualTo(embeddingUuid);
	}

	@Test
	public void shouldExcludeTheQueryAsset() {
		UUID assetUuid = UUID.randomUUID();
		VectorRecord own = new VectorRecord(UUID.randomUUID(), assetUuid, null, SPACE, base);
		VectorRecord other = record(SPACE, base);
		index.indexAll(List.of(own, other));

		// Without the exclusion the best match for "who else looks like this?" is always the query itself.
		List<VectorHit> hits = index.query(new VectorQuery(SPACE, near, 10, THRESHOLD, assetUuid));
		assertThat(hits).extracting(VectorHit::embeddingUuid)
			.containsExactly(other.embeddingUuid());
	}

	@Test
	public void shouldRemoveByEmbeddingAndByAsset() {
		UUID assetUuid = UUID.randomUUID();
		VectorRecord first = new VectorRecord(UUID.randomUUID(), assetUuid, null, SPACE, base);
		VectorRecord second = new VectorRecord(UUID.randomUUID(), assetUuid, null, SPACE, base);
		VectorRecord survivor = record(SPACE, base);
		index.indexAll(List.of(first, second, survivor));

		index.removeByEmbedding(first.embeddingUuid());
		index.commit();
		assertThat(index.query(new VectorQuery(SPACE, near, 10, THRESHOLD)))
			.extracting(VectorHit::embeddingUuid)
			.containsExactlyInAnyOrder(second.embeddingUuid(), survivor.embeddingUuid());

		// Deleting an asset must take every vector of that asset - and nothing else.
		index.removeByAsset(assetUuid);
		index.commit();
		assertThat(index.query(new VectorQuery(SPACE, near, 10, THRESHOLD)))
			.extracting(VectorHit::embeddingUuid)
			.containsExactly(survivor.embeddingUuid());
	}

	@Test
	public void shouldReproduceTheIndexOnRebuild() {
		VectorRecord stale = record(SPACE, base);
		index.index(stale);
		index.commit();

		VectorRecord kept = record(SPACE, base);
		index.rebuild(Stream.of(kept));

		// A rebuild replaces the content wholesale; this is what makes it the recovery path for a lost
		// index and the migration path for a changed backend.
		assertThat(index.query(new VectorQuery(SPACE, near, 10, THRESHOLD)))
			.extracting(VectorHit::embeddingUuid)
			.containsExactly(kept.embeddingUuid());
	}

	@Test
	public void shouldReportUnavailableWithoutThrowing() {
		index.close();
		// Callers ask this precisely when something is broken, so it must answer rather than throw - and
		// the mutations must degrade quietly instead of taking the caller's write down with them.
		assertThat(index.isAvailable()).isFalse();
		index.index(record(SPACE, base));
		index.removeByAsset(UUID.randomUUID());
		index.commit();
		assertThat(index.query(new VectorQuery(SPACE, near, 10, THRESHOLD))).isEmpty();
	}

	private static VectorRecord record(VectorSpace space, float[] vector) {
		return new VectorRecord(UUID.randomUUID(), UUID.randomUUID(), null, space, vector);
	}

	private static float[] filled(int dim, float value) {
		float[] vector = new float[dim];
		java.util.Arrays.fill(vector, value);
		return vector;
	}
}
