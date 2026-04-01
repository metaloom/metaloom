package io.metaloom.loom.rest.validation;

public class ValidationException extends RuntimeException {

	private static final long serialVersionUID = -2453567607829325722L;

	public ValidationException(String msg) {
		super(msg);
	}

}
