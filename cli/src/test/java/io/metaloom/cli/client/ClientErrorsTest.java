package io.metaloom.cli.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.metaloom.cli.ExitCode;
import io.metaloom.loom.client.http.error.LoomHttpClientException;

/**
 * Exit codes are the CLI's machine-readable contract, so the mapping is pinned here.
 */
public class ClientErrorsTest {

	private static LoomHttpClientException http(int status, String body) {
		return new LoomHttpClientException("Request failed", status, "msg", body);
	}

	@Test
	@DisplayName("HTTP statuses map to their dedicated exit codes")
	void testStatusMapping() {
		assertThat(ClientErrors.toCliException(http(400, "{}"), "x").getExitCode())
			.isEqualTo(ExitCode.VALIDATION_FAILED);
		assertThat(ClientErrors.toCliException(http(401, "{}"), "x").getExitCode())
			.isEqualTo(ExitCode.AUTH_REQUIRED);
		assertThat(ClientErrors.toCliException(http(403, "{}"), "x").getExitCode())
			.isEqualTo(ExitCode.FORBIDDEN);
		assertThat(ClientErrors.toCliException(http(404, "{}"), "x").getExitCode())
			.isEqualTo(ExitCode.NOT_FOUND);
		assertThat(ClientErrors.toCliException(http(409, "{}"), "x").getExitCode())
			.isEqualTo(ExitCode.CONFLICT);
		assertThat(ClientErrors.toCliException(http(503, "{}"), "x").getExitCode())
			.isEqualTo(ExitCode.SERVER_FAILURE);
	}

	@Test
	@DisplayName("a transport failure is a connect error, not a server failure")
	void testTransportFailureIsNotServerFailure() {
		// The client reports an unreachable server as a synthetic 500 with an empty body.
		// Taking that literally would tell the user the server failed when nothing was ever
		// contacted - and hand a script exit 20 instead of 15.
		LoomHttpClientException transport =
			new LoomHttpClientException("Error while excuting request", new ConnectException("Connection refused"));

		CliException e = ClientErrors.toCliException(transport, "list pipelines");

		assertThat(e.getExitCode()).isEqualTo(ExitCode.CONNECT_ERROR);
		assertThat(e.getMessage()).contains("Could not reach the server");
	}

	@Test
	@DisplayName("an unresolvable host says so specifically")
	void testUnknownHost() {
		LoomHttpClientException transport =
			new LoomHttpClientException("Error while excuting request", new UnknownHostException("nosuchhost"));

		CliException e = ClientErrors.toCliException(transport, "x");

		assertThat(e.getExitCode()).isEqualTo(ExitCode.CONNECT_ERROR);
		assertThat(e.getMessage()).contains("resolve");
	}

	@Test
	@DisplayName("a genuine server 500 with a body stays a server failure")
	void testRealServerError() {
		// The discriminator is the body: a real response has one, a transport failure cannot.
		CliException e = ClientErrors.toCliException(http(500, "{\"message\":\"boom\"}"), "x");

		assertThat(e.getExitCode()).isEqualTo(ExitCode.SERVER_FAILURE);
	}

	@Test
	@DisplayName("the server's own message is surfaced when it sends one")
	void testServerMessageSurfaced() {
		CliException e = ClientErrors.toCliException(
			http(409, "{\"message\":\"Pipeline run is already paused.\"}"), "pause run");

		assertThat(e.getMessage()).contains("already paused");
	}

	@Test
	@DisplayName("a non-JSON error body does not break the mapping")
	void testNonJsonBody() {
		// A proxy's HTML 502 page must not turn into a parse crash on top of the real error.
		CliException e = ClientErrors.toCliException(http(502, "<html>Bad Gateway</html>"), "x");

		assertThat(e.getExitCode()).isEqualTo(ExitCode.SERVER_FAILURE);
	}

	@Test
	@DisplayName("401 suggests the fix")
	void testAuthMessageIsActionable() {
		CliException e = ClientErrors.toCliException(http(401, ""), "x");

		assertThat(e.getMessage()).contains("metaloom login");
	}

	@Test
	@DisplayName("503 explains the usual cause for a pipeline run")
	void testNoProcessorHint() {
		CliException e = ClientErrors.toCliException(http(503, ""), "run pipeline");

		assertThat(e.getMessage()).contains("processor");
	}

	@Test
	@DisplayName("a raw IO failure maps to a connect error")
	void testRawIoException() {
		CliException e = ClientErrors.toCliException(new IOException("socket closed"), "x");

		assertThat(e.getExitCode()).isEqualTo(ExitCode.CONNECT_ERROR);
	}

	@Test
	@DisplayName("an existing CliException passes through unchanged")
	void testPassThrough() {
		CliException original = new CliException(ExitCode.USAGE, "bad input");

		assertThat(ClientErrors.toCliException(original, "x")).isSameAs(original);
	}

	@Test
	@DisplayName("an IllegalArgumentException is a usage error")
	void testIllegalArgument() {
		CliException e = ClientErrors.toCliException(new IllegalArgumentException("nope"), "x");

		assertThat(e.getExitCode()).isEqualTo(ExitCode.USAGE);
	}
}
