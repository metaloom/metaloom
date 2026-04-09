/**
 * Typed payload interfaces for Cortex pipeline node inputs and outputs.
 *
 * <p>All payload types implement the {@link io.metaloom.cortex.api.node.payload.Payload}
 * marker interface. To create a custom payload type, simply define an interface or class
 * that extends {@code Payload}.
 *
 * <h2>Built-in payload types</h2>
 * <ul>
 *   <li>{@link io.metaloom.cortex.api.node.payload.AssetPayload} — media asset reference</li>
 *   <li>{@link io.metaloom.cortex.api.node.payload.DetectionPayload} — bounding-box detections (face, object)</li>
 *   <li>{@link io.metaloom.cortex.api.node.payload.TextPayload} — text (transcripts, LLM output, OCR)</li>
 *   <li>{@link io.metaloom.cortex.api.node.payload.TagsPayload} — categorical tags</li>
 *   <li>{@link io.metaloom.cortex.api.node.payload.JsonPayload} — arbitrary JSON</li>
 *   <li>{@link io.metaloom.cortex.api.node.payload.AudioPayload} — extracted audio stream</li>
 *   <li>{@link io.metaloom.cortex.api.node.payload.ImagePayload} — image data (thumbnails, frames)</li>
 *   <li>{@link io.metaloom.cortex.api.node.payload.ScenesPayload} — scene segmentation</li>
 *   <li>{@link io.metaloom.cortex.api.node.payload.HashPayload} — cryptographic hash</li>
 *   <li>{@link io.metaloom.cortex.api.node.payload.EmbeddingPayload} — vector embedding / fingerprint</li>
 *   <li>{@link io.metaloom.cortex.api.node.payload.QualityPayload} — quality score</li>
 *   <li>{@link io.metaloom.cortex.api.node.payload.ConsistencyPayload} — completeness / consistency check</li>
 * </ul>
 */
package io.metaloom.cortex.api.node.payload;
