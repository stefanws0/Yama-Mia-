package com.yamareviewer.tools;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.gameval.SpotanimID;

/** Development tool only (test source set, never shipped, so reflection is allowed): gameval constant names by kind and id. */
final class GamevalNames
{
	private final Map<String, Map<Integer, String>> byKind = new HashMap<>();

	GamevalNames()
	{
		Map<Integer, String> animations = namesOf(AnimationID.class);
		Map<Integer, String> spotanims = namesOf(SpotanimID.class);
		Map<Integer, String> objects = namesOf(ObjectID.class);
		byKind.put("animation", animations);
		byKind.put("object-animation", animations);
		byKind.put("graphic", spotanims);
		byKind.put("ground-graphic", spotanims);
		byKind.put("projectile", spotanims);
		byKind.put("npc", namesOf(NpcID.class));
		byKind.put("object", objects);
	}

	Optional<String> name(String kind, int id)
	{
		return Optional.ofNullable(byKind.getOrDefault(kind, Map.of()).get(id));
	}

	private static Map<Integer, String> namesOf(Class<?> constants)
	{
		Map<Integer, String> names = new HashMap<>();
		for (Field field : constants.getFields())
		{
			if (Modifier.isStatic(field.getModifiers()) && field.getType() == int.class)
			{
				try
				{
					// ObjectID inherits most constants from the package-private ObjectID1.
					field.setAccessible(true);
					names.putIfAbsent(field.getInt(null), field.getName());
				}
				catch (IllegalAccessException e)
				{
					throw new IllegalStateException(e);
				}
			}
		}
		return names;
	}
}
