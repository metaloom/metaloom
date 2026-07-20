package io.metaloom.loom.db.model.cortex;

import java.time.Instant;
import java.util.Set;

import io.metaloom.loom.db.CUDElement;

/**
 * A registered Cortex/processor worker.
 *
 * <p>Keyed by the stable {@link #getNodeId() node_id} rather than the row UUID, so a
 * worker that reconnects or a Loom that restarts finds the same record. Alongside the
 * worker identity it carries an administrator-managed {@link #getNodeWhitelist()
 * nodeWhitelist} and {@link #getNodeBlacklist() nodeBlacklist} — the OVERRIDE that wins
 * over whatever restriction the worker announces at registration.</p>
 */
public interface CortexInstance extends CUDElement<CortexInstance> {

	String getNodeId();

	CortexInstance setNodeId(String nodeId);

	String getName();

	CortexInstance setName(String name);

	String getHost();

	CortexInstance setHost(String host);

	int getPriority();

	CortexInstance setPriority(int priority);

	Instant getLastSeen();

	CortexInstance setLastSeen(Instant lastSeen);

	String getState();

	CortexInstance setState(String state);

	Instant getFirstRegistered();

	CortexInstance setFirstRegistered(Instant firstRegistered);

	/**
	 * Node kinds this worker is permitted to run. An empty/absent whitelist means
	 * "unrestricted" so a worker keeps receiving everything.
	 *
	 * @return the whitelisted kinds
	 */
	Set<String> getNodeWhitelist();

	CortexInstance setNodeWhitelist(Set<String> nodeWhitelist);

	/**
	 * Node kinds this worker is explicitly forbidden to run. Takes precedence over the
	 * whitelist for any kind that appears in both.
	 *
	 * @return the blacklisted kinds
	 */
	Set<String> getNodeBlacklist();

	CortexInstance setNodeBlacklist(Set<String> nodeBlacklist);

}
