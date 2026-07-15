package io.metaloom.loom.rest.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.nodes.spec.NodeCategory;
import io.metaloom.loom.nodes.spec.NodeDescriptor;
import io.metaloom.loom.nodes.spec.NodeDescriptorRegistry;
import io.metaloom.loom.nodes.spec.NodeInput;
import io.metaloom.loom.nodes.spec.NodeMode;
import io.metaloom.loom.nodes.spec.NodeOutput;
import io.metaloom.loom.nodes.spec.NodeParameter;
import io.metaloom.loom.nodes.spec.ParameterType;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Tests for {@link PipelineValidationService}.
 */
public class PipelineValidationServiceTest {

    private NodeDescriptorRegistry registry;
    private PipelineValidationService service;

    @BeforeEach
    public void setUp() {
        registry = new NodeDescriptorRegistry();
        
        // Register some test node descriptors
        registry.register(createDescriptor("sha512", "SHA-512 Hash", NodeCategory.ANALYSIS));
        registry.register(createDescriptor("sha256", "SHA-256 Hash", NodeCategory.ANALYSIS));
        registry.register(createDescriptor("md5", "MD5 Hash", NodeCategory.ANALYSIS));
        registry.register(createDescriptor("thumbnail", "Thumbnail", NodeCategory.ANALYSIS));
        registry.register(createDescriptor("facedetect", "Face Detect", NodeCategory.ANALYSIS));
        
        service = new PipelineValidationService(registry);
    }

    private NodeDescriptor createDescriptor(String kind, String name, NodeCategory category) {
        return new NodeDescriptor()
            .setKind(kind)
            .setName(name)
            .setDescription("Test node: " + name)
            .setIcon("test")
            .setCategory(category)
            .setInputs(List.of(new NodeInput("media", "media/any", true)))
            .setOutputs(List.of(new NodeOutput("output", "data/any")))
            .setParameters(List.of())
            .setDefaultConcurrency(1)
            .setDefaultMode(NodeMode.PARALLEL)
            .setDefaultBlocking(true)
            .setEvents(List.of("NODE_STARTED", "NODE_COMPLETED", "NODE_FAILED", "NODE_SKIPPED"));
    }

    // ── Valid pipeline tests ────────────────────────────────────────────────

    @Test
    public void testValidSimplePipeline() {
        JsonObject definition = createPipelineDefinition(
            List.of(
                createNode("source", "sha512"),
                createNode("hash", "sha256")
            ),
            List.of(createEdge("source", "hash"))
        );
        
        assertDoesNotThrow(() -> service.validateDefinition(definition));
    }

    @Test
    public void testValidPipelineWithMultipleNodes() {
        JsonObject definition = createPipelineDefinition(
            List.of(
                createNode("source", "sha512"),
                createNode("hash1", "sha256"),
                createNode("hash2", "md5"),
                createNode("thumb", "thumbnail")
            ),
            List.of(
                createEdge("source", "hash1"),
                createEdge("source", "hash2"),
                createEdge("hash1", "thumb"),
                createEdge("hash2", "thumb")
            )
        );
        
        assertDoesNotThrow(() -> service.validateDefinition(definition));
    }

    @Test
    public void testValidPipelineWithFilterNode() {
        JsonObject definition = createPipelineDefinition(
            List.of(
                createNode("source", "sha512"),
                createNode("filter", "facedetect"),
                createNode("hash", "sha256")
            ),
            List.of(
                createEdge("source", "filter"),
                createEdge("filter", "hash")
            )
        );
        
        assertDoesNotThrow(() -> service.validateDefinition(definition));
    }

    // ── Invalid node ID tests ───────────────────────────────────────────────

    @Test
    public void testInvalidNodeIdUppercase() {
        JsonObject definition = createPipelineDefinition(
            List.of(createNode("Source", "sha512")),
            List.of()
        );
        
        ValidationException ex = assertThrows(ValidationException.class, 
            () -> service.validateDefinition(definition));
        assertTrue(ex.getMessage().contains("Invalid node ID"));
        assertTrue(ex.getMessage().contains("Source"));
    }

