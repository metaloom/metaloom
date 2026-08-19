package io.metaloom.loom.rest.model.message;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestResponseModel;

public class GenericMessageResponse implements RestResponseModel<GenericMessageResponse> {

	private String message;

	/**
	 * The id of the request that produced this message.
	 *
	 * <p>
	 * Set on every error response, so that a caller looking at a failure has the one value that identifies it in the server log. It is also sent as the
	 * {@code X-Trace-Id} response header - the body copy exists because that is the half a user can see, copy and paste into a report, and because a
	 * client that already parsed the JSON should not have to reach back to the transport to find it.
	 * </p>
	 */
	@JsonPropertyDescription("Id of the request that produced this message. Quote it when reporting a failure - it is what identifies the request in the server log.")
	private String traceId;

	public String getMessage() {
		return message;
	}

	public GenericMessageResponse setMessage(String message) {
		this.message = message;
		return this;
	}

	public String getTraceId() {
		return traceId;
	}

	public GenericMessageResponse setTraceId(String traceId) {
		this.traceId = traceId;
		return this;
	}

	@Override
	public GenericMessageResponse self() {
		return this;
	}
}
