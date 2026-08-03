package io.metaloom.loom.rest.service.impl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.cortex.CortexInstance;
import io.metaloom.loom.db.model.nodes.NodeDescriptorRecord;
import io.metaloom.loom.nodes.spec.ContentTypeLattice;
import io.metaloom.loom.nodes.spec.NodeDescriptor;
import io.metaloom.loom.nodes.spec.NodeDescriptorRegistry;
import io.metaloom.loom.nodes.spec.NodeDescriptors;
import io.metaloom.loom.nodes.spec.NodeVersions;
import io.metaloom.loom.nodes.spec.PortGroup;
import io.metaloom.loom.nodes.spec.PortSpec;
import io.metaloom.loom.rest.model.processor.message.NodeRegistration;
import io.metaloom.loom.rest.model.processor.message.NodeRegistrationAck;
import io.metaloom.loom.rest.model.processor.message.NodeRegistrationRejection.Reason;

/**
 * Adopts the node contracts a Cortex worker announces.
 *
 * <h2>The load-bearing idea</h2>
 *
 * <p>
 * Spec knowledge is <strong>durable</strong>; worker presence is <strong>live</strong>. A node whose
 * last worker went offline keeps validating, keeps saving and keeps opening in the editor; it simply
 * cannot <em>run</em>, which {@code unsupportedNodeKinds} already reports as a 503 at run time.
 * Deleting contracts when a worker disconnects would turn a 30-second rolling restart into "your saved
 * pipeline no longer validates", so nothing here ever removes a descriptor on disconnect — it only
 * unlinks the worker.
 * </p>
 *
 * <h2>Which contract is active</h2>
 *
 * <p>
 * A node offered by several workers on several versions resolves to the <strong>lowest</strong>
 * announced version, not the newest. A graph is validated against the active contract and then
 * dispatched to whichever worker accepts the node; if 1.1.0 added an optional port and the editor let
 * an author wire it, an item landing on a 1.0.0 worker would silently ignore that input — a green run
 * with the wrong answer. The lowest announced version is the one every worker in the fleet can honour,
 * so a saved graph is safe wherever it lands. The cost is that new ports appear only once the last old
 * worker is gone, which is visible and explainable; the alternative is invisible.
 * </p>
 */
@Singleton
public class NodeRegistrationService {

	private static final Logger log = LoggerFactory.getLogger(NodeRegistrationService.class);

	/** Node type ids are lowercase kebab, like the {@code @StringKey} bindings they mirror. */
	private static final Pattern NODE_ID_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");

	private static final Pattern PORT_ID_PATTERN = Pattern.compile(PortSpec.ID_PATTERN);

	/**
	 * An announced spec is untrusted input from whoever holds a processor token. The socket being
	 * JWT-authenticated is necessary and not sufficient, so the shape is bounded as well as checked.
	 */
	private static final int MAX_NODES_PER_FRAME = 256;
	private static final int MAX_PORTS_PER_NODE = 64;
	private static final int MAX_PARAMETERS_PER_NODE = 256;

	private final NodeDescriptorRegistry registry;

	/**
	 * Per-worker claims: {@code cortexId → nodeId → what that worker says this contract is}.
	 *
	 * <p>
	 * Keeping every worker's own claim, rather than only the merged result, is what makes the version
	 * rule <em>computable</em> instead of a cached decision that rots. When a worker unlinks, the
	 * active contract is recomputed from what remains rather than guessed.
	 * </p>
	 */
	private final Map<String, Map<String, Claim>> claimsByCortexId = new ConcurrentHashMap<>();

	private final boolean acceptAnnounced;

	/**
	 * Durable store. Null in unit tests that exercise the ingest rules without a database — every
	 * write below is guarded, so those tests stay persistence-free.
	 */
	private final DaoCollection daos;

	private volatile Runnable changeListener = () -> {
	};

	@Inject
	public NodeRegistrationService(NodeDescriptorRegistry registry, DaoCollection daos) {
		this(registry, !"false".equalsIgnoreCase(System.getenv("LOOM_NODE_SPEC_ACCEPT_ANNOUNCED")), daos);
	}

	/**
	 * @param acceptAnnounced
	 *            the kill switch for a locked-down deployment. Off, frames are still acknowledged —
	 *            with everything rejected and a reason — rather than ignored, so a worker's operator
	 *            can see why its nodes never appear
	 */
	public NodeRegistrationService(NodeDescriptorRegistry registry, boolean acceptAnnounced) {
		this(registry, acceptAnnounced, null);
	}

