package io.metaloom.loom.nodes.spec;

/**
 * Thrown when a value cannot be coerced to the type its port declares.
 *
 * <p>
 * This is a <strong>task failure</strong>, deliberately: a node that emits the wrong type for a declared port has a bug, and failing the one task
 * naming the port, the expected type and what actually arrived is far more useful than the previous behaviour — an unchecked cast that blew up
 * somewhere downstream, or a non-encodable value that silently cleared a whole persist batch.
 * </p>
 */
public class ValueCoercionException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final String portId;
	private final String contentType;

	public ValueCoercionException(String portId, String contentType, String message) {
		super("Port '" + portId + "' (" + contentType + "): " + message);
		this.portId = portId;
		this.contentType = contentType;
	}

	public String getPortId() {
		return portId;
	}

	public String getContentType() {
		return contentType;
	}
}
