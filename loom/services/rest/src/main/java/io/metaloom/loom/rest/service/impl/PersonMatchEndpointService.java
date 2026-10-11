package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.READ_PERSON;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.api.attachment.AttachmentType;
import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.cluster.Cluster;
import io.metaloom.loom.db.model.person.PersonPrintDao.PendingSample;
import io.metaloom.loom.db.model.person.PersonReference;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.builder.PersonModelBuilder;
import io.metaloom.loom.rest.model.person.PersonMatchCandidate;
import io.metaloom.loom.rest.model.person.PersonMatchRequest;
import io.metaloom.loom.rest.model.person.PersonMatchResponse;
import io.metaloom.loom.rest.model.person.PersonMatchResult;
import io.metaloom.loom.rest.model.person.PersonPendingSample;
import io.metaloom.loom.rest.service.AbstractEndpointService;
import io.metaloom.loom.rest.service.person.PersonMatcher;
import io.metaloom.loom.rest.service.person.PersonMatcher.Candidate;
import io.metaloom.loom.rest.service.person.PersonMatcher.PooledReference;
import io.metaloom.loom.rest.validation.LoomModelValidator;

/**
 * {@code POST /api/v1/person-match} - which known persons a set of face or voice vectors resembles.
 *
 * <p>
 * The producing nodes call this with the centroid of every cluster they find, and record the best candidate as a <em>suggestion</em> on the cluster.
 * Nothing here writes: matching proposes, a reviewer decides (V2.115).
 * </p>
 *
 * <p>
 * The references are read per request rather than kept in an index. A directory holds hundreds to a few thousand persons, each with a handful of
 * confirmed clusters and samples - a brute-force comparison over that is cheaper than keeping a second copy consistent with every confirm, detach,
 * delete and upload. The {@code VectorIndex} is the path when that stops being true.
 * </p>
 *
 * <p>
 * Gated on {@code READ_PERSON}: the answer names people.
 * </p>
 */
@Singleton
public class PersonMatchEndpointService extends AbstractEndpointService {

	/** Without a caller's threshold. Conservative on purpose - callers that know their model's calibration pass their own. */
	static final float DEFAULT_THRESHOLD = 0.5f;

	static final int DEFAULT_LIMIT = 3;

	static final int DEFAULT_PENDING_LIMIT = 20;

	private final DaoCollection daos;

	@Inject
	public PersonMatchEndpointService(DaoCollection daos, LoomModelBuilder modelBuilder, LoomModelValidator validator) {
		super(modelBuilder, validator);
		this.daos = daos;
	}

	public void match(LoomRoutingContext lrc) {
		checkPerm(lrc, READ_PERSON, () -> {
			PersonMatchRequest request = lrc.requestBody(PersonMatchRequest.class);
			validator.validate(request);
			int dimensions = request.getVectors().get(0).length;
			for (Float[] vector : request.getVectors()) {
				if (vector.length != dimensions) {
					// Probes of different lengths cannot come from one model, so the request cannot mean what it says.
					throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST, "Every probe vector must have the same length");
				}
			}
			String type = request.getType();
			String model = request.getModel();

			List<PersonReference> references = new ArrayList<>(daos.clusterDao().listConfirmedReferences(type, model));
			references.addAll(daos.personPrintDao().listUsableReferences(type, model));
			List<PooledReference> pooled = PersonMatcher.pool(references, dimensions);

			float threshold = request.getThreshold() == null ? DEFAULT_THRESHOLD : request.getThreshold();
			int limit = request.getLimit() == null ? DEFAULT_LIMIT : request.getLimit();

			List<List<Candidate>> perProbe = new ArrayList<>();
			Set<UUID> named = new LinkedHashSet<>();
			for (Float[] vector : request.getVectors()) {
				List<Candidate> candidates = PersonMatcher.match(unbox(vector), pooled, threshold, limit);
				candidates.forEach(c -> named.add(c.personUuid()));
				perProbe.add(candidates);
			}
			Map<UUID, String> names = named.isEmpty() ? Map.of() : daos.personDao().loadDisplayNames(named);

			PersonMatchResponse response = new PersonMatchResponse()
				.setPersonCount(pooled.size())
				.setReferenceCount(pooled.stream().mapToInt(PooledReference::count).sum());
			for (int i = 0; i < perProbe.size(); i++) {
				PersonMatchResult result = new PersonMatchResult().setIndex(i);
				for (Candidate candidate : perProbe.get(i)) {
					result.getCandidates().add(new PersonMatchCandidate()
						.setPersonUuid(candidate.personUuid().toString())
						.setName(names.get(candidate.personUuid()))
						.setScore(candidate.score())
						.setReferences(candidate.references()));
				}
				response.getResults().add(result);
			}

			AttachmentType sampleType = sampleTypeFor(type);
			int pendingLimit = request.getPendingLimit() == null ? DEFAULT_PENDING_LIMIT : request.getPendingLimit();
			if (sampleType != null && pendingLimit > 0) {
				for (PendingSample pending : daos.personPrintDao().listUnenrolled(type, model, sampleType, pendingLimit)) {
					response.getPendingSamples().add(new PersonPendingSample()
						.setPersonUuid(pending.personUuid().toString())
						.setSampleUuid(pending.attachmentUuid().toString())
						.setMimeType(pending.mimeType())
						.setUrl(sampleUrl(sampleType, pending)));
				}
			}
			lrc.send(response);
		});
	}

	/** Which of a person's samples can be embedded into a print comparable with this cluster type, or null when none can. */
	static AttachmentType sampleTypeFor(String clusterType) {
		if (Cluster.TYPE_SPEAKER.equals(clusterType)) {
			return AttachmentType.PERSON_VOICE;
		}
		if (Cluster.TYPE_FACE.equals(clusterType)) {
			return AttachmentType.PERSON_IMAGE;
		}
		return null;
	}

	private static String sampleUrl(AttachmentType sampleType, PendingSample pending) {
		return sampleType == AttachmentType.PERSON_VOICE
			? PersonModelBuilder.personVoiceSampleUrl(pending.personUuid(), pending.attachmentUuid())
			: io.metaloom.loom.rest.RESTConstants.API_V1_PATH + "/persons/" + pending.personUuid() + "/images/" + pending.attachmentUuid() + "/data";
	}

	private static float[] unbox(Float[] vector) {
		float[] out = new float[vector.length];
		for (int i = 0; i < vector.length; i++) {
			out[i] = vector[i];
		}
		return out;
	}
}
