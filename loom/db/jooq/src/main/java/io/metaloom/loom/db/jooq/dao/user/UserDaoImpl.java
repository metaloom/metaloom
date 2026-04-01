package io.metaloom.loom.db.jooq.dao.user;

import static io.metaloom.loom.db.jooq.tables.JooqUser.USER;

import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.DSLContext;
import org.jooq.SelectConditionStep;
import org.jooq.Table;
import org.jooq.TableRecord;

import io.metaloom.filter.Filter;
import io.metaloom.filter.FilterKey;
import io.metaloom.loom.api.filter.LoomFilterKey;
import io.metaloom.loom.api.sort.SortDirection;
import io.metaloom.loom.api.sort.SortKey;
import io.metaloom.loom.db.jooq.AbstractJooqDao;
import io.metaloom.loom.db.jooq.JooqDao;
import io.metaloom.loom.db.jooq.tables.JooqUser;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.db.model.user.UserDao;
import io.metaloom.loom.db.page.Page;

@Singleton
public class UserDaoImpl extends AbstractJooqDao<User> implements UserDao, JooqDao {

	@Inject
	public UserDaoImpl(DSLContext ctx) {
		super(ctx);
	}

	@Override
	public String getTypeName() {
		return "Users";
	}

	@Override
	protected Table<? extends TableRecord<?>> getTable() {
		return JooqUser.USER;
	}

	@Override
	protected Class<? extends User> getPojoClass() {
		return UserImpl.class;
	}

	@Override
	public User createUser(UUID creatorUuid, String username) {
		User user = new UserImpl();
		user.setUsername(username);
		if (creatorUuid != null) {
			setCreatorEditor(user, creatorUuid);
		}
		return user;
	}

	@Override
	public User load(UUID uuid) {
		return ctx()
			.select(getTable())
			.from(getTable())
			// Exclude deleted users
			.where(getIdField().eq(uuid).and(JooqUser.USER.DELETED.eq(false)))
			.fetchOneInto(getPojoClass());
	}

	@Override
	public User loadByUsername(String username) {
		return ctx().selectFrom(USER)
			.where(USER.USERNAME.equal(username).and(JooqUser.USER.DELETED.eq(false)))
			.fetchOneInto(User.class);
	}

	@Override
	public Page<User> loadPage(UUID fromId, int pageSize, List<Filter> filters, SortKey sortBy, SortDirection sortDirection) {
		SelectConditionStep<?> query = ctx()
			.select(getTable())
			.from(getTable())
			.where(JooqUser.USER.DELETED.eq(false));

		return loadPage(query, fromId, pageSize, filters, sortBy, sortDirection);
	}

	@Override
	protected SelectConditionStep<?> applyFilter(SelectConditionStep<?> query, Filter filter) {
		FilterKey key = filter.filterKey();
		if (key == LoomFilterKey.USERNAME && filter.getOperationKey().equals("eq")) {
			return query.and(USER.USERNAME.eq(filter.value().toString()));
		}
		return super.applyFilter(query, filter);
	}

}
