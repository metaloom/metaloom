package io.metaloom.cortex.common.node;

import static io.metaloom.utils.ConvertUtils.toHumanTime;
import static org.apache.commons.lang3.StringUtils.rightPad;

import javax.annotation.Nullable;

import io.metaloom.cortex.api.node.FilesystemNode;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.api.option.node.CortexNodeOptions;
import io.metaloom.loom.client.common.LoomClient;

public abstract class AbstractFilesystemNode<I, O, T extends CortexNodeOptions> extends AbstractCortexNode<I, O, T> implements FilesystemNode<I, O, T> {

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
		if (media == null || media.getSHA512() == null) {
			return progress + rightPad("[" + name() + "]", 28);
		} else {
			return progress + rightPad(media.shortHash() + " [" + name() + "]", 28);
		}
	}

	@Override
	public void set(long current, long total) {
		this.current = current;
		this.total = total;
	}
}
