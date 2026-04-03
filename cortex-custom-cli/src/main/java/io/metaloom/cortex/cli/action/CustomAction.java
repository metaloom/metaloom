package io.metaloom.cortex.cli.action;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.cortex.api.action.ActionResult;
import io.metaloom.cortex.api.action.context.ActionContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.action.AbstractMediaAction;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.rest.model.asset.AssetResponse;

@Singleton
public class CustomAction extends AbstractMediaAction<CustomActionOptions> {

	@Inject
	public CustomAction(@Nullable LoomClient client, CortexOptions cortexOption, CustomActionOptions options) {
		super(client, cortexOption, options);
	}

	@Override
	public String name() {
		return "custom";
	}

	@Override
	protected boolean isProcessable(ActionContext ctx) {
		return true;
	}

	@Override
	protected boolean isProcessed(ActionContext ctx) {
		return false;
	}

	@Override
	protected ActionResult compute(ActionContext ctx, AssetResponse asset) throws Exception {
		System.out.println("Custom Action Invoked");
		return ActionResult.processed(true);
	}

}
