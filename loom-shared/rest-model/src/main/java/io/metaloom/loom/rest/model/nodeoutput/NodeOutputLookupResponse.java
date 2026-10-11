package io.metaloom.loom.rest.model.nodeoutput;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.metaloom.loom.rest.model.RestResponseModel;
import io.metaloom.loom.rest.model.asset.AssetResponse;

/**
 * The answer to a node asking "has this already been computed?" before it starts work.
 *
 * <p>
 * Carries the asset as well as the stored output, because a node needs the asset either way: on a hit to know where it is, on a miss to compute and
 * persist. Answering both in one request is what makes the check free - it replaces the asset load the node made anyway.
 * </p>
 */
public class NodeOutputLookupResponse implements RestResponseModel<NodeOutputLookupResponse> {

	@JsonProperty(required = true)
	@JsonPropertyDescription("The asset the lookup was made for.")
	private AssetResponse asset;

	@JsonProperty(required = false)
	@JsonPropertyDescription("The stored output for the requested node kind and reuse key, or null when the node has to compute.")
	private NodeOutputResponse output;

	public NodeOutputLookupResponse() {
	}

	public AssetResponse getAsset() {
		return asset;
	}

	public NodeOutputLookupResponse setAsset(AssetResponse asset) {
		this.asset = asset;
		return this;
	}

	public NodeOutputResponse getOutput() {
		return output;
	}

	public NodeOutputLookupResponse setOutput(NodeOutputResponse output) {
		this.output = output;
		return this;
	}

	@Override
	public NodeOutputLookupResponse self() {
		return this;
	}
}
