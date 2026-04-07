package io.metaloom.cortex.api.node.context.impl;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultOrigin;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.media.LoomMedia;

public class NodeContextImpl<I> implements NodeContext<I> {

	private final long start;
	private final I input;
	private final LoomMedia media;

	private String info;
	private ResultOrigin origin;
	private String skipReason;

	@SuppressWarnings("unchecked")
	public NodeContextImpl(LoomMedia media) {
		this.media = media;
		this.input = (I) media;
		this.start = System.currentTimeMillis();
	}

	public NodeContextImpl(I input, LoomMedia media) {
		this.input = input;
		this.media = media;
		this.start = System.currentTimeMillis();
	}

	@Override
	public I input() {
		return input;
	}

	@Override
	public long duration() {
		return System.currentTimeMillis() - start;
	}

	@Override
	public LoomMedia media() {
		return media;
	}

	@Override
	public NodeContext<I> info(String info) {
		this.info = info;
		return this;
	}

	@Override
	public <O> NodeResult<O> next() {
		if (skipReason != null) {
			return NodeResult.skipped();
		} else {
			return NodeResult.success(null);
		}
	}

	@Override
	public <O> NodeResult<O> abort() {
		return NodeResult.failed();
	}

	@Override
	public NodeContext<I> print(String string, String string2) {
		return this;
	}

	@Override
	public NodeContext<I> origin(ResultOrigin origin) {
		this.origin = origin;
		return this;
	}

	@Override
	public ResultOrigin origin() {
		return origin;
	}

	@Override
	public NodeContext<I> failure(String cause) {
		return this;
	}

	@Override
	public NodeContext<I> skipped(String reason) {
		this.skipReason = reason;
		return this;
	}
}
