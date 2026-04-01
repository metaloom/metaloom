package io.metaloom.loom.doc;

import io.metaloom.loom.doc.impl.LoomConfigGenerator;
import io.metaloom.loom.doc.impl.OpenAPIGenerator;
import io.metaloom.loom.doc.impl.RESTModelGenerator;

public class ExampleGenerator {

	public static void main(String[] args) throws Exception {
		new LoomConfigGenerator().generate();
		new OpenAPIGenerator().generate();
		new RESTModelGenerator().generate();
	}
}
