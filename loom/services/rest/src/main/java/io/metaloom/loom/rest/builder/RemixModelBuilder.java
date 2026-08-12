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
	 * {@code memberCount} is a primitive, so what this actually sends is <b>zero</b> — and a remix
	 * card reading "0 assets" over three thumbnails is worse than no count at all. The list route
	 * therefore uses {@link #toRemixList(Page, java.util.function.ToLongFunction)} and pays for the
	 * counts; this overload is for the callers that genuinely have none to give.
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

	/**
	 * A page of remixes, each with its member count.
	 *
	 * <p>
	 * One count query per row. That is the cost of the band on the asset browser saying what it is
	 * counting: a page holds at most a few dozen remixes, the count is an indexed lookup on the join
	 * table, and the alternative — which is what this route did — is every card claiming zero members
	 * whatever it holds.
	 * </p>
	 */
	default RemixListResponse toRemixList(Page<Remix> page, java.util.function.ToLongFunction<java.util.UUID> memberCount) {
		return setPage(new RemixListResponse(), page, remix -> toResponse(remix, memberCount.applyAsLong(remix.getUuid())));
	}

	default RemixMemberListResponse toRemixMemberList(Page<RemixMember> page) {
		return setPage(new RemixMemberListResponse(), page, this::toResponse);
	}

}
