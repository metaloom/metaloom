package io.metaloom.loom.nodes.spec;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class ContentTypesTest {

	@Test
	void testAllReturnsNonEmpty() {
		List<ContentType> types = ContentTypes.all();
		assertFalse(types.isEmpty());
	}

	@Test
	void testAllIdsAreUnique() {
		List<ContentType> types = ContentTypes.all();
		Set<String> ids = types.stream().map(ContentType::getId).collect(Collectors.toSet());
		assertEquals(types.size(), ids.size(), "Content type IDs must be unique");
	}

	@Test
	void testEveryTypeHasIdAndLabel() {
		for (ContentType ct : ContentTypes.all()) {
			assertNotNull(ct.getId(), "id must not be null");
			assertNotNull(ct.getLabel(), "label must not be null for " + ct.getId());
		}
	}

	@Test
	void testMediaSubtypesHaveSuperType() {
		List<ContentType> mediaSubtypes = ContentTypes.all().stream()
			.filter(ct -> ct.getId().startsWith("media/") && !ct.getId().equals("media/*"))
			.toList();

		assertFalse(mediaSubtypes.isEmpty());
		for (ContentType ct : mediaSubtypes) {
			assertEquals(ContentTypes.MEDIA_ANY, ct.getSuperType(),
				ct.getId() + " should have media/* as superType");
		}
	}

	@Test
	void testHashHasStringSuperType() {
		ContentType hash = ContentTypes.all().stream()
			.filter(ct -> ct.getId().equals(ContentTypes.DATA_HASH))
			.findFirst()
			.orElseThrow();
		assertEquals(ContentTypes.DATA_STRING, hash.getSuperType());
	}

	@Test
	void testExpectedContentTypesPresent() {
		Set<String> ids = ContentTypes.all().stream()
			.map(ContentType::getId)
			.collect(Collectors.toSet());

		assertTrue(ids.contains(ContentTypes.MEDIA_ANY));
		assertTrue(ids.contains(ContentTypes.DATA_HASH));
		assertTrue(ids.contains(ContentTypes.DATA_FINGERPRINT));
		assertTrue(ids.contains(ContentTypes.DATA_FACEDETECTION));
		assertTrue(ids.contains(ContentTypes.CONTROL_FILTER_RESULT));
		assertTrue(ids.contains(ContentTypes.DATA_THUMBNAIL));
	}
}
