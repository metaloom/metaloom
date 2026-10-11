package io.metaloom.loom.rest.service.person;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.metaloom.loom.db.model.person.PersonReference;

/**
 * Decides which known persons a probe vector resembles.
 *
 * <p>
 * <strong>One reference per person</strong>: the normalised mean of every reference vector the person has in the space - the centroids of the
 * clusters reviewers confirmed to be them and the prints of their own samples. Averaging is enrolment as the recognition study measured it (about 30
 * seconds of speech from several occasions, pooled), and it is what keeps a person with fifty confirmed clusters from winning on volume: taking the
 * best of fifty comparisons instead would raise their false-match rate with every cluster a reviewer confirms.
 * </p>
 *
 * <p>
 * Pure on purpose - no database, no I/O - so the arithmetic that decides what a reviewer is told can be tested exhaustively.
 * </p>
 */
public final class PersonMatcher {

	private PersonMatcher() {
	}

	/**
	 * One person a probe resembles.
	 *
	 * @param personUuid who
	 * @param score      cosine similarity of the probe to the person's reference
	 * @param references how many vectors the reference was built from
	 */
	public record Candidate(UUID personUuid, float score, int references) {
	}

	/**
	 * A person's pooled reference.
	 *
	 * @param personUuid who
	 * @param vector     unit length
	 * @param count      vectors pooled into it
	 */
	public record PooledReference(UUID personUuid, float[] vector, int count) {
	}

	/**
	 * Pool the references per person. A vector whose length differs from {@code dimensions}, or that is all zeros, is skipped: it cannot be in the
	 * same space as the probes, whatever its row claims.
	 */
	public static List<PooledReference> pool(List<PersonReference> references, int dimensions) {
		Map<UUID, float[]> sums = new LinkedHashMap<>();
		Map<UUID, Integer> counts = new LinkedHashMap<>();
		for (PersonReference reference : references) {
			float[] unit = unit(reference.vector());
			if (unit == null || unit.length != dimensions) {
				continue;
			}
			float[] sum = sums.computeIfAbsent(reference.personUuid(), k -> new float[dimensions]);
			for (int i = 0; i < dimensions; i++) {
				sum[i] += unit[i];
			}
			counts.merge(reference.personUuid(), 1, Integer::sum);
		}
		List<PooledReference> pooled = new ArrayList<>();
		sums.forEach((person, sum) -> {
			float[] unit = unit(sum);
			if (unit != null) {
				pooled.add(new PooledReference(person, unit, counts.get(person)));
			}
		});
		return pooled;
	}

	/**
	 * The persons a probe resembles at or above {@code threshold}, best first, at most {@code limit}. Ties are broken by person uuid so the answer is
	 * stable across calls.
	 */
	public static List<Candidate> match(float[] probe, List<PooledReference> references, float threshold, int limit) {
		float[] unit = unit(probe);
		if (unit == null || limit <= 0) {
			return List.of();
		}
		List<Candidate> candidates = new ArrayList<>();
		for (PooledReference reference : references) {
			if (reference.vector().length != unit.length) {
				continue;
			}
			float score = dot(unit, reference.vector());
			if (score >= threshold) {
				candidates.add(new Candidate(reference.personUuid(), score, reference.count()));
			}
		}
		candidates.sort(Comparator.comparing(Candidate::score).reversed().thenComparing(Candidate::personUuid));
		return candidates.size() > limit ? List.copyOf(candidates.subList(0, limit)) : candidates;
	}

	/** Unit-length copy, or null for a missing, empty, non-finite or zero vector. */
	static float[] unit(Float[] vector) {
		if (vector == null) {
			return null;
		}
		float[] values = new float[vector.length];
		for (int i = 0; i < vector.length; i++) {
			if (vector[i] == null) {
				return null;
			}
			values[i] = vector[i];
		}
		return unit(values);
	}

	static float[] unit(float[] vector) {
		if (vector == null || vector.length == 0) {
			return null;
		}
		double norm = 0;
		for (float v : vector) {
			if (Float.isNaN(v) || Float.isInfinite(v)) {
				return null;
			}
			norm += (double) v * v;
		}
		if (norm == 0) {
			return null;
		}
		double scale = 1.0 / Math.sqrt(norm);
		float[] out = new float[vector.length];
		for (int i = 0; i < vector.length; i++) {
			out[i] = (float) (vector[i] * scale);
		}
		return out;
	}

	private static float dot(float[] a, float[] b) {
		double sum = 0;
		for (int i = 0; i < a.length; i++) {
			sum += (double) a[i] * b[i];
		}
		return (float) sum;
	}
}
