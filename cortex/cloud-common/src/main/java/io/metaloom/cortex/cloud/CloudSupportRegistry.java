package io.metaloom.cortex.cloud;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Every provider's {@link CloudSupport}, as one injectable value.
 *
 * <p>A registry rather than one binding per provider so that adding a third cloud is a change to
 * this module and its Dagger module, not to every constructor that wants cloud access.</p>
 */
public final class CloudSupportRegistry {

	private final Map<CloudProviderId, CloudSupport> supports;

	public CloudSupportRegistry(Map<CloudProviderId, CloudSupport> supports) {
		EnumMap<CloudProviderId, CloudSupport> copy = new EnumMap<>(CloudProviderId.class);
		for (CloudProviderId provider : CloudProviderId.values()) {
			CloudSupport support = supports == null ? null : supports.get(provider);
			// A missing entry is "not configured", never a null the caller has to guard.
			copy.put(provider, support == null ? CloudSupport.inactive(provider) : support);
		}
		this.supports = copy;
	}

	/**
	 * @return a registry in which no provider is configured
	 */
	public static CloudSupportRegistry empty() {
		return new CloudSupportRegistry(Map.of());
	}

	/**
	 * @param provider the provider
	 * @return its support value, never null
	 */
	public CloudSupport get(CloudProviderId provider) {
		return supports.get(provider);
	}

	/**
	 * @param provider the provider
	 * @return true when it is configured on this worker
	 */
	public boolean isActive(CloudProviderId provider) {
		return get(provider).isActive();
	}

	/**
	 * @return true when at least one provider is configured, i.e. whether the cloud reference
	 *         resolver is worth installing at all
	 */
	public boolean isAnyActive() {
		return supports.values().stream().anyMatch(CloudSupport::isActive);
	}

	/**
	 * @return the configured providers
	 */
	public List<CloudProviderId> activeProviders() {
		return supports.values().stream().filter(CloudSupport::isActive).map(CloudSupport::provider).toList();
	}
}
