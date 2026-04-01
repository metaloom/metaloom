package io.metaloom.loom.mcp.dagger;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;

import javax.inject.Qualifier;

/**
 * Dagger qualifier for the set of MCP tools injected into the tool registry.
 */
@Qualifier
@Documented
@Retention(RUNTIME)
public @interface MCPTools {

}
