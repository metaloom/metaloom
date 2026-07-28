package io.metaloom.cortex.node.script.engine;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;

/**
 * The {@code log} binding.
 *
 * <p>
 * Lines are prefixed with the node id, because a graph may contain several script nodes and an
 * unattributed line in the worker log is useless. The line budget exists so a script looping over
 * a long transcript cannot fill a disk; once exhausted, one final line says so and further calls
 * are dropped.
 * </p>
 */
public class ScriptLogger {

	private final Logger log;
	private final String nodeId;
	private final int maxLines;
	private final List<String> captured = new ArrayList<>();

	private int emitted;

	public ScriptLogger(Logger log, String nodeId, int maxLines) {
		this.log = log;
		this.nodeId = nodeId;
		this.maxLines = maxLines;
	}

	public void info(Object message) {
		emit("INFO", message);
	}

	public void warn(Object message) {
		emit("WARN", message);
	}

	public void error(Object message) {
		emit("ERROR", message);
	}

	private void emit(String level, Object message) {
		if (emitted > maxLines) {
			return;
		}
		if (emitted == maxLines) {
			emitted++;
			log.warn("[{}] script log budget of {} lines exhausted; further lines dropped", nodeId, maxLines);
			return;
		}
		emitted++;
		String line = String.valueOf(message);
		captured.add(level + " " + line);
		switch (level) {
			case "ERROR" -> log.error("[{}] {}", nodeId, line);
			case "WARN" -> log.warn("[{}] {}", nodeId, line);
			default -> log.info("[{}] {}", nodeId, line);
		}
	}

	/** The lines this script emitted, for assertions in tests. */
	public List<String> captured() {
		return captured;
	}
}
