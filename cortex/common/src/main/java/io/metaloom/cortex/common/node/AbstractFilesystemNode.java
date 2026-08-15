package io.metaloom.cortex.common.node;

import static io.metaloom.utils.ConvertUtils.toHumanTime;
import static org.apache.commons.lang3.StringUtils.rightPad;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.node.FilesystemNode;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.api.option.node.CortexNodeOptions;
import io.metaloom.loom.client.common.LoomClient;

public abstract class AbstractFilesystemNode<I, T extends CortexNodeOptions> extends AbstractCortexNode<I, T> implements FilesystemNode<I, T> {

	private static final Logger log = LoggerFactory.getLogger(AbstractFilesystemNode.class);

	private long current = 1L;
	private long total = 1L;

	public AbstractFilesystemNode(@Nullable LoomClient client, CortexOptions cortexOption, T option) {
		super(client, cortexOption, option);
	}

	@Override
	public void initialize() {
		// NOOP
	}

	@Override
	public void error(LoomMedia media, String msg) {
		String prefix = prefix(media);
		System.err.println(prefix + msg);
	}

	@Override
	public void print(NodeContext<?> ctx, String result, String msg) {
		if (result == null) {
			result = "";
		}
		LoomMedia media = ctx.media();
		long dur = ctx.duration();
		String prefix = prefix(media);
		result = rightPad(result, 12);
		String time = rightPad(" [" + toHumanTime(dur) + "] ", 12);
		System.out.println(prefix + result + time + msg);
	}

	private String prefix(LoomMedia media) {
		String progress = rightPad("[" + current + "/" + total + "] ", 17);
		String shortHash = shortHash(media);
		if (shortHash == null) {
			return progress + rightPad("[" + name() + "]", 28);
		} else {
			return progress + rightPad(shortHash + " [" + name() + "]", 28);
		}
	}

	/**
	 * Read the media hash for display purposes, tolerating a media that cannot produce one.
	 *
	 * <p>The hash is read from an extended attribute, and that read throws for a file that has been removed since the scan, that lives on a
	 * filesystem without xattr support, or that is not readable. This method is only ever reached from {@link #print(NodeContext, String, String)}
	 * and {@link #error(LoomMedia, String)} - the reporting path - and a throw there would escape the per-node catch in the processor and abort the
	 * whole scan while discarding the failure that was being reported.</p>
	 *
	 * @param media
	 * @return the short hash, or null when none can be determined
	 */
	private String shortHash(LoomMedia media) {
		if (media == null) {
			return null;
		}
		try {
			return media.getSHA512() == null ? null : media.shortHash();
		} catch (Exception e) {
			log.debug("Could not determine the hash of media {} for reporting.", media, e);
			return null;
		}
	}

	@Override
	public void set(long current, long total) {
		this.current = current;
		this.total = total;
	}
}
