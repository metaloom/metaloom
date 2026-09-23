package io.metaloom.loom.core.endpoint.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractEndpointTest;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.test.data.TestValues;

/**
 * The derived-media routes and the token that lets a browser media element reach them.
 *
 * <p>
 * The security argument here is the whole point of the feature, so most of these tests are about
 * what the token <b>cannot</b> do. An {@code <img>} or {@code <video>} cannot send an
 * {@code Authorization} header and the session cookie is {@code Secure}/{@code __Host-} - dropped
 * by every browser on a plain-HTTP deployment - so media URLs had no way to authenticate at all
 * and every preview in the UI answered 401. The fix puts a credential in a query string, which is
 * a place credentials leak from, so it is scoped to one asset, expires in minutes, and is refused
 * everywhere except the two routes that need it.
 * </p>
 *
 * <p>
 * The tests that need real bytes are skipped when there is no ffmpeg, because the capability is
 * optional by design. The authorization tests are not: they run everywhere, since a 401 is decided
 * before ffmpeg is ever consulted.
 * </p>
 */
public class AssetMediaEndpointTest extends AbstractEndpointTest implements TestValues {

	private final java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();

	private final Path storageDir;

	public AssetMediaEndpointTest() throws IOException {
		this.storageDir = Files.createTempDirectory("loom-media-test");
		loom.withOptions(o -> {
			o.getStorage().setUploadDirectory(storageDir.toString());
			o.getMedia().setCachePath(storageDir.resolve("media-cache").toString());
		});
	}

	private int port() {
		return loom.internal().boot().getRestService().getServer().actualPort();
	}

	private String url(String path) {
		return "http://localhost:" + port() + path;
	}

	/**
	 * Upload a short H.264 clip, so the poster and stream routes have something real to chew on.
	 *
	 * <p>
	 * The pattern varies per call because an asset is identified by the SHA-512 of its content:
	 * uploading the same bytes twice yields <b>one</b> asset, which silently turns any "these are
	 * two different assets" test into a tautology.
	 * </p>
	 */
	private AssetResponse uploadClip(LoomHttpClient client, String pattern) throws Exception {
		Path clip = Files.createTempFile("clip-", ".mp4");
		Files.delete(clip);
		Process ffmpeg = new ProcessBuilder("ffmpeg", "-v", "error", "-y",
			"-f", "lavfi", "-i", pattern + "=size=320x240:rate=10:duration=8",
			"-c:v", "libx264", "-pix_fmt", "yuv420p", clip.toString())
			.redirectErrorStream(true).start();
		assertThat(ffmpeg.waitFor(60, TimeUnit.SECONDS)).as("ffmpeg produced a fixture clip").isTrue();
		assertEquals(0, ffmpeg.exitValue(), "ffmpeg must produce the fixture clip");
		return client.uploadAsset(clip.toFile(), LIBRARY_UUID, "video/mp4").sync().body();
	}

	private AssetResponse uploadClip(LoomHttpClient client) throws Exception {
		return uploadClip(client, "testsrc");
	}

	/**
	 * A clip with a keyframe every two seconds, so "which keyframe does this land on" has more than
	 * one answer.
	 *
	 * <p>
	 * The default fixture is eight seconds long and libx264's default keyframe interval is 250
	 * frames, so every offset in it seeks to zero and a seek-point test passes without testing
	 * anything. Twenty frames at ten a second puts keyframes at 0, 2, 4 and 6.
	 * </p>
	 */
	private AssetResponse uploadGoppedClip(LoomHttpClient client) throws Exception {
		Path clip = Files.createTempFile("gop-", ".mp4");
		Files.delete(clip);
		Process ffmpeg = new ProcessBuilder("ffmpeg", "-v", "error", "-y",
			"-f", "lavfi", "-i", "smptebars=size=320x240:rate=10:duration=8",
			"-c:v", "libx264", "-pix_fmt", "yuv420p",
			"-g", "20", "-keyint_min", "20", "-sc_threshold", "0",
			clip.toString())
			.redirectErrorStream(true).start();
		assertThat(ffmpeg.waitFor(60, TimeUnit.SECONDS)).as("ffmpeg produced a fixture clip").isTrue();
		assertEquals(0, ffmpeg.exitValue(), "ffmpeg must produce the fixture clip");
		return client.uploadAsset(clip.toFile(), LIBRARY_UUID, "video/mp4").sync().body();
	}

