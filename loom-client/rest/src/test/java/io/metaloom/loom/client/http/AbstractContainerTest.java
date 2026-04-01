package io.metaloom.loom.client.http;


import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.loom.test.container.LoomContainer;
import io.metaloom.loom.test.data.TestValues;

import org.testcontainers.junit.jupiter.Testcontainers;


@Testcontainers
public abstract class AbstractContainerTest implements TestValues {

	@RegisterExtension
	public LoomContainer loom = new LoomContainer();

	protected void sleep(int i) {
		try {
			Thread.sleep(i);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

}
