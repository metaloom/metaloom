package io.metaloom.loom.rest.model.person;

import io.metaloom.loom.rest.model.MetaModel;
import io.metaloom.loom.rest.model.RestModel;

public interface PersonModel<T extends PersonModel<T>> extends MetaModel<T>, RestModel {

	String getAlias();

	T setAlias(String alias);

	String getFirstname();

	T setFirstname(String firstname);

	String getLastname();

	T setLastname(String lastname);

	String getPrimaryImageUuid();

	T setPrimaryImageUuid(String primaryImageUuid);

}
