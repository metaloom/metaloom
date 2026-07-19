package io.metaloom.loom.test.container;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * A Cortex worker, running from the published server image.
 *
 * <p>Each worker is pinned to a known id and a set of node kinds so assertions can
 * name the worker that was expected to do a piece of work. Without the whitelist a
 * worker announces everything its node factory can run, and a test with two such
 * workers proves only that <em>a</em> worker did the job - not that Loom routed it to
 * the right one.</p>
 */
public class CortexContainer extends GenericContainer<CortexContainer> {

	public static final String DEFAULT_IMAGE = "metaloom/cortex-server:latest";

	/**
	 * Where test media is mounted inside every worker.
	 *
	 * <p>The same path in all of them, deliberately. The scanner reports absolute paths
	 * and a hashing worker later opens those same paths, so a worker that mounted the
	 * media somewhere else would be handed a path it cannot read.</p>
	 */
	public static final String MEDIA_PATH = "/media";

	private final String nodeId;

	public CortexContainer(Network network, String nodeId, Set<String> nodeKinds, Path media) {
		super(System.getProperty("cortex.image", DEFAULT_IMAGE));
		this.nodeId = nodeId;
		withNetwork(network);
		withNetworkAliases(nodeId);

		withEnv("LOOM_HOST", "loom");
		withEnv("LOOM_PORT", String.valueOf(LoomContainer.REST_PORT));
		withEnv("CORTEX_NODE_ID", nodeId);
		if (nodeKinds != null && !nodeKinds.isEmpty()) {
			withEnv("CORTEX_NODE_KINDS", String.join(",", nodeKinds));
		}
		// The image defaults metaPath under $HOME, which is fine, but pinning it keeps
		// one worker's metadata out of another's if the home directory is ever shared.
		withEnv("CORTEX_META_PATH", "/cortex/meta");

		if (media != null) {
			// Copied in over the Docker API rather than bind mounted. A bind mount is
			// cheaper, but it silently produces an EMPTY directory whenever the daemon
			// does not share a mount namespace with the test JVM - rootless Docker,
			// Docker Desktop, or a containerised build. The scan then finds nothing and
			// the failure looks like a pipeline bug rather than a mounting one.
			// Copying works in every case, at the cost of moving the bytes; keep the
			// staged media small.
			withCopyFileToContainer(MountableFile.forHostPath(media, 0755), MEDIA_PATH);
		}

		// Registration, not merely process start. A worker that booted but never reached
		// Loom would otherwise look healthy and then silently do nothing.
		waitingFor(Wait.forLogMessage(".*Connected to Loom control websocket.*", 1)
			.withStartupTimeout(Duration.ofMinutes(3)));
	}

	public String nodeId() {
		return nodeId;
	}
}
