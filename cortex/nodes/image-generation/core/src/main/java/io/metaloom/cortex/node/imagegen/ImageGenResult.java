package io.metaloom.cortex.node.imagegen;

/**
 * One sidecar response: the PNG bytes, plus the id of the model that drew them when the
 * sidecar volunteered it.
 *
 * <p>
 * The model id exists so the {@code asset_node_result} row can carry a
 * {@code producerVersion} and a ledger row can therefore say <em>which</em> model
 * produced the image. That matters more than it used to: the backend is selected by a
 * port number alone, three backends now answer the same contract, and they do not share
 * a weight licence.
 * </p>
 *
 * <p>
 * It travels in the response rather than being remembered on the client because
 * {@link ImageGenClient} is a Dagger-provided singleton shared by every {@code imagegen}
 * instance on the worker. A {@code lastModelId()} accessor would be read by one node
 * after another node's request had already overwritten it.
 * </p>
 *
 * @param png raw PNG bytes, never null
 * @param modelId the {@code X-Model-Id} response header, or null - only
 *            {@code qwen-image-sidecar} sets it; ideogram and mage-flow do not, and a
 *            null here simply means the ledger row keeps the {@code producer_version} it
 *            has always had
 */
public record ImageGenResult(byte[] png, String modelId) {
}