	public NodeRegistrationService(NodeDescriptorRegistry registry, boolean acceptAnnounced, DaoCollection daos) {
		this.registry = registry;
		this.acceptAnnounced = acceptAnnounced;
		this.daos = daos;
	}

	/**
	 * Restore the ANNOUNCED layer from the database.
	 *
	 * <p>
	 * Must run before anything validates a graph — a saved pipeline using a custom node has to parse on
	 * a freshly booted Loom with no worker connected, or a restart during an outage turns every such
	 * pipeline unopenable.
	 * </p>
	 *
	 * @return how many contracts were restored
	 */
	public int rehydrate() {
		if (daos == null) {
			return 0;
		}
		ObjectMapper mapper = new ObjectMapper();
		List<NodeDescriptor> restored = new ArrayList<>();
		try {
			for (NodeDescriptorRecord record : daos.nodeDescriptorDao().loadAll()) {
				if (registry.isBuiltin(record.getNodeId())) {
					// This build compiles in a contract for that id now. The built-in wins, and the
					// stored copy would be a contract nothing here implements.
					continue;
				}
				try {
					NodeDescriptor descriptor = mapper.readValue(record.getDescriptor(), NodeDescriptor.class);
					descriptor.setNodeId(record.getNodeId());
					descriptor.setVersion(record.getVersion());
					restored.add(descriptor);
				} catch (JsonProcessingException e) {
					log.warn("Stored contract for node '{}' could not be parsed and was skipped", record.getNodeId(), e);
				}
			}
		} catch (RuntimeException e) {
			// A registry that is empty rather than absent is the safe failure: built-ins still work.
			log.error("Could not rehydrate announced node contracts", e);
			return 0;
		}
		registry.setAnnounced(restored);
		log.info("Restored {} announced node contracts", restored.size());
		return restored.size();
	}

	/**
	 * Called whenever the merged registry actually changed, so the UI can be told.
	 *
	 * <p>
	 * "Actually changed" is the operative part: a worker reconnecting and re-announcing an identical
	 * set is the common case, and firing on it would storm every open editor with a full re-fetch on
	 * every rolling restart.
	 * </p>
	 */
	public void onRegistryChanged(Runnable listener) {
		this.changeListener = listener == null ? () -> {
		} : listener;
	}

	/**
	 * Ingest one {@code NODE_REGISTRATION} frame.
	 *
	 * @param socketCortexId
	 *            the identity this socket registered with. The frame's own {@code cortexId} must match
	 *            it — a worker may not speak for another worker
	 * @return the per-node outcome, to be sent back as {@code NODE_REGISTRATION_ACK}
	 */
	public NodeRegistrationAck ingest(String socketCortexId, NodeRegistration frame) {
		String cortexId = frame == null ? null : frame.getCortexId();
		NodeRegistrationAck ack = new NodeRegistrationAck(cortexId != null ? cortexId : socketCortexId);

		if (frame == null) {
			return ack;
		}
		if (socketCortexId == null || !socketCortexId.equals(cortexId)) {
			log.warn("Refusing a node registration from '{}' claiming to be '{}'", socketCortexId, cortexId);
			ack.reject(null, Reason.ID_MISMATCH,
				"This socket registered as '" + socketCortexId + "' and may not announce for '" + cortexId + "'");
			return ack;
		}

		List<NodeDescriptor> nodes = frame.getNodes() == null ? List.of() : frame.getNodes();
		if (nodes.size() > MAX_NODES_PER_FRAME) {
			ack.reject(null, Reason.TOO_LARGE, "A frame may announce at most " + MAX_NODES_PER_FRAME + " nodes, got " + nodes.size());
			return ack;
		}
		if (!acceptAnnounced) {
			nodes.forEach(node -> ack.reject(node.getNodeId(), Reason.ANNOUNCEMENTS_DISABLED,
				"This Loom serves built-in node contracts only"));
			return ack;
		}

		Map<String, Claim> accepted = new LinkedHashMap<>();
		Set<String> seen = new HashSet<>();

		for (NodeDescriptor announced : nodes) {
			String nodeId = announced.getNodeId();
			if (!seen.add(nodeId)) {
				// Reject per node, never per frame: one bad custom node must not cost this worker its
				// other contracts.
				ack.reject(nodeId, Reason.DUPLICATE_NODE_ID, "This frame announces '" + nodeId + "' more than once");
				continue;
			}
			Rejection rejection = validate(announced);
			if (rejection != null) {
				ack.reject(nodeId, rejection.reason(), rejection.message());
				continue;
			}
			if (registry.isBuiltin(nodeId)) {
				// The version is still recorded below, so an operator can see which Cortex build a
				// worker runs - but the body is ignored, and saying so is the point. An author who
				// edits a forked node's ports and sees no effect otherwise loses an afternoon.
				accepted.put(nodeId, new Claim(nodeId, announced.getVersion(), NodeDescriptors.bodyHash(announced), null));
				ack.reject(nodeId, Reason.BUILTIN, "Built-in descriptor wins; the announced copy is ignored");
				continue;
			}
			NodeDescriptor stored = NodeDescriptors.copy(announced);
			accepted.put(nodeId, new Claim(nodeId, announced.getVersion(), NodeDescriptors.bodyHash(announced), stored));
			ack.accept(nodeId);
		}

		// Replaces this worker's whole claim set: a node absent from a later frame is unlinked. There
		// is no delta form, so a worker that drops a node cannot leave a stale contract behind.
		claimsByCortexId.put(cortexId, accepted);

		boolean changed = recomputeActiveContracts();
		persist(cortexId, accepted);
		if (changed) {
			changeListener.run();
		}
		log.info("Worker '{}' announced {} node contracts ({} accepted, {} rejected)",
			cortexId, nodes.size(), ack.getAccepted().size(), ack.getRejected().size());
		return ack;
	}

