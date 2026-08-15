import { useEffect, useState } from "react";
import { listUsers } from "../api/users";
import type { FilterOption } from "../components/ListControls";

/**
 * The user list, shaped for a "created by" filter.
 *
 * Filtering is by uuid rather than by username — usernames are mutable, and a filter that a
 * bookmarked URL or a saved view carries has to survive a rename. The label is the display name,
 * which is what the picker shows.
 *
 * Fails quietly to an empty list: a user without `READ_USER` still gets the rest of the view, and
 * a creator filter they cannot populate is better hidden than shown broken. Callers should render
 * the control only when there is something in it.
 */
export function useCreatorOptions(token: string | null): FilterOption[] {
  const [options, setOptions] = useState<FilterOption[]>([]);

  useEffect(() => {
    if (!token) {
      setOptions([]);
      return;
    }
    let cancelled = false;
    // Well above the server's default of 25: a picker that silently lists the first page of users
    // would be a filter that cannot select most of them.
    listUsers(token, { limit: 200 })
      .then(response => {
        if (cancelled) return;
        setOptions((response.data ?? []).map(user => ({
          value: user.uuid,
          label: displayName(user.username, user.firstname, user.lastname),
        })));
      })
      .catch(() => {
        if (!cancelled) setOptions([]);
      });
    return () => { cancelled = true; };
  }, [token]);

  return options;
}

/** "Firstname Lastname" when there is one, otherwise the username. */
function displayName(username: string, firstname?: string, lastname?: string): string {
  const full = [firstname, lastname].filter(Boolean).join(" ").trim();
  return full || username;
}
