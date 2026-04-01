package io.metaloom.loom.rest.service;

import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.db.CRUDDao;
import io.metaloom.loom.db.Element;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.RestResponseModel;
import io.metaloom.loom.rest.parameter.FilterParameters;
import io.metaloom.loom.rest.parameter.PagingParameters;
import io.metaloom.loom.rest.parameter.SortParameters;
import io.metaloom.loom.rest.validation.LoomModelValidator;

/**
 * 
 * @param <D>
 *            DAO Type
 * @param <E>
 *            DTO / POJO Type
 */
public abstract class AbstractCRUDEndpointService<D extends CRUDDao<E>, E extends Element<E>> extends AbstractEndpointService {

	private static final Logger log = LoggerFactory.getLogger(AbstractCRUDEndpointService.class);

	private final D crudDao;

	private final DaoCollection daos;

	public AbstractCRUDEndpointService(D crudDao, DaoCollection daos, LoomModelBuilder modelBuilder, LoomModelValidator validator) {
		super(modelBuilder, validator);
		this.crudDao = crudDao;
		this.daos = daos;
	}

	public D dao() {
		return crudDao;
	}

	public DaoCollection daos() {
		return daos;
	}

	public abstract void delete(LoomRoutingContext lrc, UUID uuid);

	protected void delete(LoomRoutingContext lrc, Permission permission, UUID uuid) {
		delete(lrc, permission, () -> {
			return dao().load(uuid);
		});
	}

	protected void delete(LoomRoutingContext lrc, Permission permission, Supplier<E> loader) {
		checkPerm(lrc, permission, () -> {
			E element = loader.get();
			if (element == null) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Element not found.");
			} else {
				dao().delete(element);
				lrc.sendNoContent();
			}
		});
	}

	public abstract void list(LoomRoutingContext lrc);

	protected void list(LoomRoutingContext lrc, Permission permission, Function<Page<E>, RestResponseModel<?>> builder) {
		checkPerm(lrc, permission, () -> {
			PagingParameters pagingParameters = lrc.pagingParams();
			FilterParameters filterParameters = lrc.filterParams();
			SortParameters sortParameters = lrc.sortParams();
			UUID from = pagingParameters.from();
			int limit = pagingParameters.limit();
			if (log.isDebugEnabled()) {
				log.debug("Loading page from {} limit: {}", from, limit);
			}
			Page<E> page = dao().loadPage(from, limit, filterParameters.filters(), sortParameters.sortBy(), sortParameters.sortOrder());
			RestResponseModel<?> response = builder.apply(page);
			lrc.send(response);
		});
	}

	public abstract void load(LoomRoutingContext lrc, UUID uuid);

	protected void load(LoomRoutingContext lrc, Permission permission, Supplier<E> loader, Function<E, RestResponseModel<?>> builder) {
		checkPerm(lrc, permission, () -> {
			E element = loader.get();
			if (element == null) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Element not found for type: " + dao().getTypeName());
			}
			RestResponseModel<?> response = builder.apply(element);
			lrc.send(response);
		});
	}

	public abstract void create(LoomRoutingContext lrc);

	protected void create(LoomRoutingContext lrc, Permission permission, Supplier<E> creator,
		Function<E, RestResponseModel<?>> builder) {
		checkPerm(lrc, permission, () -> {
			E element = creator.get();
			dao().store(element);
			RestResponseModel<?> response = builder.apply(element);
			lrc.send(response, 201);
		});
	}

	public abstract void update(LoomRoutingContext lrc, UUID uuid);

	protected void update(LoomRoutingContext lrc, Permission permission, Supplier<E> updator,
		Function<E, RestResponseModel<?>> builder) {
		checkPerm(lrc, permission, () -> {
			E element = updator.get();
			dao().update(element);
			RestResponseModel<?> response = builder.apply(element);
			lrc.send(response, 200);
		});
	}

}
