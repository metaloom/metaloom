package io.metaloom.loom.rest.validation;

import java.util.List;

import io.metaloom.loom.rest.model.person.PersonAvatarRequest;
import io.metaloom.loom.rest.model.person.PersonCreateRequest;
import io.metaloom.loom.rest.model.person.PersonHeroVoiceRequest;
import io.metaloom.loom.rest.model.person.PersonImageImportRequest;
import io.metaloom.loom.rest.model.person.PersonMatchRequest;
import io.metaloom.loom.rest.model.person.PersonPrintCreateRequest;
import io.metaloom.loom.rest.model.person.PersonVoiceSampleImportRequest;
import io.metaloom.loom.rest.model.person.PersonResponse;
import io.metaloom.loom.rest.model.person.PersonUpdateRequest;

public interface PersonModelValidator extends ModelValidator {

	default void validate(PersonUpdateRequest request) {

	}

	default void validate(PersonImageImportRequest request) {
		requireNonNull(request, "A valid request must be specified");
		requireNonNullOrEmpty(request.getDetectionUuid(), "A detection uuid must be set");
	}

	/**
	 * No required field: a blank or absent {@code imageUuid} is how the avatar is cleared.
	 */
	default void validate(PersonAvatarRequest request) {
		requireNonNull(request, "A valid request must be specified");
	}

	default void validate(PersonResponse response) {
		validateCreatorEditorResponse(response);
		requireNonNullOrEmpty(response.getAlias(), "A person alias must be set");
	}

	default void validate(PersonCreateRequest request) {
		requireNonNull(request, "A valid request must be specified");
		requireNonNullOrEmpty(request.getAlias(), "A person alias must be set");
	}

	/** Longest stretch a voice sample may be cut to. Long enough for the 30 s enrolment the recognition study recommends, with margin. */
	long MAX_VOICE_SAMPLE_MS = 120_000L;

	/** Most probes one match may carry: a recording with more distinct voices than this is not a recognition request. */
	int MAX_MATCH_PROBES = 256;

	/** Most candidates per probe. */
	int MAX_MATCH_LIMIT = 20;

	/** Most pending samples one match may list. */
	int MAX_PENDING_LIMIT = 100;

	/**
	 * Both times or neither: a one-sided range has no sensible reading, and guessing the missing end would cut a sample nobody asked for.
	 */
	default void validate(PersonVoiceSampleImportRequest request) {
		requireNonNull(request, "A valid request must be specified");
		requireNonNullOrEmpty(request.getClusterUuid(), "A cluster uuid must be set");
		Long from = request.getTimeFrom();
		Long to = request.getTimeTo();
		if ((from == null) != (to == null)) {
			throw new ValidationException("Set both timeFrom and timeTo, or neither to take the speaker's longest turn");
		}
		if (from != null) {
			if (from < 0) {
				throw new ValidationException("timeFrom must not be negative, got " + from);
			}
			if (to <= from) {
				throw new ValidationException("timeTo must be after timeFrom, got " + from + " - " + to);
			}
			if (to - from > MAX_VOICE_SAMPLE_MS) {
				throw new ValidationException("A voice sample is at most " + (MAX_VOICE_SAMPLE_MS / 1000) + " seconds long, got " + (to - from) + " ms");
			}
		}
	}

	/** No required field: a blank or absent {@code sampleUuid} is how the hero sample is cleared. */
	default void validate(PersonHeroVoiceRequest request) {
		requireNonNull(request, "A valid request must be specified");
	}

	/**
	 * A print is a vector or the reason there is none - the same rule the table's CHECK enforces, answered as a 400 instead of a constraint error.
	 */
	default void validate(PersonPrintCreateRequest request) {
		requireNonNull(request, "A valid request must be specified");
		requireNonNullOrEmpty(request.getType(), "A print type must be set");
		requireNonNullOrEmpty(request.getModel(), "A print model must be set");
		boolean hasVector = request.getVector() != null;
		boolean hasError = request.getError() != null && !request.getError().isBlank();
		if (hasVector == hasError) {
			throw new ValidationException("A print carries either a vector or an error explaining why there is none");
		}
		if (hasVector) {
			requireFiniteVector(request.getVector(), "vector");
		}
	}

	default void validate(PersonMatchRequest request) {
		requireNonNull(request, "A valid request must be specified");
		requireNonNullOrEmpty(request.getType(), "A match type must be set");
		requireNonNullOrEmpty(request.getModel(), "A match model must be set");
		List<Float[]> vectors = request.getVectors();
		if (vectors == null || vectors.isEmpty()) {
			throw new ValidationException("At least one probe vector must be set");
		}
		if (vectors.size() > MAX_MATCH_PROBES) {
			throw new ValidationException("At most " + MAX_MATCH_PROBES + " probe vectors per match, got " + vectors.size());
		}
		for (int i = 0; i < vectors.size(); i++) {
			requireFiniteVector(vectors.get(i), "vectors[" + i + "]");
		}
		Float threshold = request.getThreshold();
		if (threshold != null && (threshold.isNaN() || threshold < -1f || threshold > 1f)) {
			throw new ValidationException("threshold is a cosine similarity in [-1, 1], got " + threshold);
		}
		Integer limit = request.getLimit();
		if (limit != null && (limit < 1 || limit > MAX_MATCH_LIMIT)) {
			throw new ValidationException("limit must be between 1 and " + MAX_MATCH_LIMIT + ", got " + limit);
		}
		Integer pendingLimit = request.getPendingLimit();
		if (pendingLimit != null && (pendingLimit < 0 || pendingLimit > MAX_PENDING_LIMIT)) {
			throw new ValidationException("pendingLimit must be between 0 and " + MAX_PENDING_LIMIT + ", got " + pendingLimit);
		}
	}

	private void requireFiniteVector(Float[] vector, String field) {
		if (vector == null || vector.length == 0) {
			throw new ValidationException(field + " must be a non-empty vector");
		}
		for (Float value : vector) {
			if (value == null || value.isNaN() || value.isInfinite()) {
				throw new ValidationException(field + " must hold finite numbers only");
			}
		}
	}
}
