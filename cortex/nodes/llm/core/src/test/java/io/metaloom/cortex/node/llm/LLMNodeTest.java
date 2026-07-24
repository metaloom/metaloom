package io.metaloom.cortex.node.llm;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.media.test.AbstractBasicNodeTest;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.test.data.TestMedia;
import io.vertx.core.json.JsonObject;

public class LLMNodeTest extends AbstractBasicNodeTest<LLMNode> {

	@Test
	public void testProcessing() throws IOException {
		LoomMedia media = mediaVideo1();
		NodeResult result = node().process(media);
		assertThat(result).isSuccess();
		String jsonStr = """
			{"format":"mp4","genre":null,"year":null,"title":"pexels-jack-sparrow-5977265"}
			""";

		JsonObject json = new JsonObject(jsonStr);
		assertThat(result).hasOutput(LLMNode.resultKey("default"), json.encode());
	}

	@Override
	protected void assertProcessed(TestMedia testMedia, LoomMedia media, NodeResult result, LLMNode nodeMock) {
		assertThat(result).hasOutput(LLMNode.resultKey("default"));
	}

	@Override
	protected void disableNode(LLMNode nodeMock) {
		LLMNodeOptions options = nodeMock.options();
		options.setEnabled(false);
	}

	@Override
	public LLMNode mockNode(LoomClient client, CortexOptions cortexOptions) {
		LLMNodeOptions options = new LLMNodeOptions();
		options.setOllamaUrl(TestEnv.OLLAMA_URL);
		options.setEnabled(true);
		return new LLMNode(null, cortexOptions, options, new io.metaloom.ai.genai.llm.ollama.OllamaLLMProvider());
	}

}
