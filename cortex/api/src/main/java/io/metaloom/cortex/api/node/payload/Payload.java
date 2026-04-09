package io.metaloom.cortex.api.node.payload;

/**
 * Marker interface for all typed payloads that flow between Cortex pipeline nodes.
 *
 * <p>Each concrete payload type represents a specific kind of data produced by one node
 * and consumed by another — enabling type-safe interconnection of nodes in the processing graph.
 *
 * <p>To define a custom payload type, simply create an interface or class that extends {@code Payload}.
 */
public interface Payload {
}
