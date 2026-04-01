package io.metaloom.loom.api.options;

import java.io.File;

/**
 * Container that holds the information where the loaded config was located from.
 */
public record LoomOptionsLookup(File baseConfigFolder, LoomOptions options) {

}
