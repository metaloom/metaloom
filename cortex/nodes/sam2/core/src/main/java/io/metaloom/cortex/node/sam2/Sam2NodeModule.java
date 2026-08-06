package io.metaloom.cortex.node.sam2;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoMap;
import dagger.multibindings.IntoSet;
import dagger.multibindings.StringKey;
import io.metaloom.cortex.api.node.FilesystemNode;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.AbstractNodeModule;
import io.metaloom.cortex.common.option.CortexNodeOptionDeserializerInfo;

@Module
public abstract class Sam2NodeModule extends AbstractNodeModule {

	@Binds
	@IntoSet
	abstract FilesystemNode<?, ?> bindNode(Sam2Node node);

	/** Without this map binding the node exists but is never schedulable - the executable-kind registry is built from this map alone. */
	@Binds
	@IntoMap
	@StringKey("sam2")
	abstract FilesystemNode<?, ?> kindSam2(Sam2Node node);

	@IntoSet
	@Provides
	public static CortexNodeOptionDeserializerInfo optionInfo() {
		return new CortexNodeOptionDeserializerInfo(Sam2NodeOptions.class, Sam2NodeOptions.KEY);
	}

	@Provides
	public static Sam2NodeOptions options(CortexOptions options) {
		return nodeOptions(options, Sam2NodeOptions.KEY, new Sam2NodeOptions());
	}

	/**
	 * Not {@code @Singleton}: the client holds three ints and builds a fresh {@code HttpClient} per
	 * call, so sharing one buys nothing. Same shape as {@code DepthmapNodeModule}.
	 */
	@Provides
	public static Sam2Client sam2Client(Sam2NodeOptions options) {
		return new Sam2Client(options.getSam2Host(), options.getSam2Port(), (int) options.getTimeoutMs());
	}
}
