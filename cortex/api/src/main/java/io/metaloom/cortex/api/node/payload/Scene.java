package io.metaloom.cortex.api.node.payload;

/**
 * A single scene segment within a video.
 *
 * @param startFrame  the first frame of the scene (0-based)
 * @param endFrame    the last frame of the scene (inclusive)
 * @param startTimeMs the start time in milliseconds
 * @param endTimeMs   the end time in milliseconds
 */
public record Scene(int startFrame, int endFrame, long startTimeMs, long endTimeMs) {
}
