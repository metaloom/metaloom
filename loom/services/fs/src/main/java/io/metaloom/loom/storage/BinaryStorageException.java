package io.metaloom.loom.storage;

/**
 * A storage backend could not do what was asked of it.
 *
 * <p>
 * Unchecked on purpose: the REST layer turns this into a 500 through the existing {@code ServerFailureHandler}, and no caller in between has a
 * meaningful recovery. The message is expected to name the backend and the locator, because "upload failed" without either is unactionable in a
 * deployment with several pools.
 * </p>
 */
public class BinaryStorageException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public BinaryStorageException(String message) {
		super(message);
	}

	public BinaryStorageException(String message, Throwable cause) {
		super(message, cause);
	}
}
