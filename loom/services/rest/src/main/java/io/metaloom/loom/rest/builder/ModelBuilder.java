package io.metaloom.loom.rest.builder;

import java.util.UUID;
import java.util.function.Function;

import io.metaloom.loom.db.Element;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.RestResponseModel;
import io.metaloom.loom.rest.model.common.AbstractListResponse;
import io.metaloom.loom.rest.model.common.PagingInfo;
import io.metaloom.loom.rest.validation.LoomModelValidator;

public interface ModelBuilder {

	DaoCollection daos();

	LoomModelValidator validator();

	default <T extends Element<T>, TR extends RestResponseModel<TR>, LR extends AbstractListResponse<LR, TR>> LR setPage(LR response, Page<T> page,
		Function<T, TR> elementConverter) {
		UUID lastUuid = null;
		for (T element : page) {
			response.add(elementConverter.apply(element));
			lastUuid = element.getUuid();
		}
		PagingInfo metainfo = new PagingInfo();
		metainfo.setPerPage(page.perPage());
		// totalCount is the number of matches across all pages, not the size of this page. DAOs which
		// cannot compute it report TOTAL_COUNT_UNKNOWN; fall back to the page size there so the field
		// never goes negative on the wire.
		long totalCount = page.totalCount();
		metainfo.setTotalCount(totalCount == Page.TOTAL_COUNT_UNKNOWN ? page.size() : totalCount);
		metainfo.setLastUuid(lastUuid);
		response.setMetainfo(metainfo);
		return response;
	}
}
