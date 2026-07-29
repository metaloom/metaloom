package io.metaloom.cortex.node.script.engine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import io.metaloom.cortex.api.media.LoomMedia;

/**
 * Everything a script can see and everything it can produce, in one engine-agnostic carrier.
 *
 * <p>
 * The script has no channel other than this object. In particular it never receives the
 * {@link LoomMedia} handle itself - {@link #media()} is a flattened, read-only snapshot - because
 * handing out the real handle would expose {@code file()} and {@code open()} and quietly defeat
 * the sandbox for a node running with {@code trusted = false}.
 * </p>
 */
public class ScriptBindings {

	private final Map<String, Object> media;
	private final Map<String, Object> data;
	private final Map<String, Object> params;
	private final ScriptOutputCollector out;
	private final ScriptLogger log;
	private final ScriptLimits limits;

	public ScriptBindings(LoomMedia media, Map<String, Object> data, Map<String, Object> params,
		ScriptOutputCollector out, ScriptLogger log, ScriptLimits limits) {
		this.media = snapshot(media);
		this.data = data == null ? Map.of() : data;
		this.params = params == null ? Map.of() : params;
		this.out = out;
		this.log = log;
		this.limits = limits;
	}

	/**
	 * Flatten the media handle into plain values.
	 *
	 * <p>
	 * {@code size} is read defensively: the file can disappear between the pipeline's existence
	 * check and the script running, and a script node is not the place to discover that with a
	 * stack trace.
	 * </p>
	 */
	private static Map<String, Object> snapshot(LoomMedia media) {
		Map<String, Object> view = new LinkedHashMap<>();
		if (media == null) {
			return view;
		}
		view.put("path", String.valueOf(media.path()));
		view.put("absolutePath", media.absolutePath());
		view.put("isVideo", media.isVideo());
		view.put("isImage", media.isImage());
		view.put("isAudio", media.isAudio());
		view.put("isDocument", media.isDocument());
		try {
			view.put("size", media.size());
		} catch (Exception e) {
			view.put("size", -1L);
		}
		try {
			view.put("sha512", media.getSHA512() == null ? null : media.getSHA512().toString());
		} catch (Exception e) {
			view.put("sha512", null);
		}
		return view;
	}

	/** Read-only view of the media item being processed. */
	public Map<String, Object> media() {
		return Collections.unmodifiableMap(media);
	}

	/**
	 * The structured payload wired into the node's {@code data} input port.
	 *
	 * <p>
	 * This replaces the former {@code upstream} binding, a map keyed by upstream node id: a script
	 * reading {@code upstream['whisper']['whisper_result']} broke the moment the pipeline author
	 * renamed that node, and there was no way for it to find out. Now the <em>edge</em> decides
	 * what lands here.
	 * </p>
	 */
	public Map<String, Object> data() {
		return data;
	}

	/** The node's free-form parameter bag. */
	public Map<String, Object> params() {
		return params;
	}

	/** The output collector backing the {@code out} binding. */
	public ScriptOutputCollector out() {
		return out;
	}

	/** The logger backing the {@code log} binding. */
	public ScriptLogger log() {
		return log;
	}

	/** The envelope this execution runs inside; engines read the capability flags from here. */
	public ScriptLimits limits() {
		return limits;
	}
}
