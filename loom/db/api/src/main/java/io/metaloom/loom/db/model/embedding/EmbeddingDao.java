package io.metaloom.loom.db.model.embedding;

import java.util.UUID;

import io.metaloom.loom.api.embedding.EmbeddingType;
import io.metaloom.loom.db.CRUDDao;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.user.User;

public interface EmbeddingDao extends CRUDDao<Embedding> {

	default Embedding createEmbedding(User user, Asset asset, Float[] data, EmbeddingType type) {
		return createEmbedding(user.getUuid(), asset.getUuid(), data, type);
	}

	Embedding createEmbedding(UUID userUuid, UUID assetUuid, Float[] data, EmbeddingType type);

}
