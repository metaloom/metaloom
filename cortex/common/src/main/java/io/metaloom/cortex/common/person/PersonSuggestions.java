package io.metaloom.cortex.common.person;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.client.common.LoomBinaryResponse;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.rest.model.cluster.ClusterResponse;
import io.metaloom.loom.rest.model.person.PersonMatchCandidate;
import io.metaloom.loom.rest.model.person.PersonMatchRequest;
import io.metaloom.loom.rest.model.person.PersonMatchResponse;
import io.metaloom.loom.rest.model.person.PersonMatchResult;
import io.metaloom.loom.rest.model.person.PersonPendingSample;
import io.metaloom.loom.rest.model.person.PersonPrintCreateRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Person recognition for a producing node: which known person each cluster it found most resembles, and the {@code persons} output that says so.
 *
 * <p>
 * Shared by {@code facedetect} and {@code voiceprint}, which differ only in the modality and in how they turn one of a person's samples into a vector
 * - the {@link Enroller}. Everything else is the same three steps: ask Loom who the centroids resemble, embed the person samples Loom says have no
 * print in this node's model yet, and ask again if any were embedded.
 * </p>
 *
 * <p>
 * <strong>Suggestions only, and best-effort.</strong> The result is written as {@code cluster.suggested_person_uuid}, never as the verdict; and a
 * failure anywhere here leaves the clusters unsuggested rather than failing the node - recognising a known person is a convenience on top of finding
 * the faces or voices, which are the node's actual job.
 * </p>
 */
public final class PersonSuggestions {

	private static final Logger log = LoggerFactory.getLogger(PersonSuggestions.class);

	/** Cluster type of a face; the {@code type} of a face match. */
	public static final String TYPE_FACE = "face";

	/** Cluster type of a voice; the {@code type} of a voice match. */
	public static final String TYPE_SPEAKER = "speaker";

	/**
	 * How many person samples one item may embed. A new deployment with a backlog of uploads drains it over the first few items rather than stalling
	 * the first one; an established one has none pending and pays nothing.
	 */
	public static final int MAX_ENROLMENTS_PER_ITEM = 10;

	private PersonSuggestions() {
	}

	/**
	 * Turns one of a person's samples into a reference vector with the node's own model.
	 */
	@FunctionalInterface
	public interface Enroller {

		/**
		 * @param sample the sample's bytes, downloaded to a temporary file the caller deletes
		 * @return the vector, unit length or not
		 * @throws UnusableSampleException when the model cannot use this sample - no speech, no face - which is recorded so it is not retried
		 * @throws Exception                on a transient failure, which is not recorded and is retried on a later item
		 */
		float[] embed(PersonPendingSample sample, Path file) throws Exception;
	}

	/** The sample holds nothing the model can use. The message is recorded on the print as the reason. */
	public static class UnusableSampleException extends Exception {

		private static final long serialVersionUID = 1L;

		public UnusableSampleException(String reason) {
			super(reason);
		}
	}

	/**
	 * The best candidate for one probe, or none.
	 *
	 * @param personUuid who
	 * @param name       their display name, when they have one
	 * @param score      the match score
	 */
	public record Suggestion(UUID personUuid, String name, float score) {
	}

	/**
	 * The best candidate per centroid, in centroid order; null entries where nobody resembles it closely enough, or when matching failed.
	 *
	 * @param type      {@link #TYPE_FACE} or {@link #TYPE_SPEAKER}
	 * @param model     the model the centroids were computed with - the same id the node writes on its clusters
	 * @param centroids one per cluster; a null entry is a cluster with no vector and gets no suggestion
	 * @param threshold minimum score for a suggestion
	 * @param enroller  how this node embeds a person sample; null to skip enrolment
	 */
	public static List<Suggestion> suggest(LoomClient client, String type, String model, List<float[]> centroids, float threshold, Enroller enroller) {
		List<Suggestion> none = new ArrayList<>();
		centroids.forEach(c -> none.add(null));
		if (client == null || model == null || model.isBlank()) {
			return none;
		}
		List<Integer> probeIndex = new ArrayList<>();
		PersonMatchRequest request = new PersonMatchRequest()
			.setType(type)
			.setModel(model)
			.setThreshold(threshold)
			.setLimit(1)
			.setPendingLimit(enroller == null ? 0 : MAX_ENROLMENTS_PER_ITEM);
		for (int i = 0; i < centroids.size(); i++) {
			float[] centroid = centroids.get(i);
			if (centroid != null && centroid.length > 0) {
				request.getVectors().add(boxed(centroid));
				probeIndex.add(i);
			}
		}
		if (probeIndex.isEmpty()) {
			return none;
		}
		try {
			PersonMatchResponse response = client.matchPersons(request).sync().body();
			if (enroller != null && enrol(client, type, model, response.getPendingSamples(), enroller) > 0) {
				// Something new to compare against - most often the very sample somebody uploaded so that this recording would be recognised.
				response = client.matchPersons(request.setPendingLimit(0)).sync().body();
			}
			List<Suggestion> suggestions = new ArrayList<>(none);
			for (PersonMatchResult result : response.getResults()) {
				if (result.getCandidates().isEmpty() || result.getIndex() >= probeIndex.size()) {
					continue;
				}
				PersonMatchCandidate best = result.getCandidates().get(0);
				suggestions.set(probeIndex.get(result.getIndex()), new Suggestion(UUID.fromString(best.getPersonUuid()), best.getName(), best.getScore()));
			}
			return suggestions;
		} catch (Exception e) {
			log.warn("Person matching failed for {} clusters in model {}: {}", type, model, e.getMessage());
			return none;
		}
	}

