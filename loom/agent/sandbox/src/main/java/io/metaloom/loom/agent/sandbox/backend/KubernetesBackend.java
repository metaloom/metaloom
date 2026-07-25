package io.metaloom.loom.agent.sandbox.backend;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.time.Instant;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.agent.sandbox.error.SandboxException;
import io.metaloom.loom.agent.sandbox.error.SandboxQuotaException;
import io.metaloom.loom.agent.sandbox.error.SandboxScheduleException;
import io.metaloom.loom.api.options.SandboxOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Kubernetes / OpenShift sandbox backend (production).
 *
 * <p>Creates a hardened Session Runner Pod in a dedicated namespace using the Loom pod's mounted
 * <b>service-account</b> token, and reaches the pod directly on its pod IP. No heavyweight kubernetes
 * client dependency — just the JDK http client against the API server.</p>
 *
 * <p>Failure mapping (drives the UI "try again"): HTTP 403 on create whose message mentions the quota
 * &rarr; {@link SandboxQuotaException}; a pod stuck Pending with an Unschedulable condition &rarr;
 * {@link SandboxScheduleException}.</p>
 */
public class KubernetesBackend implements SandboxBackend {

	private static final Logger log = LoggerFactory.getLogger(KubernetesBackend.class);

	private static final String SA_DIR = "/var/run/secrets/kubernetes.io/serviceaccount";

	private final SandboxOptions options;
	private final HttpClient http;
	private final String api;
	private final String namespace;
	private final String token;

	public KubernetesBackend(SandboxOptions options) {
		this.options = options;
		String host = envOrDefault("KUBERNETES_SERVICE_HOST", "kubernetes.default.svc");
		String port = envOrDefault("KUBERNETES_SERVICE_PORT", "443");
		this.api = "https://" + host + ":" + port;
		this.token = readFile(SA_DIR + "/token");
		this.namespace = !options.getNamespace().isBlank() ? options.getNamespace() : firstNonBlank(readFile(SA_DIR + "/namespace"), "default");
		this.http = buildClient();
	}