	/**
	 * Write the active contracts and this worker's claims through to the database.
	 *
	 * <p>
	 * Failure here is logged and swallowed. Persistence exists so contracts survive a Loom restart; a
	 * database hiccup must not stop a worker registering, and the in-memory registry is already correct
	 * by the time this runs — the worker's nodes stay authorable for this process's lifetime either way.
	 * </p>
	 */
	private void persist(String cortexId, Map<String, Claim> claims) {
		if (daos == null) {
			return;
		}
		try {
			ObjectMapper mapper = new ObjectMapper();
			for (NodeDescriptor active : registry.getAnnounced()) {
				NodeDescriptorRecord record = daos.nodeDescriptorDao().loadByNodeId(active.getNodeId());
				if (record == null) {
					record = daos.nodeDescriptorDao().createNodeDescriptor(active.getNodeId());
				}
				record.setVersion(active.getVersion());
				record.setDescriptor(mapper.writeValueAsString(active));
				record.setBodyHash(NodeDescriptors.bodyHash(active));
				record.setSource("ANNOUNCED");
				record.setStatus(hasVersionSkew(active.getNodeId()) ? "CONFLICTED" : "ACTIVE");
				record.setLastAnnounced(Instant.now());
				daos.nodeDescriptorDao().upsertByNodeId(record);
			}

			// The claim rows hang off cortex_instance, which the REGISTER path has already created -
			// the announcement always follows a completed registration.
			CortexInstance instance = daos.cortexInstanceDao().loadByNodeId(cortexId);
			if (instance == null) {
				log.debug("No cortex_instance row for '{}' yet; claims will be written on the next announcement", cortexId);
				return;
			}
			Map<String, String[]> rows = new LinkedHashMap<>();
			claims.forEach((nodeId, claim) -> rows.put(nodeId, new String[] { claim.version(), claim.bodyHash() }));
			daos.nodeDescriptorDao().replaceClaims(instance.getUuid(), rows);
		} catch (JsonProcessingException | RuntimeException e) {
			log.warn("Could not persist announced node contracts for worker '{}'; they are still served from memory",
				cortexId, e);
		}
	}

	/**
	 * Unlink a worker's claims.
	 *
	 * <p>
	 * Deliberately <strong>not</strong> called on disconnect. Presence lives in
	 * {@code cortex_instance.state}, and a contract outliving its worker is the whole point: refcount
	 * and drop, and a rolling restart makes saved pipelines stop validating.
	 * </p>
	 */
	public void forget(String cortexId) {
		if (cortexId != null && claimsByCortexId.remove(cortexId) != null && recomputeActiveContracts()) {
			changeListener.run();
		}
	}

	/**
	 * Which workers currently claim to offer this node type, in worker-id order.
	 *
	 * <p>
	 * Sorted rather than in hash order: this reaches the editor as {@code providedBy}, and a list that
	 * reorders itself between two identical requests reads as the fleet churning when nothing has
	 * happened.
	 * </p>
	 */
	public Set<String> providersOf(String nodeId) {
		Set<String> providers = new java.util.TreeSet<>();
		claimsByCortexId.forEach((cortexId, claims) -> {
			if (claims.containsKey(nodeId)) {
				providers.add(cortexId);
			}
		});
		return new LinkedHashSet<>(providers);
	}

