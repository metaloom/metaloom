package io.metaloom.loom.rest.model.user;

import io.metaloom.loom.rest.model.MetaModel;
import io.metaloom.loom.rest.model.RestModel;

public interface UserModel<T extends UserModel<T>> extends MetaModel<T>, RestModel {

	String getUsername();

	T setUsername(String username);

	String getFirstname();

	T setFirstname(String firstname);

	String getLastname();

	T setLastname(String lastname);

	String getEmail();

	T setEmail(String email);

}
