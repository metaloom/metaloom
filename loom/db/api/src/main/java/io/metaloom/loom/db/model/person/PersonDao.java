package io.metaloom.loom.db.model.person;

import java.util.UUID;

import io.metaloom.loom.db.CRUDDao;
import io.metaloom.loom.db.model.user.User;

public interface PersonDao extends CRUDDao<Person> {

	default Person createPerson(User user, String alias) {
		return createPerson(user.getUuid(), alias);
	}

	Person createPerson(UUID userUuid, String alias);

}
