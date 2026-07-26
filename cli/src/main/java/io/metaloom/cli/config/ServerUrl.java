package io.metaloom.cli.config;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * A parsed {@code --server} value.
 *
 * <p>Splits a URL into the four things {@code LoomHttpClient.builder()} needs. The path
 * prefix matters for reverse-proxied deployments: {@code https://example.com/loom} means
 * the API lives at {@code /loom/api/v1/...}, not {@code /api/v1/...}.</p>
 */
public record ServerUrl(String scheme, String host, int port, String pathPrefix) {

	private static final int DEFAULT_HTTP_PORT = 6333;

	/**
	 * @param value a URL such as {@code http://localhost:6333} or {@code https://host/loom}
	 * @throws IllegalArgumentException when the value is not a usable http(s) URL
	 */
	public static ServerUrl parse(String value) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("A server URL must be given.");
		}
		String candidate = value.trim();
		// Accept a bare host:port, which is what people type.
		if (!candidate.contains("://")) {
			candidate = "http://" + candidate;
		}
		URI uri;
		try {
			uri = new URI(candidate);
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException("Not a valid server URL: " + value, e);
		}
		String scheme = uri.getScheme() == null ? "http" : uri.getScheme().toLowerCase();
		if (!scheme.equals("http") && !scheme.equals("https")) {
			throw new IllegalArgumentException("Unsupported scheme '" + scheme + "' in server URL: " + value);
		}
		String host = uri.getHost();
		if (host == null || host.isBlank()) {
			throw new IllegalArgumentException("No host in server URL: " + value);
		}
		int port = uri.getPort();
		if (port == -1) {
			// Only assume Loom's default port when the scheme's own default was not implied.
			port = scheme.equals("https") ? 443 : DEFAULT_HTTP_PORT;
		}
		String prefix = uri.getPath() == null ? "" : uri.getPath();
		// The client appends "/api/v1" itself, so the prefix must carry no surrounding slashes.
		prefix = prefix.replaceAll("^/+", "").replaceAll("/+$", "");
		return new ServerUrl(scheme, host, port, prefix);
	}

	/** @return the {@code ws} or {@code wss} scheme matching this server */
	public String webSocketScheme() {
		return "https".equals(scheme) ? "wss" : "ws";
	}

	/** @return the base URL, without the {@code /api/v1} suffix */
	public String baseUrl() {
		StringBuilder url = new StringBuilder(scheme).append("://").append(host).append(':').append(port);
		if (!pathPrefix.isEmpty()) {
			url.append('/').append(pathPrefix);
		}
		return url.toString();
	}
}
