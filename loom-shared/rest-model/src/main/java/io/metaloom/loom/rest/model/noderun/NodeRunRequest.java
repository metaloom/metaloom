package io.metaloom.loom.rest.model.noderun;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestRequestModel;
import io.vertx.core.json.JsonObject;

/**
 * Request payload for starting an ad-hoc node run from a definition supplied with the request.
 *
 * <p>
 * The definition is the same JSON the pipeline editor saves and {@code validate_pipeline} checks, so
 * a caller can validate a graph before spending anything on it. It is stored on the run row rather
 * than in the pipeline catalog: an ad-hoc graph is a one-off, and putting it in the catalog would
 * leave rows behind that look like pipelines somebody drew.
 * </p>
 *
 * <p>
 * The source node may be omitted. Loom prepends its own ({@code loom-fetch}) and wires it to every
 * node without an inbound edge, because the media come from {@link #getAssetUuids()} rather than from
 * a worker enumerating a filesystem.
 * </p>
 */
public class NodeRunRequest implements RestRequestModel {

	@JsonPropertyDescription("The graph to run, in the same definition format a stored pipeline uses. The source node may be omitted.")
	private JsonObject definition;

	@JsonPropertyDescription("The assets to run the graph against. Assets with no stored binary path are reported as rejected rather than skipped silently.")
	private List<UUID> assetUuids;

	@JsonPropertyDescription("Record results in the per-asset processing ledger under an 'adhoc:' node id. Defaults to LOOM_AGENT_EXEC_PERSIST_DEFAULT (off).")
	private Boolean persist;

	@JsonPropertyDescription("Run without letting nodes take effect, to see what would happen.")
	private Boolean dryRun;

	@JsonPropertyDescription("Ask workers to attach small renderings of what each node emits.")
	private Boolean debug;

	public NodeRunRequest() {
	}

	public JsonObject getDefinition() {
		return definition;
	}

	public NodeRunRequest setDefinition(JsonObject definition) {
		this.definition = definition;
		return this;
	}

	public List<UUID> getAssetUuids() {
		return assetUuids;
	}

	public NodeRunRequest setAssetUuids(List<UUID> assetUuids) {
		this.assetUuids = assetUuids;
		return this;
	}

	public Boolean getPersist() {
		return persist;
	}

	public NodeRunRequest setPersist(Boolean persist) {
		this.persist = persist;
		return this;
	}

	public Boolean getDryRun() {
		return dryRun;
	}

	public NodeRunRequest setDryRun(Boolean dryRun) {
		this.dryRun = dryRun;
		return this;
	}

	public Boolean getDebug() {
		return debug;
	}

	public NodeRunRequest setDebug(Boolean debug) {
		this.debug = debug;
		return this;
	}

}
