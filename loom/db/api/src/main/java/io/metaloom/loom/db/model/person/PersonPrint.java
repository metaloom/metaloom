package io.metaloom.loom.db.model.person;

import java.time.Instant;
import java.util.UUID;

/**
 * A reference vector computed from one of a person's own samples, in one comparable space (V2.115).
 *
 * <p>
 * Either {@link #vector()} or {@link #error()} is set, never both: a sample the model could not use is recorded with the reason, so it is not tried
 * again on every item of every run.
 * </p>
 *
 * @param uuid
 *            row identity
 * @param personUuid
 *            the person the sample belongs to
 * @param attachmentUuid
 *            the sample - a {@code PERSON_VOICE} (or {@code PERSON_IMAGE}) attachment
 * @param type
 *            the cluster type the print is comparable with, e.g. {@code speaker}
 * @param model
 *            the model that produced it
 * @param dimensions
 *            length of {@link #vector()}; null with an error
 * @param vector
 *            the reference vector, or null when the sample was unusable
 * @param error
 *            why there is no vector, or null
 */
public record PersonPrint(UUID uuid, UUID personUuid, UUID attachmentUuid, String type, String model, Integer dimensions, Float[] vector,
	String error, Instant created, Instant edited) {

	public boolean usable() {
		return vector != null && error == null;
	}
}
