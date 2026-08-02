package io.metaloom.loom.nodes.spec;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Derives a {@code filter} node's output ports from its {@code buckets} option.
 *
 * <p>
 * The option is a JSON array of <code>{"id": ..., "label": ..., "match": ...}</code> objects. Each well-formed {@code id} becomes one
 * {@link PortSpec#isSelective() selective} {@code media/*} port, which is how the node expresses an N-way branch: the pipeline author wires the port
 * they care about, and the engine skips that consumer for the items that went down another branch.
 * </p>
 *
 * <p>
 * Three ports are always present regardless of configuration:
 * </p>
 * <ul>
 * <li>{@code other} - the catch-all, also selective. It exists even with zero buckets so a freshly dropped node is connectable at all; the same
 * reasoning as {@link PromptPortResolver}'s fallback {@code result} port.</li>
 * <li>{@code passed} - the {@code control/filter} boolean, so {@code NodeTaskResult.getFilterPassed()} and any graph still using the older
 * {@code PASS}/{@code REJECT} edge branches keep working.</li>
 * <li>{@code bucket} - the decision as a string, <em>not</em> selective, for a node that wants to run on every item and read where it landed.</li>
 * </ul>
 *
 * <p>
 * Nothing here throws. The options come from a pipeline definition an author typed, so a malformed entry must degrade to "this port does not exist"
 * and let save-time validation report the unwired edge - a resolver that threw would take out the whole descriptor listing instead.
 * </p>
 */
public class FilterPortResolver implements NodePortResolver {

	static final String KIND = "filter";

	/** The option holding the bucket declarations. */
	static final String OPTION_BUCKETS = "buckets";

	/** The catch-all port, always present. */
	public static final String PORT_OTHER = "other";

	/** The boolean verdict port, kept for branch-routing compatibility. */
	public static final String PORT_PASSED = "passed";

	/** The port carrying the resolved bucket id. */
	public static final String PORT_BUCKET = "bucket";

	/**
	 * Ids a bucket may not claim. The first three are the fixed output ports; the last two would collide with the node's own inputs and make the
	 * descriptor ambiguous to read.
	 */
	static final Set<String> RESERVED = Set.of(PORT_OTHER, PORT_PASSED, PORT_BUCKET, "media", "text");

	@Override
	public String kind() {
		return KIND;
	}

	@Override
	public List<PortSpec> resolveOutputPorts(NodeDescriptor descriptor, Map<String, Object> options) {
		List<Object> declarations = asList(options == null ? null : options.get(OPTION_BUCKETS));
		List<PortSpec> ports = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();

		for (Object declaration : declarations) {
			if (!(declaration instanceof Map<?, ?> entry)) {
				continue;
			}
			String id = asString(entry.get("id"));
			if (id == null || !id.matches(PortSpec.ID_PATTERN) || RESERVED.contains(id) || !seen.add(id)) {
				continue;
			}
			String label = asString(entry.get("label"));
			ports.add(PortSpec.selectiveOne(id, ContentTypeRegistry.MEDIA_ANY)
				.describedAs(label == null ? id : label, "Items classified as '" + (label == null ? id : label) + "'"));
		}

		ports.add(PortSpec.selectiveOne(PORT_OTHER, ContentTypeRegistry.MEDIA_ANY)
			.describedAs("Other", "Items no configured bucket matched"));
		ports.add(PortSpec.one(PORT_PASSED, ContentTypeRegistry.CONTROL_FILTER)
			.describedAs("Passed", "True when a bucket other than 'other' matched"));
		ports.add(PortSpec.one(PORT_BUCKET, ContentTypeRegistry.SCALAR_STRING)
			.describedAs("Bucket", "The id of the bucket this item landed in. Carries a value for every item"));
		return ports;
	}

	private static List<Object> asList(Object value) {
		if (value instanceof List<?> list) {
			// Deliberately not List.copyOf: a malformed definition may contain a null element, and
			// rejecting the whole node over it would be worse than skipping the entry.
			return new ArrayList<>(list);
		}
		return List.of();
	}

	private static String asString(Object value) {
		if (value instanceof String text) {
			String trimmed = text.trim();
			return trimmed.isEmpty() ? null : trimmed;
		}
		return null;
	}
}
