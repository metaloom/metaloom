package io.metaloom.loom.rest.service.person;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.model.person.PersonReference;
import io.metaloom.loom.rest.service.person.PersonMatcher.Candidate;
import io.metaloom.loom.rest.service.person.PersonMatcher.PooledReference;

public class PersonMatcherTest {

	private static final UUID ANNA = UUID.fromString("00000000-0000-0000-0000-00000000000a");

	private static final UUID BERT = UUID.fromString("00000000-0000-0000-0000-00000000000b");

	private static PersonReference ref(UUID person, Float... vector) {
		return new PersonReference(person, UUID.randomUUID(), PersonReference.SOURCE_CLUSTER, vector);
	}

	/** References are pooled per person into one unit vector - the mean of the unit vectors, renormalised. */
	@Test
	public void testPoolAveragesUnitVectorsPerPerson() {
		List<PooledReference> pooled = PersonMatcher.pool(List.of(ref(ANNA, 2f, 0f), ref(ANNA, 0f, 5f), ref(BERT, -1f, 0f)), 2);
		assertEquals(2, pooled.size());
		PooledReference anna = pooled.get(0);
		assertEquals(ANNA, anna.personUuid());
		assertEquals(2, anna.count());
		float h = (float) Math.sqrt(0.5);
		assertEquals(h, anna.vector()[0], 1e-5, "Magnitudes do not weigh in: (1,0) and (0,1) pool to the diagonal");
		assertEquals(h, anna.vector()[1], 1e-5);
	}

	/** A reference of another length, or a degenerate one, cannot be in the probe's space and is skipped. */
	@Test
	public void testPoolSkipsVectorsOutsideTheSpace() {
		List<PooledReference> pooled = PersonMatcher.pool(List.of(ref(ANNA, 1f, 0f, 0f), ref(BERT, 0f, 0f), ref(BERT, 0f, Float.NaN)), 2);
		assertTrue(pooled.isEmpty());
	}

	/** Candidates at or above the threshold, best first, capped at the limit. */
	@Test
	public void testMatchOrderIsByScore() {
		List<PooledReference> pooled = PersonMatcher.pool(List.of(ref(ANNA, 1f, 0f), ref(BERT, 0.6f, 0.8f)), 2);
		List<Candidate> both = PersonMatcher.match(new float[] { 0.8f, 0.6f }, pooled, 0.5f, 5);
		assertEquals(BERT, both.get(0).personUuid());
		assertEquals(0.96f, both.get(0).score(), 1e-5);
		assertEquals(ANNA, both.get(1).personUuid());
		assertEquals(0.8f, both.get(1).score(), 1e-5);

		List<Candidate> strict = PersonMatcher.match(new float[] { 0.8f, 0.6f }, pooled, 0.9f, 5);
		assertEquals(1, strict.size(), "Anna's 0.8 is below a 0.9 threshold");
		List<Candidate> capped = PersonMatcher.match(new float[] { 0.8f, 0.6f }, pooled, 0.5f, 1);
		assertEquals(1, capped.size(), "The limit applies after ranking");
		assertEquals(BERT, capped.get(0).personUuid());
	}

	/** Equal scores are ordered by person so two calls answer identically. */
	@Test
	public void testTiesAreStable() {
		List<PooledReference> pooled = PersonMatcher.pool(List.of(ref(BERT, 1f, 0f), ref(ANNA, 1f, 0f)), 2);
		List<Candidate> tied = PersonMatcher.match(new float[] { 1f, 0f }, pooled, 0f, 5);
		assertEquals(ANNA, tied.get(0).personUuid());
		assertEquals(BERT, tied.get(1).personUuid());
	}

	@Test
	public void testDegenerateProbeMatchesNobody() {
		List<PooledReference> pooled = PersonMatcher.pool(List.of(ref(ANNA, 1f, 0f)), 2);
		assertTrue(PersonMatcher.match(new float[] { 0f, 0f }, pooled, -1f, 5).isEmpty());
		assertTrue(PersonMatcher.match(new float[] { 1f, 0f, 0f }, pooled, -1f, 5).isEmpty(), "Another length is another space");
		assertNull(PersonMatcher.unit(new Float[] { 1f, null }));
	}
}
