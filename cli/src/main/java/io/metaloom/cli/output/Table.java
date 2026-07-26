package io.metaloom.cli.output;

import java.util.ArrayList;
import java.util.List;

/**
 * A small column-aligned table.
 *
 * <p>Hand-rolled rather than reusing {@code picocli.CommandLine.Help.TextTable}, which is
 * built for laying out usage help (fixed column widths, wrapping, indent rules) and fights
 * you when what you want is "print these rows, size the columns to the content".</p>
 *
 * <p>Cells may contain ANSI escapes. Width is measured on the visible text, otherwise a
 * coloured column throws the alignment out by the length of its escape sequences.</p>
 */
public class Table {

	private final List<String> headers = new ArrayList<>();
	private final List<List<String>> rows = new ArrayList<>();

	public Table(String... headers) {
		for (String header : headers) {
			this.headers.add(header);
		}
	}

	public Table row(String... cells) {
		List<String> row = new ArrayList<>();
		for (String cell : cells) {
			row.add(cell == null ? "" : cell);
		}
		rows.add(row);
		return this;
	}

	public boolean isEmpty() {
		return rows.isEmpty();
	}

	public int size() {
		return rows.size();
	}

	/** Strip ANSI escapes so column widths are measured on what the eye sees. */
	static int visibleWidth(String text) {
		if (text == null) {
			return 0;
		}
		int width = 0;
		boolean inEscape = false;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (inEscape) {
				if (c == 'm') {
					inEscape = false;
				}
				continue;
			}
			if (c == '') {
				inEscape = true;
				continue;
			}
			width++;
		}
		return width;
	}

	/**
	 * Render the table.
	 *
	 * @param ansi used for the header styling; may be a disabled instance
	 */
	public String render(Ansi ansi) {
		int columns = headers.size();
		int[] widths = new int[columns];
		for (int i = 0; i < columns; i++) {
			widths[i] = visibleWidth(headers.get(i));
		}
		for (List<String> row : rows) {
			for (int i = 0; i < Math.min(columns, row.size()); i++) {
				widths[i] = Math.max(widths[i], visibleWidth(row.get(i)));
			}
		}

		StringBuilder out = new StringBuilder();
		appendRow(out, headers, widths, ansi, true);
		for (List<String> row : rows) {
			appendRow(out, row, widths, ansi, false);
		}
		return out.toString();
	}

	private void appendRow(StringBuilder out, List<String> cells, int[] widths, Ansi ansi, boolean header) {
		for (int i = 0; i < widths.length; i++) {
			String cell = i < cells.size() ? cells.get(i) : "";
			String text = header ? ansi.bold(cell) : cell;
			out.append(text);
			boolean last = i == widths.length - 1;
			if (!last) {
				int padding = widths[i] - visibleWidth(cell) + 2;
				out.append(" ".repeat(Math.max(1, padding)));
			}
		}
		out.append(System.lineSeparator());
	}
}
