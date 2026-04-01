package io.metaloom.loom.core;

import org.junit.jupiter.api.Test;

public class SplashTextProviderTest {

	@Test
	public void testSplashText() {
		String text = SplashTextProvider.getSplashText();
		System.out.println(text);

	}
}
