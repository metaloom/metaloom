package io.metaloom.loom.core.boot;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.options.LoomOptions;

/**
 * A server does not seed demo content unless somebody asked it to.
 *
 * <p>
 * The seed used to run on every boot, gated only by "is the asset table empty". That gate is real but it is not a decision: a freshly installed
 * production server <em>is</em> empty, so it invented a demo space, three libraries, two collections, a cast of people and a wall of assets, and
 * the operator's first real library then sat beside them with nothing to tell the two apart. {@code LOOM_DEMO_ENABLED} is the decision, and it is
 * off.
 * </p>
 *
 * <p>
 * <b>Why every collaborator is null.</b> That is the assertion. The initializer takes thirty-eight DAOs, and if the gate lets anything through it
 * reaches one of them and this test throws. A test that passed mocks in could only check that no <em>write</em> happened, which is a weaker claim
 * and the one a future refactor would quietly break. The second case is what keeps the first honest: with the switch on, the same call must fail,
 * or the null-argument construction would be proving nothing at all.
 * </p>
 */
public class DemoSeedGateTest {

	private DemoDatabaseInitializer initializer(boolean enabled) {
		LoomOptions options = new LoomOptions();
		options.getDemo().setEnabled(enabled);
		return new DemoDatabaseInitializer(
			null, null, null, null, null, null, null, null,
			null, null, null, null, null,
			null, null, null,
			null, null, null, null, null,
			null, null,
			null, null, null,
			null, null, null, null,
			null, null, null, null,
			null, null, null, null, options);
	}

	@Test
	@DisplayName("With demo seeding off, init() touches nothing at all")
	public void shouldNotTouchTheDatabaseWhenDisabled() {
		assertFalse(new LoomOptions().getDemo().isEnabled(), "demo seeding must be off unless it is asked for");
		initializer(false).init();
	}

	@Test
	@DisplayName("With demo seeding on, init() does reach the database")
	public void shouldReachTheDatabaseWhenEnabled() {
		DemoDatabaseInitializer initializer = initializer(true);
		// Not an assertion about NPE as such: it is how this test proves the case above is the gate
		// working rather than the initializer having become a no-op.
		assertThrows(NullPointerException.class, initializer::init);
	}

	@Test
	@DisplayName("The demo content directory is still independent of the switch")
	public void shouldKeepContentDirectorySeparate() {
		LoomOptions options = new LoomOptions();
		options.getDemo().setContentDirectory("/demo-content");
		// Pointing at media is not consent to seed it. The demo image sets both, deliberately.
		assertFalse(options.getDemo().isEnabled());
		options.getDemo().setEnabled(true);
		assertTrue(options.getDemo().isEnabled());
	}
}
