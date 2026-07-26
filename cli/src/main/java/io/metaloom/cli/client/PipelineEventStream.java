package io.metaloom.cli.client;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import io.metaloom.cli.CliContext;
import io.metaloom.cli.ExitCode;
import io.metaloom.cli.config.ServerUrl;
import io.metaloom.cli.output.CliJson;
import io.metaloom.cli.output.OutputFormat;
import io.metaloom.cli.output.Printer;
import io.metaloom.loom.rest.model.pipeline.event.PipelineEventMessage;
import io.metaloom.loom.rest.model.pipeline.event.PipelineEventType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Streams pipeline run events over the events WebSocket.
 *
 * <p>Built on OkHttp's WebSocket, which is already on the classpath via
 * {@code loom-client-rest}; pulling in {@code vertx-web-client} for this would add a second
 * HTTP stack to a program that wants to be a small native binary.</p>
 *
 * <p>The socket authenticates with {@code ?token=}, not a header, because that is what the
 * server's {@code WebSocketAuthenticator} reads - browsers cannot set headers on a WS
 * upgrade, so the server never learned to.</p>
 */
@Singleton
public class PipelineEventStream {

	/** The server's close code for a rejected token. */
	private static final int CLOSE_UNAUTHORIZED = 4401;

	private static final int CLOSE_NORMAL = 1000;

	private final Provider<CliContext> contextProvider;
	private final Provider<TokenResolver> tokenProvider;

	@Inject
	public PipelineEventStream(Provider<CliContext> contextProvider, Provider<TokenResolver> tokenProvider) {
		this.contextProvider = contextProvider;
		this.tokenProvider = tokenProvider;
	}

	/**
	 * Open the stream.
	 *
	 * @param pipelineName optional server-side filter by pipeline name; may be null
	 * @param printer      where events are rendered
	 */
	public Subscription subscribe(String pipelineName, Printer printer) {
		return subscribe(pipelineName, null, printer);
	}

	/**
	 * Open the stream, narrowed server-side to one run where possible.
	 *
	 * @param pipelineName optional pipeline-name filter; may be null
	 * @param runUuid      optional run filter; may be null when the run id is not known yet
	 * @param printer      where events are rendered
	 */
	public Subscription subscribe(String pipelineName, UUID runUuid, Printer printer) {
		CliContext context = contextProvider.get();
		ServerUrl server = ServerUrl.parse(context.serverUri().toString());
		String token = tokenProvider.get().requireToken();

		StringBuilder url = new StringBuilder()
			.append(server.webSocketScheme()).append("://")
			.append(server.host()).append(':').append(server.port());
		if (!server.pathPrefix().isEmpty()) {
			url.append('/').append(server.pathPrefix());
		}
		url.append("/api/v1/pipelines/events/ws?token=").append(urlEncode(token));
		if (pipelineName != null && !pipelineName.isBlank()) {
			url.append("&pipeline=").append(urlEncode(pipelineName));
		}
		if (runUuid != null) {
			// A server that predates this filter simply ignores the parameter, and the
			// client-side filter in emit() still narrows the stream correctly.
			url.append("&run=").append(urlEncode(runUuid.toString()));
		}

		OkHttpClient client = new OkHttpClient.Builder()
			// The stream is idle whenever the pipeline is; no read timeout, and pings keep
			// intermediaries from dropping it.
			.readTimeout(Duration.ZERO)
			.pingInterval(Duration.ofSeconds(20))
			.build();

		Subscription subscription = new Subscription(printer, client);
		client.newWebSocket(new Request.Builder().url(url.toString()).build(), subscription.listener());
		return subscription;
	}

	private static String urlEncode(String value) {
		return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
	}

	/**
	 * A live subscription.
	 *
	 * <p>Events that arrive before {@link #watch(UUID)} names a run are buffered and replayed
	 * once it does. This is what makes "subscribe, then dispatch" work: the run id does not
	 * exist until the POST returns, but events for it can arrive before that.</p>
	 */
	public static class Subscription implements AutoCloseable {

		private final Printer printer;
		private final OkHttpClient client;
		private final CountDownLatch finished = new CountDownLatch(1);
		private final AtomicReference<UUID> runUuid = new AtomicReference<>();
		private final List<PipelineEventMessage> buffered = new ArrayList<>();
		private final AtomicReference<WebSocket> socket = new AtomicReference<>();
		private volatile CliException failure;
		private volatile boolean closed;

