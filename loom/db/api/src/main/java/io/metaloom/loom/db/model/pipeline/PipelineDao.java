package io.metaloom.loom.db.model.pipeline;

import java.util.UUID;

import io.metaloom.loom.db.CRUDDao;
import io.metaloom.loom.db.model.user.User;

public interface PipelineDao extends CRUDDao<Pipeline> {

	default Pipeline createPipeline(User user, String name) {
		return createPipeline(user.getUuid(), name);
	}

	Pipeline createPipeline(UUID userUuid, String name);

}
