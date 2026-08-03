package io.metaloom.loom.nodes.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Ordering of announced contract versions.
 *
 * <p>
 * The active contract for a node offered by several workers is the <em>lowest</em> announced version,
 * so this ordering decides what an author is allowed to wire across a whole fleet. The two cases that
 * matter most are the ones a naive {@code String.compareTo} gets wrong: {@code 1.0.0-SNAPSHOT} must
 * precede {@code 1.0.0}, and {@code 1.10.0} must follow {@code 1.9.0}.
 * </p>
 */
public class NodeVersionsTest {

	@Test
	public void shouldOrderSnapshotBelowItsRelease() {
		assertTrue(NodeVersions.compare("1.0.0-SNAPSHOT", "1.0.0") < 0);
		assertTrue(NodeVersions.compare("1.0.0", "1.0.0-SNAPSHOT") > 0);
	}

	@Test
	public void shouldOrderNumericallyNotLexicographically() {
		assertTrue(NodeVersions.compare("1.9.0", "1.10.0") < 0, "1.10.0 is newer than 1.9.0");
		assertTrue(NodeVersions.compare("2.0.0", "10.0.0") < 0);
	}

	@Test
	public void shouldTreatMissingSegmentsAsZero() {
		assertEquals(0, NodeVersions.compare("1.0", "1.0.0"));
		assertEquals(0, NodeVersions.compare("1", "1.0.0"));
	}

	@Test
	public void shouldOrderQualifiersAmongThemselves() {
		assertTrue(NodeVersions.compare("1.0.0-alpha", "1.0.0-beta") < 0);
		assertEquals(0, NodeVersions.compare("1.0.0-RC1", "1.0.0-rc1"), "qualifier comparison is case-insensitive");
	}

	@Test
	public void shouldRecogniseSnapshots() {
		assertTrue(NodeVersions.isSnapshot("1.0.0-SNAPSHOT"));
		assertTrue(NodeVersions.isSnapshot("1.0.0-snapshot"));
		assertFalse(NodeVersions.isSnapshot("1.0.0"));
		assertFalse(NodeVersions.isSnapshot(null));
	}

	@Test
	public void shouldRefuseToOrderUnparseableVersions() {
		assertFalse(NodeVersions.isParseable(null));
		assertFalse(NodeVersions.isParseable(""));
		assertFalse(NodeVersions.isParseable("  "));
		assertFalse(NodeVersions.isParseable("v1.0.0"), "a leading 'v' is not a number");
		assertFalse(NodeVersions.isParseable("latest"));
		assertFalse(NodeVersions.isParseable("1..0"));

		// Never guess an order. Guessing here silently picks a contract for the whole fleet.
		assertThrows(IllegalArgumentException.class, () -> NodeVersions.compare("latest", "1.0.0"));
	}

	@Test
	public void shouldAcceptTheShapesMavenActuallyProduces() {
		assertTrue(NodeVersions.isParseable("1.0.0"));
		assertTrue(NodeVersions.isParseable("1.0.0-SNAPSHOT"));
		assertTrue(NodeVersions.isParseable("0.1"));
		assertTrue(NodeVersions.isParseable("2024.11.3-rc2"));
	}

	@Test
	public void shouldPickTheLowestAsTheActiveContract() {
		assertEquals("1.0.0", NodeVersions.lowest(List.of("1.1.0", "1.0.0", "2.0.0")));
		assertEquals("1.0.0-SNAPSHOT", NodeVersions.lowest(List.of("1.0.0", "1.0.0-SNAPSHOT")));
	}

	@Test
	public void shouldReportUnorderableSetsAsNull() {
		// A null return means "these cannot be ordered", which the caller must treat as skew.
		assertNull(NodeVersions.lowest(List.of("1.0.0", "latest")));
		assertNull(NodeVersions.lowest(List.of()));
		assertNull(NodeVersions.lowest(null));
	}

	@Test
	public void shouldDetectAgreement() {
		assertTrue(NodeVersions.allEqual(List.of("1.0.0", "1.0.0", "1.0")));
		assertFalse(NodeVersions.allEqual(List.of("1.0.0", "1.1.0")));
		assertFalse(NodeVersions.allEqual(List.of("1.0.0", "latest")));
		assertTrue(NodeVersions.allEqual(List.of()));
	}
}
