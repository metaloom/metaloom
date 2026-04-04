package io.metaloom.cortex.pipeline.api.event;

import io.metaloom.cortex.pipeline.api.NodeResult;
import io.metaloom.cortex.api.media.LoomMedia;

/**
 * Event published on the internal pipeline event bus when a node completes processing a media item.
 */
public class NodeCompletionEvent {

	private final String nodeId;
	private final LoomMedia media;
	private final NodeResult result;
	private final long timestamp;

	public NodeCompletionEvent(String nodeId, LoomMedia media, NodeResult result) {
		this.nodeId = nodeId;
		this.media = media;
		this.result = result;
		this.timestamp = System.currentTimeMillis();
	}

	public String getNodeId() {
		return nodeId;
	}

	public LoomMedia getMedia() {
		return media;
	}

	public NodeResult getResult() {
		return result;
	}

	public long getTimestamp() {
		return timestamp;
	}

	@Override
	public String toString() {
		return "NodeCompletionEvent{node=" + nodeId + ", state=" + result.getState() + "}";
	}
}
