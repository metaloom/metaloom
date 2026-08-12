package io.metaloom.loom.db.jooq.dao.remix;

import java.util.UUID;

import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.remix.Remix;

public class RemixImpl extends AbstractEditableElement<Remix> implements Remix {

	private String name;

	private String description;

	private UUID sourceAssetUuid;

	@Override
	public String getName() {
		return name;
	}

	@Override
	public Remix setName(String name) {
		this.name = name;
		return this;
	}

	@Override
	public String getDescription() {
		return description;
	}

	@Override
	public Remix setDescription(String description) {
		this.description = description;
		return this;
	}

	@Override
	public UUID getSourceAssetUuid() {
		return sourceAssetUuid;
	}

	@Override
	public Remix setSourceAssetUuid(UUID sourceAssetUuid) {
		this.sourceAssetUuid = sourceAssetUuid;
		return this;
	}

}
