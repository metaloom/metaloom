package io.metaloom.loom.nodes.spec;

import static io.metaloom.loom.nodes.spec.ContentTypeLattice.family;
import static io.metaloom.loom.nodes.spec.ContentTypeLattice.isAssignable;
import static io.metaloom.loom.nodes.spec.ContentTypeLattice.isProvisional;
import static io.metaloom.loom.nodes.spec.ContentTypeLattice.isWildcard;
import static io.metaloom.loom.nodes.spec.ContentTypeLattice.wildcardOf;
import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.ARTIFACT_IMAGE;
import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.DETECTION_ANY;
import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.DETECTION_FACE;
import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.DETECTION_OBJECT;
import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.HASH_ANY;
import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.HASH_MD5;
import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.HASH_SHA256;
import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.MEDIA_ANY;
import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.MEDIA_IMAGE;
import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.MEDIA_VIDEO;
import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.SCALAR_STRING;
import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.TEXT_ANY;
import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.TEXT_PLAIN;
import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.TEXT_TRANSCRIPT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins every arm of {@link ContentTypeLattice}.
 *
 * <p>
 * The lattice is the one rule that decides whether two nodes may be connected - it is consulted by
 * save-time validation, by the graph parser, and by the runtime boundary check, and it is mirrored
 * in five lines of TypeScript in the editor. A silent change to any arm here would either let an
 * invalid graph be saved or make a legitimate one unconnectable, so each arm gets its own case.
 * </p>
 */
public class ContentTypeLatticeTest {

	@Test
	void testExactMatchIsAssignable() {
		assertTrue(isAssignable(DETECTION_FACE, DETECTION_FACE));
		assertTrue(isAssignable(MEDIA_ANY, MEDIA_ANY), "a wildcard satisfies itself");
		assertTrue(isAssignable(HASH_MD5, HASH_MD5));
	}

	/**
	 * Second arm: a consumer that declares the family wildcard accepts any subtype of it. This is
	 * what lets {@code scene-layout} take faces or objects, and {@code hash-dedup} take any digest.
	 */
	@Test
	void testConsumerWildcardAcceptsSubtypes() {
		assertTrue(isAssignable(DETECTION_FACE, DETECTION_ANY));
		assertTrue(isAssignable(DETECTION_OBJECT, DETECTION_ANY));
		assertTrue(isAssignable(HASH_MD5, HASH_ANY));
		assertTrue(isAssignable(TEXT_TRANSCRIPT, TEXT_ANY));
		assertTrue(isAssignable(MEDIA_VIDEO, MEDIA_ANY));

		assertFalse(isProvisional(DETECTION_FACE, DETECTION_ANY),
			"a concrete producer into a wildcard consumer is decided now, not at runtime");
	}

	/**
	 * Third arm: a source emits {@code media/*} because the concrete mime is unknown when the graph
	 * is drawn, so wildcard-into-subtype is <em>provisionally</em> valid and the real verdict is
	 * reached at the runtime boundary with the file in hand.
	 */
	@Test
	void testProducerWildcardIsProvisionallyAssignable() {
		assertTrue(isAssignable(MEDIA_ANY, MEDIA_IMAGE));
		assertTrue(isAssignable(MEDIA_ANY, MEDIA_VIDEO));
		assertTrue(isProvisional(MEDIA_ANY, MEDIA_IMAGE),
			"filesystem-source -> ocr can only be decided once the file is opened");

		assertTrue(isAssignable(TEXT_ANY, TEXT_PLAIN));
		assertTrue(isProvisional(TEXT_ANY, TEXT_PLAIN));
	}

	/**
	 * Cross-family inheritance was dropped deliberately: {@code data/hash} used to extend
	 * {@code data/string}, so a digest satisfied any generic string consumer. It no longer does.
	 */
	@Test
	void testAssignabilityNeverCrossesFamilies() {
		assertFalse(isAssignable(HASH_MD5, SCALAR_STRING), "a hash is not a generic string any more");
		assertFalse(isAssignable(TEXT_PLAIN, MEDIA_ANY), "text is not media, not even into the wildcard");
		assertFalse(isAssignable(MEDIA_IMAGE, ARTIFACT_IMAGE), "a media item is not a produced file");
		assertFalse(isAssignable(ARTIFACT_IMAGE, MEDIA_IMAGE));
		assertFalse(isAssignable(DETECTION_FACE, SCALAR_STRING));
	}

	/**
	 * Two concrete subtypes of the same family are still unrelated - only the wildcard bridges them.
	 */
	@Test
	void testSiblingSubtypesAreNotAssignable() {
		assertFalse(isAssignable(DETECTION_FACE, DETECTION_OBJECT));
		assertFalse(isAssignable(HASH_MD5, HASH_SHA256));
		assertFalse(isAssignable(MEDIA_IMAGE, MEDIA_VIDEO));
		assertFalse(isAssignable(TEXT_TRANSCRIPT, TEXT_PLAIN));
	}

	@Test
	void testNullsAreNeverAssignable() {
		assertFalse(isAssignable(null, MEDIA_ANY));
		assertFalse(isAssignable(MEDIA_ANY, null));
		assertFalse(isAssignable(null, null));
		assertFalse(isProvisional(null, MEDIA_IMAGE));
	}

	@Test
	void testMalformedIdsAreNeverAssignable() {
		assertFalse(isAssignable("media", MEDIA_ANY), "an id without a slash has no family");
		assertFalse(isAssignable(MEDIA_ANY, "media"));
		assertFalse(isAssignable("", MEDIA_ANY));
	}

	@Test
	void testFamily() {
		assertEquals("detection", family(DETECTION_FACE));
		assertEquals("media", family(MEDIA_ANY));
		assertEquals("struct", family(ContentTypeRegistry.STRUCT_SCENE_LAYOUT), "a hyphenated subtype still has a family");
		assertNull(family(null));
		assertNull(family("nofamily"), "no slash means no family");
	}

	@Test
	void testWildcardOf() {
		assertEquals(DETECTION_ANY, wildcardOf(DETECTION_FACE));
		assertEquals(MEDIA_ANY, wildcardOf(MEDIA_IMAGE));
		assertEquals(MEDIA_ANY, wildcardOf(MEDIA_ANY), "the wildcard of a wildcard is itself");
		assertNull(wildcardOf(null));
		assertNull(wildcardOf("nofamily"));
	}

	@Test
	void testIsWildcard() {
		assertTrue(isWildcard(MEDIA_ANY));
		assertTrue(isWildcard(HASH_ANY));
		assertFalse(isWildcard(MEDIA_IMAGE));
		assertFalse(isWildcard(null));
	}

	/**
	 * The wildcard of every registered type must itself be a registered type, otherwise a consumer
	 * could not declare "any of this family" and the second arm would be unreachable for it.
	 */
	@Test
	void testEveryFamilyHasARegisteredWildcard() {
		for (ContentType type : ContentTypeRegistry.all()) {
			String wildcard = wildcardOf(type.getId());
			assertTrue(ContentTypeRegistry.isKnown(wildcard),
				"content type " + type.getId() + " has family wildcard " + wildcard + " which is not registered");
			assertTrue(ContentTypeRegistry.FAMILIES.contains(type.getFamily()),
				"content type " + type.getId() + " is in unlisted family " + type.getFamily());
		}
	}
}
