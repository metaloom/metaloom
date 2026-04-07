package io.metaloom.cortex.common.node;

import io.metaloom.cortex.api.node.CortexNode;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.api.option.node.CortexNodeOptions;
import io.metaloom.loom.client.common.LoomClient;

public abstract class AbstractCortexNode<I, O, T extends CortexNodeOptions> implements CortexNode<I, O, T> {

	private final LoomClient client;
	private final CortexOptions cortexOption;
	private final T option;

	public AbstractCortexNode(LoomClient client, CortexOptions cortexOption, T option) {
		this.client = client;
		this.cortexOption = cortexOption;
		this.option = option;
	}

	@Override
	public T options() {
		return option;
	}

	@Override
	public CortexOptions cortexOption() {
		return cortexOption;
	}

	public boolean isOfflineMode() {
		return client == null;
	}

	protected LoomClient client() {
		return client;
	}

	@Override
	public boolean isDryrun() {
		return cortexOption().isDryrun();
	}
}
