package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.metaloom.loom.api.embedding.EmbeddingType;
import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.embedding.Embedding;
import io.metaloom.loom.db.model.embedding.EmbeddingDao;
import io.metaloom.loom.db.model.user.User;

public class EmbeddingDaoTest extends AbstractJooqTest implements CRUDDaoTestcases<EmbeddingDao, Embedding> {

	@Override
	public EmbeddingDao getDao() {
		return embeddingDao();
	}

	@Override
	public Embedding createElement(User user, int i) {
		Embedding embedding = getDao().createEmbedding(user, asset(), VECTOR_DATA, EmbeddingType.DLIB_FACE_RESNET_v1);
		embedding.setNodeKind("facedetect");
		embedding.setProducerVersion("dlib-v1");
		// The identity is (asset, node kind, type, frame, subject) - vary the subject so a test
		// that needs several embeddings of one asset does not collide on it.
		embedding.setSubjectIndex(i);
		return embedding;
	}

	@Override
	public void assertCreate(Embedding createdElement) {
		assertEquals(asset().getUuid(), createdElement.getAssetUuid());
		assertEquals(EmbeddingType.DLIB_FACE_RESNET_v1, createdElement.getType());
		assertEquals("facedetect", createdElement.getNodeKind());
		assertEquals(VECTOR_DATA.length, createdElement.getDimensions().intValue());
	}

	@Override
	public void updateElement(Embedding element) {
		// Not "manual": the test fixture already owns (asset, manual, DLIB_FACE_RESNET_v1, 0, 0).
		element.setNodeKind("facedescription");
	}

	@Override
	public void assertUpdate(Embedding updatedElement) {
		assertEquals("facedescription", updatedElement.getNodeKind());
	}

}
