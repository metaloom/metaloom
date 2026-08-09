package io.metaloom.loom.rest.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.rest.model.pipeline.PipelineCreateRequest;
import io.metaloom.loom.rest.model.pipeline.PipelineResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineUpdateRequest;
import io.metaloom.loom.rest.model.user.UserReference;
import io.metaloom.loom.rest.validation.impl.LoomModelValidatorImpl;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * {@link PipelineModelValidator}, which had no test at all.
 *
 * <p>
 * This is the shared-model copy of the structural rules — the second of the three implementations
 * {@code spec/tasks/PIPELINE_TASKS.md} Task 8 exists to collapse (the others being
 * {@code PipelineValidationService} and {@code validatePipeline()} in the editor). Until that
 * consolidation lands, the copy is on the classpath and reachable through
 * {@link LoomModelValidator}, so what it accepts and rejects is worth pinning: an untested validator
 * is one that can start rejecting valid definitions without anybody noticing.
 * </p>
 *
 * <p>
 * Two of the cases here are the ones that mattered most in practice. A definition whose
 * {@code nodes} is present but is not an array used to reach {@code getJsonArray} and throw a
 * {@code ClassCastException}, turning a client's bad request into a 500. And a definition whose
 * edges form a cycle has to be refused here rather than at run time, because the graph the engine
 * builds from it cannot be ordered at all.
 * </p>
 *
 * <p>
 * <strong>If Task 8 deletes the structural checks, delete these cases with them.</strong> They
 * describe that class's behaviour, not a requirement of the REST layer — the authority for these
 * rules is meant to end up in one place.
 * </p>
 */
public class PipelineModelValidatorTest {

	private final LoomModelValidator validator = new LoomModelValidatorImpl();

	private static JsonObject node(String id, String type) {
		return new JsonObject().put("id", id).put("type", type);
	}

	private static JsonObject edge(String source, String target) {
		return new JsonObject().put("source", source).put("target", target);
	}

	/** A three-node chain, the shape everything below varies from. */
	private static JsonObject validDefinition() {
		return new JsonObject()
			.put("nodes", new JsonArray()
				.add(node("pn1", "filesystem-source"))
				.add(node("pn2", "sha512"))
				.add(node("pn3", "md5")))
			.put("edges", new JsonArray()
				.add(edge("pn1", "pn2"))
				.add(edge("pn2", "pn3")));
	}

	private ValidationException rejected(JsonObject definition) {
		return assertThrows(ValidationException.class, () -> validator.validateDefinition(definition));
	}

	// ── The happy path ────────────────────────────────────────────────────

	@Test
	public void testAWellFormedDefinitionIsAccepted() {
		assertDoesNotThrow(() -> validator.validateDefinition(validDefinition()));
	}

	@Test
	public void testEdgesAreOptional() {
		// A single-node pipeline is legitimate, and so is a definition that simply has no edges yet.
		assertDoesNotThrow(() -> validator.validateDefinition(
			new JsonObject().put("nodes", new JsonArray().add(node("pn1", "filesystem-source")))));
	}

	@Test
	public void testADiamondIsNotACycle() {
		// Kahn's counts visited nodes, so a join reached by two paths must not be mistaken for a loop.
		assertDoesNotThrow(() -> validator.validateDefinition(new JsonObject()
			.put("nodes", new JsonArray()
				.add(node("src", "filesystem-source"))
				.add(node("left", "sha512"))
				.add(node("right", "md5"))
				.add(node("join", "scene-layout")))
			.put("edges", new JsonArray()
				.add(edge("src", "left"))
				.add(edge("src", "right"))
				.add(edge("left", "join"))
				.add(edge("right", "join")))));
	}

	// ── Shape of the definition itself ────────────────────────────────────

	/**
	 * The wrong <em>type</em> under a known key is a client error, not an internal one. Reading it
	 * straight through {@code getJsonArray} threw {@code ClassCastException} out of the validator,
	 * which the REST layer has no case for and answers with a 500.
	 */
	@Test
	public void testANonArrayNodesFieldIsABadRequestRatherThanACrash() {
		ValidationException e = rejected(new JsonObject().put("nodes", new JsonObject().put("pn1", "sha512")));
		assertTrue(e.getMessage().contains("must be an array"), e.getMessage());
	}

