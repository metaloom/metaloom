package io.metaloom.loom.nodes.spec;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * Structural conformance of every advertised {@link NodeDescriptor}'s ports.
 *
 * <p>
 * Descriptors are hand-written data, and the previous model let them drift for months without
 * anything noticing: {@code facedetect} and {@code whisper} both declared two input connectors
 * literally named {@code media}, the {@code llm} descriptor advertised an output key the node never
 * wrote, and content types were plain strings nothing validated. None of that produced a compile
 * error or a failing test - it produced an editor that drew handles no edge could attach to.
 * </p>
 *
 * <p>
 * These tests run against every descriptor discovered through the {@link ServiceLoader}, so a new
 * node kind is held to the same rules from the moment it is registered.
 * </p>
 */
public class NodeDescriptorPortsTest {

	private static final Pattern ID = Pattern.compile(PortSpec.ID_PATTERN);

	/**
	 * A port id is a stable identity referenced by edges and by the node itself, so it must fit the
	 * shape the whole system agrees on.
	 */
	@Test
	void testPortIdsAreWellFormed() {
		forEachPort((descriptor, side, port) -> {
			assertNotNull(port.getId(), descriptor.getKind() + " has a " + side + " port with no id");
			assertTrue(ID.matcher(port.getId()).matches(),
				descriptor.getKind() + " " + side + " port id '" + port.getId() + "' does not match " + PortSpec.ID_PATTERN);
		});
	}

	/**
	 * Two ports on the same side sharing an id make an edge ambiguous - this is exactly the defect
	 * the XOR groups were introduced to fix.
	 */
	@Test
	void testNoDuplicatePortIdsPerSide() {
		for (NodeDescriptor descriptor : descriptors()) {
			assertNoDuplicates(descriptor, "input", descriptor.getInputPorts());
			assertNoDuplicates(descriptor, "output", descriptor.getOutputPorts());
		}
	}

	/**
	 * An unregistered content type would be invisible to the lattice, so every connection involving
	 * it would be rejected - and the editor would have no colour or label for the handle.
	 */
	@Test
	void testEveryPortDeclaresAKnownContentType() {
		forEachPort((descriptor, side, port) -> assertTrue(ContentTypeRegistry.isKnown(port.getContentType()),
			descriptor.getKind() + " " + side + " port '" + port.getId() + "' declares unknown content type '"
				+ port.getContentType() + "'"));
	}

	/**
	 * The editor shows the label and description on hover; a port without them is an unexplained
	 * handle in the palette.
	 */
	@Test
	void testEveryPortIsDescribed() {
		forEachPort((descriptor, side, port) -> {
			assertFalse(port.getLabel() == null || port.getLabel().isBlank(),
				descriptor.getKind() + " " + side + " port '" + port.getId() + "' has no label");
			assertFalse(port.getDescription() == null || port.getDescription().isBlank(),
				descriptor.getKind() + " " + side + " port '" + port.getId() + "' has no description");
		});
	}

	/**
	 * A port pointing at a group that is not declared on its own side would never have its XOR or
	 * EXCLUSIVE rule evaluated - the constraint would silently not exist.
	 */
	@Test
	void testGroupedPortsReferenceADeclaredGroupOnTheirOwnSide() {
		for (NodeDescriptor descriptor : descriptors()) {
			for (PortSpec port : descriptor.getInputPorts()) {
				if (port.getGroup() != null) {
					assertNotNull(descriptor.inputGroup(port.getGroup()),
						descriptor.getKind() + " input port '" + port.getId() + "' references undeclared input group '"
							+ port.getGroup() + "'");
				}
			}
			for (PortSpec port : descriptor.getOutputPorts()) {
				if (port.getGroup() != null) {
					assertNotNull(descriptor.outputGroup(port.getGroup()),
						descriptor.getKind() + " output port '" + port.getId() + "' references undeclared output group '"
							+ port.getGroup() + "'");
				}
			}
		}
	}

