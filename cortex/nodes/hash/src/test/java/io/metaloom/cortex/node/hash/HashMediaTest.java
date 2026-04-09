package io.metaloom.cortex.node.hash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.common.node.media.AbstractMediaTest;
import io.metaloom.utils.hash.MD5;

public class HashMediaTest extends AbstractMediaTest {

	private static final MD5 MD5SUM = MD5.fromString("d41d8cd98f00b204e9800998ecf8427e");
	private static final MD5 MD5SUM_2 = MD5.fromString("0cc175b9c0f1b6a831c399e269772661");

	private HashMetaStorage hashStorage() {
		return new HashMetaStorage(storage());
	}

	@Test
	public void testSHA512() throws IOException {
		LoomMedia media = mediaVideo1();
		HashMetaStorage hs = hashStorage();
		hs.setSHA512(media, SHA512_HASH);
		assertEquals(SHA512_HASH, hs.getSHA512(media));
	}

	@Test
	public void testMD5() throws IOException {
		LoomMedia media = mediaVideo1();
		HashMetaStorage hs = hashStorage();
		assertNull(hs.getMD5(media));
		hs.setMD5(media, MD5SUM);
		assertEquals(MD5SUM, hs.getMD5(media));
		hs.setMD5(media, MD5SUM_2);
		assertEquals(MD5SUM_2, hs.getMD5(media));
	}

}
