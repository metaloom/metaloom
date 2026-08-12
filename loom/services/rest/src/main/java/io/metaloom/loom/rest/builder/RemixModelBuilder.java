package io.metaloom.loom.rest.builder;

import io.metaloom.loom.db.model.remix.Remix;
import io.metaloom.loom.db.model.remix.RemixMember;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.remix.RemixListResponse;
import io.metaloom.loom.rest.model.remix.RemixMemberListResponse;
import io.metaloom.loom.rest.model.remix.RemixMemberResponse;
import io.metaloom.loom.rest.model.remix.RemixResponse;

public interface RemixModelBuilder extends ModelBuilder, UserModelBuilder {

	/**
	 * Build a remix response without its member count.
	 *
	 * <p>
	 * The list route uses this one: counting the members of every remix on a page would be one query
	 * per row. A caller that needs the count for a single remix uses the overload below.
	 * </p>
	 */
	default RemixResponse toResponse(Remix remix) {
		RemixResponse response = new RemixResponse();
		response.setUuid(remix.getUuid());
		response.setName(remix.getName());
		response.setDescription(remix.getDescription());
		response.setSourceAssetUuid(remix.getSourceAssetUuid());
		response.setMeta(remix.getMeta());
		setStatus(remix, response);
		return response;
	}

	default RemixResponse toResponse(Remix remix, long memberCount) {
		return toResponse(remix).setMemberCount(memberCount);
	}

	default RemixMemberResponse toResponse(RemixMember member) {
		RemixMemberResponse response = new RemixMemberResponse();
		response.setUuid(member.getUuid());
		response.setAssetUuid(member.getAssetUuid());
		response.setRole(member.getRole() == null ? null : member.getRole().name());
		response.setOrdinal(member.getOrdinal());
		response.setFilename(member.getFilename());
		response.setMimeType(member.getMimeType());
		response.setSha512sum(member.getSha512sum());
		response.setSize(member.getSize());
		response.setAdded(member.getCreated());
		response.setAddedBy(member.getCreatorUuid());
		return response;
	}

	default RemixListResponse toRemixList(Page<Remix> page) {
		return setPage(new RemixListResponse(), page, this::toResponse);
	}

	default RemixMemberListResponse toRemixMemberList(Page<RemixMember> page) {
		return setPage(new RemixMemberListResponse(), page, this::toResponse);
	}

}
