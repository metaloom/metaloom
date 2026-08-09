package io.metaloom.loom.rest.service.impl;

import java.util.Locale;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.api.options.NodeExecOptions;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.nodes.spec.NodeCategory;
import io.metaloom.loom.nodes.spec.NodeDescriptor;
import io.metaloom.loom.nodes.spec.NodeDescriptorRegistry;
import io.metaloom.loom.nodes.spec.PortSpec;

/**
 * Decides which node kinds may be run as a synchronous probe.
 *
 * <h2>Derived, not listed</h2>
 *
 * <p>
 * A hand-maintained allow-list of "safe" kinds goes stale the day somebody adds a node, and it goes
 * stale silently - in the permissive direction if the list is a deny-list, in the useless direction
 * if it is an allow-list. So the default rule is derived from the node's own declared contract:
 * </p>
 *
 * <ol>
 * <li>a source produces media rather than consuming it, and Loom supplies the media itself;</li>
 * <li>an output/sink kind exists to push data somewhere outside Loom, which is not a probe;</li>
 * <li>a kind with an {@code artifact/*} output writes bytes to a worker-local directory that Loom has
 * no way to fetch, so a caller would be told "success" and handed nothing. That stays true until byte
 * ingest exists;</li>
 * <li>a kind that writes to Loom out of band cannot honour {@code persist=false} - see
 * {@link AdhocNodeResultWriter} - so those are named explicitly in
 * {@code LOOM_AGENT_PROBE_DENY_KINDS}.</li>
 * </ol>
 *
 * <p>
 * An operator who wants something narrower sets {@code LOOM_AGENT_PROBE_KINDS}, which replaces the
 * whole rule with a strict allow-list.
 * </p>
 */
@Singleton
public class ProbeEligibility {

	/** Content type family of "a file this node wrote next to itself". */
	private static final String ARTIFACT_PREFIX = ContentTypeRegistry.ARTIFACT_ANY.substring(0,
		ContentTypeRegistry.ARTIFACT_ANY.indexOf('/') + 1);

	private final NodeDescriptorRegistry registry;
	private final NodeExecOptions options;

	@Inject
	public ProbeEligibility(NodeDescriptorRegistry registry, LoomOptions loomOptions) {
		this.registry = registry;
		this.options = loomOptions.getNodeExec();
	}

	/**
	 * Why a kind may not be probed, or {@code null} when it may.
	 *
	 * <p>
	 * A reason rather than a boolean on purpose: every caller of this turns a refusal into a message
	 * for a person or a model, and "no" without a "because" is the kind of tool result that makes an
	 * agent retry the same call.
	 * </p>
	 */
	public String rejectionReason(String kind) {
		if (kind == null || kind.isBlank()) {
			return "No node kind was given.";
		}
		String normalized = kind.toLowerCase(Locale.ROOT);

		NodeDescriptor descriptor = registry.get(kind);
		if (descriptor == null) {
			return "There is no node kind '" + kind + "'. Use list_nodes to see what is available.";
		}

		Set<String> allowList = options.probeKindSet();
		if (!allowList.isEmpty()) {
			return allowList.contains(normalized) ? null
				: "Node kind '" + kind + "' is not in the configured probe allow-list (LOOM_AGENT_PROBE_KINDS).";
		}

		if (descriptor.getCategory() == NodeCategory.SOURCE) {
			return "Node kind '" + kind + "' is a source: it produces media rather than processing it. "
				+ "An ad-hoc run already supplies the assets.";
		}
		if (descriptor.getCategory() == NodeCategory.OUTPUT) {
			return "Node kind '" + kind + "' writes data out of Loom and is not something to run as a probe.";
		}
		if (options.probeDenyKindSet().contains(normalized)) {
			return "Node kind '" + kind + "' writes results back to Loom while it runs, so it cannot be run "
				+ "without recording anything. It is excluded by LOOM_AGENT_PROBE_DENY_KINDS.";
		}
		String artifactPort = artifactOutput(descriptor);
		if (artifactPort != null) {
			return "Node kind '" + kind + "' produces media bytes on port '" + artifactPort
				+ "', which stay on the worker - Loom cannot fetch them yet, so the result would be empty.";
		}
		return null;
	}

	/** Convenience for callers that only need the verdict. */
	public boolean isEligible(String kind) {
		return rejectionReason(kind) == null;
	}

	private static String artifactOutput(NodeDescriptor descriptor) {
		if (descriptor.getOutputPorts() == null) {
			return null;
		}
		for (PortSpec port : descriptor.getOutputPorts()) {
			String contentType = port.getContentType();
			if (contentType != null && contentType.startsWith(ARTIFACT_PREFIX)) {
				return port.getId();
			}
		}
		return null;
	}

}
