package io.metaloom.loom.rest.model.pipeline;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestRequestModel;

/**
 * Ask a node held at a breakpoint to run again over the same input.
 *
 * <p>
 * The point of stopping a run at a node is to change something and see what happens. This is the
 * "and see what happens" half: the node runs again over exactly the inputs it already had — the
 * engine rebuilds them from the upstream results the item still holds — with whatever settings are
 * given here.
 * </p>
 *
 * <p>
 * Settings apply to <strong>this run only</strong> and never touch the stored pipeline. Keeping a
 * setting you liked is a separate, deliberate act: the editor saves it through the ordinary pipeline
 * update endpoint, which is what creates a new version. That separation is why experimenting on a
 * live run is safe.
 * </p>
 */
public class PipelineNodeReExecuteRequest implements RestRequestModel {

	@JsonPropertyDescription("The run item whose execution should run again.")
	private String itemUuid;

	@JsonPropertyDescription("Which element of a fanned-out sequence to re-run; 0 when the node runs once per item.")
	private int elementSeq;

	@JsonPropertyDescription("Node options to apply for the rest of this run, merged over the pipeline's own. Omit to re-run with the settings already in effect; send an empty object to drop any override and go back to the pipeline definition.")
	private Map<String, Object> options;

	public PipelineNodeReExecuteRequest() {
	}

	public String getItemUuid() {
		return itemUuid;
	}

	public PipelineNodeReExecuteRequest setItemUuid(String itemUuid) {
		this.itemUuid = itemUuid;
		return this;
	}

	public int getElementSeq() {
		return elementSeq;
	}

	public PipelineNodeReExecuteRequest setElementSeq(int elementSeq) {
		this.elementSeq = elementSeq;
		return this;
	}

	public Map<String, Object> getOptions() {
		return options;
	}

	public PipelineNodeReExecuteRequest setOptions(Map<String, Object> options) {
		this.options = options;
		return this;
	}
}
