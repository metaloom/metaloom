package io.metaloom.loom.agent.sandbox;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.agent.sandbox.backend.KubernetesBackend;
import io.metaloom.loom.agent.sandbox.backend.PodmanBackend;
import io.metaloom.loom.agent.sandbox.backend.SandboxBackend;
import io.metaloom.loom.agent.sandbox.backend.SandboxInfo;
import io.metaloom.loom.agent.sandbox.backend.SandboxStatus;
import io.metaloom.loom.agent.sandbox.error.SandboxException;
import io.metaloom.loom.agent.sandbox.error.SandboxQuotaException;
import io.metaloom.loom.agent.sandbox.error.SandboxUnhealthyException;
import io.metaloom.loom.agent.sandbox.tool.CodingTools;
import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.api.options.SandboxOptions;
import io.vertx.core.json.JsonObject;

/**
 * Owns the {@code session -> Session Runner} registry, lazily provisions one runner per session
 * (chat uuid), applies the per-deployment concurrency cap, waits for readiness, and reaps runners on
 * reset / idle-TTL / max-session.
 *
 * <p>All methods here are blocking and are called from the agentic loop's worker thread (for
 * dispatch) or from the reaper's {@code executeBlocking} (for sweep). One lock per session serialises
 * provisioning so concurrent tool calls for the same chat share a single runner.</p>
 */
@Singleton
public class SandboxOrchestrator {

	private static final Logger log = LoggerFactory.getLogger(SandboxOrchestrator.class);

	private static final SecureRandom RANDOM = new SecureRandom();

	private final LoomOptions options;

	private final ConcurrentHashMap<String, SandboxHandle> handles = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

	private volatile SandboxBackend backend;

	/** A session terminated by the reaper, with the reason ("idle" | "max_duration"). */
	public record TerminatedSession(String session, String reason) {
	}

	@Inject
	public SandboxOrchestrator(LoomOptions options) {
		this.options = options;
	}

	/** Whether the coding sandbox is enabled by configuration. */
	public boolean available() {
		return cfg().isEnabled();
	}

	private SandboxOptions cfg() {
		return options.getSandbox();
	}

	private SandboxBackend backend() {
		SandboxBackend b = backend;
		if (b == null) {
			synchronized (this) {
				if (backend == null) {
					backend = SandboxOptions.BACKEND_KUBERNETES.equalsIgnoreCase(cfg().getBackend())
						? new KubernetesBackend(cfg())
						: new PodmanBackend(cfg());
					log.info("sandbox backend initialised: {} (image={})", cfg().getBackend(), cfg().getImage());
				}
				b = backend;
			}
		}
		return b;
	}

	private ReentrantLock lock(String session) {
		return locks.computeIfAbsent(session, s -> new ReentrantLock());
	}

	/**
	 * Ensure a ready runner for the session, provisioning one if needed, and dispatch a coding tool.
	 */
	public JsonObject dispatchCodingTool(String session, String name, JsonObject args) {
		SandboxClient client = ensureSandbox(session);
		return CodingTools.dispatch(client, name, args, cfg().getExecTimeoutSeconds());
	}

	/**
	 * Return a ready {@link SandboxClient} for the session, provisioning a runner if none is healthy.
	 *
	 * @throws SandboxException
	 *             (or a retryable subtype) when provisioning fails
	 */
	public SandboxClient ensureSandbox(String session) {
		ReentrantLock lock = lock(session);
		lock.lock();
		try {
			SandboxHandle handle = handles.get(session);
			if (handle != null && handle.client().healthz()) {
				handle.touch();
				return handle.client();
			}
			if (handle != null) {
				destroy(handle);
				handles.remove(session);
			}
			if (handles.size() >= cfg().getMaxConcurrent()) {
				throw new SandboxQuotaException("Maximum number of concurrent coding sandboxes reached (" + cfg().getMaxConcurrent() + ").");
			}
			return provision(session);
		} finally {
			lock.unlock();
		}
	}

