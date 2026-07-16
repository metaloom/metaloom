package io.metaloom.cortex.pipeline.core.serde;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.pipeline.api.NodeMode;
import io.metaloom.cortex.pipeline.api.NodeResult;
import io.metaloom.cortex.pipeline.api.Pipeline;
import io.metaloom.cortex.pipeline.api.filter.FilterBranch;
import io.metaloom.cortex.pipeline.api.node.PipelineNode;
import io.metaloom.cortex.pipeline.core.DefaultPipeline;
import io.metaloom.cortex.pipeline.core.node.AbstractPipelineNode;

/**
 * Round-trip test: Pipeline A → JSON → Pipeline B.
 * Asserts that A and B have the same structure, settings, and node configuration.
 */
class PipelineSerdeRoundTripTest {

private PipelineSerializer serializer;
private PipelineDeserializer deserializer;

@BeforeEach
void setUp() {
ObjectMapper mapper = new ObjectMapper();
serializer = new PipelineSerializer(mapper);
deserializer = new PipelineDeserializer(mapper);
}

@Test
void testMinimalPipelineRoundTrip() throws JsonProcessingException {
PipelineNode fs = sourceNode("fs", "Filesystem Source");

Pipeline original = DefaultPipeline.builder("minimal")
.description("A minimal pipeline")
.priority(1)
.enabled(true)
.dryRun(false)
.source(fs)
.build();

Pipeline restored = roundTrip(original);

assertPipelinesEqual(original, restored);
}

@Test
void testFullPipelineRoundTrip() throws JsonProcessingException {
PipelineNode filesystem = sourceNode("filesystem", "Filesystem Source");
PipelineNode sha512 = processorNode("sha512", "SHA-512 Hash", NodeMode.PARALLEL, true, 4, true, 30000L, Map.of());
PipelineNode tika = processorNode("tika", "Tika Extractor", NodeMode.PARALLEL, true, 2, false, 120000L, Map.of());
PipelineNode fingerprint = processorNode("fingerprint", "Video Fingerprint", NodeMode.SEQUENTIAL, true, 1, true, 300000L,
Map.of("processIncomplete", false, "timeout", 30000L));

filesystem.connectTo(sha512);
filesystem.connectTo(tika);
sha512.connectTo(fingerprint);

Pipeline original = DefaultPipeline.builder("video-analysis")
.description("Full video processing pipeline")
.priority(100)
.enabled(true)
.dryRun(false)
.source(filesystem)
.build();

Pipeline restored = roundTrip(original);

assertPipelinesEqual(original, restored);
}

@Test
void testPipelineWithConditionalDependencies() throws JsonProcessingException {
PipelineNode filesystem = sourceNode("filesystem", "Filesystem Source");
PipelineNode videoFilter = processorNode("video-filter", "Video Filter", NodeMode.PARALLEL, true, 1, false, Map.of());
PipelineNode fingerprint = processorNode("fingerprint", "Video Fingerprint", NodeMode.PARALLEL, true, 2, true, Map.of());
PipelineNode rejectedLogger = processorNode("rejected-logger", "Rejection Logger", NodeMode.SEQUENTIAL, false, 1, false, Map.of());

filesystem.connectTo(videoFilter);
videoFilter.connectTo(fingerprint, FilterBranch.PASS);
videoFilter.connectTo(rejectedLogger, FilterBranch.REJECT);

Pipeline original = DefaultPipeline.builder("filter-pipeline")
.description("Pipeline with filter branches")
.priority(50)
.enabled(false)
.dryRun(true)
.source(filesystem)
.build();

Pipeline restored = roundTrip(original);

assertPipelinesEqual(original, restored);
}

@Test
void testPipelineWithNestedOptions() throws JsonProcessingException {
Map<String, Object> options = Map.of(
"modelPath", "/models/whisper-large.bin",
"temperature", 0.2,
"enabled", true,
"maxRetries", 3L,
"tags", List.of("video", "audio")
);

PipelineNode fs = sourceNode("fs", "FS");
PipelineNode whisper = processorNode("whisper", "Whisper", NodeMode.SEQUENTIAL, true, 1, true, 600000L, options);

fs.connectTo(whisper);

Pipeline original = DefaultPipeline.builder("options-pipeline")
.description("Pipeline testing complex options")
.priority(10)
.enabled(true)
.dryRun(false)
.source(fs)
.build();

Pipeline restored = roundTrip(original);

assertPipelinesEqual(original, restored);
}

@Test
void testPipelineWithTimeouts() throws JsonProcessingException {
PipelineNode fs = sourceNode("fs", "FS");
PipelineNode fastNode = processorNode("fast", "Fast Node", NodeMode.PARALLEL, true, 4, false, 1000L, Map.of());
PipelineNode slowNode = processorNode("slow", "Slow Node", NodeMode.SEQUENTIAL, true, 1, true, 300000L, Map.of());
PipelineNode noTimeoutNode = processorNode("no-timeout", "No Timeout Node", NodeMode.PARALLEL, true, 2, false, 0L, Map.of());

fs.connectTo(fastNode);
fs.connectTo(slowNode);
fs.connectTo(noTimeoutNode);

Pipeline original = DefaultPipeline.builder("timeout-pipeline")
.description("Pipeline with various timeout configurations")
.priority(20)
.enabled(true)
.dryRun(false)
.source(fs)
.build();

Pipeline restored = roundTrip(original);

assertPipelinesEqual(original, restored);
}

@Test
void testDoubleRoundTrip() throws JsonProcessingException {
PipelineNode source = sourceNode("source", "Source");
PipelineNode hash = processorNode("hash", "Hash", NodeMode.PARALLEL, true, 4, true, Map.of("algo", "sha512"));
PipelineNode thumb = processorNode("thumb", "Thumbnail", NodeMode.SEQUENTIAL, true, 1, false, Map.of());

source.connectTo(hash);
hash.connectTo(thumb);

Pipeline original = DefaultPipeline.builder("double-trip")
.description("Ensure idempotency")
.priority(42)
.enabled(true)
.dryRun(false)
.source(source)
.build();

String json1 = serializer.serialize(original);
Pipeline restored1 = deserializer.deserialize(json1);
String json2 = serializer.serialize(restored1);
Pipeline restored2 = deserializer.deserialize(json2);

// JSON output must be identical across round-trips
assertEquals(json1, json2, "JSON should be identical after double round-trip");
assertPipelinesEqual(original, restored2);
}

// --- Helpers ---

private Pipeline roundTrip(Pipeline original) throws JsonProcessingException {
String json = serializer.serialize(original);
assertNotNull(json);
assertFalse(json.isEmpty());
return deserializer.deserialize(json);
}

private void assertPipelinesEqual(Pipeline expected, Pipeline actual) {
assertEquals(expected.name(), actual.name(), "name");
assertEquals(expected.description(), actual.description(), "description");
assertEquals(expected.priority(), actual.priority(), "priority");
assertEquals(expected.isEnabled(), actual.isEnabled(), "enabled");
assertEquals(expected.isDryRun(), actual.isDryRun(), "dryRun");

// Source node
assertNotNull(actual.sourceNode(), "sourceNode should not be null");
assertEquals(expected.sourceNode().id(), actual.sourceNode().id(), "sourceNode id");

// Nodes count
List<PipelineNode> expectedNodes = expected.nodes();
List<PipelineNode> actualNodes = actual.nodes();
assertEquals(expectedNodes.size(), actualNodes.size(), "nodes count");

// Compare each node by id (order may differ due to topological sort of DeserializedNodes)
for (PipelineNode expectedNode : expectedNodes) {
PipelineNode actualNode = actual.node(expectedNode.id());
assertNotNull(actualNode, "node '" + expectedNode.id() + "' should exist in deserialized pipeline");
assertNodesEqual(expectedNode, actualNode);
}
}

private void assertNodesEqual(PipelineNode expected, PipelineNode actual) {
String ctx = "node[" + expected.id() + "]";
assertEquals(expected.id(), actual.id(), ctx + ".id");
assertEquals(expected.name(), actual.name(), ctx + ".name");
assertEquals(expected.isSource(), actual.isSource(), ctx + ".isSource");
assertEquals(expected.mode(), actual.mode(), ctx + ".mode");
assertEquals(expected.isBlocking(), actual.isBlocking(), ctx + ".blocking");
assertEquals(expected.concurrency(), actual.concurrency(), ctx + ".concurrency");
assertEquals(expected.syncToLoom(), actual.syncToLoom(), ctx + ".syncToLoom");
assertEquals(expected.timeoutMs(), actual.timeoutMs(), ctx + ".timeoutMs");
assertEquals(expected.dependencies(), actual.dependencies(), ctx + ".dependencies");
assertEquals(expected.conditionalDependencies(), actual.conditionalDependencies(), ctx + ".conditionalDependencies");
assertEquals(expected.options(), actual.options(), ctx + ".options");
}

// --- Node factories ---

private static PipelineNode sourceNode(String id, String name) {
TestNode node = new TestNode(id, name, NodeMode.PARALLEL, true, 1, false, 0, Map.of());
node.setSource(true);
return node;
}

private static PipelineNode processorNode(String id, String name, NodeMode mode, boolean blocking,
int concurrency, boolean syncToLoom, Map<String, Object> options) {
return new TestNode(id, name, mode, blocking, concurrency, syncToLoom, 0, options);
}

private static PipelineNode processorNode(String id, String name, NodeMode mode, boolean blocking,
int concurrency, boolean syncToLoom, long timeoutMs, Map<String, Object> options) {
return new TestNode(id, name, mode, blocking, concurrency, syncToLoom, timeoutMs, options);
}

/**
 * Concrete test node with configurable options and source flag.
 */
private static class TestNode extends AbstractPipelineNode {

private final Map<String, Object> options;

TestNode(String id, String name, NodeMode mode, boolean blocking,
int concurrency, boolean syncToLoom, Map<String, Object> options) {
super(id, name, mode, blocking, concurrency, syncToLoom, 0);
this.options = options != null ? Map.copyOf(options) : Map.of();
}

TestNode(String id, String name, NodeMode mode, boolean blocking,
int concurrency, boolean syncToLoom, long timeoutMs, Map<String, Object> options) {
super(id, name, mode, blocking, concurrency, syncToLoom, timeoutMs);
this.options = options != null ? Map.copyOf(options) : Map.of();
}

@Override
public Map<String, Object> options() {
return options;
}

@Override
public NodeResult process(LoomMedia media, Map<String, NodeResult> upstreamResults) {
return NodeResult.success(id(), 0);
}
}
}
