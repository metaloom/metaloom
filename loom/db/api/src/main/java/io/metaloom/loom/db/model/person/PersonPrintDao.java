package io.metaloom.loom.db.model.person;

import java.util.List;
import java.util.UUID;

import io.metaloom.loom.api.attachment.AttachmentType;
import io.metaloom.loom.db.Dao;

/**
 * Reference prints computed from a person's own samples (V2.115).
 *
 * <p>
 * Not a {@code CRUDDao}: a print is never authored, only computed. The node owning a model writes one per (sample, type, model) and a later write for
 * the same key replaces it; deleting the sample or the person deletes it with them.
 * </p>
 */
public interface PersonPrintDao extends Dao {

	/**
	 * Store the outcome of embedding one sample, replacing an earlier outcome for the same (sample, type, model).
	 *
	 * @param vector the reference vector, or null when the sample was unusable
	 * @param error  why there is no vector; must be set exactly when {@code vector} is null
	 */
	PersonPrint upsert(UUID personUuid, UUID attachmentUuid, String type, String model, Float[] vector, String error);

	/** Every print computed from one sample, across all spaces. */
	List<PersonPrint> listBySample(UUID attachmentUuid);

	/** Every print computed from any of the person's samples. */
	List<PersonPrint> listByPerson(UUID personUuid);

	/** The usable prints of one space, as references for person matching. Prints that recorded an error are left out. */
	List<PersonReference> listUsableReferences(String type, String model);

	/**
	 * Samples of the given attachment type that have no print, usable or not, in this space yet - the work a node owning the model can do.
	 *
	 * @param limit at most this many, oldest sample first so a backlog drains in upload order
	 */
	List<PendingSample> listUnenrolled(String type, String model, AttachmentType sampleType, int limit);

	/**
	 * A sample waiting to be embedded.
	 *
	 * @param personUuid     the owner
	 * @param attachmentUuid the sample
	 * @param mimeType       what the bytes are, so the node can tell audio from an image without downloading first
	 */
	record PendingSample(UUID personUuid, UUID attachmentUuid, String mimeType) {
	}
}
