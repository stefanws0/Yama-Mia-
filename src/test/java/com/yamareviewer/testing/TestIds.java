package com.yamareviewer.testing;

import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.RoleKind;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Made-up IDs: every non-text role gets 100000 + ordinal * 10; text roles get their real built-in texts. */
public final class TestIds
{
	private static final int BASE = 100_000;
	private static final int STEP = 10;
	private static final Map<Role, Set<String>> TEXTS = Map.of(
		Role.PHASE_TRANSITION_TEXT, Set.of("Begone", "You bore me.", "Enough."),
		Role.PRAYER_DISABLED_MESSAGE, Set.of("You've been injured and can't use protection prayers!"),
		Role.GLYPH_CONJURE_MESSAGE, Set.of("Yama conjures"));

	private TestIds()
	{
	}

	public static IdRegistry registry()
	{
		return registryWithout();
	}

	/** The full registry minus the given roles, which are then uncaptured. */
	public static IdRegistry registryWithout(Role... uncaptured)
	{
		Set<Role> skip = uncaptured.length == 0 ? EnumSet.noneOf(Role.class) : EnumSet.copyOf(Arrays.asList(uncaptured));
		Map<Role, Set<Integer>> ids = new EnumMap<>(Role.class);
		Map<Role, Set<String>> texts = new EnumMap<>(Role.class);
		for (Role role : Role.values())
		{
			if (skip.contains(role))
			{
				continue;
			}
			if (isText(role))
			{
				texts.put(role, TEXTS.get(role));
			}
			else
			{
				ids.put(role, Set.of(id(role)));
			}
		}
		return new IdRegistry(ids, texts);
	}

	public static int id(Role role)
	{
		if (isText(role))
		{
			throw new IllegalArgumentException(role + " is a text role; use text()");
		}
		return BASE + role.ordinal() * STEP;
	}

	/** The canonical text of an overhead or message role: "Enough.", the prayer-disabled line, "Yama conjures". */
	public static String text(Role role)
	{
		if (role == Role.PHASE_TRANSITION_TEXT)
		{
			return "Enough.";
		}
		Set<String> texts = TEXTS.get(role);
		if (texts == null)
		{
			throw new IllegalArgumentException(role + " is not a text role; use id()");
		}
		return texts.iterator().next();
	}

	private static boolean isText(Role role)
	{
		return role.kind() == RoleKind.OVERHEAD || role.kind() == RoleKind.MESSAGE;
	}
}
