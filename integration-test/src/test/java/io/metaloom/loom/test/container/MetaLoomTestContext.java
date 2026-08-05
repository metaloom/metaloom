package io.metaloom.loom.test.container;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;

import io.metaloom.loom.client.http.LoomHttpClient;

/**
 * A whole MetaLoom deployment in containers: PostgreSQL, one Loom, and any number of
 * specialised Cortex workers on a shared network.
 *
 * <p>{@code LoomTestContext} could not be reused. It is a JUnit 4 {@code TestWatcher}
 * where this module runs JUnit 5, it starts only PostgreSQL and then wires Loom into
 * the <em>test</em> JVM through Dagger, and it knows nothing about Cortex. What is
 * wanted here is the opposite: nothing in the test JVM but a REST client, with Loom
 * and Cortex running as the images a deployment would actually ship.</p>
 *
 * <p>Usage - workers are declared before starting, because a worker needs the network
 * and Loom's alias to exist:</p>
 *
 * <pre>
 * try (MetaLoomTestContext ctx = new MetaLoomTestContext()
 *     .withMedia(mediaDir)
 *     .withWorker("it-cortex-source", Set.of("filesystem-source"))
 *     .withWorker("it-cortex-hash", Set.of("sha512"))) {
 *     ctx.start();
 *     ...
 * }
 * </pre>
 */