		Subscription(Printer printer, OkHttpClient client) {
			this.printer = printer;
			this.client = client;
		}

		WebSocketListener listener() {
			return new WebSocketListener() {

				@Override
				public void onOpen(WebSocket webSocket, Response response) {
					socket.set(webSocket);
				}

				@Override
				public void onMessage(WebSocket webSocket, String text) {
					handle(text);
				}

				@Override
				public void onClosed(WebSocket webSocket, int code, String reason) {
					if (code == CLOSE_UNAUTHORIZED) {
						failure = new CliException(ExitCode.AUTH_REQUIRED,
							"The server rejected the token for the event stream. Run 'metaloom login'.");
					}
					finished.countDown();
				}

				@Override
				public void onFailure(WebSocket webSocket, Throwable t, Response response) {
					if (!closed) {
						failure = new CliException(ExitCode.CONNECT_ERROR,
							"Event stream failed: " + t.getMessage(), null, t);
					}
					finished.countDown();
				}
			};
		}

		/** Narrow the stream to one run, replaying anything already buffered. */
		public synchronized void watch(UUID uuid) {
			runUuid.set(uuid);
			List<PipelineEventMessage> replay = new ArrayList<>(buffered);
			buffered.clear();
			for (PipelineEventMessage event : replay) {
				emit(event);
			}
		}

		private synchronized void handle(String text) {
			PipelineEventMessage event;
			try {
				event = CliJson.json().readValue(text, PipelineEventMessage.class);
			} catch (Exception e) {
				printer.warn("Ignoring an unreadable event frame.");
				return;
			}
			if (runUuid.get() == null) {
				// Not told which run to watch yet - hold on to it rather than dropping it.
				buffered.add(event);
				return;
			}
			emit(event);
		}

		private void emit(PipelineEventMessage event) {
			UUID watching = runUuid.get();
			// The server filters by pipeline name at best, so the run-level filter is applied
			// here regardless of what the server supports.
			if (watching != null && event.getPipelineRunUuid() != null
				&& !watching.equals(event.getPipelineRunUuid())) {
				return;
			}
			render(event);
			if (event.getType() == PipelineEventType.PIPELINE_COMPLETED) {
				finished.countDown();
			}
		}

		private void render(PipelineEventMessage event) {
			switch (printer.format()) {
				case JSON -> printer.printNdjson(event);
				case YAML -> printer.printYamlDocument(event);
				case TABLE -> printer.out().println(formatLine(event));
			}
			printer.out().flush();
		}

		private String formatLine(PipelineEventMessage event) {
			var ansi = printer.ansi();
			String type = event.getType() == null ? "?" : event.getType().name();
			String badge = switch (type) {
				case "NODE_FAILED" -> ansi.red(type);
				case "NODE_COMPLETED", "PIPELINE_COMPLETED" -> ansi.green(type);
				case "NODE_SKIPPED", "NODE_BUFFERED" -> ansi.dim(type);
				case "PIPELINE_STARTED", "NODE_STARTED" -> ansi.cyan(type);
				default -> type;
			};
			StringBuilder line = new StringBuilder(badge);
			if (event.getNodeId() != null) {
				line.append(' ').append(ansi.bold(event.getNodeId()));
			}
			if (event.getMediaPath() != null) {
				line.append(' ').append(event.getMediaPath());
			}
			if (event.getMessage() != null) {
				line.append(" - ").append(event.getMessage());
			}
			if (event.getDurationMs() != null) {
				line.append(ansi.dim(" (" + event.getDurationMs() + "ms)"));
			}
			return line.toString();
		}

		/**
		 * Wait for the run to complete.
		 *
		 * @return false if the timeout expired first
		 * @throws CliException if the stream failed or the token was rejected
		 */
		public boolean awaitCompletion(Duration timeout) {
			try {
				boolean completed = finished.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
				if (failure != null) {
					throw failure;
				}
				return completed;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new CliException(ExitCode.INTERRUPTED, "Interrupted while following the run.");
			}
		}

		@Override
		public void close() {
			closed = true;
			WebSocket ws = socket.get();
			if (ws != null) {
				ws.close(CLOSE_NORMAL, "done");
			}
			client.dispatcher().executorService().shutdown();
			client.connectionPool().evictAll();
		}
	}
}
