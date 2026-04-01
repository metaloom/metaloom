package io.metaloom.loom.utils;

import java.nio.file.NoSuchFileException;

import io.vertx.core.file.FileSystemException;

public final class ExceptionUtils {

	private ExceptionUtils() {

	}

	public static boolean isNotFoundError(Throwable err) {
		return err instanceof FileSystemException && ((FileSystemException) err).getCause() instanceof NoSuchFileException;
	}

}
