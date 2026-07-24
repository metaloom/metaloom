package io.metaloom.loom.rest.validation;

import io.metaloom.loom.rest.model.fingerprintcomp.FingerprintCompCreateRequest;
import io.metaloom.loom.rest.model.fingerprintcomp.FingerprintCompResponse;

public interface FingerprintCompModelValidator extends ModelValidator {

	default void validate(FingerprintCompCreateRequest request) {
		requireNonNullOrEmpty(request.getNodeKind(), "nodeKind");
		requireNonNullOrEmpty(request.getAlgorithm(), "algorithm");
		requireNonNullOrEmpty(request.getFingerprint(), "fingerprint");
	}

	default void validate(FingerprintCompResponse response) {

	}
}
