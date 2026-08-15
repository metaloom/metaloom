package io.metaloom.cortex.scanner.impl;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.FilesystemNode;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.common.media.LoomMediaLoader;
import io.metaloom.cortex.scanner.FilesystemProcessor;
import io.metaloom.fs.FileInfo;
import io.metaloom.fs.FileState;
import io.metaloom.fs.linux.LinuxFilesystemScanner;
import io.metaloom.utils.fs.FilterHelper;
import io.metaloom.utils.hash.SHA512;
import me.tongfei.progressbar.ProgressBar;

@Singleton
public class FilesystemProcessorImpl implements FilesystemProcessor {

	private final Set<FilesystemNode<?, ?>> nodes;

	private final LinuxFilesystemScanner scanner;

	private final LoomMediaLoader loader;

	public static final Logger log = LoggerFactory.getLogger(FilesystemProcessorImpl.class);

	@Inject
	public FilesystemProcessorImpl(LinuxFilesystemScanner scanner, Set<FilesystemNode<?, ?>> nodes, LoomMediaLoader loader) {
		this.scanner = scanner;
		this.nodes = nodes;
		this.loader = loader;
	}

	@Override
	public void analyze(List<String> enabledNodes, Path path) throws IOException {

		AtomicLong count = new AtomicLong(0);
		// 1. Scan the whole fs tree and count the files.
		List<FileInfo> newMediaFiles = scanner.scanStream(path)
			.filter(info -> {
				return info.state() == FileState.NEW;
			})
			.filter(info -> FilterHelper.isVideo(info.path()))
			.filter(info -> FilterHelper.notEmpty(info.path()))
			.collect(Collectors.toList());

		long total = newMediaFiles.size();
		// try (ProgressBar pb = new ProgressBar("Processor", total)) {

		newMediaFiles.stream().map(info -> loader.load(info.path()))
			.forEach(media -> {

				long current = count.incrementAndGet();
				boolean processed = false;
				// pb.setExtraMessage("[" + media.path().toFile().getName() + "]");
				for (FilesystemNode<?, ?> node : nodes) {
					if (enabledNodes != null && !enabledNodes.isEmpty() && !enabledNodes.contains(node.name().toLowerCase())) {
						if (log.isDebugEnabled()) {
							log.debug("Node {} will be skipped", node.name());
						}
						continue;
					}

					log.debug("Processing media {} using node {}", media, node.name());
					node.set(current, total);
					try {
						NodeContext<LoomMedia> ctx = NodeContext.create(media);
						@SuppressWarnings("unchecked")
						FilesystemNode<LoomMedia, ?> typedNode = (FilesystemNode<LoomMedia, ?>) node;
						NodeResult result = typedNode.process(ctx);
						if (result == null) {
							log.error("Node '{}' failed to process media {}. Invalid result returned.", node.name(), media);
							return;
						}

						String originName = ctx.resultOrigin() != null ? ctx.resultOrigin().name() : "NA";
						node.print(ctx, result.getState().name(), originName);
						processed |= result.getState() == ResultState.SUCCESS;
					} catch (Exception e) {
						e.printStackTrace();
						node.error(media, "Error while processing node " + node.name());
					}
					if (current % 100 == 0) {
						node.flush();
					}
				}
				if (processed) {
					System.out.println();
				}
				if (log.isTraceEnabled()) {
					// Reading the hash hits an extended attribute and throws for media that has gone away or lives on a filesystem without
					// xattr support. Enabling trace logging must not be able to abort a scan.
					try {
						SHA512 hashsum = media.getSHA512();
						log.trace("Adding {}", hashsum);
					} catch (Exception e) {
						log.trace("Could not read the hash of media {}", media, e);
					}
					if (current % 1000 == 0) {
						log.trace("Count: " + current);
					}
				}
				// pb.step();
				// pb.refresh();
				// System.out.println();
			});
		// }
	}

	@Override
	public LinuxFilesystemScanner getScanner() {
		return scanner;
	}

}
