package io.metaloom.loom.rest.validation;

import io.metaloom.loom.rest.model.nodeoutput.NodeOutputCreateRequest;

public interface NodeOutputModelValidator extends ModelValidator {

	/** Upper bound for a reuse key. The node sends a 64-character SHA-256; anything far longer is not a key. */
	int MAX_REUSE_KEY_LENGTH = 128;

	default void validate(NodeOutputCreateRequest request) {
		requireNonNullOrEmpty(request.getNodeKind(), "nodeKind");
		requireNonNullOrEmpty(request.getReuseKey(), "reuseKey");
		if (request.getReuseKey().length() > MAX_REUSE_KEY_LENGTH) {
			throw new ValidationException("The field reuseKey is longer than " + MAX_REUSE_KEY_LENGTH + " characters");
		}
		requireNonNullOrEmpty(request.getState(), "state");
		// A failure is never stored for reuse: replaying one would turn a transient outage into a permanent answer.
		if (!"SUCCESS".equals(request.getState()) && !"SKIPPED".equals(request.getState())) {
			throw new ValidationException("The field state must be SUCCESS or SKIPPED, got " + request.getState());
		}
		if (request.getOutputs() == null) {
			throw new ValidationException("The field outputs is required");
		}
		if (request.getRunUuid() != null) {
			try {
				java.util.UUID.fromString(request.getRunUuid());
			} catch (IllegalArgumentException e) {
				throw new ValidationException("The field runUuid is not a valid uuid: " + request.getRunUuid());
			}
		}
	}

}
