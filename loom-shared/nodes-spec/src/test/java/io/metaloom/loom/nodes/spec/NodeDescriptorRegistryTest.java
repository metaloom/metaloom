package io.metaloom.loom.nodes.spec;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NodeDescriptorRegistryTest {

	private NodeDescriptorRegistry registry;

	@BeforeEach
	void setUp() {
		registry = new NodeDescriptorRegistry();
	}

	@Test
	void testRegisterAndGet() {
		NodeDescriptor desc = new NodeDescriptor()
			.setKind("test-node")
			.setName("Test Node")
			.setCategory(NodeCategory.ANALYSIS);

		registry.register(desc);

		assertEquals(1, registry.size());
		assertTrue(registry.contains("test-node"));
		assertSame(desc, registry.get("test-node"));
	}

	@Test
	void testGetReturnsNullForUnknown() {
		assertNull(registry.get("nonexistent"));
	}

	@Test
	void testContainsReturnsFalseForUnknown() {
		assertFalse(registry.contains("nonexistent"));
	}

	@Test
	void testRegisterReplacesExisting() {
		NodeDescriptor first = new NodeDescriptor()
			.setKind("dup")
			.setName("First");
		NodeDescriptor second = new NodeDescriptor()
			.setKind("dup")
			.setName("Second");

		registry.register(first);
		registry.register(second);

		assertEquals(1, registry.size());
		assertEquals("Second", registry.get("dup").getName());
	}

	@Test
	void testGetAllReturnsUnmodifiableCollection() {
		registry.register(new NodeDescriptor().setKind("a").setName("A"));
		registry.register(new NodeDescriptor().setKind("b").setName("B"));

		var all = registry.getAll();
		assertEquals(2, all.size());
		assertThrows(UnsupportedOperationException.class, () -> all.add(new NodeDescriptor()));
	}

	@Test
	void testRegisterNullThrows() {
		assertThrows(NullPointerException.class, () -> registry.register(null));
	}

	@Test
	void testRegisterNullKindThrows() {
		assertThrows(NullPointerException.class,
			() -> registry.register(new NodeDescriptor().setName("No Kind")));
	}

	@Test
	void testEmptyRegistrySize() {
		assertEquals(0, registry.size());
		assertTrue(registry.getAll().isEmpty());
	}
}
