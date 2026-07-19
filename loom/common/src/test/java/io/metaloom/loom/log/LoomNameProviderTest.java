package io.metaloom.loom.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The instance name used in log output.
 *
 * <p>What is worth testing is not that a name is produced but that it cannot misbehave:
 * a converter runs on every logging call, so a name that varied between lines, or a
 * lookup that threw, would be worse than having no name at all.</p>
 */
public class LoomNameProviderTest {

	@Test
	void testTheNameIsStableForTheLifeOfTheProcess() {
		LoomNameProvider provider = LoomNameProvider.getInstance();

		// The load-bearing property. A name that changed between lines would make the
		// output actively misleading - it would look like two processes.
		String first = provider.getName();
		for (int i = 0; i < 100; i++) {
			assertEquals(first, provider.getName());
		}
	}

	@Test
	void testTheProviderIsASingleton() {
		assertSame(LoomNameProvider.getInstance(), LoomNameProvider.getInstance());
	}

	@Test
	void testGeneratedNamesLookLikeAdjectiveNoun() {
		String name = LoomNameProvider.getInstance().getRandomName();

		assertNotNull(name);
		assertFalse(name.isBlank());
		// Two words: the word lists loaded. Had either failed to load, this would fall
		// back to the single-word FALLBACK_NAME and the split would be 1.
		assertEquals(2, name.split(" ").length, "Expected 'adjective Noun' but got: " + name);
	}

	@Test
	void testTheGeneratorDoesNotReturnOneName() {
		LoomNameProvider provider = LoomNameProvider.getInstance();

		Set<String> seen = new HashSet<>();
		for (int i = 0; i < 200; i++) {
			seen.add(provider.getRandomName());
		}

		// Not an assertion about randomness quality - only that the lists are actually
		// being indexed. A constant here would mean the name distinguishes nothing.
		assertTrue(seen.size() > 1, "Expected varied names, always got: " + seen);
	}

	@Test
	void testAConfiguredNameWins() throws Exception {
		String previous = System.getProperty(LoomNameProvider.NAME_PROPERTY);
		try {
			System.setProperty(LoomNameProvider.NAME_PROPERTY, "ingest-3");

			// A fresh provider - the singleton may already have resolved a name, and the
			// point of caching is that it does not change afterwards.
			assertEquals("ingest-3", freshProvider().getName());
		} finally {
			if (previous == null) {
				System.clearProperty(LoomNameProvider.NAME_PROPERTY);
			} else {
				System.setProperty(LoomNameProvider.NAME_PROPERTY, previous);
			}
		}
	}

	@Test
	void testABlankConfiguredNameFallsBackToAGeneratedOne() throws Exception {
		String previous = System.getProperty(LoomNameProvider.NAME_PROPERTY);
		try {
			// An unset environment variable often arrives as empty rather than absent.
			// Rendering an empty [] in every log line would be a silent regression.
			System.setProperty(LoomNameProvider.NAME_PROPERTY, "   ");

			String name = freshProvider().getName();
			assertFalse(name.isBlank());
			assertEquals(2, name.split(" ").length);
		} finally {
			if (previous == null) {
				System.clearProperty(LoomNameProvider.NAME_PROPERTY);
			} else {
				System.setProperty(LoomNameProvider.NAME_PROPERTY, previous);
			}
		}
	}

	@Test
	void testTheConverterRendersTheName() {
		LoomLogNameConverter converter = new LoomLogNameConverter();

		// Called with a null event: the converter must not touch it, because it is
		// invoked for every line and has no business inspecting one.
		String rendered = converter.convert(null);

		assertEquals(LoomNameProvider.getInstance().getName(), rendered);
	}

	/** A provider that has not yet resolved its name, so configuration can be observed. */
	private static LoomNameProvider freshProvider() throws Exception {
		java.lang.reflect.Constructor<LoomNameProvider> constructor = LoomNameProvider.class.getDeclaredConstructor();
		constructor.setAccessible(true);
		return constructor.newInstance();
	}
}
