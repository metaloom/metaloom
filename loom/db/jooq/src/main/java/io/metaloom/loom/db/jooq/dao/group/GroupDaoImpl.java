package io.metaloom.loom.db.jooq.dao.group;

import static io.metaloom.loom.db.jooq.tables.JooqGroup.GROUP;
import static io.metaloom.loom.db.jooq.tables.JooqRole.ROLE;
import static io.metaloom.loom.db.jooq.tables.JooqRoleGroup.ROLE_GROUP;
import static io.metaloom.loom.db.jooq.tables.JooqUserGroup.USER_GROUP;

import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.DSLContext;
import org.jooq.Table;
import org.jooq.TableRecord;

import io.metaloom.loom.db.jooq.AbstractJooqDao;
import io.metaloom.loom.db.jooq.dao.role.RoleImpl;
import io.metaloom.loom.db.jooq.tables.JooqGroup;
import io.metaloom.loom.db.model.group.Group;
import io.metaloom.loom.db.model.group.GroupDao;
import io.metaloom.loom.db.model.role.Role;
import io.metaloom.loom.db.model.user.User;

@Singleton
public class GroupDaoImpl extends AbstractJooqDao<Group> implements GroupDao {

	@Inject
	public GroupDaoImpl(DSLContext context) {
		super(context);
	}

	@Override
	public String getTypeName() {
		return "Groups";
	}

	@Override
	protected Class<? extends Group> getPojoClass() {
		return GroupImpl.class;
	}

	@Override
	protected Table<? extends TableRecord<?>> getTable() {
		return JooqGroup.GROUP;
	}

	@Override
	public Group loadByName(String name) {
		return ctx().selectFrom(GROUP)
			.where(GROUP.NAME.equal(name))
			.fetchOneInto(Group.class);
		// .and(JooqGroup.GROUP.DELETED.eq(false)
	}

	// @Override
	// public Completable addUserToGroup(Group group, LoomUser user) {
	// Completable result = Completable.create(sub -> {
	// try {
	// Objects.requireNonNull(group);
	// Objects.requireNonNull(user);
	// UserGroup ug = new UserGroup();
	// ug.setUserUuid(user.getUuid());
	// ug.setGroupUuid(group.getUuid());
	// userGroupDao.insert(ug);
	// sub.onComplete();
	// } catch (Throwable t) {
	// sub.onError(t);
	// }
	// });
	// return result.subscribeOn(scheduler);
	// }

	// @Override
	// public Completable deleteGroup(UUID uuid) {
	// Objects.requireNonNull(uuid, "Group uuid must not be null");
	// return dao.deleteById(uuid).ignoreElement();
	// }
	//
	// @Override
	// public Single<LoomGroup> createGroup(String name) {
	// LoomGroup group = new LoomGroup();
	// group.setName(name);
	// return insertReturningPrimary(group).map(pk -> new LoomGroup2Impl(group.setUuid(pk)));
	// }
	//
	// @Override
	// public Single<? extends LoomGroup> updateGroup(LoomGroup group) {
	// Objects.requireNonNull(group, "Group must not be null");
	// return update(unwrap(group));
	// }
	//
	// @Override
	// public Completable addUserToGroup(LoomGroup group, LoomUser user) {
	// UserGroup userGroup = new UserGroup();
	// userGroup.setGroupUuid(group.getUuid());
	// userGroup.setUserUuid(user.getUuid());
	// return userGroupDao.insert(userGroup).ignoreElement();
	// }
	//
	// @Override
	// public Completable removeUserFromGroup(LoomGroup group, LoomUser user) {
	// UserGroupRecord record = new UserGroupRecord(user.getUuid(), group.getUuid());
	// return userGroupDao.deleteById(record).ignoreElement();
	// }

	@Override
	public void removeRoleFromGroup(Group group, Role role) {
		// ctx().deleteFrom(ROLE_GROUP)
		// .where(ROLE_GROUP.GROUP_UUID.eq(group.getUuid())
		// .and(ROLE_GROUP.ROLE_UUID.eq(role.getUuid())))
		// .execute();

		deleteCrossTableEntry(ROLE_GROUP.GROUP_UUID, group.getUuid(), ROLE_GROUP.ROLE_UUID, role.getUuid());
	}

	@Override
	public void addRoleToGroup(Group group, Role role) {
		ctx().insertInto(ROLE_GROUP,
			ROLE_GROUP.ROLE_UUID, ROLE_GROUP.GROUP_UUID)
			.values(role.getUuid(), group.getUuid())
			.onConflict(ROLE_GROUP.ROLE_UUID, ROLE_GROUP.GROUP_UUID)
			.doNothing()
			.execute();
	}

