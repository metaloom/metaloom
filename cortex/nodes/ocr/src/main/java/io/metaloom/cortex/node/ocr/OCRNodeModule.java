package io.metaloom.cortex.node.ocr;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import io.metaloom.cortex.api.node.FilesystemNode;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.AbstractNodeModule;
import io.metaloom.cortex.common.option.CortexNodeOptionDeserializerInfo;

@Module
public abstract class OCRNodeModule extends AbstractNodeModule {

	@Binds
	@IntoSet
	abstract FilesystemNode<?, ?, ?> bindNode(OCRNode node);

	@IntoSet
	@Provides
	public static CortexNodeOptionDeserializerInfo optionInfo() {
		return new CortexNodeOptionDeserializerInfo(OCRNodeOptions.class, OCRNodeOptions.KEY);
	}

	@Provides
	public static OCRNodeOptions options(CortexOptions options) {
		return nodeOptions(options, OCRNodeOptions.KEY, new OCRNodeOptions());
	}

}
