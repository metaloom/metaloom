import React from "react";
import { FormControl, MenuItem, Select, SelectChangeEvent, ToggleButton, Tooltip } from "@mui/material";
import { ArrowDownwardOutlined, ArrowUpwardOutlined } from "@mui/icons-material";
import { useTranslation } from "react-i18next";
import { tokens } from "../theme";
import type { ListSortDirection, ListSortKey } from "../api/paging";

/**
 * The sort and filter controls shared by the listing views.
 *
 * Both are **server-side**. That is the whole point of the component: a listing route serves 25
 * rows by default and the views page through it, so a comparator applied to the rows already in
 * memory would sort page one against itself and leave page two to arrive in a different order
 * entirely. Sorting has to be a query parameter or it is a lie, and the same holds for a filter
 * that claims to narrow a collection.
 *
 * A consequence worth knowing at the call site: changing either control invalidates the cursor, so
 * the list reloads from the first page. `usePagedList` does that automatically as long as the
 * loader callback lists the sort and filter state in its dependencies.
 */

export interface SortState {
  sort: ListSortKey;
  dir: ListSortDirection;
}

/** The default for a catalogue view: oldest first, which under UUIDv7 is also insertion order. */
export const DEFAULT_SORT: SortState = { sort: "created", dir: "asc" };

const SORT_KEYS: ListSortKey[] = ["name", "created", "edited"];

interface SortControlProps {
  value: SortState;
  onChange: (next: SortState) => void;
  /** `data-testid` prefix: the select gets `<testId>`, the direction toggle `<testId>-direction`. */
  testId: string;
}

/**
 * Column picker plus a direction toggle.
 *
 * Two controls rather than a single six-entry "Name A–Z / Name Z–A / …" list: the direction is
 * the parameter a user flips repeatedly while the column is the one they set once, and collapsing
 * them doubles the option count for no gain.
 */
export function ListSortControl({ value, onChange, testId }: SortControlProps) {
  const { t } = useTranslation();

  return (
    <>
      <FormControl size="small" sx={{ minWidth: 130 }}>
        <Select
          value={value.sort}
          onChange={(e: SelectChangeEvent) => onChange({ ...value, sort: e.target.value as ListSortKey })}
          displayEmpty
          inputProps={{ "aria-label": t("list.sort.label") }}
          SelectDisplayProps={{ "data-testid": testId } as React.HTMLAttributes<HTMLDivElement>}
          sx={{ fontSize: "0.78rem", bgcolor: tokens.bg.elevated }}
        >
          {SORT_KEYS.map(key => (
            <MenuItem key={key} value={key} data-testid={`${testId}-option-${key}`} sx={{ fontSize: "0.8rem" }}>
              {t(`list.sort.${key}`)}
            </MenuItem>
          ))}
        </Select>
      </FormControl>

      <Tooltip title={value.dir === "asc" ? t("list.sort.ascending") : t("list.sort.descending")}>
        <ToggleButton
          value="direction"
          size="small"
          selected={value.dir === "desc"}
          data-testid={`${testId}-direction`}
          onChange={() => onChange({ ...value, dir: value.dir === "asc" ? "desc" : "asc" })}
          sx={{
            border: `1px solid ${tokens.border.default}`,
            borderRadius: `${tokens.radius.sm} !important`,
            px: 0.75,
          }}
        >
          {value.dir === "asc"
            ? <ArrowUpwardOutlined sx={{ fontSize: 16 }} />
            : <ArrowDownwardOutlined sx={{ fontSize: 16 }} />}
        </ToggleButton>
      </Tooltip>
    </>
  );
}

/**
 * What a row exposes to a local sort. Any field may be absent — a row missing the active key keeps
 * its position relative to the rows that have one rather than being dropped or bunched at the top.
 */
export interface SortableRow {
  name?: string | null;
  created?: string | number | null;
  edited?: string | number | null;
}

/**
 * Sort rows in the browser.
 *
 * **Only correct when the whole set is in memory.** For a listing served a page at a time, use the
 * `sort`/`dir` query parameters instead — see the note at the top of this file. This exists for the
 * screens that are not backed by a Loom list route at all: the Cortex worker registry is live
 * in-memory state, and agent memory has its own scoped API. There, everything is loaded, so a
 * comparator is the honest mechanism rather than a shortcut.
 *
 * Names compare with `localeCompare` so that accented and non-ASCII names order the way a reader
 * expects; timestamps compare as ISO strings or epoch numbers, both of which sort lexically.
 */
export function sortLocally<T extends SortableRow>(rows: readonly T[], state: SortState): T[] {
  const pick = (row: T) => (state.sort === "name" ? row.name : state.sort === "edited" ? row.edited : row.created);
  const sorted = [...rows].sort((a, b) => {
    const left = pick(a);
    const right = pick(b);
    // Missing values sort last in both directions — a row with no timestamp is not "the oldest",
    // it is unknown, and flipping the direction should not promote it to the top.
    if (left == null && right == null) return 0;
    if (left == null) return 1;
    if (right == null) return -1;
    if (typeof left === "number" && typeof right === "number") return left - right;
    return String(left).localeCompare(String(right));
  });
  if (state.dir === "desc") {
    // Reverse only the rows that had a value, so the unknowns stay at the bottom.
    const known = sorted.filter(row => pick(row) != null).reverse();
    const unknown = sorted.filter(row => pick(row) == null);
    return [...known, ...unknown];
  }
  return sorted;
}

export interface FilterOption {
  value: string;
  label: string;
}

interface FilterSelectProps {
  value: string;
  onChange: (next: string) => void;
  options: FilterOption[];
  /** Shown as the first entry, and the value that means "do not filter". */
  allLabel: string;
  testId: string;
  minWidth?: number;
}

/**
 * A one-of-many filter, rendered as a select whose first entry clears it.
 *
 * The empty string is the cleared value, matching `filterExpression`, which drops blank terms
 * rather than sending `creator[eq]=`.
 */
export function ListFilterSelect({ value, onChange, options, allLabel, testId, minWidth = 140 }: FilterSelectProps) {
  return (
    <FormControl size="small" sx={{ minWidth }}>
      <Select
        value={value}
        onChange={(e: SelectChangeEvent) => onChange(e.target.value)}
        displayEmpty
        SelectDisplayProps={{ "data-testid": testId } as React.HTMLAttributes<HTMLDivElement>}
        sx={{ fontSize: "0.78rem", bgcolor: tokens.bg.elevated }}
      >
        <MenuItem value="" data-testid={`${testId}-option-all`} sx={{ fontSize: "0.8rem" }}>{allLabel}</MenuItem>
        {options.map(option => (
          <MenuItem
            key={option.value}
            value={option.value}
            data-testid={`${testId}-option-${option.value}`}
            sx={{ fontSize: "0.8rem" }}
          >
            {option.label}
          </MenuItem>
        ))}
      </Select>
    </FormControl>
  );
}
