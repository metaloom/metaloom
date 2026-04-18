package io.metaloom.cortex.node.captioning;

import io.metaloom.cortex.api.option.node.AbstractNodeOptions;

public class CaptioningNodeOptions extends AbstractNodeOptions<CaptioningNodeOptions> {

	private int smolVLMPort = 8000;
	private String smolVLMHost = "localhost";

	@Override
	protected CaptioningNodeOptions self() {
		return this;
	}

	public int getSmolVLMPort() {
		return smolVLMPort;
	}

	public void setSmolVLMPort(int smolVLMPort) {
		this.smolVLMPort = smolVLMPort;
	}

	public String getSmolVLMHost() {
		return smolVLMHost;
	}

	public void setSmolVLMHost(String smolVLMHost) {
		this.smolVLMHost = smolVLMHost;
	}

}
