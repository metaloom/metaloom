package io.metaloom.cortex.node.objectdetect;

import io.metaloom.cortex.api.node.payload.BoundingBox;

/**
 * One detected object, in the coordinate space of the frame it was found in.
 *
 * <p>
 * Deliberately not {@code io.metaloom.yolo4j.Detection}: that record resolves its label through a
 * static call into the native library, so passing it around would make every consumer — including
 * the tests — depend on an initialized {@code YoloLib}. The label is resolved once, at the engine
 * boundary, and travels as data from there on.
 * </p>
 *
 * @param box        where in the frame, absolute pixels
 * @param confidence the detector's score, 0.0 – 1.0
 * @param classId    the model's class index
 * @param label      the class name, or null when the id is outside the loaded label set
 */
public record ObjectDetection(BoundingBox box, float confidence, int classId, String label) {
}