    @Test
    public void testInvalidNodeIdSpecialChars() {
        JsonObject definition = createPipelineDefinition(
            List.of(createNode("source-node!", "sha512")),
            List.of()
        );
        
        ValidationException ex = assertThrows(ValidationException.class, 
            () -> service.validateDefinition(definition));
        assertTrue(ex.getMessage().contains("Invalid node ID"));
    }

    @Test
    public void testInvalidNodeIdTooLong() {
        String longId = "a".repeat(65); // Max is 64
        JsonObject definition = createPipelineDefinition(
            List.of(createNode(longId, "sha512")),
            List.of()
        );
        
        ValidationException ex = assertThrows(ValidationException.class, 
            () -> service.validateDefinition(definition));
        assertTrue(ex.getMessage().contains("Invalid node ID"));
    }

    @Test
    public void testValidNodeIdMaxLength() {
        String maxId = "a".repeat(64);
        JsonObject definition = createPipelineDefinition(
            List.of(createNode(maxId, "sha512")),
            List.of()
        );
        
        assertDoesNotThrow(() -> service.validateDefinition(definition));
    }

    @Test
    public void testValidNodeIdWithHyphens() {
        JsonObject definition = createPipelineDefinition(
            List.of(createNode("source-node-hash", "sha512")),
            List.of()
        );
        
        assertDoesNotThrow(() -> service.validateDefinition(definition));
    }

    @Test
    public void testMissingNodeId() {
        JsonObject definition = createPipelineDefinition(
            List.of(new JsonObject().put("type", "sha512")),
            List.of()
        );
        
        ValidationException ex = assertThrows(ValidationException.class, 
            () -> service.validateDefinition(definition));
        assertTrue(ex.getMessage().contains("missing an id"));
    }

    // ── Duplicate node ID tests ─────────────────────────────────────────────

    @Test
    public void testDuplicateNodeIds() {
        JsonObject definition = createPipelineDefinition(
            List.of(
                createNode("source", "sha512"),
                createNode("source", "sha256") // duplicate id
            ),
            List.of()
        );
        
        ValidationException ex = assertThrows(ValidationException.class, 
            () -> service.validateDefinition(definition));
        assertTrue(ex.getMessage().contains("Duplicate node ID"));
        assertTrue(ex.getMessage().contains("source"));
    }

    // ── Missing node type tests ─────────────────────────────────────────────

    @Test
    public void testMissingNodeType() {
        JsonObject definition = createPipelineDefinition(
            List.of(new JsonObject().put("id", "source")),
            List.of()
        );
        
        ValidationException ex = assertThrows(ValidationException.class, 
            () -> service.validateDefinition(definition));
        assertTrue(ex.getMessage().contains("missing a type"));
    }

    // ── Unknown node type tests ─────────────────────────────────────────────

    @Test
    public void testUnknownNodeType() {
        JsonObject definition = createPipelineDefinition(
            List.of(createNode("source", "unknown-type")),
            List.of()
        );
        
        ValidationException ex = assertThrows(ValidationException.class, 
            () -> service.validateDefinition(definition));
        assertTrue(ex.getMessage().contains("Unknown node type"));
        assertTrue(ex.getMessage().contains("unknown-type"));
    }

    // ── Edge validation tests ───────────────────────────────────────────────

    @Test
    public void testEdgeReferencesNonExistentSource() {
        JsonObject definition = createPipelineDefinition(
            List.of(createNode("target", "sha256")),
            List.of(createEdge("nonexistent", "target"))
        );
        
        ValidationException ex = assertThrows(ValidationException.class, 
            () -> service.validateDefinition(definition));
        assertTrue(ex.getMessage().contains("source"));
        assertTrue(ex.getMessage().contains("does not match any node ID"));
    }

    @Test
    public void testEdgeReferencesNonExistentTarget() {
        JsonObject definition = createPipelineDefinition(
            List.of(createNode("source", "sha512")),
            List.of(createEdge("source", "nonexistent"))
        );
        
        ValidationException ex = assertThrows(ValidationException.class, 
            () -> service.validateDefinition(definition));
        assertTrue(ex.getMessage().contains("target"));
        assertTrue(ex.getMessage().contains("does not match any node ID"));
    }

    @Test
    public void testEdgeMissingSource() {
        JsonObject definition = createPipelineDefinition(
            List.of(createNode("target", "sha256")),
            List.of(new JsonObject().put("target", "target"))
        );
        
        ValidationException ex = assertThrows(ValidationException.class, 
            () -> service.validateDefinition(definition));
        assertTrue(ex.getMessage().contains("missing a source"));
    }