	@Override
	public List<Role> loadRoles(Group group) {
		return ctx().select(GROUP)
			.from(ROLE)
			.join(ROLE_GROUP)
			.on(ROLE_GROUP.GROUP_UUID.eq(group.getUuid()))
			.where(GROUP.UUID.eq(group.getUuid()))
			.fetchInto(RoleImpl.class);
	}

	@Override
	public Group createGroup(UUID userUuid, String name) {
		Group group = new GroupImpl();
		group.setName(name);
		setCreatorEditor(group, userUuid);
		return group;
	}

	@Override
	public void addUserToGroup(Group group, User user) {
		ctx().insertInto(USER_GROUP,
			USER_GROUP.USER_UUID, USER_GROUP.GROUP_UUID)
			.values(user.getUuid(), group.getUuid())
			.onConflict(USER_GROUP.USER_UUID, USER_GROUP.GROUP_UUID)
			.doNothing()
			.execute();
	}

	@Override
	public void removeUserFromGroup(Group group, User user) {
		ctx().deleteFrom(USER_GROUP)
			.where(USER_GROUP.GROUP_UUID.eq(group.getUuid())
				.and(USER_GROUP.USER_UUID.eq(user.getUuid())))
			.execute();
	}

	@Override
	public User loadUsers(Group group) {
		return null;
	}

	@Override
	public void testMultiOp() {
	}

	// @Override
	// public Observable<LoomUser> loadUsers(LoomGroup group) {
	// Single<List<User>> result = queryExecutor.findMany(dslContext -> dslContext.select()
	// .from(USER_GROUP
	// .join(USER)
	// .on(USER.UUID.eq(USER_GROUP.USER_UUID))
	// .where(USER_GROUP.GROUP_UUID.eq(group.getUuid())).asTable(USER))
	// .coerce(USER));
	//
	// return result.flatMapObservable(list -> {
	// return Observable.fromIterable(list);
	// }).map(jooq -> {
	// return JooqWrapperHelper.wrap(jooq, JooqUserImpl.class);
	// });
	// }
	//
	// @Override
	// public Completable testMultiOp() {
	// Observable<User> txOperation = userDao.queryExecutor().beginTransaction()
	// .flatMapObservable(tx -> {
	// Single<List<User>> users1 = tx.findMany(ctx -> {
	// ResultQuery<UserRecord> userRecords = ctx.select().from(USER).coerce(USER);
	// return userRecords;
	// });
	//
	// User userPojo = new User();
	// userPojo.setUsername("ABCD");
	// // Single<User> createdUser1 = tx.executeAny(ctx -> {
	// // return ctx
	// // .insertInto(USER)
	// // .set(ctx.newRecord(USER, userPojo))
	// // .returning(USER.getPrimaryKey().getFieldsArray());
	// // }).map(rows -> rows.iterator().next())
	// // .map(io.vertx.reactivex.sqlclient.Row::getDelegate)
	// // .map(keyConverter()::apply).map(pk -> userPojo.setUuid(pk));
	//
	// User userPojo2 = new User();
	// userPojo2.setUsername("ABCD2");
	//
	// Single<User> createdUser1 = GroupOps.createUserOp(tx, userPojo, keyConverter());
	//
	// // Single<User> createdUser1 = tx.insertReturning(ctx -> {
	// // return ctx
	// // .insertInto(USER)
	// // .set(ctx.newRecord(USER, userPojo))
	// // .returning(USER.getPrimaryKey().getFieldsArray());
	// // }, keyConverter()).map(pk -> userPojo.setUuid(pk));
	//
	// Single<User> createdUser2 = GroupOps.insertUser(tx, userPojo2, keyConverter());
	//
	// Single<List<User>> s = Single.zip(users1, createdUser1, createdUser2, (u1, c1, c2) -> {
	// System.out.println("Adding users");
	// u1.add(c1);
	// u1.add(c2);
	// return u1;
	// });
	//
	// Observable<User> obs = s.flatMapObservable(list -> {
	// System.out.println("Convert to list");
	// return Observable.fromIterable(list);
	// });
	//
	// return tx.commit().andThen(obs);
	// });
	//
	// // .blockingForEach(user -> {
	// // System.out.println(user.getUsername());
	// // });
	// return txOperation.ignoreElements();
	// }
	//
	// @Override
	// public Observable<? extends LoomGroup> findAll() {
	// Observable<? extends Group> result = Single.just(groupDao.findAll()).flatMapObservable(Observable::fromIterable).map(LoomGroupImpl::new);
	// return result.observeOn(scheduler);
	// }
}
