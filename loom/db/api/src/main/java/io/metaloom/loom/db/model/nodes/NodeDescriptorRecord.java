package io.metaloom.loom.db.model.nodes;

import java.time.Instant;

import io.metaloom.loom.db.CUDElement;

/**
 * A persisted node contract, as announced by one or more Cortex workers.
 *
 * <p>
 * Named {@code ...Record} rather than {@code NodeDescriptor} on purpose: the wire and REST type
 * {@code io.metaloom.loom.nodes.spec.NodeDescriptor} already owns that name, and the two are different
 * things. This is the row — provenance, hash, timestamps — carrying the contract as JSON in
 * {@link #getDescriptor()}. Giving them the same simple name would guarantee a wrong import in a file
 * that touches both.
 * </p>
 *
 * <p>
 * Only {@code ANNOUNCED} contracts are stored. Built-ins are recomputed from the classpath at every
 * boot, and a persisted copy would outlive a Loom downgrade — serving a contract this build's engine
 * no longer implements.
 * </p>
 */
public interface NodeDescriptorRecord extends CUDElement<NodeDescriptorRecord> {

	/**
	 * The node <strong>type</strong> id: {@code whisper}, {@code acme-nsfw}.
	 *
	 * <p>
	 * Not the graph-instance id that {@code asset_node_result.node_id} holds, and not the worker id
	 * that {@code cortex_instance.node_id} holds. Three different things wear this name; check which
	 * side of the wire you are on.
	 * </p>
	 */
	String getNodeId();

	NodeDescriptorRecord setNodeId(String nodeId);

	/** The active contract's version — the lowest any worker offering this node announced. */
	String getVersion();

	NodeDescriptorRecord setVersion(String version);

	/** The full announced descriptor as JSON, so rehydrating at boot needs no worker. */
	String getDescriptor();

	NodeDescriptorRecord setDescriptor(String descriptor);

	/** SHA-256 of the canonical contract body, excluding version and the deprecated alias. */
	String getBodyHash();

	NodeDescriptorRecord setBodyHash(String bodyHash);

	/** Always {@code ANNOUNCED}. */
	String getSource();

	NodeDescriptorRecord setSource(String source);

	/** {@code ACTIVE}, or {@code CONFLICTED} when workers disagree on one version's body. */
	String getStatus();

	NodeDescriptorRecord setStatus(String status);

	Instant getFirstSeen();

	NodeDescriptorRecord setFirstSeen(Instant firstSeen);

	/**
	 * When this contract last arrived over the socket.
	 *
	 * <p>
	 * <strong>Not liveness.</strong> A worker announces once, right after registering, and then stays
	 * connected for days — so this stops moving while the fleet is perfectly healthy. Availability is
	 * read from {@code cortex_instance.last_seen} through the link table.
	 * </p>
	 */
	Instant getLastAnnounced();

	NodeDescriptorRecord setLastAnnounced(Instant lastAnnounced);
}
