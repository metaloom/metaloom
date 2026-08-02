package io.metaloom.cortex.cli.dagger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.cloud.CloudFileRef;
import io.metaloom.cortex.cloud.CloudFileStore;
import io.metaloom.cortex.cloud.CloudLoomMedia;
import io.metaloom.cortex.cloud.CloudMediaMaterializer;
import io.metaloom.cortex.cloud.CloudProviderId;
import io.metaloom.cortex.cloud.CloudSupport;
import io.metaloom.cortex.cloud.CloudSupportRegistry;
import io.metaloom.cortex.common.media.LoomMediaLoader;
import io.metaloom.cortex.common.media.MediaReferenceResolver;
import io.metaloom.cortex.common.media.SchemeMediaReferenceResolver;
import io.metaloom.cortex.s3.S3LoomMedia;
import io.metaloom.cortex.s3.S3MediaMaterializer;
import io.metaloom.cortex.s3.S3ObjectRef;
import io.metaloom.cortex.s3.S3ObjectStore;
import io.metaloom.cortex.s3.S3Support;

/**
 * The composite resolver, which replaced an {@code if/else} that could only ever know about one
 * remote scheme.
 *
 * <p>The property that matters most is the negative one: a worker with nothing remote configured
 * must still get exactly the plain resolver it always had.</p>
 */
public class MediaResolverModuleTest {

	@TempDir
	Path cacheDir;

	private LoomMediaLoader mediaLoader;

	@BeforeEach
	public void setup() {
		mediaLoader = mock(LoomMediaLoader.class);
		when(mediaLoader.load(any(Path.class))).thenAnswer(invocation -> mock(LoomMedia.class));
	}

	private S3Support activeS3() throws IOException {
		S3ObjectStore store = mock(S3ObjectStore.class);
		when(store.head(any(), any())).thenAnswer(invocation ->
			new S3ObjectRef(invocation.getArgument(0), invocation.getArgument(1), "etag", 10, 0));
		return S3Support.active(store, new S3MediaMaterializer(store, cacheDir, 0, 0), cacheDir);
	}

	private CloudSupportRegistry activeCloud() throws IOException {
		CloudFileStore store = mock(CloudFileStore.class);
		when(store.provider()).thenReturn(CloudProviderId.GDRIVE);
		when(store.get(any(), any())).thenAnswer(invocation -> new CloudFileRef(CloudProviderId.GDRIVE,
			invocation.getArgument(0), invocation.getArgument(1), "clip.mp4", null, "video/mp4",
			"md5:x", 10, 0, false, false, null, true));
		CloudMediaMaterializer materializer = new CloudMediaMaterializer(store, cacheDir, 0, 0);
		return new CloudSupportRegistry(Map.of(CloudProviderId.GDRIVE,
			CloudSupport.active(CloudProviderId.GDRIVE, store, materializer, cacheDir)));
	}

	@Test
	public void testPlainResolverWhenNothingRemoteIsConfigured() {
		MediaReferenceResolver resolver = MediaResolverModule.provideMediaReferenceResolver(
			mediaLoader, S3Support.inactive(), CloudSupportRegistry.empty());

		// Not a subclass: the exact object a worker got before remote media existed.
		assertThat(resolver.getClass()).isEqualTo(MediaReferenceResolver.class);
	}

	@Test
	public void testS3OnlyStillResolvesS3AndLocal() throws IOException {
		MediaReferenceResolver resolver = MediaResolverModule.provideMediaReferenceResolver(
			mediaLoader, activeS3(), CloudSupportRegistry.empty());

		assertThat(resolver).isInstanceOf(SchemeMediaReferenceResolver.class);
		assertThat(resolver.resolve("s3://bucket/key.mp4")).isInstanceOf(S3LoomMedia.class);

		resolver.resolve("/media/clip.mp4");
		verify(mediaLoader).load(Paths.get("/media/clip.mp4"));
	}

	@Test
	public void testCloudOnlyResolvesGdriveAndLocal() throws IOException {
		MediaReferenceResolver resolver = MediaResolverModule.provideMediaReferenceResolver(
			mediaLoader, S3Support.inactive(), activeCloud());

		assertThat(resolver.resolve("gdrive://d/f/clip.mp4")).isInstanceOf(CloudLoomMedia.class);

		resolver.resolve("/media/clip.mp4");
		verify(mediaLoader).load(Paths.get("/media/clip.mp4"));
	}

	@Test
	public void testS3AndCloudTogetherRouteByScheme() throws IOException {
		MediaReferenceResolver resolver = MediaResolverModule.provideMediaReferenceResolver(
			mediaLoader, activeS3(), activeCloud());

		assertThat(resolver.resolve("s3://bucket/key.mp4")).isInstanceOf(S3LoomMedia.class);
		assertThat(resolver.resolve("gdrive://d/f/clip.mp4")).isInstanceOf(CloudLoomMedia.class);
	}

	@Test
	public void testAnUnknownSchemeFallsThroughToTheMediaLoader() throws IOException {
		MediaReferenceResolver resolver = MediaResolverModule.provideMediaReferenceResolver(
			mediaLoader, activeS3(), activeCloud());

		// An unrecognised reference has always meant "a path"; that must not change.
		resolver.resolve("/media/clip.mp4");
		verify(mediaLoader).load(Paths.get("/media/clip.mp4"));
	}

	@Test
	public void testAConfiguredButUnusedProvidersSchemeIsNotClaimed() throws IOException {
		MediaReferenceResolver resolver = MediaResolverModule.provideMediaReferenceResolver(
			mediaLoader, S3Support.inactive(), activeCloud());

		// Only Google is active, so a onedrive:// reference is nobody's and falls back to local.
		resolver.resolve("onedrive://d/f/clip.mp4");
		verify(mediaLoader).load(any(Path.class));
	}
}
