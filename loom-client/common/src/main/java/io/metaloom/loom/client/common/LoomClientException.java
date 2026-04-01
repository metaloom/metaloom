package io.metaloom.loom.client.common;

public class LoomClientException extends Exception {

	private static final long serialVersionUID = 6434236577973357902L;
	private int code;

	public LoomClientException(int code, String msg) {
		super(msg);
		this.code = code;
	}

	public LoomClientException(int code, String msg, Exception e) {
		super(msg, e);
		this.code = code;
	}

	public int getStatusCode() {
		return code;
	}

	public String getStatusMsg() {
		return getMessage();
	}

}