	@Test
	public void testANonArrayEdgesFieldIsABadRequestRatherThanACrash() {
		ValidationException e = rejected(validDefinition().put("edges", "pn1->pn2"));
		assertTrue(e.getMessage().contains("must be an array"), e.getMessage());
	}

	@Test
	public void testADefinitionWithoutNodesIsRejected() {
		assertThrows(ValidationException.class, () -> validator.validateDefinition(null));
		rejected(new JsonObject());
		rejected(new JsonObject().put("nodes", new JsonArray()));
	}

	@Test
	public void testANullEntryInAnArrayIsRejectedByPosition() {
		assertTrue(rejected(new JsonObject().put("nodes", new JsonArray().add(node("pn1", "sha512")).addNull()))
			.getMessage().contains("index 1"));
		assertTrue(rejected(validDefinition().put("edges", new JsonArray().addNull()))
			.getMessage().contains("index 0"));
	}

	// ── Node rules ────────────────────────────────────────────────────────

	@Test
	public void testANodeWithoutAnIdOrTypeIsRejected() {
		assertTrue(rejected(new JsonObject().put("nodes", new JsonArray().add(new JsonObject().put("type", "sha512"))))
			.getMessage().contains("missing an id"));
		assertTrue(rejected(new JsonObject().put("nodes", new JsonArray().add(new JsonObject().put("id", "pn1"))))
			.getMessage().contains("missing a type"));
		assertTrue(rejected(new JsonObject().put("nodes", new JsonArray().add(node("pn1", "  "))))
			.getMessage().contains("missing a type"), "A blank type is no type");
	}

	/**
	 * The id ends up in URLs, log lines and edge references, so it is restricted to lowercase
	 * alphanumerics and inner hyphens.
	 */
	@Test
	public void testTheNodeIdPatternIsEnforced() {
		for (String bad : new String[] { "PN1", "pn 1", "-pn1", "pn1-", "pn_1", "pn.1", "ä" }) {
			ValidationException e = rejected(new JsonObject().put("nodes", new JsonArray().add(node(bad, "sha512"))));
			assertTrue(e.getMessage().contains("Invalid node ID"), "Expected '" + bad + "' to be refused: " + e.getMessage());
		}
		for (String good : new String[] { "a", "1", "pn1", "filesystem-source-1", "a-b-c" }) {
			assertDoesNotThrow(() -> validator.validateDefinition(
				new JsonObject().put("nodes", new JsonArray().add(node(good, "sha512")))),
				"Expected '" + good + "' to be accepted");
		}
	}

	@Test
	public void testDuplicateNodeIdsAreRejected() {
		// Edges address nodes by id, so two nodes sharing one makes every edge to it ambiguous.
		ValidationException e = rejected(new JsonObject().put("nodes", new JsonArray()
			.add(node("pn1", "sha512"))
			.add(node("pn1", "md5"))));
		assertTrue(e.getMessage().contains("Duplicate node ID"), e.getMessage());
	}

	// ── Edge rules ────────────────────────────────────────────────────────

	@Test
	public void testAnEdgeWithoutASourceOrTargetIsRejected() {
		assertTrue(rejected(validDefinition().put("edges", new JsonArray().add(new JsonObject().put("target", "pn2"))))
			.getMessage().contains("missing a source"));
		assertTrue(rejected(validDefinition().put("edges", new JsonArray().add(new JsonObject().put("source", "pn1"))))
			.getMessage().contains("missing a target"));
	}

	@Test
	public void testAnEdgeReferencingAnUnknownNodeIsRejectedByName() {
		assertTrue(rejected(validDefinition().put("edges", new JsonArray().add(edge("ghost", "pn2"))))
			.getMessage().contains("ghost"));
		assertTrue(rejected(validDefinition().put("edges", new JsonArray().add(edge("pn1", "ghost"))))
			.getMessage().contains("ghost"));
	}

	@Test
	public void testACycleIsRejected() {
		ValidationException e = rejected(validDefinition().put("edges", new JsonArray()
			.add(edge("pn1", "pn2"))
			.add(edge("pn2", "pn3"))
			.add(edge("pn3", "pn1"))));
		assertTrue(e.getMessage().contains("Cycle detected"), e.getMessage());
	}

	@Test
	public void testASelfEdgeIsACycle() {
		// The one-node loop is the case a naive "have I seen this node twice" check misses.
		assertTrue(rejected(new JsonObject()
			.put("nodes", new JsonArray().add(node("pn1", "sha512")))
			.put("edges", new JsonArray().add(edge("pn1", "pn1"))))
				.getMessage().contains("Cycle detected"));
	}

