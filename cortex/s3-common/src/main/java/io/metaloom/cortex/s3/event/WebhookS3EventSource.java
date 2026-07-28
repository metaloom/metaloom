package io.metaloom.cortex.s3.event;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.option.S3EventOptions;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;

/**
 * Receives S3 bucket notifications over HTTP and files them into the {@link S3EventBuffer}.
 *
 * <p>Registers on the worker's <em>existing</em> monitoring router rather than opening a second
 * server, the same way the health and metrics endpoints do. The route is only registered when
 * webhook events are enabled, so a default deployment exposes nothing new.</p>
 *
 * <p>This is what MinIO's {@code notify_webhook} target speaks:</p>
 *
 * <pre>
 * mc admin config set local notify_webhook:cortex \
 *     endpoint="http://cortex:8093/s3-events" auth_token="&lt;secret&gt;"
 * mc admin service restart local
 * mc event add local/media arn:minio:sqs::cortex:webhook --event put,delete
 * </pre>
 *
 * <p>AWS reaches the same route through EventBridge or a small Lambda.</p>
 */
public class WebhookS3EventSource implements S3EventSource {

	private static final Logger log = LoggerFactory.getLogger(WebhookS3EventSource.class);

	private final S3EventBuffer buffer;
	private final S3EventOptions options;

	public WebhookS3EventSource(S3EventBuffer buffer, S3EventOptions options) {
		this.buffer = buffer;
		this.options = options;
	}

	/**
	 * Register the webhook route.
	 *
	 * @param router the monitoring router
	 */
	public void register(Router router) {
		if (!isEnabled()) {
			return;
		}
		String path = options.getWebhookPath();
		router.post(path).handler(BodyHandler.create()).handler(this::handle);
		log.info("Listening for S3 bucket notifications on POST {}", path);
	}

	private boolean isEnabled() {
		if (options == null || !options.isEnabled()) {
			return false;
		}
		if (options.getMode() != S3EventOptions.Mode.WEBHOOK) {
			return false;
		}
		if (options.getWebhookSecret() == null || options.getWebhookSecret().isBlank()) {
			// Refuse rather than expose an unauthenticated route that lets anyone who can reach
			// the monitoring port inject work into pipelines.
			log.error("S3 webhook events are enabled but no webhook secret is set "
				+ "(--s3-events-webhook-secret / CORTEX_S3_EVENTS_WEBHOOK_SECRET); the route is NOT registered");
			return false;
		}
		return true;
	}

	private void handle(RoutingContext ctx) {
		String token = ctx.request().getHeader(S3EventOptions.TOKEN_HEADER);
		if (!isAuthorized(token)) {
			log.warn("Rejected an S3 notification with a missing or invalid {} header", S3EventOptions.TOKEN_HEADER);
			ctx.response().setStatusCode(401).end();
			return;
		}

		JsonObject payload;
		try {
			payload = ctx.body().asJsonObject();
		} catch (DecodeException e) {
			log.warn("Rejected an S3 notification with an unparseable body", e);
			ctx.response().setStatusCode(400).end();
			return;
		}

		List<S3ChangeHint> hints = S3EventParser.parse(payload);
		for (S3ChangeHint hint : hints) {
			buffer.record(hint);
		}
		if (!hints.isEmpty()) {
			log.debug("Buffered {} S3 change hint(s) from a notification", hints.size());
		}
		// Always 200 once authorized, including for zero hints: MinIO retries on failure, and a
		// test event or an uninteresting event type is not a failure.
		ctx.response().setStatusCode(200).end();
	}

	/** Constant-time comparison so the endpoint does not leak the secret through timing. */
	private boolean isAuthorized(String token) {
		if (token == null) {
			return false;
		}
		byte[] expected = options.getWebhookSecret().getBytes(StandardCharsets.UTF_8);
		byte[] actual = token.getBytes(StandardCharsets.UTF_8);
		return MessageDigest.isEqual(expected, actual);
	}

	@Override
	public void start() {
		// Nothing to start: the route is live as soon as the monitoring server is.
	}

	@Override
	public void stop() {
		// The monitoring server owns the route lifecycle.
	}
}
