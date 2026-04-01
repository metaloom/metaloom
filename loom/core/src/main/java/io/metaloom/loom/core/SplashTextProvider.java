package io.metaloom.loom.core;

import io.metaloom.loom.api.LoomVersion;

public class SplashTextProvider {

	public static String getSplashText() {
		return """
			 _
			| |
			| |      ___    ___   _ __ ___
			| |     / _ \\  / _ \\ | '_ ` _ \\
			| |____| (_) || (_) || | | | | |
			|______|\\___/  \\___/ |_| |_| |_|
			
			Version v""" + LoomVersion.getBuildInfo() + "\n\n" +
			"Made in Vienna by MetaLoom";

	}

}
