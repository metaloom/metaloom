package io.metaloom.loom.nodes.spec;

import java.util.List;

/**
 * SPI interface for modules that provide {@link NodeDescriptor} definitions.
 * Implementations are discovered via {@link java.util.ServiceLoader}.
 */
public interface NodeDescriptorProvider {

	/**
	 * Return the node descriptors provided by this module.
	 */
	List<NodeDescriptor> getDescriptors();
}
