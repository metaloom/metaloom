package io.metaloom.loom.rest;

import java.security.SecureRandom;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

/**
 * Stamps every request with a trace id and echoes it back on the response.
 *
 * <p>
 * <b>Why this exists.</b> Until a request can be named, a user reporting "it said 500" and an operator reading the server log have no way to establish
 * that they are talking about the same event. The log line carries a path and a stack trace, the user carries a screenshot and a time of day, and
 * matching the two is guesswork on a busy instance. The trace id is the join key: {@link ServerFailureHandler} logs it next to the failure,
 * {@code GenericMessageResponse} carries it in the error body, and the UI puts it into the failure report the user submits.
 * </p>
 *
 * <p>
 * <b>Registered before the body handler and before every route</b>, so the header is present even on the paths that never reach an endpoint - a 404
 * from the router's own error handler is exactly the kind of failure a user cannot otherwise describe.
 * </p>
 *
 * <p>
 * <b>An inbound {@code X-Trace-Id} is honoured but never trusted verbatim.</b> A caller that already has a trace id - the CLI, cortex, a proxy that
 * stamped one - should be able to keep one identifier across the hop, otherwise the correlation breaks at every tier boundary. But this value is
 * written into log lines, so accepting arbitrary bytes would let a caller forge log records by embedding newlines. {@link #sanitize(String)} therefore
 * admits only a short run of characters that cannot break a log line or an HTTP header, and anything else is silently replaced by a fresh id rather
 * than rejected: a malformed trace header is not a reason to fail somebody's request.
 * </p>
 */
@Singleton
public class TraceIdHandler implements Handler<RoutingContext> {

	/** Response and request header carrying the id. Also the name the UI reads, and the one CORS must expose. */
	public static final String TRACE_ID_HEADER = "X-Trace-Id";

	/** Key under which the id is stashed on the {@link RoutingContext} for {@link LoomRoutingContext#traceId()}. */
	public static final String TRACE_ID_KEY = "loomTraceId";

	/**
	 * Longest accepted inbound id. 32 hex characters is what this class generates; the allowance above that is for callers carrying a W3C
	 * {@code trace-id} or a proxy's own request id, which are of comparable length.
	 */
	static final int MAX_TRACE_ID_LENGTH = 64;

	/**
	 * 16 random bytes, hex encoded - the same width as a W3C {@code trace-id}, so an instance that later grows real distributed tracing can adopt these
	 * ids rather than having to run a second numbering scheme alongside them.
	 */
	private static final int TRACE_ID_BYTES = 16;

	private static final char[] HEX = "0123456789abcdef".toCharArray();

	private final SecureRandom random = new SecureRandom();

	@Inject
	public TraceIdHandler() {
	}

	@Override
	public void handle(RoutingContext rc) {
		String inbound = rc.request().getHeader(TRACE_ID_HEADER);
		String traceId = sanitize(inbound);
		if (traceId == null) {
			traceId = newTraceId();
		}
		rc.put(TRACE_ID_KEY, traceId);

		// Set on the response head immediately rather than at end(): a streaming route (a binary download, the SSE
		// channel) writes its head long before it finishes, and a handler that failed midway never gets to add it.
		rc.response().putHeader(TRACE_ID_HEADER, traceId);
		rc.next();
	}

	/**
	 * Read the id stamped onto this request, or null when this handler did not run (which in practice means a test built a routing context by hand).
	 */
	public static String traceIdOf(RoutingContext rc) {
		Object value = rc.get(TRACE_ID_KEY);
		return value instanceof String s ? s : null;
	}

	/**
	 * Accept a caller-supplied id, or return null when it is unusable.
	 *
	 * <p>
	 * Deliberately strict. The id ends up in log lines and in an HTTP response header, so CR, LF and anything non-printable would let a caller forge log
	 * records or split the response. Only characters that are safe in both places are admitted.
	 * </p>
	 */
	static String sanitize(String candidate) {
		if (candidate == null) {
			return null;
		}
		String trimmed = candidate.trim();
		if (trimmed.isEmpty() || trimmed.length() > MAX_TRACE_ID_LENGTH) {
			return null;
		}
		for (int i = 0; i < trimmed.length(); i++) {
			char c = trimmed.charAt(i);
			boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.';
			if (!ok) {
				return null;
			}
		}
		return trimmed;
	}

	private String newTraceId() {
		byte[] bytes = new byte[TRACE_ID_BYTES];
		random.nextBytes(bytes);
		char[] out = new char[bytes.length * 2];
		for (int i = 0; i < bytes.length; i++) {
			out[i * 2] = HEX[(bytes[i] >> 4) & 0xF];
			out[i * 2 + 1] = HEX[bytes[i] & 0xF];
		}
		return new String(out);
	}
}
