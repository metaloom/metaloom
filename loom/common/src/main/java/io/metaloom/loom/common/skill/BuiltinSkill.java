package io.metaloom.loom.common.skill;

/**
 * An instruction package that ships with Loom.
 *
 * <p>
 * The shape mirrors a row of the {@code skill} table — a machine-friendly {@code name}, a one-line {@code description} that goes into the system
 * prompt, and a markdown {@code content} body fetched on demand — because the agent loop treats the two interchangeably. What differs is ownership: a
 * built-in has no uuid, no creator and no version history, so it never appears in the skill CRUD surface and can never be edited or deleted by a user.
 * </p>
 *
 * @param name
 *            stable identifier the model passes to {@code load_skill}
 * @param description
 *            one line, action-complete: it is all the model sees before deciding whether to load the body
 * @param content
 *            the full markdown instructions
 */
public record BuiltinSkill(String name, String description, String content) {
}
