package io.metaloom.loom.rest.model.pipeline;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestRequestModel;

/**
 * Request payload for triggering a pipeline run.
 *
 * <p>The caller may narrow the media that the pipeline runs against by supplying
 * {@link #getMediaUuids()}, {@link #getPath()} or {@link #getPathGlobs()}. If none is
 * supplied the processor uses the pipeline's own source node to enumerate media.</p>
 *
 * <p>When more than one selector is present the most specific one wins:
 * {@code mediaUuids} &gt; {@code pathGlobs} &gt; {@code path}. Note that {@code pathGlobs}
 * forces a full filesystem re-walk, whereas a bare {@code path} lets the source node run
 * its differential scan against the persisted per-root index — prefer {@code path} when
 * you simply want "everything under this directory".</p>
 */
public class PipelineRunRequest implements RestRequestModel {

	@JsonPropertyDescription("Optional list of asset UUIDs to run the pipeline against.")
	private List<UUID> mediaUuids;

	@JsonPropertyDescription("Optional single root directory or file to run against. Enables the source node's differential index-backed scan. Ignored when pathGlobs is set.")
	private String path;

	@JsonPropertyDescription("Optional list of path glob patterns used to select media. Forces a full re-walk and takes precedence over path.")
	private List<String> pathGlobs;

	@JsonPropertyDescription("Override the pipeline's dry-run flag for this run.")
	private Boolean dryRun;

	/**
	 * Run in debug mode: ask workers to attach small renderings of what each node emits.
	 *
	 * <p>
	 * Off by default and deliberately per <em>run</em> rather than per pipeline. A node emitting an
	 * image emits a path on the worker that produced it, which nothing else can resolve into
	 * something a human can look at - but encoding and storing a thumbnail per image per item is a
	 * cost no production run should pay, so it is asked for one run at a time.
	 * </p>
	 */
	@JsonPropertyDescription("Attach debugging previews of what each node emits. Off by default; costs nothing when unset.")
	private Boolean debug;

	/**
	 * Node ids to hold at, so a run can start already armed.
	 *
	 * <p>
	 * Run state, never definition state: these are set by whoever triggered <em>this</em> run and are
	 * not written back into the stored pipeline. Setting a breakpoint while debugging must not change
	 * the pipeline everyone else runs.
	 * </p>
	 */
	@JsonPropertyDescription("Node ids to halt at. Each node still runs; its output is withheld from downstream nodes until released.")
	private List<String> breakpoints;

	public PipelineRunRequest() {
	}

	public List<String> getBreakpoints() {
		return breakpoints;
	}

	public PipelineRunRequest setBreakpoints(List<String> breakpoints) {
		this.breakpoints = breakpoints;
		return this;
	}

	public Boolean isDebug() {
		return debug;
	}

	public PipelineRunRequest setDebug(Boolean debug) {
		this.debug = debug;
		return this;
	}

	public List<UUID> getMediaUuids() {
		return mediaUuids;
	}

	public PipelineRunRequest setMediaUuids(List<UUID> mediaUuids) {
		this.mediaUuids = mediaUuids;
		return this;
	}

	public String getPath() {
		return path;
	}

	public PipelineRunRequest setPath(String path) {
		this.path = path;
		return this;
	}

	public List<String> getPathGlobs() {
		return pathGlobs;
	}

	public PipelineRunRequest setPathGlobs(List<String> pathGlobs) {
		this.pathGlobs = pathGlobs;
		return this;
	}

	public Boolean isDryRun() {
		return dryRun;
	}

	public PipelineRunRequest setDryRun(Boolean dryRun) {
		this.dryRun = dryRun;
		return this;
	}
}