public class MetaLoomTestContext implements AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(MetaLoomTestContext.class);

	// 16.3 to match the pooled test databases and the rest of the compose files. Not below 15:
	// V2.71 uses UNIQUE NULLS NOT DISTINCT, so an older server fails the migration on boot and the
	// container never reports healthy - which reads as "the image is broken" rather than
	// "the database is too old".
	public static final String POSTGRES_IMAGE = "postgres:16.3-bullseye";
	private static final String DB_ALIAS = "postgres";
	private static final String DB_NAME = "loom";
	private static final String DB_USER = "sa";
	private static final String DB_PASSWORD = "sa";

	private final Network network = Network.newNetwork();

	private final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE)
		.withDatabaseName(DB_NAME)
		.withUsername(DB_USER)
		.withPassword(DB_PASSWORD)
		.withNetwork(network)
		.withNetworkAliases(DB_ALIAS);

	private final LoomContainer loom = new LoomContainer(network, DB_ALIAS, DB_NAME, DB_USER, DB_PASSWORD);

	/** Insertion ordered so start-up logging reads the way the test declared them. */
	private final Map<String, CortexContainer> workers = new LinkedHashMap<>();

	private final Map<String, Set<String>> plannedWorkers = new LinkedHashMap<>();

	private Path media;

	private boolean started;

	/** Cached so repeated calls do not re-login for every assertion. */
	private String adminToken;

	/**
	 * Host directory holding the test media, mounted read-only into every worker.
	 */
	public MetaLoomTestContext withMedia(Path media) {
		this.media = media;
		return this;
	}

	/**
	 * Declare a worker accepting exactly the given node kinds.
	 *
	 * @param nodeId    identity the worker registers under, and its network alias
	 * @param nodeKinds accepted kinds; empty or null means the worker accepts anything
	 */
	public MetaLoomTestContext withWorker(String nodeId, Set<String> nodeKinds) {
		if (started) {
			throw new IllegalStateException("Workers must be declared before start()");
		}
		plannedWorkers.put(nodeId, nodeKinds);
		return this;
	}

	/**
	 * Start everything, in dependency order.
	 *
	 * <p>Sequential rather than parallel: Loom migrates the database on boot, so it
	 * cannot start before PostgreSQL is accepting connections, and a worker's wait
	 * strategy is "registered with Loom", which Loom has to be up to satisfy.</p>
	 */
	public MetaLoomTestContext start() {
		log.info("Starting PostgreSQL container");
		postgres.start();

		log.info("Starting Loom container");
		attachLogs(loom, "loom");
		loom.start();

		for (Map.Entry<String, Set<String>> entry : plannedWorkers.entrySet()) {
			String nodeId = entry.getKey();
			log.info("Starting Cortex worker {} accepting {}", nodeId, entry.getValue());
			CortexContainer worker = new CortexContainer(network, nodeId, entry.getValue(), media);
			attachLogs(worker, nodeId);
			worker.start();
			workers.put(nodeId, worker);
		}
		started = true;
		return this;
	}

	/**
	 * Stream a container's output into the test log when
	 * {@code -Dmetaloom.containerLogs=true}.
	 *
	 * <p>Off by default because Loom logs every jOOQ statement at DEBUG and drowns the
	 * test output. It is the first thing to turn on when a container test fails, since
	 * the containers are gone by the time the assertion is read.</p>
	 */
	private void attachLogs(GenericContainer<?> container, String prefix) {
		if (Boolean.getBoolean("metaloom.containerLogs")) {
			container.withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("container." + prefix)));
		}
	}

	public LoomContainer loom() {
		return loom;
	}

	public CortexContainer worker(String nodeId) {
		CortexContainer worker = workers.get(nodeId);
		if (worker == null) {
			throw new IllegalArgumentException("No worker '" + nodeId + "'. Known: " + workers.keySet());
		}
		return worker;
	}

	/** Base URL of the Loom REST API as reachable from the test JVM. */
	public String restUrl() {
		return "http://" + loom.getHost() + ":" + loom.restPort() + "/api/v1";
	}

	/**
	 * A REST client pointed at the Loom container, already logged in as admin.
	 *
	 * <p>The caller owns the returned client and should close it.</p>
	 */
	public LoomHttpClient client() throws Exception {
		LoomHttpClient client = LoomHttpClient.builder()
			.setHostname(loom.getHost())
			.setPort(loom.restPort())
			.setReadTimeout(Duration.ofMinutes(5))
			.build();
		client.setToken(adminToken());
		return client;
	}

	/**
	 * An admin bearer token.
	 *
	 * <p>Needed because a few endpoints - triggering a run among them - have no method
	 * on {@link LoomHttpClient} yet and have to be called over plain HTTP.</p>
	 */
	public String adminToken() throws Exception {
		if (adminToken == null) {
			try (LoomHttpClient client = LoomHttpClient.builder()
				.setHostname(loom.getHost())
				.setPort(loom.restPort())
				.setReadTimeout(Duration.ofMinutes(5))
				.build()) {
				adminToken = client.login(LoomContainer.ADMIN_USERNAME, LoomContainer.ADMIN_PASSWORD)
					.sync().body().getToken();
			}
		}
		return adminToken;
	}

	/**
	 * Run a query against Loom's database.
	 *
	 * <p>The run and task tables have no REST surface yet, so reading the database
	 * directly is the only way to assert which worker did what.</p>
	 */
	public List<String[]> query(String sql, Binder binder, int columns) throws Exception {
		List<String[]> rows = new ArrayList<>();
		try (Connection connection = DriverManager.getConnection(
			postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
			PreparedStatement ps = connection.prepareStatement(sql)) {
			binder.bind(ps);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					String[] row = new String[columns];
					for (int i = 0; i < columns; i++) {
						row[i] = rs.getString(i + 1);
					}
					rows.add(row);
				}
			}
		}
		return rows;
	}

	@FunctionalInterface
	public interface Binder {
		void bind(PreparedStatement ps) throws Exception;
	}

	/**
	 * Stop everything, workers first.
	 *
	 * <p>Each step is guarded: a failure during start-up leaves some containers
	 * running, and letting the first stop() failure abort the rest would leak them.</p>
	 */
	@Override
	public void close() {
		for (CortexContainer worker : workers.values()) {
			stopQuietly(worker.nodeId(), worker);
		}
		stopQuietly("loom", loom);
		stopQuietly("postgres", postgres);
		try {
			network.close();
		} catch (Exception e) {
			log.warn("Could not close the network", e);
		}
	}

	private void stopQuietly(String name, AutoCloseable container) {
		try {
			container.close();
		} catch (Exception e) {
			log.warn("Could not stop container {}", name, e);
		}
	}
}
