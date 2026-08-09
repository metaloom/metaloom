package io.metaloom.loom.rest.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.rest.model.pipeline.PipelineCreateRequest;
import io.metaloom.loom.rest.model.pipeline.PipelineResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineUpdateRequest;
import io.metaloom.loom.rest.model.user.UserReference;
import io.metaloom.loom.rest.validation.impl.LoomModelValidatorImpl;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * {@link PipelineModelValidator} — what a pipeline request and response must <em>look like</em>.
 *
 * <p>
 * Nothing here is about the contents of a definition. That used to be the bulk of this class: node
 * ids, edge references and its own copy of Kahn's algorithm, a second implementation of rules
 * {@code PipelineValidationService} also owned. Those checks are gone and their cases went with
 * them — see {@link PipelineValidationServiceTest}, which is now the only place the structural rules
 * are described.
 * </p>
 *
 * <p>
 * What remains is the part this module can actually answer without a descriptor registry: a create
 * request needs a name and a definition, an update needs neither, and a response has to carry the
 * version identity a client needs to edit it.
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

	/**
	 * A definition this module would once have refused is accepted here — the check moved, it did
	 * not disappear.
	 *
	 * <p>
	 * {@code PipelineAuthoringService} runs {@code PipelineValidationService} over the same request
	 * immediately after this validator, so nothing structural reaches the database. Asserting the
	 * silence is the guard against someone restoring a second copy of the rules here.
	 * </p>
	 */
	@Test
	@DisplayName("A structurally broken definition is not this validator's business")
	public void testTheDefinitionContentsAreNotCheckedHere() {
		assertDoesNotThrow(() -> validator.validate(
			new PipelineCreateRequest().setName("p").setDefinition(validDefinition()
				.put("edges", new JsonArray().add(edge("pn1", "ghost"))))));
		assertDoesNotThrow(() -> validator.validate(
			new PipelineCreateRequest().setName("p").setDefinition(new JsonObject().put("nodes", new JsonArray()))));
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
	 * sent, and re-running the graph rules on every read would cost a traversal per response.
	 */
	@Test
	public void testAResponseDefinitionIsNotRevalidatedStructurally() {
		assertDoesNotThrow(() -> validator.validate(response().setDefinition(new JsonObject())));
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
