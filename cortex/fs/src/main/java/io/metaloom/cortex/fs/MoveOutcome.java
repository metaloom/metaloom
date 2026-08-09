package io.metaloom.cortex.fs;

import java.nio.file.Path;

/**
 * What a {@link LocalMover} actually did.
 *
 * @param state
 *            how it finished
 * @param target
 *            where the bytes ended up, or null when nothing moved
 * @param bytes
 *            the size of the file that was relocated, for the log line
 * @param crossDevice
 *            whether the source and destination were on different filesystems
 * @param sourceRemoved
 *            whether the original file is gone. False for a copy that was told to keep the source, and for every failure
 * @param reason
 *            a human-readable explanation for anything other than {@link State#MOVED}
 */
public record MoveOutcome(State state, Path target, long bytes, boolean crossDevice, boolean sourceRemoved, String reason) {

	public enum State {
		/** The bytes are at the destination and the source is gone. */
		MOVED,
		/** The bytes are at the destination and the source was deliberately kept. */
		COPIED,
		/** Nothing was done, and that was the correct outcome. */
		SKIPPED
	}

	public boolean isSkipped() {
		return state == State.SKIPPED;
	}

	public static MoveOutcome skipped(String reason) {
		return new MoveOutcome(State.SKIPPED, null, 0L, false, false, reason);
	}
}
