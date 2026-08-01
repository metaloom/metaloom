package io.metaloom.cortex.node.llm;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import io.metaloom.ai.genai.llm.LLMProviderType;
import io.metaloom.ai.genai.llm.vllm.VLLMLLMProvider;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.media.test.AbstractBasicNodeTest;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.test.data.TestMedia;
import io.vertx.core.json.JsonObject;

/**
 * Drives {@link LLMNode} against the llama.cpp test server — see {@link TestEnv}.
 */
public class LLMNodeTest extends AbstractBasicNodeTest<LLMNode> {

	@Test
	public void testProcessing() throws IOException {
		LoomMedia media = mediaVideo1();
		NodeResult result = node().process(media);
		assertThat(result).isSuccess();

		String answer = result.get(LLMNode.resultPort("default"));
		assertThat(answer).as("The node must emit the prompt's answer").isNotNull();

		// The prompt asks for metadata extracted from the filename as JSON. Assert that shape
		// rather than one exact response: the answer is whatever model the test server happens to
		// host, and pinning the literal string made the test a fixture of one specific model.
		JsonObject json = new JsonObject(answer);
		assertThat(json.fieldNames())
			.as("The answer must carry the fields the prompt asks for")
			.contains("format", "genre", "year", "title");
	}

	@Override
	protected void assertProcessed(TestMedia testMedia, LoomMedia media, NodeResult result, LLMNode nodeMock) {
		assertThat(result).hasOutput(LLMNode.resultPort("default"));
	}

	@Override
	protected void disableNode(LLMNode nodeMock) {
		LLMNodeOptions options = nodeMock.options();
		options.setEnabled(false);
	}

	@Override
	public LLMNode mockNode(LoomClient client, CortexOptions cortexOptions) {
		// Every test in this class builds its node here (AbstractNodeTest does it in @BeforeEach),
		// so guarding once covers the whole class.
		TestEnv.assumeRunning();

		LLMNodeOptions options = new LLMNodeOptions();
		options.setOllamaUrl(TestEnv.LLM_URL);
		options.setProviderType(LLMProviderType.VLLM);
		options.setEnabled(true);
		return new LLMNode(null, cortexOptions, options, new VLLMLLMProvider());
	}

}
