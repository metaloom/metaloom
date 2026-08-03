package io.metaloom.loom.rest.model.nodes;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestModel;

/**
 * Runtime fleet state for one node type — served <em>beside</em> its contract, never inside it.
 *
 * <p>
 * The separation is load-bearing. {@code NodeDescriptor} is the one contract type: the same object a
 * worker announces, Loom serves, the checked-in snapshot holds and the graph analyzer validates
 * against. Putting {@code available} on it would break that in both directions — a worker could
 * announce a claim about its own availability, and every consumer of the type would carry a field
 * meaningless outside one HTTP response.
 * </p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NodeAvailability implements RestModel {

	@JsonPropertyDescription("Where the contract came from: BUILTIN (compiled into Loom) or ANNOUNCED (from a worker)")
	private String source;

	@JsonPropertyDescription("Whether at least one worker offering this node is currently ONLINE. Not a timestamp "
		+ "comparison - a worker announces once and then stays connected for days")
	private boolean available;

	/**
	 * When a worker offering this node was last seen alive.
	 *
	 * <p>
	 * Taken from the heartbeat-updated {@code cortex_instance.last_seen}, <strong>not</strong> from
	 * when the contract arrived. A worker announces once, right after registering, and then stays up:
	 * deriving liveness from the announcement time would grey out the whole palette roughly one
	 * heartbeat after a perfectly healthy fleet connected, and the bug would look like a worker fault.
	 * </p>
	 */
	@JsonPropertyDescription("When a worker offering this node was last seen alive (heartbeat-driven)")
	private Instant lastSeen;

	@JsonPropertyDescription("When this contract last arrived over the socket. Diagnostic only - see lastSeen for liveness")
	private Instant lastAnnounced;

	@JsonPropertyDescription("Worker ids currently offering this node. Only served to a caller with READ_CORTEX_INSTANCE, "
		+ "since it is fleet topology")
	private List<String> providedBy;

	@JsonPropertyDescription("Whether the workers offering this node disagree about its contract or version")
	private boolean versionSkew;

	public String getSource() {
		return source;
	}

	public NodeAvailability setSource(String source) {
		this.source = source;
		return this;
	}

	public boolean isAvailable() {
		return available;
	}

	public NodeAvailability setAvailable(boolean available) {
		this.available = available;
		return this;
	}

	public Instant getLastSeen() {
		return lastSeen;
	}

	public NodeAvailability setLastSeen(Instant lastSeen) {
		this.lastSeen = lastSeen;
		return this;
	}

	public Instant getLastAnnounced() {
		return lastAnnounced;
	}

	public NodeAvailability setLastAnnounced(Instant lastAnnounced) {
		this.lastAnnounced = lastAnnounced;
		return this;
	}

	public List<String> getProvidedBy() {
		return providedBy;
	}

	public NodeAvailability setProvidedBy(List<String> providedBy) {
		this.providedBy = providedBy;
		return this;
	}

	public boolean isVersionSkew() {
		return versionSkew;
	}

	public NodeAvailability setVersionSkew(boolean versionSkew) {
		this.versionSkew = versionSkew;
		return this;
	}
}
