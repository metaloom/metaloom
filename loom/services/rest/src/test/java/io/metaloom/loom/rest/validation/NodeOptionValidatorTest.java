package io.metaloom.loom.rest.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.nodes.spec.NodeDescriptor;
import io.metaloom.loom.nodes.spec.NodeParameter;
import io.metaloom.loom.nodes.spec.ParameterType;

/**
 * Checking node options against the parameters the node declares.
 *
 * <p>
 * The rejections are the point. Until re-execution, options only ever arrived from an editor form
 * generated from these same declarations, so nothing invalid was reachable; a request can carry
 * anything. An unchecked bad value does not fail loudly — it becomes a node failure to be found in
 * a log, or an option the node ignores while the operator concludes the setting does nothing.
 * </p>
 */
public class NodeOptionValidatorTest {

	private static NodeParameter parameter(String key, ParameterType type) {
		return new NodeParameter().setKey(key).setType(type).setLabel(key);
	}

	/** A node with one bounded integer, one enum and one free-form string. */
	private static NodeDescriptor descriptor() {
		return new NodeDescriptor()
			.setName("thumbnail")
			.setParameters(List.of(
				parameter("cols", ParameterType.INTEGER).setMin(1).setMax(20),
				parameter("scale", ParameterType.NUMBER).setMin(0.0).setMax(1.0),
				parameter("enabled", ParameterType.BOOLEAN),
				parameter("mode", ParameterType.ENUM).setValues(List.of("fast", "accurate")),
				parameter("tags", ParameterType.ENUM_SET).setValues(List.of("a", "b")),
				parameter("label", ParameterType.STRING)));
	}

	private static String messageOf(org.junit.jupiter.api.function.Executable call) {
		return assertThrows(ValidationException.class, call).getMessage();
	}

	@Test
	@DisplayName("a value inside its declared range passes")
	void testValidOptionsPass() {
		assertDoesNotThrow(() -> NodeOptionValidator.validate(descriptor(), Map.of(
			"cols", 6, "scale", 0.5, "enabled", true, "mode", "fast", "tags", List.of("a"), "label", "anything")));
	}

	@Test
	@DisplayName("a partial set passes, because changing one setting means changing one setting")
	void testPartialOptionsPass() {
		assertDoesNotThrow(() -> NodeOptionValidator.validate(descriptor(), Map.of("cols", 6)));
		assertDoesNotThrow(() -> NodeOptionValidator.validate(descriptor(), Map.of()));
		assertDoesNotThrow(() -> NodeOptionValidator.validate(descriptor(), null));
	}

	@Test
	@DisplayName("a parameter the node does not declare is rejected by name")
	void testUnknownParameterIsRejected() {
		assertTrue(messageOf(() -> NodeOptionValidator.validate(descriptor(), Map.of("colsss", 6)))
			.contains("colsss"));
	}

	@Test
	@DisplayName("a number outside min/max is rejected, naming the bound it broke")
	void testOutOfRangeIsRejected() {
		assertTrue(messageOf(() -> NodeOptionValidator.validate(descriptor(), Map.of("cols", 99)))
			.contains("at most 20"));
		assertTrue(messageOf(() -> NodeOptionValidator.validate(descriptor(), Map.of("cols", 0)))
			.contains("at least 1"));
		assertTrue(messageOf(() -> NodeOptionValidator.validate(descriptor(), Map.of("scale", 1.5)))
			.contains("at most"));
	}

	@Test
	@DisplayName("a value of the wrong type is rejected")
	void testTypeMismatchIsRejected() {
		assertTrue(messageOf(() -> NodeOptionValidator.validate(descriptor(), Map.of("cols", "six")))
			.contains("whole number"));
		assertTrue(messageOf(() -> NodeOptionValidator.validate(descriptor(), Map.of("cols", 1.5)))
			.contains("whole number"), "A fractional value for an integer parameter is not merely rounded");
		assertTrue(messageOf(() -> NodeOptionValidator.validate(descriptor(), Map.of("enabled", "yes")))
			.contains("boolean"));
	}

	@Test
	@DisplayName("an enum value outside the allowed set is rejected, listing what is allowed")
	void testEnumIsRejected() {
		String message = messageOf(() -> NodeOptionValidator.validate(descriptor(), Map.of("mode", "quick")));
		assertTrue(message.contains("fast") && message.contains("accurate"),
			"Naming the alternatives is the whole value of the check: " + message);
		assertTrue(messageOf(() -> NodeOptionValidator.validate(descriptor(), Map.of("tags", List.of("a", "z"))))
			.contains("z"));
	}

	@Test
	@DisplayName("an unknown node kind passes rather than being refused")
	void testUnknownDescriptorPasses() {
		// A third-party node this Loom has never seen. Refusing would make the feature unusable
		// against it the moment its worker reconnected; the node itself is the authority on what it
		// accepts.
		assertDoesNotThrow(() -> NodeOptionValidator.validate(null, Map.of("whatever", 1)));
	}
}