	private SandboxClient provision(String session) {
		String token = newToken();
		String name = "loom-runner-" + shortId(session) + "-" + randomHex();
		SandboxInfo info = backend().create(session, name, token);
		String endpoint = info.endpoint();
		long createdAt = info.createdAtEpochMs() > 0 ? info.createdAtEpochMs() : System.currentTimeMillis();

		long deadline = System.currentTimeMillis() + cfg().getReadyTimeoutSeconds() * 1000L;
		while (System.currentTimeMillis() < deadline) {
			if (endpoint == null) {
				SandboxStatus st = backend().status(name);
				endpoint = st.endpoint();
				if (st.createdAtEpochMs() > 0) {
					createdAt = st.createdAtEpochMs();
				}
				if (st.isTerminal()) {
					break;
				}
			}
			if (endpoint != null) {
				SandboxClient client = new SandboxClient(endpoint, token);
				if (client.healthz()) {
					SandboxHandle handle = new SandboxHandle(session, name, endpoint, token, createdAt, client);
					handles.put(session, handle);
					log.info("sandbox ready session={} pod={}", session, name);
					return client;
				}
			}
			sleep(1000);
		}

		// Never became healthy — clean up and report.
		try {
			backend().delete(session, name);
		} catch (RuntimeException ignore) {
			// best effort
		}
		throw new SandboxUnhealthyException("The coding sandbox did not become ready in time.");
	}

	public SandboxHandle get(String session) {
		return handles.get(session);
	}

	/**
	 * Return the client for an already-running runner without provisioning a new one (used by the
	 * filesystem browse/download proxy). Returns {@code null} when no live runner exists for the
	 * session. Touches the runner's last-used timestamp so active browsing keeps it alive.
	 */
	public SandboxClient existingClient(String session) {
		SandboxHandle handle = handles.get(session);
		if (handle == null) {
			return null;
		}
		handle.touch();
		return handle.client();
	}

	public List<SandboxHandle> listActive() {
		return new ArrayList<>(handles.values());
	}

	/** Destroy a session's runner (e.g. on chat delete / reset). Returns true if one existed. */
	public boolean reap(String session) {
		SandboxHandle handle = handles.remove(session);
		if (handle == null) {
			return false;
		}
		destroy(handle);
		log.info("sandbox reaped session={} pod={}", session, handle.podName());
		return true;
	}

	public void reapAll() {
		for (String session : new ArrayList<>(handles.keySet())) {
			reap(session);
		}
	}

	/**
	 * Terminate runners exceeding idle-TTL or max-session. Returns the terminated sessions + reasons.
	 */
	public List<TerminatedSession> sweep() {
		long now = System.currentTimeMillis();
		long idleTtl = cfg().getIdleTtlSeconds() * 1000L;
		long maxSession = cfg().getMaxSessionSeconds() * 1000L;
		List<TerminatedSession> terminated = new ArrayList<>();
		for (SandboxHandle handle : new ArrayList<>(handles.values())) {
			String reason = null;
			if (maxSession > 0 && (now - handle.createdAt()) > maxSession) {
				reason = "max_duration";
			} else if (idleTtl > 0 && (now - handle.lastUsed()) > idleTtl) {
				reason = "idle";
			}
			if (reason == null) {
				continue;
			}
			handles.remove(handle.session());
			destroy(handle);
			log.info("sandbox swept session={} pod={} reason={}", handle.session(), handle.podName(), reason);
			terminated.add(new TerminatedSession(handle.session(), reason));
		}
		return terminated;
	}

	private void destroy(SandboxHandle handle) {
		try {
			backend().delete(handle.session(), handle.podName());
		} catch (RuntimeException e) {
			log.warn("sandbox delete failed session={} pod={}: {}", handle.session(), handle.podName(), e.getMessage());
		}
	}

	private static String newToken() {
		byte[] buf = new byte[24];
		RANDOM.nextBytes(buf);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
	}

	private static String randomHex() {
		return Long.toHexString(RANDOM.nextLong() & 0xffffffffL);
	}

	private static String shortId(String session) {
		String cleaned = session.replace("-", "").toLowerCase();
		return cleaned.length() > 8 ? cleaned.substring(0, 8) : cleaned;
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new SandboxException("sandbox provisioning interrupted", e);
		}
	}
}
