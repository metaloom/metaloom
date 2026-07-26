package io.metaloom.cli.output;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class TableTest {

	private static final Ansi PLAIN = new Ansi(false);
	private static final Ansi COLOUR = new Ansi(true);

	@Test
	@DisplayName("columns are sized to their widest cell")
	void testColumnWidths() {
		String rendered = new Table("ID", "NAME")
			.row("1", "short")
			.row("1000", "a much longer value")
			.render(PLAIN);

		String[] lines = rendered.strip().split("\\R");
		assertThat(lines).hasSize(3);
		// "1000" is the widest in column one, so every column two entry starts at the same
		// offset - that alignment is the only thing a table is for.
		int nameColumn = lines[0].indexOf("NAME");
		assertThat(lines[1].indexOf("short")).isEqualTo(nameColumn);
		assertThat(lines[2].indexOf("a much longer value")).isEqualTo(nameColumn);
	}

	@Test
	@DisplayName("ANSI escapes do not count towards column width")
	void testAnsiDoesNotBreakAlignment() {
		// The bug this guards against: measuring the escape sequence as visible text pushes
		// every following column out by ~9 characters, and only for coloured rows.
		String rendered = new Table("STATUS", "NAME")
			.row(COLOUR.green("SUCCESS"), "first")
			.row("SUCCESS", "second")
			.render(PLAIN);

		String[] lines = rendered.strip().split("\\R");
		int firstNameOffset = lines[1].indexOf("first");
		int secondNameOffset = lines[2].indexOf("second");
		// The coloured row's visible offset is shifted by the escape length, so compare the
		// visible prefix widths instead.
		assertThat(Table.visibleWidth(lines[1].substring(0, firstNameOffset)))
			.isEqualTo(Table.visibleWidth(lines[2].substring(0, secondNameOffset)));
	}

	@Test
	@DisplayName("visibleWidth ignores escape sequences")
	void testVisibleWidth() {
		assertThat(Table.visibleWidth("plain")).isEqualTo(5);
		assertThat(Table.visibleWidth(COLOUR.red("plain"))).isEqualTo(5);
		assertThat(Table.visibleWidth(COLOUR.bold(COLOUR.green("ab")))).isEqualTo(2);
		assertThat(Table.visibleWidth(null)).isZero();
	}

	@Test
	@DisplayName("a table with no rows still renders its header")
	void testEmptyTable() {
		Table table = new Table("A", "B");

		assertThat(table.isEmpty()).isTrue();
		assertThat(table.render(PLAIN).strip()).isEqualTo("A  B");
	}

	@Test
	@DisplayName("a short row is padded rather than throwing")
	void testRaggedRow() {
		String rendered = new Table("A", "B", "C").row("1").render(PLAIN);

		assertThat(rendered.strip()).startsWith("A");
		assertThat(rendered.split("\\R")).hasSize(2);
	}

	@Test
	@DisplayName("null cells render as empty")
	void testNullCell() {
		String rendered = new Table("A", "B").row("1", null).render(PLAIN);

		assertThat(rendered).contains("1");
	}
}