	@Test
	public void testACycleIsFoundEvenWhenPartOfTheGraphIsAcyclic() {
		// Kahn's drains the acyclic prefix first; the check is that the *remaining* nodes are noticed
		// rather than the traversal simply finishing.
		assertTrue(rejected(new JsonObject()
			.put("nodes", new JsonArray()
				.add(node("src", "filesystem-source"))
				.add(node("a", "sha512"))
				.add(node("b", "md5"))
				.add(node("c", "sha256")))
			.put("edges", new JsonArray()
				.add(edge("src", "a"))
				.add(edge("b", "c"))
				.add(edge("c", "b"))))
					.getMessage().contains("Cycle detected"));
	}

	// ── The request and response entry points ─────────────────────────────

	@Test
	public void testACreateRequestNeedsANameAndADefinition() {
		assertThrows(ValidationException.class, () -> validator.validate((PipelineCreateRequest) null));
		assertThrows(ValidationException.class,
			() -> validator.validate(new PipelineCreateRequest().setDefinition(validDefinition())));
		assertThrows(ValidationException.class, () -> validator.validate(new PipelineCreateRequest().setName("p")));

		assertDoesNotThrow(() -> validator.validate(
			new PipelineCreateRequest().setName("p").setDefinition(validDefinition())));
	}

	@Test
	public void testACreateRequestsDefinitionIsCheckedStructurally() {
		ValidationException e = assertThrows(ValidationException.class, () -> validator.validate(
			new PipelineCreateRequest().setName("p").setDefinition(validDefinition()
				.put("edges", new JsonArray().add(edge("pn1", "ghost"))))));
		assertTrue(e.getMessage().contains("ghost"), e.getMessage());
	}

	/**
	 * An update may legitimately touch only the name or the priority, so a null definition means
	 * "unchanged" rather than "invalid". Requiring one here would make renaming a pipeline
	 * impossible without resending its whole graph.
	 */
	@Test
	public void testAnUpdateWithoutADefinitionLeavesTheGraphAlone() {
		assertDoesNotThrow(() -> validator.validate(new PipelineUpdateRequest().setName("renamed")));
		assertDoesNotThrow(() -> validator.validate((PipelineUpdateRequest) null));

		assertThrows(ValidationException.class, () -> validator.validate(
			new PipelineUpdateRequest().setDefinition(new JsonObject().put("nodes", new JsonArray()))));
	}

	@Test
	public void testAResponseCarriesItsVersionIdentity() {
		assertDoesNotThrow(() -> validator.validate(response()));

		// A response without a version cannot be edited: the client has nothing to base the next
		// version on.
		assertThrows(ValidationException.class, () -> validator.validate(response().setVersionUuid(null)));
		assertThrows(ValidationException.class, () -> validator.validate(response().setVersionNumber(null)));
		assertThrows(ValidationException.class, () -> validator.validate(response().setName(null)));
		assertThrows(ValidationException.class, () -> validator.validate(response().setDefinition(null)));
	}

	/**
	 * The response check is deliberately not structural: it is a shape assertion on what the server
	 * sent, and re-running Kahn's on every read would cost a graph traversal per response.
	 */
	@Test
	public void testAResponseDefinitionIsNotRevalidatedStructurally() {
		assertDoesNotThrow(() -> validator.validate(response().setDefinition(new JsonObject())));
	}

	@Test
	public void testTheNodeIdPatternIsSharedRatherThanRedeclared() {
		// A second copy of this regex is how the editor and the server end up disagreeing about
		// which ids are legal.
		assertEquals("^[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?$", PipelineModelValidator.NODE_ID_PATTERN.pattern());
	}

	private static PipelineResponse response() {
		PipelineResponse response = new PipelineResponse();
		response.setUuid(UUID.randomUUID());
		response.getStatus().setCreator(new UserReference().setName("abc").setUuid(UUID.randomUUID()));
		response.getStatus().setEditor(new UserReference().setName("abc").setUuid(UUID.randomUUID()));
		response.getStatus().setCreated(Instant.now());
		response.getStatus().setEdited(Instant.now());
		return response
			.setVersionUuid(UUID.randomUUID())
			.setVersionNumber(1)
			.setName("demo")
			.setDefinition(validDefinition());
	}
}