	/** Every node type any worker has claimed, mapped to its claiming workers in worker-id order. */
	public Map<String, Set<String>> providersByNodeId() {
		Map<String, Set<String>> providers = new LinkedHashMap<>();
		for (String cortexId : sortedCortexIds()) {
			Map<String, Claim> claims = claimsByCortexId.get(cortexId);
			if (claims != null) {
				claims.keySet().forEach(nodeId -> providers.computeIfAbsent(nodeId, k -> new LinkedHashSet<>()).add(cortexId));
			}
		}
		return providers;
	}

	/** The version this worker claims for this node type, or null. */
	public String versionOf(String cortexId, String nodeId) {
		Map<String, Claim> claims = claimsByCortexId.get(cortexId);
		Claim claim = claims == null ? null : claims.get(nodeId);
		return claim == null ? null : claim.version();
	}

	/**
	 * Whether the workers offering this node disagree — different versions, an unorderable version, or
	 * the same version with different bodies. Surfaced as a badge rather than an error: a conflict
	 * nobody can see is a conflict nobody will fix.
	 */
	public boolean hasVersionSkew(String nodeId) {
		List<Claim> claims = claimsFor(nodeId);
		if (claims.size() < 2) {
			return false;
		}
		List<String> versions = claims.stream().map(Claim::version).toList();
		if (!NodeVersions.allEqual(versions)) {
			return true;
		}
		String firstHash = claims.get(0).bodyHash();
		return claims.stream().anyMatch(claim -> !firstHash.equals(claim.bodyHash()));
	}

	/**
	 * Rebuild the ANNOUNCED layer from every worker's current claim.
	 *
	 * @return whether the merged registry actually changed
	 */
	private boolean recomputeActiveContracts() {
		Map<String, NodeDescriptor> active = new LinkedHashMap<>();

		for (Map.Entry<String, Set<String>> entry : providersByNodeId().entrySet()) {
			String nodeId = entry.getKey();
			if (registry.isBuiltin(nodeId)) {
				continue;
			}
			NodeDescriptor winner = resolveActive(claimsFor(nodeId), registry.get(nodeId));
			if (winner != null) {
				active.put(nodeId, winner);
			}
		}

		Collection<NodeDescriptor> previous = registry.getAnnounced();
		boolean changed = previous.size() != active.size();
		if (!changed) {
			for (NodeDescriptor before : previous) {
				NodeDescriptor after = active.get(before.getNodeId());
				if (after == null || !NodeDescriptors.sameBody(before, after)
					|| !java.util.Objects.equals(before.getVersion(), after.getVersion())) {
					changed = true;
					break;
				}
			}
		}
		if (changed) {
			registry.setAnnounced(active.values());
		}
		return changed;
	}

	/**
	 * The active contract among several claims for one node type.
	 *
	 * <p>
	 * The lowest parseable version wins. When the versions cannot be ordered — one is null, or one is
	 * something like {@code latest} — the <strong>incumbent</strong> is kept: the contract already
	 * being served stays served, and {@link #hasVersionSkew(String)} reports the disagreement rather
	 * than the registry silently changing shape under saved pipelines. Guessing here would pick a
	 * contract for the whole fleet on the strength of nothing.
	 * </p>
	 *
	 * @param incumbent
	 *            what is currently active for this node type, or null on first announcement
	 */
	private NodeDescriptor resolveActive(List<Claim> claims, NodeDescriptor incumbent) {
		Claim best = null;
		for (Claim claim : claims) {
			if (claim.descriptor() == null) {
				continue;
			}
			if (best == null) {
				best = claim;
				continue;
			}
			if (NodeVersions.isParseable(claim.version()) && NodeVersions.isParseable(best.version())) {
				if (NodeVersions.compare(claim.version(), best.version()) < 0) {
					best = claim;
				}
				continue;
			}
			// Unorderable. Prefer whichever claim reproduces what is already being served; if neither
			// does, the claims are walked in worker-id order so the outcome is at least deterministic.
			if (incumbent != null && NodeDescriptors.sameBody(claim.descriptor(), incumbent)) {
				best = claim;
			}
		}
		return best == null ? null : best.descriptor();
	}

