package io.metaloom.loom.api.error;

import java.io.IOException;

public class LoomRestException extends RuntimeException {

	private static final long serialVersionUID = -4086888737127388941L;
	private int statusCode;
	private String message;
	private LoomRestErrorCode errorCode;

	public LoomRestException(int httpErrorCode, String message, IOException e) {
		super(e);
		this.statusCode = httpErrorCode;
		this.message = message;
	}

	public LoomRestException(int statusCode, LoomRestErrorCode errorCode, String message) {
		this.statusCode = statusCode;
		this.errorCode = errorCode;
		this.message = message;
	}

	public String getMessage() {
		return message;
	}

	public int httpCode() {
		return statusCode;
	}

	public LoomRestErrorCode errorCode() {
		return errorCode;
	}

}
