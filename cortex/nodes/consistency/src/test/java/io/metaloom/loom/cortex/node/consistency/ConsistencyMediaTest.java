package io.metaloom.loom.cortex.node.consistency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.common.node.media.AbstractMediaTest;
import io.metaloom.cortex.node.consistency.ConsistencyMetaStorage;

public class ConsistencyMediaTest extends AbstractMediaTest {

	@Test
	public void testGetSetUpdateLong() throws IOException {
		LoomMedia media = createEmptyLoomMedia();
		ConsistencyMetaStorage consistencyStorage = new ConsistencyMetaStorage(storage());
		assertNull(consistencyStorage.getZeroChunkCount(media));
		consistencyStorage.setZeroChunkCount(media, 42L);
		Long value1 = consistencyStorage.getZeroChunkCount(media);
		assertEquals(42L, value1.longValue());
		consistencyStorage.setZeroChunkCount(media, 43L);
		Long value2 = consistencyStorage.getZeroChunkCount(media);
		assertEquals(43L, value2.longValue());
	}
}
