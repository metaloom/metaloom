package io.metaloom.cli.client;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;

import io.metaloom.cli.ExitCode;
import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.error.LoomHttpClientException;
import io.metaloom.loom.rest.model.message.GenericMessageResponse;

/**
 * Turns a client failure into an exit code and a one-line message.
 *
 * <p>Concentrating the mapping here is what makes the CLI scriptable: a caller can branch on
 * "not found" versus "could not connect" without parsing English out of stderr.</p>
 */
public final class ClientErrors {

	private ClientErrors() {
	}

	/**
	 * @param e       the failure
	 * @param context what was being attempted, e.g. {@code "load pipeline abc"}
	 */
	public static CliException toCliException(Exception e, String context) {
		if (e instanceof CliException cliException) {
			return cliException;
		}
		if (e instanceof LoomHttpClientException http) {
			return fromHttp(http, context);
		}
		if (e instanceof LoomClientException client) {
			return new CliException(exitCodeForStatus(client.getStatusCode()),
				context + ": " + client.getStatusMsg(), null, e);
		}
		Throwable cause = rootCause(e);
		if (cause instanceof UnknownHostException) {
			return new CliException(ExitCode.CONNECT_ERROR,
				"Could not resolve host: " + cause.getMessage(), null, e);
		}
		if (cause instanceof ConnectException || cause instanceof IOException) {
			return new CliException(ExitCode.CONNECT_ERROR,
				"Could not reach the server: " + cause.getMessage(), null, e);
		}
		if (e instanceof IllegalArgumentException) {
			return new CliException(ExitCode.USAGE, e.getMessage(), null, e);
		}
		return new CliException(ExitCode.ERROR, context + ": " + e.getMessage(), null, e);
	}

	private static CliException fromHttp(LoomHttpClientException e, String context) {
		// A transport failure never reached the server, but the client reports it as a
		// synthetic 500 with an empty body (LoomHttpClientException(String, Exception)).
		// Taking that at face value would tell the user "the server failed" when in fact
		// nothing was ever contacted - and would give them exit 20 instead of 15.
		Throwable cause = rootCause(e);
		if (isTransportFailure(e, cause)) {
			if (cause instanceof UnknownHostException) {
				return new CliException(ExitCode.CONNECT_ERROR,
					"Could not resolve host '" + cause.getMessage() + "'.", null, e);
			}
			return new CliException(ExitCode.CONNECT_ERROR,
				"Could not reach the server: " + cause.getMessage(), null, e);
		}

		int status = e.getStatusCode();
		String serverMessage = serverMessage(e);
		String message = switch (status) {
			case 401 -> "Not authenticated. Run 'metaloom login' first.";
			case 403 -> "Not permitted: " + orDefault(serverMessage, "you lack the required permission.");
			case 404 -> orDefault(serverMessage, "Not found.");
			case 409 -> orDefault(serverMessage, "Conflict.");
			case 400 -> orDefault(serverMessage, "The server rejected the request.");
			case 503 -> orDefault(serverMessage,
				"The server cannot serve this request right now. For a pipeline run this usually "
					+ "means no registered processor accepts the pipeline's source kind.");
			default -> context + ": " + orDefault(serverMessage, e.getStatusMsg());
		};
		return new CliException(exitCodeForStatus(status), message, e.getBody(), e);
	}

	/** Pull the server's own message out of the error body, if it sent one. */
	private static String serverMessage(LoomHttpClientException e) {
		String body = e.getBody();
		if (body == null || body.isBlank()) {
			return null;
		}
		try {
			GenericMessageResponse response = e.getResponse();
			return response == null ? null : response.getMessage();
		} catch (Exception ignored) {
			// A non-JSON error body (a proxy's HTML 502 page, say) is not worth failing over.
			return null;
		}
	}

	/**
	 * Distinguish "the request never left" from "the server answered 500".
	 *
	 * <p>Both surface as a {@code LoomHttpClientException}; only the transport failure has an
	 * {@link IOException} cause and no response body, because there was no response.</p>
	 */
	private static boolean isTransportFailure(LoomHttpClientException e, Throwable cause) {
		boolean noBody = e.getBody() == null || e.getBody().isBlank();
		return e.getStatusCode() == 500 && noBody && cause instanceof IOException;
	}

	public static int exitCodeForStatus(int status) {
		return switch (status) {
			case 400 -> ExitCode.VALIDATION_FAILED;
			case 401 -> ExitCode.AUTH_REQUIRED;
			case 403 -> ExitCode.FORBIDDEN;
			case 404 -> ExitCode.NOT_FOUND;
			case 409 -> ExitCode.CONFLICT;
			default -> status >= 500 ? ExitCode.SERVER_FAILURE : ExitCode.ERROR;
		};
	}

	private static String orDefault(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value;
	}

	private static Throwable rootCause(Throwable t) {
		Throwable current = t;
		while (current.getCause() != null && current.getCause() != current) {
			current = current.getCause();
		}
		return current;
	}
}
