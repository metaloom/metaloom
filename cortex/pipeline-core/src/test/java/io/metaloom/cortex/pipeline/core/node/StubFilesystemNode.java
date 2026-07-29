package io.metaloom.cortex.pipeline.core.node;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.FilesystemNode;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.PortOutput;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.api.option.node.CortexNodeOptions;

/**
 * Minimal cortex-side node used to drive {@link CortexNodeAdapter} directly.
 * The processing behaviour is supplied as a function so a test can return any
 * {@link NodeResult} — including {@code null} — without defining a new class
 * per case.
 */
class StubFilesystemNode implements FilesystemNode<LoomMedia, CortexNodeOptions> {

	private final String name;
	private final Function<NodeContext<LoomMedia>, NodeResult> behaviour;
	private final AtomicInteger initializeCount = new AtomicInteger();

	private volatile NodeContext<LoomMedia> lastContext;

	StubFilesystemNode(String name, Function<NodeContext<LoomMedia>, NodeResult> behaviour) {
		this.name = name;
		this.behaviour = behaviour;
	}

	/** A node that always succeeds with the given port outputs. */
	static StubFilesystemNode succeeding(String name, Map<String, PortOutput> outputs) {
		return new StubFilesystemNode(name, ctx -> NodeResult.success(outputs));
	}

	@Override
	public NodeResult process(NodeContext<LoomMedia> ctx) throws IOException {
		this.lastContext = ctx;
		return behaviour.apply(ctx);
	}

	/** The context handed to the last {@code process} call, for input-port assertions. */
	NodeContext<LoomMedia> lastContext() {
		return lastContext;
	}

	int initializeCount() {
		return initializeCount.get();
	}

	@Override
	public String name() {
		return name;
	}

	@Override
	public void initialize() {
		initializeCount.incrementAndGet();
	}

	@Override
	public boolean isDryrun() {
		return false;
	}

	@Override
	public CortexNodeOptions options() {
		return null;
	}

	@Override
	public CortexOptions cortexOption() {
		return null;
	}

	@Override
	public void print(NodeContext<?> ctx, String result, String msg) {
		// NOOP
	}

	@Override
	public void error(LoomMedia media, String msg) {
		// NOOP
	}

	@Override
	public void set(long current, long total) {
		// NOOP
	}
}
