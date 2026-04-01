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
		metainfo.setTotalCount(page.size());
		metainfo.setLastUuid(lastUuid);
		response.setMetainfo(metainfo);
		return response;
	}
}
