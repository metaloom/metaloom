package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.library.Library;
import io.metaloom.loom.db.model.library.LibraryDao;
import io.metaloom.loom.db.model.user.User;

public class LibraryDaoTest extends AbstractJooqTest implements CRUDDaoTestcases<LibraryDao, Library> {

	@Override
	public Library createElement(User user, int i) {
		return libraryDao().createLibrary(user, "library_" + i);
	}

	@Override
	public void assertCreate(Library createdElement) {
		assertEquals("library_0", createdElement.getName());
	}

	@Override
	public LibraryDao getDao() {
		return libraryDao();
	}

	@Override
	public void updateElement(Library element) {
		element.setName("Dummy2");
	}

	@Override
	public void assertUpdate(Library updatedLibrary) {
		assertEquals("Dummy2", updatedLibrary.getName());
	}

}
