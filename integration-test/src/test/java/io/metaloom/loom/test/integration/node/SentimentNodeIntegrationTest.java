package io.metaloom.loom.test.integration.node;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.node.sentiment.SentimentClient;
import io.metaloom.cortex.node.sentiment.SentimentNode;
import io.metaloom.cortex.node.sentiment.SentimentNodeOptions;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompResponse;
import io.metaloom.loom.rest.model.noderesult.NodeResultResponse;
import io.vertx.core.json.JsonObject;

/**
 * Integration test for {@code SentimentNode}. The node runs its real text-in / label-out + persistence path against a real asset, but its injected
 * {@link SentimentClient} is replaced by a stub returning a fixed scored payload instead of calling a live FastAPI sidecar. The scored result must
 * reach the {@code sentiment} JSON component and the {@code asset_node_result} ledger, both readable back through REST.
 */
public class SentimentNodeIntegrationTest extends AbstractNodeIntegrationTest {

	private static final String MODEL = "oliverguhr/german-sentiment-bert";

	private static final JsonObject SCORED = new JsonObject()
		.put("label", "NEGATIVE")
		.put("score", 0.87d)
		.put("polarity", -0.81d)
		.put("scores", new JsonObject().put("positive", 0.06d).put("neutral", 0.07d).put("negative", 0.87d))
		.put("lang", "de")
		.put("model", MODEL)
		.put("chunks", 1)
		.put("truncated", false);

	/** A SentimentClient that returns a fixed scored payload instead of calling the FastAPI sidecar. */
	private static SentimentClient stubClient() {
		return new SentimentClient("localhost", 0) {
			@Override
			public JsonObject analyze(String text, String lang, JsonObject modelOverride) {
				return SCORED.copy();
			}
		};
	}

	@Test
	public void testSentimentPersistsJsonCompAndLedger() throws Exception {
		withLoom(client -> {
			AssetResponse asset = getOrCreateAsset(client, image1(), "image/jpeg");

			Path metaPath = Files.createTempDirectory("node-it-sentiment");
			CortexOptions options = new CortexOptions().setMetaPath(metaPath);
			SentimentNode node = new SentimentNode(client, options, new SentimentNodeOptions(), stubClient());

			// Fed through the declared text input port. Which upstream node fills it is the graph's
			// business, not the node's - the old textSources option named node ids and is gone.
			NodeContext<LoomMedia> ctx = NodeContext.create(media(image1()),
				NodeInputs.builder()
					.input(SentimentNode.IN_TEXT, "Der Kundenservice war eine Katastrophe.")
					.build());
			NodeResult result = node.process(ctx);
			assertThat(result.getState()).isEqualTo(ResultState.SUCCESS);
			assertThat(result.get(SentimentNode.OUT_LABEL)).isEqualTo("NEGATIVE");

			// The sentiment JSON component must be readable via REST.
			JsonCompResponse comp = client.listAssetJsonComps(asset.getUuid()).sync().body().getData().stream()
				.filter(c -> "sentiment".equals(c.getSchemaType()))
				.findFirst().orElse(null);
			assertThat(comp).as("sentiment JSON component must be readable via REST").isNotNull();
			// The variant used to carry the upstream output key, which only worked while the node
			// picked its own text source. Now the edge decides, so there is one row per asset per
			// node kind and the variant is empty.
			assertThat(comp.getVariant()).as("one sentiment row per asset per node kind").isEmpty();
			assertThat(comp.getData().getString("label")).isEqualTo("NEGATIVE");
			assertThat(comp.getData().getDouble("polarity")).isEqualTo(-0.81d);
			assertThat(comp.getData().getString("model")).isEqualTo(MODEL);
			assertThat(comp.getData().getInteger("textChars"))
				.as("the scored text is measured on the way through")
				.isEqualTo("Der Kundenservice war eine Katastrophe.".length());

			// The sentiment node-result ledger row must be readable via REST.
			boolean recorded = client.listAssetNodeResults(asset.getUuid()).sync().body().getData().stream()
				.map(NodeResultResponse::getNodeKind)
				.anyMatch("sentiment"::equals);
			assertThat(recorded).as("sentiment node-result ledger row must be readable via REST").isTrue();
		});
	}
}