	private HttpClient buildClient() {
		HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30));
		try {
			String caPem = readFile(SA_DIR + "/ca.crt");
			if (!caPem.isBlank()) {
				builder.sslContext(sslContextForCa(caPem));
			}
		} catch (Exception e) {
			log.warn("Could not build SSL context from service-account CA; falling back to default trust store", e);
		}
		return builder.build();
	}

	private static SSLContext sslContextForCa(String caPem) throws Exception {
		CertificateFactory cf = CertificateFactory.getInstance("X.509");
		KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
		ks.load(null, null);
		int i = 0;
		var in = new ByteArrayInputStream(caPem.getBytes(StandardCharsets.UTF_8));
		for (Certificate cert : cf.generateCertificates(in)) {
			ks.setCertificateEntry("ca-" + (i++), cert);
		}
		TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		tmf.init(ks);
		SSLContext ctx = SSLContext.getInstance("TLS");
		ctx.init(null, tmf.getTrustManagers(), null);
		return ctx;
	}

	@Override
	public SandboxInfo create(SandboxSpec spec) {
		String name = spec.name();
		JsonObject manifest = podManifest(spec);
		HttpResponse<String> res = send(HttpRequest.newBuilder()
			.uri(URI.create(api + "/api/v1/namespaces/" + namespace + "/pods"))
			.header("Content-Type", "application/json")
			.POST(BodyPublishers.ofString(manifest.encode())));
		if (res.statusCode() == 403 && res.body() != null && res.body().toLowerCase().contains("quota")) {
			throw new SandboxQuotaException("Namespace quota reached: " + truncate(res.body()));
		}
		if (res.statusCode() >= 400) {
			throw new SandboxScheduleException("pod create -> " + res.statusCode() + ": " + truncate(res.body()));
		}
		JsonObject pod = new JsonObject(res.body());
		return new SandboxInfo(name, null, parseCreated(pod));
	}

	@Override
	public void delete(String session, String podName) {
		try {
			send(HttpRequest.newBuilder()
				.uri(URI.create(api + "/api/v1/namespaces/" + namespace + "/pods/" + podName + "?gracePeriodSeconds=0"))
				.DELETE());
		} catch (SandboxException e) {
			log.warn("pod delete failed for {}: {}", podName, e.getMessage());
		}
	}

	@Override
	public SandboxStatus status(String podName) {
		HttpResponse<String> res = send(HttpRequest.newBuilder()
			.uri(URI.create(api + "/api/v1/namespaces/" + namespace + "/pods/" + podName))
			.GET());
		if (res.statusCode() == 404) {
			return new SandboxStatus("Gone", null, 0L);
		}
		if (res.statusCode() >= 400) {
			throw new SandboxScheduleException("pod status -> " + res.statusCode() + ": " + truncate(res.body()));
		}
		JsonObject pod = new JsonObject(res.body());
		JsonObject status = pod.getJsonObject("status", new JsonObject());
		String phase = status.getString("phase", "Pending");
		String podIp = status.getString("podIP");

		// Detect an unschedulable pod so the caller can surface a retryable "try again".
		JsonArray conditions = status.getJsonArray("conditions");
		if (conditions != null) {
			for (int i = 0; i < conditions.size(); i++) {
				JsonObject c = conditions.getJsonObject(i);
				if ("PodScheduled".equals(c.getString("type")) && "False".equals(c.getString("status"))
					&& "Unschedulable".equals(c.getString("reason"))) {
					throw new SandboxScheduleException("pod unschedulable: " + c.getString("message", ""));
				}
			}
		}
		String endpoint = podIp != null ? "http://" + podIp + ":8080" : null;
		return new SandboxStatus(phase, endpoint, parseCreated(pod));
	}

	private JsonObject podManifest(SandboxSpec spec) {
		String session = spec.session();
		String name = spec.name();
		JsonObject container = new JsonObject()
			.put("name", "runner")
			.put("image", options.getImage())
			.put("ports", new JsonArray().add(new JsonObject().put("containerPort", 8080)))
			.put("env", env(spec))
			.put("securityContext", new JsonObject()
				.put("allowPrivilegeEscalation", false)
				.put("readOnlyRootFilesystem", true)
				.put("capabilities", new JsonObject().put("drop", new JsonArray().add("ALL"))))
			.put("resources", new JsonObject()
				.put("requests", new JsonObject().put("cpu", options.getCpuRequest()).put("memory", options.getMemRequest()))
				.put("limits", new JsonObject().put("cpu", options.getCpuLimit()).put("memory", options.getMemLimit())))
			.put("volumeMounts", volumeMounts(spec))
			.put("readinessProbe", new JsonObject()
				.put("httpGet", new JsonObject().put("path", "/healthz").put("port", 8080))
				.put("initialDelaySeconds", 1).put("periodSeconds", 2));

		JsonObject podSpec = new JsonObject()
			.put("restartPolicy", "Never")
			.put("automountServiceAccountToken", false)
			.put("enableServiceLinks", false)
			.put("securityContext", new JsonObject()
				.put("runAsNonRoot", true)
				.put("runAsUser", 10001)
				.put("seccompProfile", new JsonObject().put("type", "RuntimeDefault")))
			.put("containers", new JsonArray().add(container))
			.put("volumes", volumes(spec));
		if (options.getMaxSessionSeconds() > 0) {
			podSpec.put("activeDeadlineSeconds", options.getMaxSessionSeconds());
		}

		return new JsonObject()
			.put("apiVersion", "v1")
			.put("kind", "Pod")
			.put("metadata", new JsonObject()
				.put("name", name)
				.put("labels", new JsonObject().put("app", "loom-session-runner").put("loom-session", session)))
			.put("spec", podSpec);
	}

	private HttpResponse<String> send(HttpRequest.Builder builder) {
		try {
			if (!token.isBlank()) {
				builder.header("Authorization", "Bearer " + token);
			}
			return http.send(builder.timeout(Duration.ofSeconds(30)).build(), BodyHandlers.ofString());
		} catch (IOException e) {
			throw new SandboxScheduleException("kubernetes API call failed: " + e.getMessage(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new SandboxScheduleException("kubernetes API call interrupted", e);
		}
	}

	private static long parseCreated(JsonObject pod) {
		String ts = pod.getJsonObject("metadata", new JsonObject()).getString("creationTimestamp");
		if (ts == null) {
			return System.currentTimeMillis();
		}
		try {
			return Instant.parse(ts).toEpochMilli();
		} catch (Exception e) {
			return System.currentTimeMillis();
		}
	}

	private static String envOrDefault(String name, String def) {
		String v = System.getenv(name);
		return v != null && !v.isBlank() ? v : def;
	}

	private static String readFile(String path) {
		try {
			return Files.readString(Path.of(path)).strip();
		} catch (IOException e) {
			return "";
		}
	}

	private static String firstNonBlank(String a, String b) {
		return a != null && !a.isBlank() ? a : b;
	}

	private static String truncate(String s) {
		if (s == null) {
			return "";
		}
		return s.length() <= 200 ? s : s.substring(0, 200);
	}

	private JsonArray env(SandboxSpec spec) {
		JsonArray env = new JsonArray()
			.add(new JsonObject().put("name", "RUNNER_TOKEN").put("value", spec.token()))
			.add(new JsonObject().put("name", "RUNNER_WORKSPACE").put("value", "/workspace"))
			.add(new JsonObject().put("name", "RUNNER_PORT").put("value", "8080"));
		if (spec.memoryStage()) {
			env.add(new JsonObject().put("name", "RUNNER_MEMORY_STAGE").put("value", SandboxSpec.MEMORY_STAGE_PATH));
		}
		return env;
	}

	/**
	 * The memory volume is mounted twice into the same container: read-write at the stage path, which only runnerd writes through, and read-only at the
	 * agent-visible path. Both are the same emptyDir, so a note synced through the stage is instantly readable while {@code run_shell} gets EROFS — the
	 * read-only guarantee is enforced by the kubelet, not by the unprivileged daemon.
	 */
	private JsonArray volumeMounts(SandboxSpec spec) {
		JsonArray mounts = new JsonArray()
			.add(new JsonObject().put("name", "workspace").put("mountPath", "/workspace"))
			.add(new JsonObject().put("name", "tmp").put("mountPath", "/tmp"));
		if (spec.memoryStage()) {
			mounts.add(new JsonObject().put("name", "memory").put("mountPath", spec.memoryMountPath()).put("readOnly", true));
			mounts.add(new JsonObject().put("name", "memory").put("mountPath", SandboxSpec.MEMORY_STAGE_PATH).put("readOnly", false));
		}
		return mounts;
	}

	private JsonArray volumes(SandboxSpec spec) {
		JsonArray volumes = new JsonArray()
			.add(new JsonObject().put("name", "workspace").put("emptyDir", new JsonObject().put("sizeLimit", options.getWorkspaceSize())))
			.add(new JsonObject().put("name", "tmp").put("emptyDir", new JsonObject().put("sizeLimit", "64Mi")));
		if (spec.memoryStage()) {
			volumes.add(new JsonObject().put("name", "memory").put("emptyDir", new JsonObject().put("sizeLimit", options.getWorkspaceSize())));
		}
		return volumes;
	}

}
