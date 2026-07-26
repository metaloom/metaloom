package io.metaloom.cli.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class ServerUrlTest {

	@Test
	@DisplayName("a plain URL splits into scheme, host and port")
	void testSimple() {
		ServerUrl url = ServerUrl.parse("http://localhost:6333");

		assertThat(url.scheme()).isEqualTo("http");
		assertThat(url.host()).isEqualTo("localhost");
		assertThat(url.port()).isEqualTo(6333);
		assertThat(url.pathPrefix()).isEmpty();
	}

	@Test
	@DisplayName("a bare host:port is accepted, because that is what people type")
	void testBareHostPort() {
		ServerUrl url = ServerUrl.parse("loom.example.com:8080");

		assertThat(url.scheme()).isEqualTo("http");
		assertThat(url.host()).isEqualTo("loom.example.com");
		assertThat(url.port()).isEqualTo(8080);
	}

	@Test
	@DisplayName("https without a port defaults to 443, not to Loom's port")
	void testHttpsDefaultPort() {
		// Getting this wrong would send every TLS request to :6333 and fail confusingly.
		ServerUrl url = ServerUrl.parse("https://loom.example.com");

		assertThat(url.port()).isEqualTo(443);
		assertThat(url.webSocketScheme()).isEqualTo("wss");
	}

	@Test
	@DisplayName("http without a port defaults to Loom's port")
	void testHttpDefaultPort() {
		assertThat(ServerUrl.parse("http://localhost").port()).isEqualTo(6333);
	}

	@Test
	@DisplayName("a sub-path becomes the path prefix, for reverse-proxied deployments")
	void testPathPrefix() {
		ServerUrl url = ServerUrl.parse("https://example.com/loom");

		assertThat(url.pathPrefix()).isEqualTo("loom");
		assertThat(url.baseUrl()).isEqualTo("https://example.com:443/loom");
	}

	@Test
	@DisplayName("surrounding slashes are stripped from the prefix")
	void testPrefixSlashesStripped() {
		// The client appends "/api/v1" itself, so a prefix carrying slashes would produce
		// a doubled separator.
		assertThat(ServerUrl.parse("https://example.com/loom/").pathPrefix()).isEqualTo("loom");
		assertThat(ServerUrl.parse("https://example.com//loom//").pathPrefix()).isEqualTo("loom");
	}

	@Test
	@DisplayName("ws scheme follows the http scheme")
	void testWebSocketScheme() {
		assertThat(ServerUrl.parse("http://a:1").webSocketScheme()).isEqualTo("ws");
		assertThat(ServerUrl.parse("https://a:1").webSocketScheme()).isEqualTo("wss");
	}

	@Test
	@DisplayName("unusable values are rejected with a clear message")
	void testInvalid() {
		assertThatThrownBy(() -> ServerUrl.parse(null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> ServerUrl.parse("  ")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> ServerUrl.parse("ftp://example.com"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("scheme");
	}
}
