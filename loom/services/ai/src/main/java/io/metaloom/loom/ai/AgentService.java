package io.metaloom.loom.ai;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.ai.genai.llm.omni.OmniProvider;
import io.metaloom.ai.genai.llm.ollama.OllamaLLMProvider;
import io.metaloom.ai.genai.llm.vllm.VLLMLLMProvider;
import io.metaloom.ai.genai.llm.LLMProvider;
import io.metaloom.loom.ai.event.AgentEventSink;
import io.metaloom.loom.ai.loop.AgentLoop;
import io.metaloom.loom.ai.loop.BlockingTurnStreamer;
import io.metaloom.loom.ai.loop.StreamingTurnStreamer;
import io.metaloom.loom.ai.loop.TurnStreamer;
import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.mcp.tool.MCPToolRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;

/**
 * Entry point for agent runs. Guards against concurrent runs on the same chat, executes the blocking {@link AgentLoop} on a Vert.x worker thread and exposes
 * abort (used both by the explicit cancel route and by client disconnects).
 */
@Singleton
public class AgentService {

	private static final Logger log = LoggerFactory.getLogger(AgentService.class);

	private final Vertx vertx;
	private final LoomOptions options;
	private final DaoCollection daos;
	private final MCPToolRegistry toolRegistry;

	private final Map<UUID, AgentLoop> activeRuns = new ConcurrentHashMap<>();

	/**
	 * Factory for the turn streamer. Tests replace this with a scripted fake to run the loop without a live LLM.
	 */
	private Supplier<TurnStreamer> turnStreamerFactory;

	@Inject
	public AgentService(Vertx vertx, LoomOptions options, DaoCollection daos, MCPToolRegistry toolRegistry) {
		this.vertx = vertx;
		this.options = options;
		this.daos = daos;
		this.toolRegistry = toolRegistry;
		this.turnStreamerFactory = () -> {
			LLMProvider provider = new OmniProvider(new OllamaLLMProvider(), new VLLMLLMProvider());
			// True token/reasoning streaming is opt-in (LOOM_AI_STREAMING) — the blocking streamer
			// delivers turn-granular streaming and works with every provider.
			if (options.getAi().isStreaming()) {
				return new StreamingTurnStreamer(provider);
			}
			return new BlockingTurnStreamer(provider);
		};
	}

	/**
	 * Whether a run is currently active for the given chat.
	 */
	public boolean isBusy(UUID chatUuid) {
		return activeRuns.containsKey(chatUuid);
	}

	/**
	 * Start an agent run for the given request. Only one run per chat is allowed — the returned future fails immediately with
	 * {@link AgentBusyException} when a run is already active.
	 *
	 * @param request
	 *            The validated request (chat existence + ownership are checked by the caller)
	 * @param sink
	 *            Receiver for the run events (must be safe to call from worker threads)
	 * @return Future which completes when the run has finished (including the terminal agent_end event)
	 */
	public Future<Void> run(AgentRequest request, AgentEventSink sink) {
		AgentLoop loop = new AgentLoop(options.getAi(), daos.chatDao(), daos.skillDao(), toolRegistry, turnStreamerFactory.get(), sink, request);
		if (activeRuns.putIfAbsent(request.chatUuid(), loop) != null) {
			return Future.failedFuture(new AgentBusyException(request.chatUuid()));
		}
		return vertx.<Void>executeBlocking(() -> {
			loop.run();
			return null;
		}, false).onComplete(done -> {
			activeRuns.remove(request.chatUuid(), loop);
			if (done.failed()) {
				log.error("Agent run for chat {} terminated abnormally", request.chatUuid(), done.cause());
			}
		});
	}

	/**
	 * Abort the active run of the given chat (no-op when none is active).
	 *
	 * @return true when an active run was signalled
	 */
	public boolean abort(UUID chatUuid) {
		AgentLoop loop = activeRuns.get(chatUuid);
		if (loop == null) {
			return false;
		}
		loop.abort();
		return true;
	}

	/**
	 * Replace the turn streamer factory — intended for tests which script the LLM turns.
	 */
	public void setTurnStreamerFactory(Supplier<TurnStreamer> factory) {
		this.turnStreamerFactory = factory;
	}

	/**
	 * Raised when a second run is requested while a chat already has an active one.
	 */
	public static class AgentBusyException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		public AgentBusyException(UUID chatUuid) {
			super("An agent run is already active for chat " + chatUuid);
		}
	}

}
