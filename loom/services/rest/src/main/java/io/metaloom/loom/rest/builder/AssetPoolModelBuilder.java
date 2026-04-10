package io.metaloom.loom.rest.builder;

import io.metaloom.loom.db.model.pool.AssetPool;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.pool.AssetPoolListResponse;
import io.metaloom.loom.rest.model.pool.AssetPoolResponse;

public interface AssetPoolModelBuilder extends ModelBuilder, UserModelBuilder {

	default AssetPoolResponse toResponse(AssetPool pool) {
		AssetPoolResponse response = new AssetPoolResponse();
		response.setUuid(pool.getUuid());
		response.setName(pool.getName());
		response.setMeta(pool.getMeta());
		response.setFsPath(pool.getFsPath());
		response.setS3Bucket(pool.getS3Bucket());
		response.setS3Region(pool.getS3Region());
		response.setS3Endpoint(pool.getS3Endpoint());
		setStatus(pool, response);
		return response;
	}

	default AssetPoolListResponse toPoolList(Page<AssetPool> page) {
		return setPage(new AssetPoolListResponse(), page, this::toResponse);
	}

}
