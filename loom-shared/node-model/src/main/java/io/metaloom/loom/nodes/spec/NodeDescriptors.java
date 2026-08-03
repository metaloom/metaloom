package io.metaloom.loom.nodes.spec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Canonicalisation and content hashing for {@link NodeDescriptor}.
 *
 * <p>
 * The body hash answers one question: <em>have two workers announced the same contract?</em> It must
 * therefore depend only on the contract's <em>content</em> — never on how Jackson happened to order
 * properties on the day it ran. Hashing {@code mapper.writeValueAsString(descriptor)} directly would
 * make a Jackson upgrade, a reordered field or a newly added default look like a contract change on
 * every worker in the fleet at once.
 * </p>
 *
 * <p>
 * Two fields are excluded from the body:
 * </p>
 * <ul>
 * <li>{@code version} — compared separately. It is the label on the contract, not part of it, and
 * including it would make every {@code -SNAPSHOT} rebuild look like a conflict.</li>
 * <li>{@code kind} — the deprecated duplicate of {@code nodeId}. Hashing both would let a worker that
 * emits only the new name and one that emits both disagree about an identical contract.</li>
 * </ul>
 */
public final class NodeDescriptors {

	/** Fields that describe the announcement rather than the contract. */
	private static final List<String> NON_BODY_FIELDS = List.of("version", "kind");

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private NodeDescriptors() {
	}

	/**
	 * A stable, key-sorted JSON rendering of the descriptor's contract content.
	 *
	 * @return canonical JSON, identical for two descriptors that describe the same contract
	 */
	public static String canonicalJson(NodeDescriptor descriptor) {
		if (descriptor == null) {
			throw new NullPointerException("descriptor must not be null");
		}
		JsonNode tree = MAPPER.valueToTree(descriptor);
		if (tree instanceof ObjectNode object) {
			NON_BODY_FIELDS.forEach(object::remove);
		}
		StringBuilder sb = new StringBuilder();
		write(tree, sb);
		return sb.toString();
	}

	/**
	 * SHA-256 of {@link #canonicalJson(NodeDescriptor)}, lowercase hex.
	 *
	 * <p>
	 * This is what {@code node_descriptor.body_hash} stores and what §4.2's conflict rule compares.
	 * </p>
	 */
	public static String bodyHash(NodeDescriptor descriptor) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(canonicalJson(descriptor).getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder(hash.length * 2);
			for (byte b : hash) {
				sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
			}
			return sb.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is required by every JVM but was not available", e);
		}
	}

	/** Whether two descriptors describe the same contract, ignoring version and the deprecated alias. */
	public static boolean sameBody(NodeDescriptor a, NodeDescriptor b) {
		if (a == null || b == null) {
			return a == b;
		}
		return canonicalJson(a).equals(canonicalJson(b));
	}

	/**
	 * A deep copy, made by round-tripping through JSON.
	 *
	 * <p>
	 * Used wherever an announced descriptor crosses into a registry that outlives the frame it arrived
	 * in — the alternative is a caller mutating a descriptor Loom is already serving.
	 * </p>
	 */
	public static NodeDescriptor copy(NodeDescriptor descriptor) {
		if (descriptor == null) {
			return null;
		}
		return MAPPER.convertValue(MAPPER.valueToTree(descriptor), NodeDescriptor.class);
	}

	private static void write(JsonNode node, StringBuilder sb) {
		if (node == null || node.isNull()) {
			sb.append("null");
			return;
		}
		if (node.isObject()) {
			List<String> names = new ArrayList<>();
			for (Iterator<String> it = node.fieldNames(); it.hasNext();) {
				names.add(it.next());
			}
			Collections.sort(names);
			sb.append('{');
			boolean first = true;
			for (String name : names) {
				if (!first) {
					sb.append(',');
				}
				first = false;
				appendString(name, sb);
				sb.append(':');
				write(node.get(name), sb);
			}
			sb.append('}');
			return;
		}
		if (node.isArray()) {
			// Array order is contract - a node's ports are ordered by the editor. Never sorted here.
			sb.append('[');
			for (int i = 0; i < node.size(); i++) {
				if (i > 0) {
					sb.append(',');
				}
				write(node.get(i), sb);
			}
			sb.append(']');
			return;
		}
		if (node.isTextual()) {
			appendString(node.textValue(), sb);
			return;
		}
		sb.append(node.asText());
	}

	private static void appendString(String value, StringBuilder sb) {
		sb.append('"');
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
			case '"' -> sb.append("\\\"");
			case '\\' -> sb.append("\\\\");
			case '\n' -> sb.append("\\n");
			case '\r' -> sb.append("\\r");
			case '\t' -> sb.append("\\t");
			default -> {
				if (c < 0x20) {
					sb.append(String.format("\\u%04x", (int) c));
				} else {
					sb.append(c);
				}
			}
			}
		}
		sb.append('"');
	}

	/** Convenience for building a descriptor map keyed by node id. */
	public static Map<String, NodeDescriptor> byNodeId(Iterable<NodeDescriptor> descriptors) {
		Map<String, NodeDescriptor> map = new java.util.LinkedHashMap<>();
		for (NodeDescriptor descriptor : descriptors) {
			map.put(descriptor.getNodeId(), descriptor);
		}
		return map;
	}
}
