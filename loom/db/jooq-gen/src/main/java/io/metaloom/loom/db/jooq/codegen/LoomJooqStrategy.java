package io.metaloom.loom.db.jooq.codegen;

import org.jooq.codegen.DefaultGeneratorStrategy;
import org.jooq.meta.Definition;

public class LoomJooqStrategy extends DefaultGeneratorStrategy {

	@Override
	public String getJavaClassName(Definition definition, Mode mode) {
		String defaultName = super.getJavaClassName(definition, mode);
		return "Jooq" + defaultName;
	}

}
