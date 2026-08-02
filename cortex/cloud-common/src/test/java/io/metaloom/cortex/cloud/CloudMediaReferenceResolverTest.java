package io.metaloom.cortex.cloud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.common.media.LoomMediaLoader;
import io.metaloom.loom.pipeline.model.MediaRef;

public class CloudMediaReferenceResolverTest {

	@TempDir
	Path cacheDir;

	private FakeCloudFileStore gdrive;
	private LoomMediaLoader mediaLoader;
	private CloudSupportRegistry registry;

	@BeforeEach
	public void setup() {
		gdrive = new FakeCloudFileStore(CloudProviderId.GDRIVE);
		mediaLoader = mock(LoomMediaLoader.class);
		when(mediaLoader.load(any(Path.class))).thenAnswer(invocation -> {
			LoomMedia media = mock(LoomMedia.class);
			when(media.absolutePath()).thenReturn(invocation.getArgument(0).toString());
			return media;
		});
		registry = registryWith(0);
	}

	private CloudSupportRegistry registryWith(long maxObjectSize) {
		CloudMediaMaterializer materializer = new CloudMediaMaterializer(gdrive, cacheDir, maxObjectSize, 0);
		return new CloudSupportRegistry(Map.of(CloudProviderId.GDRIVE,
			CloudSupport.active(CloudProviderId.GDRIVE, gdrive, materializer, cacheDir)));
	}

	@Test
	public void testGdriveReferenceResolvesToLazyMedia() {
		String id = gdrive.putFile("d", null, "clip.mp4", "x");
		LoomMedia media = new CloudMediaReferenceResolver(mediaLoader, registry)
			.resolve("gdrive://d/" + id + "/clip.mp4");

		assertThat(media).isInstanceOf(CloudLoomMedia.class);
		assertThat(((CloudLoomMedia) media).isMaterialized()).isFalse();
		assertThat(gdrive.downloadCalls).hasValue(0);
	}

	@Test
	public void testResolutionCostsOneMetadataReadAndNoBytes() {
		String id = gdrive.putFile("d", null, "clip.mp4", "x");
		new CloudMediaReferenceResolver(mediaLoader, registry).resolve("gdrive://d/" + id + "/clip.mp4");

		assertThat(gdrive.getCalls).hasValue(1);
		assertThat(gdrive.downloadCalls).hasValue(0);
	}

	@Test
	public void testResolvedMediaMaterializesOnAccess() {
		String id = gdrive.putFile("d", null, "clip.mp4", "hello");
		LoomMedia media = new CloudMediaReferenceResolver(mediaLoader, registry)
			.resolve("gdrive://d/" + id + "/clip.mp4");

		media.path();
		assertThat(gdrive.downloadCalls).hasValue(1);
	}

	@Test
	public void testLocalPathStillGoesThroughTheMediaLoader() {
		new CloudMediaReferenceResolver(mediaLoader, registry).resolve("/media/clip.mp4");
		verify(mediaLoader).load(Paths.get("/media/clip.mp4"));
	}

	@Test
	public void testAnUnconfiguredProvidersReferenceFallsThroughToLocal() {
		// OneDrive is not active in this registry, so an onedrive:// reference is not ours to
		// resolve; it must not blow up in the runner.
		new CloudMediaReferenceResolver(mediaLoader, registry).resolve("onedrive://d/f/x.mp4");
		verify(mediaLoader).load(any(Path.class));
	}

	@Test
	public void testHandlesOnlyClaimsConfiguredProviders() {
		CloudMediaReferenceResolver resolver = new CloudMediaReferenceResolver(mediaLoader, registry);

		assertThat(resolver.handles("gdrive://d/f/x.mp4")).isTrue();
		assertThat(resolver.handles("onedrive://d/f/x.mp4")).isFalse();
		assertThat(resolver.handles("/media/x.mp4")).isFalse();
	}

	@Test
	public void testAVanishedFileStillYieldsAHandle() {
		LoomMedia media = new CloudMediaReferenceResolver(mediaLoader, registry)
			.resolve("gdrive://d/vanished/x.mp4");

		// A handle, not an exception: the node then reports a normal "file not found" instead of
		// the worker failing the task with an unrelated transport error.
		assertThat(media).isInstanceOf(CloudLoomMedia.class);
		assertThat(media.exists()).isFalse();
	}

	@Test
	public void testAnOversizedFileIsRejectedWithoutAnyNetworkCall() {
		CloudSupportRegistry capped = registryWith(5);
		MediaRef ref = new MediaRef("gdrive://d/f/big.mp4", null, 100);

		assertThatThrownBy(() -> new CloudMediaReferenceResolver(mediaLoader, capped).resolve(ref))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("exceeds the configured maxObjectSize");
		assertThat(gdrive.getCalls).hasValue(0);
		verifyNoInteractions(mediaLoader);
	}

	@Test
	public void testResolvingAMediaRefMatchesResolvingItsReference() {
		String id = gdrive.putFile("d", null, "clip.mp4", "x");
		CloudMediaReferenceResolver resolver = new CloudMediaReferenceResolver(mediaLoader, registry);

		LoomMedia fromString = resolver.resolve("gdrive://d/" + id + "/clip.mp4");
		LoomMedia fromRef = resolver.resolve(new MediaRef("gdrive://d/" + id + "/clip.mp4", null, -1));

		assertThat(fromRef.reference()).isEqualTo(fromString.reference());
	}
}