	/**
	 * A group with fewer than two members is not a choice. A one-member XOR is just a required port
	 * wearing a costume, and an empty one silently blocks the node from ever validating.
	 */
	@Test
	void testEveryDeclaredGroupHasAtLeastTwoMembers() {
		for (NodeDescriptor descriptor : descriptors()) {
			assertGroupsPopulated(descriptor, "input", descriptor.getInputGroups(), descriptor.getInputPorts());
			assertGroupsPopulated(descriptor, "output", descriptor.getOutputGroups(), descriptor.getOutputPorts());
		}
	}

	/**
	 * A dynamic kind must declare no static outputs (they would be drawn <em>in addition</em> to the
	 * resolved ones) and must have a resolver registered, or the editor draws no output handles at
	 * all and the node cannot be wired up.
	 */
	@Test
	void testDynamicKindsHaveNoStaticOutputsAndAResolver() {
		Map<String, NodePortResolver> resolvers = resolvers();

		for (NodeDescriptor descriptor : descriptors()) {
			if (!descriptor.isDynamicPorts()) {
				continue;
			}
			assertTrue(descriptor.getOutputPorts().isEmpty(),
				"kind '" + descriptor.getKind() + "' sets dynamicPorts but also declares static output ports "
					+ ids(descriptor.getOutputPorts()) + "; the resolver owns the output side");
			assertNotNull(resolvers.get(descriptor.getKind()),
				"kind '" + descriptor.getKind() + "' sets dynamicPorts but no NodePortResolver is registered for it in "
					+ "META-INF/services/io.metaloom.loom.nodes.spec.NodePortResolver. Known resolvers: " + resolvers.keySet());
		}
	}

	/**
	 * The converse: a resolver for a kind that is not marked dynamic would never be consulted.
	 */
	@Test
	void testEveryResolverTargetsADynamicKind() {
		Set<String> dynamicKinds = descriptors().stream()
			.filter(NodeDescriptor::isDynamicPorts)
			.map(NodeDescriptor::getKind)
			.collect(Collectors.toSet());

		for (NodePortResolver resolver : resolvers().values()) {
			assertTrue(dynamicKinds.contains(resolver.kind()),
				resolver.getClass().getSimpleName() + " resolves kind '" + resolver.kind()
					+ "' but that kind's descriptor does not set dynamicPorts, so the resolver is never consulted");
		}
	}

	// ---------------------------------------------------------------- helpers ---

	private interface PortCheck {
		void check(NodeDescriptor descriptor, String side, PortSpec port);
	}

	private static void forEachPort(PortCheck check) {
		for (NodeDescriptor descriptor : descriptors()) {
			for (PortSpec port : descriptor.getInputPorts()) {
				check.check(descriptor, "input", port);
			}
			for (PortSpec port : descriptor.getOutputPorts()) {
				check.check(descriptor, "output", port);
			}
		}
	}

	private static void assertNoDuplicates(NodeDescriptor descriptor, String side, List<PortSpec> ports) {
		Set<String> seen = new HashSet<>();
		for (PortSpec port : ports) {
			assertTrue(seen.add(port.getId()),
				descriptor.getKind() + " declares two " + side + " ports named '" + port.getId() + "'; ports on one side "
					+ "must be distinguishable, use a group for alternatives");
		}
	}

	private static void assertGroupsPopulated(NodeDescriptor descriptor, String side, List<PortGroup> groups, List<PortSpec> ports) {
		for (PortGroup group : groups) {
			long members = ports.stream().filter(p -> group.getId().equals(p.getGroup())).count();
			assertTrue(members >= 2,
				descriptor.getKind() + " " + side + " group '" + group.getId() + "' has " + members
					+ " member port(s); a group with fewer than two members is not a choice");
		}
	}

	private static List<NodeDescriptor> descriptors() {
		List<NodeDescriptor> all = new ArrayList<>();
		ServiceLoader.load(NodeDescriptorProvider.class).forEach(p -> all.addAll(p.getDescriptors()));
		assertFalse(all.isEmpty(), "no node descriptors were discovered at all");
		return all;
	}

	private static Map<String, NodePortResolver> resolvers() {
		Map<String, NodePortResolver> byKind = new java.util.LinkedHashMap<>();
		ServiceLoader.load(NodePortResolver.class).forEach(r -> byKind.put(r.kind(), r));
		return byKind;
	}

	private static List<String> ids(List<PortSpec> ports) {
		return ports.stream().map(PortSpec::getId).toList();
	}
}
