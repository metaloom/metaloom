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

	default TagReference toReference(Tag tag) {
		TagReference reference = new TagReference();
		return reference.setName(tag.getName()).setUuid(tag.getUuid());
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
