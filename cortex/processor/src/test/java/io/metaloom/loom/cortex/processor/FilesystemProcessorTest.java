package io.metaloom.loom.cortex.processor;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.FilesystemNode;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.option.node.CortexNodeOptions;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.cortex.common.media.LoomMediaComponent;
import io.metaloom.cortex.common.media.LoomMediaLoader;
import io.metaloom.cortex.scanner.FilesystemProcessor;
import io.metaloom.cortex.scanner.impl.FilesystemProcessorImpl;
import io.metaloom.fs.linux.LinuxFilesystemScanner;
import io.metaloom.fs.linux.impl.LinuxFilesystemScannerImpl;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.test.LocalTestData;

public class FilesystemProcessorTest {

	@BeforeAll
	public static void reset() throws IOException {
		LocalTestData.resetXattr();
	}

	@Test
	public void testProcessor() throws IOException {
		LinuxFilesystemScanner scanner = new LinuxFilesystemScannerImpl();
		FilesystemProcessor processor = new FilesystemProcessorImpl(scanner, Set.of(dummyNode()), mockLoader());
		processor.analyze(null, LocalTestData.localDir());
	}

	private FilesystemNode<?, ?, ?> dummyNode() throws IOException {
		FilesystemNode<?, ?, ?> node = new AbstractMediaNode<Void, CortexNodeOptions>(null, null, null) {

			@Override
			public CortexNodeOptions options() {
				CortexNodeOptions options = mock(CortexNodeOptions.class);
				when(options.isEnabled()).thenReturn(true);
				return options;
			}

			@Override
			public String name() {
				return "dummy";
			}

			@Override
			protected boolean isProcessable(NodeContext<LoomMedia> ctx) {
				return true;
			}

			@Override
			protected NodeResult<Void> compute(NodeContext<LoomMedia> ctx, AssetResponse asset) throws Exception {
				return NodeResult.success(null);
			}
		};
		return node;
	}

	private LoomMediaLoader mockLoader() {
		LoomMediaComponent.Builder mediaBuilder = mock(LoomMediaComponent.Builder.class);

		// Just fake the binding of the instance
		when(mediaBuilder.loomMediaPath(Mockito.any())).thenAnswer(invocation -> {
			LoomMedia mediaMock = mock(LoomMedia.class);
			LoomMediaComponent mediaComponent = mock(LoomMediaComponent.class);
			Path path = invocation.getArgument(0, Path.class);
			when(mediaMock.path()).thenReturn(path);
			when(mediaBuilder.build()).thenReturn(mediaComponent);
			when(mediaComponent.media()).thenReturn(mediaMock);
			return mediaBuilder;
		});

		return new LoomMediaLoader(() -> mediaBuilder);
	}
}
