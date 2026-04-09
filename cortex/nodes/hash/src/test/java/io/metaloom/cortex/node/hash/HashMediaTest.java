package io.metaloom.cortex.node.hash;

import static io.metaloom.cortex.media.hash.HashMedia.HASH;
import static io.metaloom.cortex.media.hash.HashMedia.MD5_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.common.node.media.AbstractMediaTest;
import io.metaloom.cortex.media.hash.HashMedia;
import io.metaloom.utils.hash.SHA512;

public class HashMediaTest extends AbstractMediaTest {

	@Test
	public void testSHA512() throws IOException {
		SHA512 hash = storage().get(mediaVideo1(), SHA512Node.SHA_512_KEY);
		HashMedia media = HASH.wrap(mediaVideo1(), storage());
		assertNotNull(media.getSHA512(), "The sha512sum should always be computed");
		media.setSHA512(SHA512_HASH);
		assertEquals(SHA512_HASH, media.getSHA512());
		assertEquals(1, media.listXAttr().size());
		LoomMedia media2 = mediaVideo1();
		assertEquals(SHA512_HASH, media2.getSHA512());
	}

	@Test
	public void testMD5() throws IOException {
		HashMedia media = HASH.wrap(mediaVideo1(), storage());
		assertNull(media.getMD5());
		media.put(MD5_KEY, MD5SUM);
		assertEquals(MD5SUM, media.get(MD5_KEY));
		media.put(MD5_KEY, MD5SUM_2);
		assertEquals(MD5SUM_2, media.get(MD5_KEY));
		assertEquals(1, media.listXAttr().size());
		assertEquals(MD5SUM_2, media.getMD5());

		HashMedia media2 = HASH.wrap(mediaVideo1(), storage());
		assertEquals(MD5SUM_2, media2.getMD5(), "We reload the media. The attribute should be read from xattr");
	}

}
