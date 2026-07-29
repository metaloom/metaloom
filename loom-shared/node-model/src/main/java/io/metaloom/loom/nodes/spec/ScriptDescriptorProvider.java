package io.metaloom.loom.nodes.spec;

import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.*;
import static io.metaloom.loom.nodes.spec.NodeCategory.TRANSFORM;
import static io.metaloom.loom.nodes.spec.NodeMode.PARALLEL;
import static io.metaloom.loom.nodes.spec.ParameterType.BOOLEAN;
import static io.metaloom.loom.nodes.spec.ParameterType.CODE;
import static io.metaloom.loom.nodes.spec.ParameterType.INTEGER;
import static io.metaloom.loom.nodes.spec.ParameterType.JSON;
import static io.metaloom.loom.nodes.spec.ParameterType.STRING;
import static io.metaloom.loom.nodes.spec.PortSpec.optionalOne;

import java.util.List;

/**
 * Provides the node descriptor for the script node.
 *
 * <p>
 * ⚠️ The static output port list is deliberately <strong>empty</strong> and the descriptor sets
 * {@link NodeDescriptor#setDynamicPorts(boolean) dynamicPorts}. A script node's outputs are declared
 * per instance in its {@code outputs} parameter, so the descriptor cannot know them;
 * {@link ScriptPortResolver} derives them from that parameter and the editor draws the handles from
 * the resolved set. Adding placeholder ports here would draw handles that no edge could meaningfully
 * attach to.
 * </p>
 */
public class ScriptDescriptorProvider implements NodeDescriptorProvider {

	private static final List<String> STANDARD_EVENTS = List.of(
		"NODE_STARTED", "NODE_COMPLETED", "NODE_FAILED", "NODE_SKIPPED", "NODE_STATS");

	private static final String DEFAULT_SCRIPT = """
		// Runs once per media item.
		//   media    - { path, absolutePath, size, sha512, isVideo, isImage, isAudio, isDocument }
		//   upstream - upstream["<nodeId>"]["<outputKey>"]
		//   params   - the Parameters bag below
		//   out      - out.text/number/integer/bool/json/list/timeframes/image/path(key, value)
		//   log      - log.info/warn/error(msg)
		//   ctx      - ctx.skip(reason) / ctx.fail(reason)
		out.text('result', 'hello from ' + media.path);
		""";

	private static final String DEFAULT_OUTPUTS = "[{\"key\": \"result\", \"type\": \"TEXT\"}]";

	@Override
	public List<NodeDescriptor> getDescriptors() {
		return List.of(
			new NodeDescriptor()
				.setKind("script")
				.setName("Script")
				.setDescription("Run a small script over the media item and its upstream outputs, and emit any "
					+ "number of declared outputs - texts, numbers, JSON, timeframes or images.")
				.setIcon("code")
				.setCategory(TRANSFORM)
				.setInputPorts(List.of(
					optionalOne("media", MEDIA_ANY)
						.describedAs("Media", "The media item the script runs over. Leave unwired for a script that only reshapes upstream data"),
					optionalOne("data", STRUCT_JSON)
						.describedAs("Data", "A structured payload from an upstream node, handed to the script as its input data"),
					// Deriving something from upstream text - a reading time, a tag, a chapter list -
					// is the most common thing a script is asked to do, and text is not a struct.
					optionalOne("text", TEXT_ANY)
						.describedAs("Text", "Text from an upstream node, such as a transcript or extracted document content")))
				.setOutputPorts(List.of())
				.setDynamicPorts(true)
				.setParameters(List.of(
					commonEnabled(), commonProcessIncomplete(), commonRetryFailed(),
					new NodeParameter().setKey("engine").setType(STRING).setDefaultValue("js")
						.setLabel("Engine").setDescription("Script engine id. 'js' runs JavaScript on GraalJS"),
					new NodeParameter().setKey("script").setType(CODE).setDefaultValue(DEFAULT_SCRIPT)
						.setLanguage("javascript").setRows(16)
						.setLabel("Script").setDescription("The script body. Runs once per media item"),
					new NodeParameter().setKey("outputs").setType(JSON).setDefaultValue(DEFAULT_OUTPUTS).setRows(6)
						.setLabel("Outputs")
						.setDescription("Declared outputs as [{\"key\": ..., \"type\": ...}]. Types: STRING, TEXT, "
							+ "INTEGER, NUMBER, BOOLEAN, JSON, TEXT_LIST, TIMEFRAMES, IMAGE, IMAGE_LIST, PATH. A "
							+ "TIMEFRAMES output may also set \"segmentType\": one of SCENE, SILENCE, SHOT or "
							+ "CHAPTER (default CHAPTER) - the database accepts no others"),
					new NodeParameter().setKey("params").setType(JSON).setDefaultValue("{}").setRows(4)
						.setLabel("Parameters").setDescription("Constants handed to the script as 'params'"),
					new NodeParameter().setKey("trusted").setType(BOOLEAN).setDefaultValue(true)
						.setLabel("Trusted").setDescription("Run with full worker privileges. Turn off to deny host "
							+ "access, class lookup, threads and IO"),
					new NodeParameter().setKey("allowNetwork").setType(BOOLEAN).setDefaultValue(false)
						.setLabel("Allow Network").setDescription("Expose the 'http' binding to the script"),
					new NodeParameter().setKey("allowFilesystem").setType(BOOLEAN).setDefaultValue(false)
						.setLabel("Allow Filesystem").setDescription("Expose the read-only 'fs' binding to the script"),
					new NodeParameter().setKey("timeoutMs").setType(INTEGER).setDefaultValue(10000)
						.setLabel("Timeout (ms)").setDescription("Wall-clock budget per media item").setMin(1),
					new NodeParameter().setKey("statementLimit").setType(INTEGER).setDefaultValue(10000000)
						.setLabel("Statement Limit").setDescription("Guest-statement budget; guards against tight loops").setMin(1),
					new NodeParameter().setKey("maxOutputBytes").setType(INTEGER).setDefaultValue(1048576)
						.setLabel("Max Output Bytes").setDescription("Cap on the encoded output bag").setMin(1),
					new NodeParameter().setKey("maxLogLines").setType(INTEGER).setDefaultValue(200)
						.setLabel("Max Log Lines").setDescription("Cap on log.* calls per media item").setMin(0)))
				.setDefaultConcurrency(1)
				.setDefaultMode(PARALLEL)
				.setEvents(STANDARD_EVENTS));
	}

	private static NodeParameter commonEnabled() {
		return new NodeParameter().setKey("enabled").setType(BOOLEAN).setDefaultValue(true)
			.setLabel("Enabled").setDescription("Whether this node is active in the pipeline");
	}

	private static NodeParameter commonProcessIncomplete() {
		return new NodeParameter().setKey("processIncomplete").setType(BOOLEAN).setDefaultValue(false)
			.setLabel("Process Incomplete").setDescription("Process media files that are still being written");
	}

	private static NodeParameter commonRetryFailed() {
		return new NodeParameter().setKey("retryFailed").setType(BOOLEAN).setDefaultValue(false)
			.setLabel("Retry Failed").setDescription("Retry processing media that previously failed");
	}
}
