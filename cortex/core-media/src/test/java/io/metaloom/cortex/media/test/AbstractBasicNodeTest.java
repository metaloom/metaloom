package io.metaloom.cortex.media.test;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;

import java.io.IOException;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.FilesystemNode;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.media.type.handler.impl.AvroLoomMetaTypeHandlerImpl;
import io.metaloom.cortex.api.media.type.handler.impl.HeapLoomMetaTypeHandlerImpl;
import io.metaloom.cortex.api.media.type.handler.impl.XAttrLoomMetaTypeHandlerImpl;
import io.metaloom.cortex.api.meta.MetaStorage;
import io.metaloom.cortex.common.meta.MetaStorageImpl;
import io.metaloom.loom.test.data.TestMedia;

public abstract class AbstractBasicNodeTest<T extends FilesystemNode<?, ?>> extends AbstractNodeTest<T> implements NodeTestcases {

	@Test
	@Override
	public void testProcessing() throws IOException {
		TestMedia video1 = video1();
		LoomMedia media = media(video1);
		T nodeMock = node();
		assertProcessed(nodeMock, media, video1);
	}

	protected abstract void assertProcessed(TestMedia testMedia, LoomMedia media, NodeResult result, T nodeMock);

	@Test
	@Override
	public void testProcessVideo() throws IOException {
		TestMedia video1 = video1();
		LoomMedia media = media(video1);
		T nodeMock = node();
		assertProcessedVideo(nodeMock, media, video1);
	}

	protected void assertProcessedVideo(T nodeMock, LoomMedia media, TestMedia video) throws IOException {
		assertProcessed(nodeMock, media, video);
	}

	@Test
	@Override
	public void testProcessDoc() throws IOException {
		TestMedia testMedia = docDOCX();
		LoomMedia media = media(testMedia);
		T nodeMock = node();
		assertProcessedDoc(nodeMock, media, testMedia);
	}

	protected void assertProcessedDoc(T nodeMock, LoomMedia media, TestMedia docMedia) throws IOException {
		assertProcessed(nodeMock, media, docMedia);
	}

	@Test
	@Override
	public void testProcessImage() throws IOException {
		TestMedia image1 = image1();
		LoomMedia media = media(image1);
		T nodeMock = node();
		assertProcessedImage(nodeMock, media, image1);
	}

	protected void assertProcessedImage(T nodeMock, LoomMedia media, TestMedia image) throws IOException {
		assertProcessed(nodeMock, media, image);
	}

	@Test
	@Override
	public void testProcessAudio() throws IOException {
		TestMedia audio1 = audio1();
		LoomMedia media = media(audio1);
		T nodeMock = node();
		assertProcessedAudio(nodeMock, media, audio1);
	}

	protected void assertProcessedAudio(T nodeMock, LoomMedia media, TestMedia audio) throws IOException {
		assertProcessed(nodeMock, media, audio);
	}

	@Test
	@Override
	public void testProcessMissing() throws IOException {
		LoomMedia media = mediaMissingMP4();
		T nodeMock = node();
		NodeResult result = nodeMock.process(ctx(media));
		assertThat(result).isFailed();
	}

	@Test
	@Override
	public void testProcessOther() throws IOException {
		LoomMedia media = mediaBogusBin();
		T nodeMock = node();
		NodeResult result = nodeMock.process(ctx(media));
	}

	@Test
	@Override
	public void testFailure() throws IOException {
		LoomMedia media = mediaVideo1();
		T nodeMock = node();
		NodeResult result = nodeMock.process(ctx(media));
	}

	@Test
	@Override
	public void testDisabled() throws IOException {
		LoomMedia media = mediaVideo1();
		T nodeMock = node();
		disableNode(nodeMock);

		NodeResult result = nodeMock.process(ctx(media));
		assertThat(result).isSkipped();
		assertDisabled(media, result);
	}

	protected void assertDisabled(LoomMedia media, NodeResult result) {
		assertThat(media).hasXAttr(0);
	}

	protected void assertSkipped(T nodeMock, LoomMedia media) throws IOException {
		assertThat(media).hasXAttr(0);
	}

	private void assertProcessed(T nodeMock, LoomMedia media, TestMedia testMedia) throws IOException {
		NodeResult result = nodeMock.process(ctx(media));
		assertThat(result).isSuccess();
		assertThat(media).hasSHA512();
		assertProcessed(testMedia, media, result, nodeMock);

		// Run the process again on the media to ensure that it will be skipped
		NodeResult result2 = nodeMock.process(ctx(media));
		assertThat(result2).isSkipped();
	}

	protected abstract void disableNode(T nodeMock);

	@Override
	public MetaStorage storage() {
		return new MetaStorageImpl(
			Set.of(new HeapLoomMetaTypeHandlerImpl(), new AvroLoomMetaTypeHandlerImpl(options()), new XAttrLoomMetaTypeHandlerImpl()));
	}
}