	/**
	 * Embed the pending samples and store a print for each - the vector, or the reason there is none.
	 *
	 * @return how many usable prints were stored
	 */
	static int enrol(LoomClient client, String type, String model, List<PersonPendingSample> pending, Enroller enroller) {
		int stored = 0;
		for (PersonPendingSample sample : pending == null ? List.<PersonPendingSample> of() : pending) {
			UUID person = UUID.fromString(sample.getPersonUuid());
			UUID sampleUuid = UUID.fromString(sample.getSampleUuid());
			Path file = null;
			PersonPrintCreateRequest print = new PersonPrintCreateRequest().setType(type).setModel(model);
			try {
				file = download(client, type, person, sampleUuid);
				float[] vector = enroller.embed(sample, file);
				if (vector == null || vector.length == 0) {
					throw new UnusableSampleException("the model produced no vector");
				}
				print.setVector(boxed(vector));
				stored++;
			} catch (UnusableSampleException e) {
				print.setError(e.getMessage());
			} catch (Exception e) {
				// Transient - a download that failed, a sidecar that was busy. Left unrecorded so the next item tries again.
				log.warn("Could not embed sample {} of person {} in model {}: {}", sampleUuid, person, model, e.getMessage());
				continue;
			} finally {
				deleteQuietly(file);
			}
			try {
				if (TYPE_FACE.equals(type)) {
					client.storePersonImagePrint(person, sampleUuid, print).sync();
				} else {
					client.storePersonVoicePrint(person, sampleUuid, print).sync();
				}
			} catch (Exception e) {
				log.warn("Could not store the print of sample {} of person {}: {}", sampleUuid, person, e.getMessage());
				if (print.getVector() != null) {
					stored--;
				}
			}
		}
		return stored;
	}

	private static Path download(LoomClient client, String type, UUID person, UUID sample) throws Exception {
		Path file = Files.createTempFile("person-sample-", TYPE_FACE.equals(type) ? ".img" : ".audio");
		try (LoomBinaryResponse response = TYPE_FACE.equals(type)
			? client.downloadPersonImage(person, sample).sync().body()
			: client.downloadPersonVoiceSample(person, sample).sync().body();
			InputStream in = response.getStream()) {
			Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
			return file;
		} catch (Exception e) {
			deleteQuietly(file);
			throw e;
		}
	}

	/**
	 * The {@code struct/persons} payload: one entry per cluster that names a known person - the reviewer's verdict where there is one, the suggestion
	 * otherwise. Rejected clusters name nobody.
	 *
	 * <pre>
	 * [ { "personUuid": "...", "name": "Anna Meyer", "source": "voice", "status": "CONFIRMED",
	 *     "clusterUuid": "...", "clusterIndex": 0 },
	 *   { "personUuid": "...", "name": "Bert Huber", "source": "voice", "status": "SUGGESTED", "score": 0.61,
	 *     "clusterUuid": "...", "clusterIndex": 1 } ]
	 * </pre>
	 */
	public static JsonArray personsPayload(List<ClusterResponse> clusters) {
		JsonArray persons = new JsonArray();
		for (ClusterResponse cluster : clusters == null ? List.<ClusterResponse> of() : clusters) {
			String source = sourceOf(cluster.getType());
			if (source == null || "REJECTED".equals(cluster.getReviewStatus())) {
				continue;
			}
			JsonObject entry = new JsonObject()
				.put("source", source)
				.put("clusterUuid", cluster.getUuid() == null ? null : cluster.getUuid().toString())
				.put("clusterIndex", cluster.getClusterIndex());
			if ("CONFIRMED".equals(cluster.getReviewStatus()) && cluster.getPersonUuid() != null) {
				persons.add(entry.put("personUuid", cluster.getPersonUuid()).put("name", cluster.getPersonName()).put("status", "CONFIRMED"));
			} else if (cluster.getSuggestedPersonUuid() != null) {
				persons.add(entry.put("personUuid", cluster.getSuggestedPersonUuid()).put("name", cluster.getSuggestedPersonName())
					.put("status", "SUGGESTED").put("score", cluster.getSuggestedScore()));
			}
		}
		return persons;
	}

	private static String sourceOf(String clusterType) {
		if (clusterType == null) {
			return null;
		}
		return switch (clusterType.toLowerCase(Locale.ROOT)) {
			case TYPE_FACE -> "face";
			case TYPE_SPEAKER -> "voice";
			default -> null;
		};
	}

	private static Float[] boxed(float[] vector) {
		Float[] out = new Float[vector.length];
		for (int i = 0; i < vector.length; i++) {
			out[i] = vector[i];
		}
		return out;
	}

	private static void deleteQuietly(Path file) {
		if (file == null) {
			return;
		}
		try {
			Files.deleteIfExists(file);
		} catch (Exception e) {
			log.debug("Could not delete {}", file, e);
		}
	}
}