    @Test
    public void testEdgeMissingTarget() {
        JsonObject definition = createPipelineDefinition(
            List.of(createNode("source", "sha512")),
            List.of(new JsonObject().put("source", "source"))
        );
        
        ValidationException ex = assertThrows(ValidationException.class, 
            () -> service.validateDefinition(definition));
        assertTrue(ex.getMessage().contains("missing a target"));
    }

    // ── Cycle detection tests ───────────────────────────────────────────────

    @Test
    public void testDirectCycle() {
        JsonObject definition = createPipelineDefinition(
            List.of(
                createNode("a", "sha512"),
                createNode("b", "sha256")
            ),
            List.of(
                createEdge("a", "b"),
                createEdge("b", "a") // cycle
            )
        );
        
        ValidationException ex = assertThrows(ValidationException.class, 
            () -> service.validateDefinition(definition));
        assertTrue(ex.getMessage().contains("Cycle detected"));
    }

    @Test
    public void testIndirectCycle() {
        JsonObject definition = createPipelineDefinition(
            List.of(
                createNode("a", "sha512"),
                createNode("b", "sha256"),
                createNode("c", "md5")
            ),
            List.of(
                createEdge("a", "b"),
                createEdge("b", "c"),
                createEdge("c", "a") // cycle
            )
        );
        
        ValidationException ex = assertThrows(ValidationException.class, 
            () -> service.validateDefinition(definition));
        assertTrue(ex.getMessage().contains("Cycle detected"));
    }

    @Test
    public void testSelfLoop() {
        JsonObject definition = createPipelineDefinition(
            List.of(createNode("a", "sha512")),
            List.of(createEdge("a", "a")) // self-loop
        );
        
        ValidationException ex = assertThrows(ValidationException.class, 
            () -> service.validateDefinition(definition));
        assertTrue(ex.getMessage().contains("Cycle detected"));
    }

    @Test
    public void testNoCycleInValidDAG() {
        JsonObject definition = createPipelineDefinition(
            List.of(
                createNode("a", "sha512"),
                createNode("b", "sha256"),
                createNode("c", "md5"),
                createNode("d", "thumbnail")
            ),
            List.of(
                createEdge("a", "b"),
                createEdge("a", "c"),
                createEdge("b", "d"),
                createEdge("c", "d")
            )
        );
        
        assertDoesNotThrow(() -> service.validateDefinition(definition));
    }

    // ── Empty pipeline tests ────────────────────────────────────────────────

    @Test
    public void testEmptyNodesArray() {
        JsonObject definition = new JsonObject().put("nodes", new JsonArray());
        
        ValidationException ex = assertThrows(ValidationException.class, 
            () -> service.validateDefinition(definition));
        assertTrue(ex.getMessage().contains("at least one node"));
    }

    @Test
    public void testNullNodesArray() {
        JsonObject definition = new JsonObject(); // no nodes key
        
        ValidationException ex = assertThrows(ValidationException.class, 
            () -> service.validateDefinition(definition));
        assertTrue(ex.getMessage().contains("at least one node"));
    }

    @Test
    public void testNullDefinition() {
        ValidationException ex = assertThrows(ValidationException.class, 
            () -> service.validateDefinition(null));
        assertTrue(ex.getMessage().contains("must be set"));
    }

    // ── Helper methods ──────────────────────────────────────────────────────

    private JsonObject createPipelineDefinition(List<JsonObject> nodes, List<JsonObject> edges) {
        JsonObject definition = new JsonObject();
        JsonArray nodesArray = new JsonArray();
        nodes.forEach(nodesArray::add);
        definition.put("nodes", nodesArray);
        
        if (edges != null && !edges.isEmpty()) {
            JsonArray edgesArray = new JsonArray();
            edges.forEach(edgesArray::add);
            definition.put("edges", edgesArray);
        }
        
        return definition;
    }

    private JsonObject createNode(String id, String type) {
        return new JsonObject()
            .put("id", id)
            .put("type", type);
    }

    private JsonObject createEdge(String source, String target) {
        return new JsonObject()
            .put("source", source)
            .put("target", target);
    }
}