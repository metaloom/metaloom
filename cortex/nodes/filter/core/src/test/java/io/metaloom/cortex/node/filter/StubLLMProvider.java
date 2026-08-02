package io.metaloom.cortex.node.filter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import io.metaloom.ai.genai.llm.Chunk;
import io.metaloom.ai.genai.llm.LLMContext;
import io.metaloom.ai.genai.llm.LLMProvider;
import io.metaloom.ai.genai.llm.LLMProviderType;
import io.metaloom.ai.genai.llm.ToolCallResponse;
import io.reactivex.rxjava3.core.Flowable;
import io.vertx.core.json.JsonObject;

/**
 * A language model that answers whatever the test tells it to, and counts how often it was asked.
 *
 * <p>
 * Deliberately a stub rather than a live endpoint. The sibling {@code LLMNodeTest} skips itself when
 * no model server is running, which is fine for a node whose only job is to relay an answer — but
 * routing tests decide what runs and what does not, and a test that silently skips would let a
 * routing regression through unnoticed.
 * </p>
 */
public class StubLLMProvider implements LLMProvider {

	private final Function<Integer, JsonObject> answers;

	private final List<LLMContext> calls = new ArrayList<>();

	private RuntimeException failure;

	private StubLLMProvider(Function<Integer, JsonObject> answers) {
		this.answers = answers;
	}

	/** Always answers with the given bucket at full confidence. */
	public static StubLLMProvider answering(String bucket) {
		return new StubLLMProvider(call -> new JsonObject().put("bucket", bucket).put("confidence", 1.0));
	}

	public static StubLLMProvider answering(String bucket, double confidence) {
		return new StubLLMProvider(call -> new JsonObject().put("bucket", bucket).put("confidence", confidence));
	}

	/** Answers with a raw JSON object, so a test can send back something malformed. */
	public static StubLLMProvider returning(JsonObject answer) {
		return new StubLLMProvider(call -> answer);
	}

	/** Throws instead of answering, standing in for an unreachable model. */
	public static StubLLMProvider failing(String message) {
		StubLLMProvider provider = new StubLLMProvider(call -> null);
		provider.failure = new IllegalStateException(message);
		return provider;
	}

	public int callCount() {
		return calls.size();
	}

	public LLMContext lastCall() {
		return calls.get(calls.size() - 1);
	}

	@Override
	public JsonObject generateJson(LLMContext ctx) {
		calls.add(ctx);
		if (failure != null) {
			throw failure;
		}
		return answers.apply(calls.size() - 1);
	}

	@Override
	public String generate(LLMContext ctx) {
		JsonObject json = generateJson(ctx);
		return json == null ? null : json.encode();
	}

	@Override
	public Flowable<Chunk> generateStream(LLMContext ctx) {
		throw new UnsupportedOperationException("the filter node never streams");
	}

	@Override
	public ToolCallResponse generateWithTools(LLMContext ctx) {
		throw new UnsupportedOperationException("the filter node never calls tools");
	}

	@Override
	public LLMProviderType type() {
		return LLMProviderType.OLLAMA;
	}
}
