package io.metaloom.loom.client.http.error;

import java.util.function.Function;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.rest.json.LoomJson;
import io.metaloom.loom.rest.model.message.GenericMessageResponse;

/**
 * Exception which is also used to return non-200 error responses.
 */
public class LoomHttpClientException extends LoomClientException {

	private static final long serialVersionUID = 7139036965636956371L;

	private final String body;

	private final String statusMsg;

	public LoomHttpClientException(String message, Exception e) {
		super(500, message, e);
		this.statusMsg = message;
		this.body = "";
	}

	public LoomHttpClientException(String message, int statusCode, String statusMsg, String body) {
		super(statusCode, message);
		this.statusMsg = statusMsg;
		this.body = body;
	}

	/**
	 * Returns the error response body.
	 * 
	 * @return
	 */
	public String getBody() {
		return body;
	}

	@Override
	public String getStatusMsg() {
		return statusMsg;
	}

	/**
	 * Return the server response.
	 * 
	 * @return
	 */
	public GenericMessageResponse getResponse() {
		return LoomJson.parse(body, GenericMessageResponse.class);
	}

	/**
	 * Transform the body string into the object of choice.
	 * 
	 * @param parser
	 *            Function used to transform the string.
	 * @return
	 */
	public <T> T getBodyObject(Function<String, T> parser) {
		return parser.apply(getBody());
	}

	@Override
	public String toString() {
		return getMessage() + " - status: " + getStatusCode() + " body {" + getBody() + "}";
	}

}
