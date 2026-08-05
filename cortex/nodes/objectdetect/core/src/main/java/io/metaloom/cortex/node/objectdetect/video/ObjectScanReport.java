package io.metaloom.cortex.node.objectdetect.video;

import java.util.List;

import io.metaloom.cortex.node.objectdetect.DetectedObject;

/**
 * What one pass over a video found.
 *
 * @param detections   the objects kept, in frame order then detector order
 * @param framesScanned how many frames inference actually ran on
 * @param capped       whether the scan stopped early because {@code maxDetections} was reached — the
 *                     difference between "this is everything in the file" and "this is the first N",
 *                     which the node reports on its flag port rather than letting it pass silently
 */
public record ObjectScanReport(List<DetectedObject> detections, int framesScanned, boolean capped) {
}
