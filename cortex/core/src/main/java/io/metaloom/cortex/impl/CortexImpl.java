package io.metaloom.cortex.impl;

import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.cortex.Cortex;
import io.metaloom.cortex.api.node.CortexNode;
import io.metaloom.cortex.api.option.CortexOptions;

@Singleton
public class CortexImpl implements Cortex {

	private final CortexOptions options;
	private final Set<CortexNode<?, ?, ?>> nodes;

	@Inject
	public CortexImpl(CortexOptions options, Set<CortexNode<?, ?, ?>> nodes) {
		this.options = options;
		this.nodes = nodes;
	}

	@Override
	public void checkNodes() {
		for (CortexNode<?, ?, ?> node : nodes) {
			System.out.println(node.options());
		}
	}

}