	/** Length of a local media file in seconds, via ffprobe. */
	private static double probeDuration(Path file) throws Exception {
		Process probe = new ProcessBuilder("ffprobe", "-v", "error",
			"-show_entries", "format=duration", "-of", "default=nw=1:nk=1", file.toString())
			.redirectErrorStream(true).start();
		String out = new String(probe.getInputStream().readAllBytes()).strip();
		assertThat(probe.waitFor(30, TimeUnit.SECONDS)).as("ffprobe finished").isTrue();
		return Double.parseDouble(out.lines().findFirst().orElseThrow().strip());
	}

	private io.vertx.core.json.JsonObject streamStart(LoomHttpClient client, UUID assetUuid, String query) throws Exception {
		HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
			.uri(URI.create(url("/api/v1/assets/" + assetUuid + "/stream-start" + query)))
			.header("Authorization", "Bearer " + client.getToken())
			.GET().build(), BodyHandlers.ofString());
		assertEquals(200, resp.statusCode(), "asking where a stream would start");
		return new io.vertx.core.json.JsonObject(resp.body());
	}

	private static boolean ffmpegPresent() {
		try {
			Process p = new ProcessBuilder("ffmpeg", "-version")
				.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectErrorStream(true).start();
			return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
		} catch (IOException | InterruptedException e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			return false;
		}
	}

	/** A raw request with no cookie and no Authorization header - what a browser media element is. */
	private HttpResponse<byte[]> getAnonymously(String url) throws Exception {
		return http.send(HttpRequest.newBuilder().uri(URI.create(url)).GET().build(), BodyHandlers.ofByteArray());
	}

	private String mintToken(LoomHttpClient client, UUID assetUuid) throws Exception {
		HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
			.uri(URI.create(url("/api/v1/assets/" + assetUuid + "/media-token")))
			.header("Authorization", "Bearer " + client.getToken())
			.POST(HttpRequest.BodyPublishers.noBody())
			.build(), BodyHandlers.ofString());
		assertEquals(200, resp.statusCode(), "minting a media token");
		return new io.vertx.core.json.JsonObject(resp.body()).getString("token");
	}

	// ── The token itself ─────────────────────────────────────────────────

	@Test
	@DisplayName("A media token is minted with a lifetime")
	public void shouldMintAToken() throws Exception {
		Assumptions.assumeTrue(ffmpegPresent(), "ffmpeg is required to create the fixture clip");
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			AssetResponse asset = uploadClip(client);

			HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
				.uri(URI.create(url("/api/v1/assets/" + asset.getUuid() + "/media-token")))
				.header("Authorization", "Bearer " + client.getToken())
				.POST(HttpRequest.BodyPublishers.noBody())
				.build(), BodyHandlers.ofString());

			assertEquals(200, resp.statusCode());
			io.vertx.core.json.JsonObject body = new io.vertx.core.json.JsonObject(resp.body());
			assertThat(body.getString("token")).isNotBlank();
			assertThat(body.getInteger("expiresIn")).isPositive();
		}
	}

	// ── What the token may do ────────────────────────────────────────────

	@Test
	@DisplayName("A poster frame is served to a caller holding only a media token")
	public void shouldServeAPosterToAMediaToken() throws Exception {
		Assumptions.assumeTrue(ffmpegPresent(), "ffmpeg is required for poster extraction");
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			AssetResponse asset = uploadClip(client);
			String mt = mintToken(client, asset.getUuid());

			// No cookie, no header - exactly what an <img src> sends.
			HttpResponse<byte[]> resp = getAnonymously(url("/api/v1/assets/" + asset.getUuid() + "/poster?t=1&w=160&mt=" + mt));

			assertEquals(200, resp.statusCode(), "an <img> must be able to load a poster");
			assertThat(resp.headers().firstValue("content-type").orElse("")).isEqualTo("image/jpeg");
			// JPEG's magic number. A zero-length 200 would pass a status check and render nothing.
			assertThat(resp.body().length).isGreaterThan(100);
			assertThat(resp.body()[0] & 0xFF).isEqualTo(0xFF);
			assertThat(resp.body()[1] & 0xFF).isEqualTo(0xD8);
		}
	}

	@Test
	@DisplayName("The stream is served as MP4 to a caller holding only a media token")
	public void shouldServeAStreamToAMediaToken() throws Exception {
		Assumptions.assumeTrue(ffmpegPresent(), "ffmpeg is required for remuxing");
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			AssetResponse asset = uploadClip(client);
			String mt = mintToken(client, asset.getUuid());

			HttpResponse<byte[]> resp = getAnonymously(url("/api/v1/assets/" + asset.getUuid() + "/stream?mt=" + mt));

			assertEquals(200, resp.statusCode());
			assertThat(resp.headers().firstValue("content-type").orElse("")).isEqualTo("video/mp4");
			// "inline", or the browser downloads it instead of playing it.
			assertThat(resp.headers().firstValue("content-disposition").orElse("")).contains("inline");
			assertThat(resp.body().length).isGreaterThan(1000);
		}
	}

	// ── Media info ───────────────────────────────────────────────────────

	@Test
	@DisplayName("Media info reports the duration and frame rate a timeline needs")
	public void shouldReportMediaInfo() throws Exception {
		Assumptions.assumeTrue(ffmpegPresent(), "ffmpeg is required to create the fixture clip");
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			AssetResponse asset = uploadClip(client);

			HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
				.uri(URI.create(url("/api/v1/assets/" + asset.getUuid() + "/media-info")))
				.header("Authorization", "Bearer " + client.getToken())
				.GET().build(), BodyHandlers.ofString());

			assertEquals(200, resp.statusCode());
			io.vertx.core.json.JsonObject body = new io.vertx.core.json.JsonObject(resp.body());
			// The fixture is eight seconds at ten frames a second, 320x240, H.264. Asserted with a
			// tolerance rather than exactly: a container rounds, and pinning 8.0 would make this
			// test about ffmpeg's arithmetic rather than about the route reporting what it read.
			assertThat(body.getDouble("duration")).isNotNull().isBetween(7.0d, 9.0d);
			assertThat(body.getDouble("frameRate")).isNotNull().isBetween(9.5d, 10.5d);
			assertEquals(320, body.getInteger("width"));
			assertEquals(240, body.getInteger("height"));
			assertEquals("h264", body.getString("videoCodec"));
			// The whole reason the field exists: the UI must be able to say "this will not play"
			// before it renders a player that never starts.
			assertThat(body.getBoolean("streamable")).isTrue();
		}
	}

	@Test
	@DisplayName("Media info is not reachable with a media token")
	public void shouldRefuseAMediaTokenOnMediaInfo() throws Exception {
		Assumptions.assumeTrue(ffmpegPresent(), "ffmpeg is required to create the fixture clip");
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			AssetResponse asset = uploadClip(client);
			String mt = mintToken(client, asset.getUuid());

			// Media info is an ordinary JSON call from application code, which can send a header.
			// Only the two routes a media element has to reach on its own accept mt, and widening
			// that set is how a narrowly scoped credential stops being narrow.
			HttpResponse<byte[]> resp = getAnonymously(url("/api/v1/assets/" + asset.getUuid() + "/media-info?mt=" + mt));

			assertEquals(401, resp.statusCode(), "mt opens the poster and stream routes and nothing else");
		}
	}

	// ── Where a stream really begins ─────────────────────────────────────

	@Test
	@DisplayName("A seek point is the keyframe at or before the offset, not the offset")
	public void shouldReportWhereAStreamWillActuallyStart() throws Exception {
		Assumptions.assumeTrue(ffmpegPresent(), "ffmpeg is required to create the fixture clip");
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			AssetResponse asset = uploadGoppedClip(client);

			io.vertx.core.json.JsonObject body = streamStart(client, asset.getUuid(), "?t=5.5");

			// The whole point of the route. A stream copy cannot begin between keyframes, so a
			// request for 5.5s yields a response starting at 4s - and a player told otherwise runs
			// a clock a second and a half fast for as long as that response lasts.
			assertThat(body.getDouble("requested")).isEqualTo(5.5d);
			assertThat(body.getDouble("start"))
				.as("the keyframe at or before 5.5s, with keyframes every 2s")
				.isNotNull()
				.isBetween(3.8d, 4.2d);
			assertThat(body.getDouble("start")).as("a seek never lands after what was asked for").isLessThan(5.5d);
		}
	}

	@Test
	@DisplayName("The start of the file needs no probe and no seek")
	public void shouldReportZeroForTheStartOfTheFile() throws Exception {
		Assumptions.assumeTrue(ffmpegPresent(), "ffmpeg is required to create the fixture clip");
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			AssetResponse asset = uploadGoppedClip(client);

			assertThat(streamStart(client, asset.getUuid(), "?t=0").getDouble("start")).isEqualTo(0d);
			// An absent t is the same request: a player that has not seeked yet starts at the top.
			assertThat(streamStart(client, asset.getUuid(), "").getDouble("start")).isEqualTo(0d);
		}
	}

	@Test
	@DisplayName("An offset past the end answers rather than failing the seek")
	public void shouldAnswerForAnOffsetPastTheEnd() throws Exception {
		Assumptions.assumeTrue(ffmpegPresent(), "ffmpeg is required to create the fixture clip");
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			AssetResponse asset = uploadGoppedClip(client);

			// Nothing to seek to in an eight-second clip. The answer must still be a number the
			// player can use as an origin - a 500 here would leave a seek half-applied.
			io.vertx.core.json.JsonObject body = streamStart(client, asset.getUuid(), "?t=600");
			assertThat(body.getDouble("start")).isNotNull().isLessThanOrEqualTo(600d);
		}
	}

	@Test
	@DisplayName("The stream really does begin where the seek point said it would")
	public void shouldStartTheStreamWhereTheSeekPointSaid() throws Exception {
		Assumptions.assumeTrue(ffmpegPresent(), "ffmpeg is required for remuxing");
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			AssetResponse asset = uploadGoppedClip(client);
			String mt = mintToken(client, asset.getUuid());

			// The two halves have to agree, and nothing else in this file checks that they do. They
			// once did not: the probe predicted the keyframe from the container index while ffmpeg
			// subtracts a seek margin before looking, so for offsets just after a keyframe the
			// stream began a whole group of pictures earlier than the player was told.
			double start = streamStart(client, asset.getUuid(), "?t=5.5").getDouble("start");

			HttpResponse<byte[]> resp = http.send(HttpRequest.newBuilder()
				.uri(URI.create(url("/api/v1/assets/" + asset.getUuid() + "/stream?t=5.5&mt=" + mt)))
				.GET().build(), BodyHandlers.ofByteArray());
			assertEquals(200, resp.statusCode());

			Path remuxed = Files.createTempFile("remux-", ".mp4");
			Files.write(remuxed, resp.body());
			// The invariant, and the only one that ties the two routes together: a stream runs from
			// where it began to the end of the file, so what came back plus where the probe said it
			// starts must add up to the whole clip. Had the stream snapped to an earlier keyframe
			// than the probe reported, this sum would overshoot by that keyframe's distance.
			double remaining = probeDuration(remuxed);
			Files.deleteIfExists(remuxed);
			assertThat(start).as("a seek to 5.5s of a clip with keyframes every 2s").isBetween(3.8d, 4.2d);
			assertThat(start + remaining)
				.as("where the stream began (%s) plus what it delivered (%s) is the whole 8s clip", start, remaining)
				.isBetween(7.5d, 8.5d);
		}
	}

	@Test
	@DisplayName("Asking where a stream starts needs the permission the bytes need")
	public void shouldRefuseASeekPointWithoutPermission() throws Exception {
		Assumptions.assumeTrue(ffmpegPresent(), "ffmpeg is required to create the fixture clip");
		UUID assetUuid;
		try (LoomHttpClient admin = loom.httpClient()) {
			loginAdmin(admin);
			assetUuid = uploadGoppedClip(admin).getUuid();
		}
		try (LoomHttpClient client = loginPermissionlessClient()) {
			HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
				.uri(URI.create(url("/api/v1/assets/" + assetUuid + "/stream-start?t=5")))
				.header("Authorization", "Bearer " + client.getToken())
				.GET().build(), BodyHandlers.ofString());
			assertEquals(403, resp.statusCode(), "the seek point describes an asset you may not read");
		}
	}

	@Test
	@DisplayName("A seek point is not reachable with a media token")
	public void shouldRefuseAMediaTokenOnStreamStart() throws Exception {
		Assumptions.assumeTrue(ffmpegPresent(), "ffmpeg is required to create the fixture clip");
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			AssetResponse asset = uploadGoppedClip(client);
			String mt = mintToken(client, asset.getUuid());

			// Like media-info: application code asking a question, and able to send a header. Only
			// the two routes a media element must reach unaided accept mt.
			HttpResponse<byte[]> resp = getAnonymously(url("/api/v1/assets/" + asset.getUuid() + "/stream-start?t=5&mt=" + mt));

			assertEquals(401, resp.statusCode(), "mt opens the poster and stream routes and nothing else");
		}
	}

	// ── What the token may not do ────────────────────────────────────────

	@Test
	@DisplayName("Without any credential the poster route is 401")
	public void shouldRejectAnAnonymousPosterRequest() throws Exception {
		Assumptions.assumeTrue(ffmpegPresent(), "ffmpeg is required to create the fixture clip");
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			AssetResponse asset = uploadClip(client);

			HttpResponse<byte[]> resp = getAnonymously(url("/api/v1/assets/" + asset.getUuid() + "/poster"));

			assertEquals(401, resp.statusCode(), "the media routes are not public");
		}
	}

	@Test
	@DisplayName("A media token minted for one asset does not open another")
	public void shouldRefuseATokenMintedForAnotherAsset() throws Exception {
		Assumptions.assumeTrue(ffmpegPresent(), "ffmpeg is required to create the fixture clips");
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			AssetResponse first = uploadClip(client, "testsrc");
			AssetResponse second = uploadClip(client, "smptebars");
			assertThat(second.getUuid()).as("the fixtures must be two distinct assets").isNotEqualTo(first.getUuid());
			String mtForFirst = mintToken(client, first.getUuid());

			HttpResponse<byte[]> resp = getAnonymously(url("/api/v1/assets/" + second.getUuid() + "/poster?mt=" + mtForFirst));

			// The whole reason the asset is a claim rather than an afterthought: one leaked poster
			// URL must not become a key to the library.
			assertEquals(401, resp.statusCode(), "a media token is scoped to the asset it names");
		}
	}

	@Test
	@DisplayName("A media token is not a session: it opens no other route")
	public void shouldRefuseAMediaTokenAsASessionCredential() throws Exception {
		Assumptions.assumeTrue(ffmpegPresent(), "ffmpeg is required to create the fixture clip");
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			AssetResponse asset = uploadClip(client);
			String mt = mintToken(client, asset.getUuid());

			// As a bearer header on an ordinary route.
			HttpResponse<String> asHeader = http.send(HttpRequest.newBuilder()
				.uri(URI.create(url("/api/v1/assets/" + asset.getUuid())))
				.header("Authorization", "Bearer " + mt)
				.GET().build(), BodyHandlers.ofString());
			assertEquals(401, asHeader.statusCode(), "a media token must not authenticate the JSON API");

			// And as ?mt= on a route that does not accept media tokens at all.
			HttpResponse<byte[]> asQuery = getAnonymously(url("/api/v1/assets/" + asset.getUuid() + "?mt=" + mt));
			assertEquals(401, asQuery.statusCode(), "only the poster and stream routes look at mt");
		}
	}

	@Test
	@DisplayName("A session token presented as a media token is refused")
	public void shouldRefuseASessionTokenInTheMediaParameter() throws Exception {
		Assumptions.assumeTrue(ffmpegPresent(), "ffmpeg is required to create the fixture clip");
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			AssetResponse asset = uploadClip(client);

			// The session token is far longer-lived than a media token; honouring it here would let
			// a UI hand a full session to anything that can read a URL.
			HttpResponse<byte[]> resp = getAnonymously(
				url("/api/v1/assets/" + asset.getUuid() + "/poster?mt=" + client.getToken()));

			assertEquals(401, resp.statusCode(), "only a media-scoped token is accepted as mt");
		}
	}
}
