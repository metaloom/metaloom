package io.metaloom.loom.api.options;

public class ValueEntry {

	public ValueEntry(String stringValue, Object actualValue) {
		this.stringValue = stringValue;
		this.expectedValue = actualValue;
	}

	String stringValue;
	Object expectedValue;
}
