package io.metaloom.loom.rest.builder;

import io.metaloom.loom.db.model.tag.AssetTag;
import io.metaloom.loom.db.model.tag.Tag;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.annotation.AreaInfo;
import io.metaloom.loom.rest.model.tag.TagListResponse;
import io.metaloom.loom.rest.model.tag.TagReference;
import io.metaloom.loom.rest.model.tag.TagResponse;

public interface TagModelBuilder extends ModelBuilder, UserModelBuilder {

	default TagResponse toResponse(Tag tag) {
		TagResponse response = new TagResponse();
		response.setUuid(tag.getUuid());
		response.setName(tag.getName());
		response.setColor(tag.getColor());
		if (tag instanceof AssetTag at) {
			response.setArea(tagArea(at));
		}
		response.setCollection(tag.getCollection());
		response.setMeta(tag.getMeta());
		setStatus(tag, response);
		return response;
	}

	/**
	 * A tag as it appears on an asset.
	 *
	 * <p>
	 * Since V2.71 the reference carries the <em>placement</em>: its own uuid, and who attached it. Both are what a client needs in order to act on one
	 * occurrence of a tag rather than on all of them, and to tell a machine tag from one a person typed.
	 * </p>
	 */
	default TagReference toReference(Tag tag) {
		TagReference reference = new TagReference();
		reference.setName(tag.getName()).setUuid(tag.getUuid());
		if (tag instanceof AssetTag at) {
			if (hasRegion(at)) {
				reference.setArea(tagArea(at));
			}
			reference.setPlacementUuid(at.getPlacementUuid());
			reference.setNodeKind(at.getNodeKind());
			reference.setNodeId(at.getNodeId());
			reference.setConfidence(at.getConfidence());
			reference.setAttached(at.getAttached());
			reference.setAttachedBy(at.getAttachedBy());
		}
		return reference;
	}

	default boolean hasRegion(AssetTag tag) {
		return tag.hasRegion();
	}

	default TagListResponse toTagList(Page<Tag> page) {
		return setPage(new TagListResponse(), page, this::toResponse);
	}

	default AreaInfo tagArea(AssetTag tag) {
		AreaInfo area = new AreaInfo();
		area.setFrom(tag.getTimeFrom());
		area.setTo(tag.getTimeTo());
		area.setHeight(tag.getAreaHeight());
		area.setWidth(tag.getAreaWidth());
		area.setStartX(tag.getAreaStartX());
		area.setStartY(tag.getAreaStartY());
		return area;
	}

}
