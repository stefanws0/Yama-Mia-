package com.yamareviewer.domain.ids;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * All game IDs by role. A role with no IDs is "uncaptured" and every section that needs it is hidden.
 */
public final class IdRegistry
{
	private final Map<Role, Set<Integer>> ids;
	private final Map<Role, Set<String>> texts;

	public IdRegistry(Map<Role, Set<Integer>> ids, Map<Role, Set<String>> texts)
	{
		this.ids = copy(ids);
		this.texts = copy(texts);
	}

	public Set<Integer> ids(Role role)
	{
		return ids.getOrDefault(role, Set.of());
	}

	public Set<String> texts(Role role)
	{
		return texts.getOrDefault(role, Set.of());
	}

	public boolean is(Role role, int id)
	{
		return ids(role).contains(id);
	}

	/** Overhead roles match exactly; message roles match by prefix after colour tags are removed. */
	public boolean matchesText(Role role, String text)
	{
		if (text == null)
		{
			return false;
		}
		if (role.kind() == RoleKind.MESSAGE)
		{
			String plain = text.replaceAll("<[^>]*>", "");
			return texts(role).stream().anyMatch(plain::startsWith);
		}
		return texts(role).contains(text);
	}

	public boolean isCaptured(Role role)
	{
		boolean textRole = role.kind() == RoleKind.OVERHEAD || role.kind() == RoleKind.MESSAGE;
		return textRole ? !texts(role).isEmpty() : !ids(role).isEmpty();
	}

	/** The first role (in declaration order) of the given kind that contains the id. */
	public Optional<Role> roleOf(RoleKind kind, int id)
	{
		for (Map.Entry<Role, Set<Integer>> entry : ids.entrySet())
		{
			if (entry.getKey().kind() == kind && entry.getValue().contains(id))
			{
				return Optional.of(entry.getKey());
			}
		}
		return Optional.empty();
	}

	/** Changes whenever any mapping changes; stored in every raw log header. */
	public String fingerprint()
	{
		StringBuilder canonical = new StringBuilder();
		for (Role role : Role.values())
		{
			canonical.append(role.name()).append('=');
			ids(role).stream().sorted().forEach(id -> canonical.append(id).append(','));
			texts(role).stream().sorted().forEach(text -> canonical.append(text).append(','));
			canonical.append(';');
		}
		return Integer.toHexString(canonical.toString().hashCode());
	}

	private static <T> Map<Role, Set<T>> copy(Map<Role, Set<T>> source)
	{
		Map<Role, Set<T>> copy = new EnumMap<>(Role.class);
		source.forEach((role, values) -> copy.put(role, Set.copyOf(values)));
		return Collections.unmodifiableMap(copy);
	}
}
