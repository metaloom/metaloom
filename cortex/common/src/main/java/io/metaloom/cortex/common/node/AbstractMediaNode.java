package io.metaloom.cortex.common.node;

import static io.metaloom.cortex.api.node.ResultOrigin.LOCAL;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.api.option.node.CortexNodeOptions;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.utils.hash.SHA512;

/**
 * Abstract base for media processing nodes. The input is always {@link LoomMedia}
 * and the output type {@code O} represents the computed value.
 *
 * @param <O> the output type this node produces (e.g. SHA256, fingerprint, transcript)
 * @param <T> the options type for this node
 */
public abstract class AbstractMediaNode<O, T extends CortexNodeOptions> extends AbstractFilesystemNode<LoomMedia, O, T> {

	public static final Logger log = LoggerFactory.getLogger(AbstractMediaNode.class);

	public AbstractMediaNode(LoomClient client, CortexOptions cortexOption, T option) {
		super(client, cortexOption, option);
	}

	@Override
	public NodeResult<O> process(NodeContext<LoomMedia> ctx) {
		if (!options().isEnabled()) {
			return ctx.skipped("Disabled").next();
		}
		LoomMedia media = ctx.media();
		if (!media.exists()) {
			return ctx.failure("File " + media.path() + " not found").abort();
		}
		if (isProcessable(ctx)) {
			if (isProcessed(ctx)) {
				return ctx.skipped("Already computed").origin(LOCAL).next();
			} else {
				try {
					if (isOfflineMode()) {
						return compute(ctx, null);
					} else {
						SHA512 sha512 = media.getSHA512();
						AssetResponse asset = client().loadAsset(sha512).sync();
						return compute(ctx, asset);
					}
				} catch (Exception e) {
					log.error("Failure to process media", e);
					return ctx.failure(e.getMessage()).next();
				}
			}
		} else {
			return ctx.skipped("unprocessable").next();
		}
	}

	/**
	 * Check whether the media in the context is processable by this node.
	 */
	protected abstract boolean isProcessable(NodeContext<LoomMedia> ctx);

	/**
	 * Check whether the media has already been processed and the needed value is present.
	 */
	protected abstract boolean isProcessed(NodeContext<LoomMedia> ctx);

	/**
	 * Compute the output for the given media.
	 *
	 * @param ctx   the processing context
	 * @param asset optional asset response from Loom (may be used to skip computation)
	 * @return the result containing the computed output of type {@code O}
	 */
	protected abstract NodeResult<O> compute(NodeContext<LoomMedia> ctx, AssetResponse asset) throws Exception;
}
