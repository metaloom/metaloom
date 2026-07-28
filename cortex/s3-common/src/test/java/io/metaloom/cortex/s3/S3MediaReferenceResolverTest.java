package io.metaloom.cortex.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.common.media.LoomMediaLoader;
import io.metaloom.cortex.common.media.MediaReferenceResolver;
import io.metaloom.loom.pipeline.model.MediaRef;

public class S3MediaReferenceResolverTest {

	private static final String BUCKET = "media";

	@TempDir
	Path cacheRoot;

	@TempDir
	Path localDir;

	private FakeS3ObjectStore store;
	private LoomMediaLoader mediaLoader;
	private S3MediaReferenceResolver resolver;

	@BeforeEach
	public void setup() {
		store = new FakeS3ObjectStore().put(BUCKET, "2026/clip.mp4", "video-bytes");
		mediaLoader = mock(LoomMediaLoader.class);
		when(mediaLoader.load(any(Path.class))).thenAnswer(inv -> {
			LoomMedia media = mock(LoomMedia.class);
			when(media.path()).thenReturn(inv.getArgument(0));
			return media;
		});
		resolver = new S3MediaReferenceResolver(mediaLoader,
			new S3MediaMaterializer(store, cacheRoot, 0, 0));
	}

	@Test
	public void testS3ReferenceResolvesToLazyS3Media() {
		LoomMedia media = resolver.resolve("s3://media/2026/clip.mp4");

		assertThat(media).isInstanceOf(S3LoomMedia.class);
		assertThat(media.reference()).isEqualTo("s3://media/2026/clip.mp4");
		// Resolution costs a HEAD for the etag, but never the bytes.
		assertThat(store.downloadCalls).hasValue(0);
		verify(mediaLoader, never()).load(any(Path.class));
	}

	@Test
	public void testResolvedMediaMaterializesOnAccess() throws Exception {
		LoomMedia media = resolver.resolve("s3://media/2026/clip.mp4");

		assertThat(Files.readString(media.path())).isEqualTo("video-bytes");
		assertThat(store.downloadCalls).hasValue(1);
	}

	@Test
	public void testLocalPathStillGoesThroughTheMediaLoader() {
		// A worker with S3 configured must keep resolving filesystem media exactly as before,
		// so that mixed pipelines need no special handling.
		Path local = localDir.resolve("a.mp4");

		LoomMedia media = resolver.resolve(local.toString());

		assertThat(media).isNotInstanceOf(S3LoomMedia.class);
		verify(mediaLoader).load(local);
	}

	@Test
	public void testVanishedObjectStillYieldsAHandle() {
		// The object disappeared between enumeration and execution. The node should report a
		// normal "file not found" rather than the worker failing the task with a transport error.
		LoomMedia media = resolver.resolve("s3://media/gone.mp4");

		assertThat(media).isInstanceOf(S3LoomMedia.class);
		assertThat(((S3LoomMedia) media).ref().etag()).isNull();
	}

	@Test
	public void testOversizedObjectIsRejectedWithoutAnyNetworkCall() {
		// The size the engine already knows is enough to refuse the object, so the widened
		// MediaResolver signature earns its keep: no HEAD, no connection.
		S3MediaReferenceResolver limited = new S3MediaReferenceResolver(mediaLoader,
			new S3MediaMaterializer(store, cacheRoot, 5, 0));

		org.assertj.core.api.Assertions
			.assertThatThrownBy(() -> limited.resolve(new MediaRef("s3://media/2026/clip.mp4", null, 4096)))
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("maxObjectSize");

		assertThat(store.headCalls).hasValue(0);
		assertThat(store.downloadCalls).hasValue(0);
	}

	@Test
	public void testResolvingAMediaRefMatchesResolvingItsReference() {
		LoomMedia viaRef = resolver.resolve(new MediaRef("s3://media/2026/clip.mp4", null, 11));
		LoomMedia viaString = resolver.resolve("s3://media/2026/clip.mp4");

		assertThat(viaRef.reference()).isEqualTo(viaString.reference());
	}

	@Test
	public void testPlainResolverIsUnchangedWithoutS3() {
		// The base resolver is what a worker with no S3 configuration uses; it must stay a
		// pure pass-through to the media loader.
		Path local = localDir.resolve("b.mp4");

		new MediaReferenceResolver(mediaLoader).resolve(local.toString());

		verify(mediaLoader).load(local);
	}
}
