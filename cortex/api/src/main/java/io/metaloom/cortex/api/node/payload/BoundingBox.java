package io.metaloom.cortex.api.node.payload;

/**
 * A bounding box within a frame, used by {@link DetectionPayload} to locate
 * detected objects or faces.
 *
 * @param x      the x-coordinate of the top-left corner
 * @param y      the y-coordinate of the top-left corner
 * @param width  the width of the bounding box
 * @param height the height of the bounding box
 */
public record BoundingBox(int x, int y, int width, int height) {
}
