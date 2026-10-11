package io.metaloom.loom.db.model.person;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import io.metaloom.loom.db.CRUDDao;
import io.metaloom.loom.db.model.user.User;

public interface PersonDao extends CRUDDao<Person> {

	default Person createPerson(User user, String alias) {
		return createPerson(user.getUuid(), alias);
	}

	Person createPerson(UUID userUuid, String alias);

	/**
	 * The name each person is shown by, for many persons in one query.
	 *
	 * <p>
	 * The alias when there is one, otherwise first and last name joined. Persons with neither, and uuids that name nobody, are absent from the
	 * result. Exists so a page of clusters can carry the names of the people on it without a query per card.
	 * </p>
	 */
	Map<UUID, String> loadDisplayNames(Collection<UUID> personUuids);

	/** The name a person is shown by: the alias, else first and last name, else null. */
	static String displayName(String alias, String firstname, String lastname) {
		if (alias != null && !alias.isBlank()) {
			return alias.trim();
		}
		String joined = ((firstname == null ? "" : firstname.trim()) + " " + (lastname == null ? "" : lastname.trim())).trim();
		return joined.isEmpty() ? null : joined;
	}

}