	/**
	 * Every worker's claim about one node type, in worker-id order.
	 *
	 * <p>
	 * The ordering is not cosmetic. {@code claimsByCortexId} is a {@code ConcurrentHashMap}, so
	 * iterating it directly walks in hash order — and "keep the incumbent when the versions cannot be
	 * ordered" would then resolve differently depending on which worker ids happened to hash where.
	 * That is a guess wearing a rule's clothes, and it is the one thing the version rule must not do.
	 * </p>
	 */
	private List<Claim> claimsFor(String nodeId) {
		List<Claim> claims = new ArrayList<>();
		for (String cortexId : sortedCortexIds()) {
			Map<String, Claim> byNode = claimsByCortexId.get(cortexId);
			Claim claim = byNode == null ? null : byNode.get(nodeId);
			if (claim != null) {
				claims.add(claim);
			}
		}
		return claims;
	}

	private List<String> sortedCortexIds() {
		List<String> ids = new ArrayList<>(claimsByCortexId.keySet());
		java.util.Collections.sort(ids);
		return ids;
	}

	// ── Validation ───────────────────────────────────────────────────────────────────────────────

	private Rejection validate(NodeDescriptor node) {
		String nodeId = node.getNodeId();
		if (nodeId == null || !NODE_ID_PATTERN.matcher(nodeId).matches()) {
			return new Rejection(Reason.INVALID_NODE_ID,
				"'" + nodeId + "' does not match " + NODE_ID_PATTERN.pattern());
		}
		if (node.getName() == null || node.getName().isBlank()) {
			return new Rejection(Reason.INVALID_NODE_ID, "A node contract must carry a display name");
		}
		if (node.getCategory() == null) {
			return new Rejection(Reason.INVALID_NODE_ID, "A node contract must carry a category");
		}
		int portCount = size(node.getInputPorts()) + size(node.getOutputPorts());
		if (portCount > MAX_PORTS_PER_NODE) {
			return new Rejection(Reason.TOO_LARGE, "A node may declare at most " + MAX_PORTS_PER_NODE + " ports, got " + portCount);
		}
		if (size(node.getParameters()) > MAX_PARAMETERS_PER_NODE) {
			return new Rejection(Reason.TOO_LARGE,
				"A node may declare at most " + MAX_PARAMETERS_PER_NODE + " parameters, got " + size(node.getParameters()));
		}
		Rejection inputs = validatePorts(node.getInputPorts(), node.getInputGroups(), "inputPorts");
		if (inputs != null) {
			return inputs;
		}
		return validatePorts(node.getOutputPorts(), node.getOutputGroups(), "outputPorts");
	}

	private Rejection validatePorts(List<PortSpec> ports, List<PortGroup> groups, String side) {
		if (ports == null) {
			return null;
		}
		Set<String> ids = new HashSet<>();
		Set<String> groupIds = new HashSet<>();
		if (groups != null) {
			groups.forEach(group -> groupIds.add(group.getId()));
		}
		for (int i = 0; i < ports.size(); i++) {
			PortSpec port = ports.get(i);
			String where = side + "[" + i + "]";
			if (port.getId() == null || !PORT_ID_PATTERN.matcher(port.getId()).matches()) {
				return new Rejection(Reason.INVALID_PORT_ID,
					where + ".id '" + port.getId() + "' does not match " + PortSpec.ID_PATTERN);
			}
			if (!ids.add(port.getId())) {
				return new Rejection(Reason.INVALID_PORT_ID, where + ".id '" + port.getId() + "' repeats on this side");
			}
			// Structural only, and deliberately so: assignability never consults a vocabulary, which is
			// exactly what lets a third-party node introduce 'struct/nsfw' with no schema change.
			if (!ContentTypeLattice.isWellFormed(port.getContentType())) {
				return new Rejection(Reason.INVALID_CONTENT_TYPE,
					where + ".contentType '" + port.getContentType() + "' is not 'family/subtype'");
			}
			if (port.getGroup() != null && !groupIds.contains(port.getGroup())) {
				return new Rejection(Reason.UNKNOWN_GROUP,
					where + ".group '" + port.getGroup() + "' names no group declared on this side");
			}
		}
		return null;
	}

	private static int size(List<?> list) {
		return list == null ? 0 : list.size();
	}

	private record Rejection(Reason reason, String message) {
	}

	/**
	 * One worker's claim about one node type.
	 *
	 * @param descriptor
	 *            null for a built-in id, whose body is ignored but whose version is still recorded so
	 *            an operator can see which Cortex build the worker runs
	 */
	private record Claim(String nodeId, String version, String bodyHash, NodeDescriptor descriptor) {
	}

	/** Timestamp helper kept here so persistence and the REST view agree on the clock. */
	static Instant now() {
		return Instant.now();
	}
}
