package io.metaloom.loom.rest.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.rest.json.LoomJson;
import io.metaloom.loom.rest.model.example.Example;

/**
 * Every documentation example renders the same JSON every time it is built.
 *
 * <p>
 * The examples end up in the committed {@code loom/doc/src/main/generated/openapi.{json,yaml}} and their website copies. A single
 * {@code UUID.randomUUID()} or {@code Instant.now()} in an example turned every regeneration into a diff of every affected example - 66 lines per
 * run from the pipeline examples alone - which buries the real API changes in noise. Use the fixed values in {@code ExampleValues} or a named
 * constant instead.
 * </p>
 */
public class ModelExamplesDeterminismTest {

	@Test
	public void testEveryExampleIsReproducible() throws Exception {
		List<Method> methods = Arrays.stream(ModelExamples.class.getMethods())
			.filter(m -> m.getParameterCount() == 0 && Example.class.isAssignableFrom(m.getReturnType()) && !Modifier.isStatic(m.getModifiers()))
			.sorted(Comparator.comparing(Method::getName))
			.toList();
		assertTrue(methods.size() > 100, "the example catalogue should have been found, got " + methods.size());

		List<String> first = render(new ModelExamples(), methods);
		// Long enough that a clock-derived value cannot render identically twice.
		Thread.sleep(5);
		List<String> second = render(new ModelExamples(), methods);

		List<String> unstable = new ArrayList<>();
		for (int i = 0; i < methods.size(); i++) {
			if (!first.get(i).equals(second.get(i))) {
				unstable.add(methods.get(i).getName());
			}
		}
		assertEquals(List.of(), unstable, "these examples render differently on every build - replace random or time-derived values with constants");
	}

	private static List<String> render(ModelExamples examples, List<Method> methods) throws Exception {
		List<String> out = new ArrayList<>();
		for (Method method : methods) {
			Example example = (Example) method.invoke(examples);
			out.add(example == null || example.body() == null ? "" : LoomJson.encode(example.body()));
		}
		return out;
	}
}
