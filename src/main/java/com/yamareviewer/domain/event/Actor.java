package com.yamareviewer.domain.event;

import lombok.Value;

/**
 * Who an event is about, resolved when it is recorded.
 * {@code ref} is the NPC index for {@link ActorKind#FLARE}, the NPC id for {@link ActorKind#NPC}, otherwise -1.
 * {@code name} is only set for {@link ActorKind#OTHER}.
 */
@Value
public class Actor
{
	public static final Actor SELF = new Actor(ActorKind.SELF, -1, null);
	public static final Actor PARTNER = new Actor(ActorKind.PARTNER, -1, null);
	public static final Actor YAMA = new Actor(ActorKind.YAMA, -1, null);
	public static final Actor JUDGE = new Actor(ActorKind.JUDGE, -1, null);

	ActorKind kind;
	int ref;
	String name;

	public static Actor flare(int npcIndex)
	{
		return new Actor(ActorKind.FLARE, npcIndex, null);
	}

	public static Actor npc(int npcId)
	{
		return new Actor(ActorKind.NPC, npcId, null);
	}

	public static Actor other(String name)
	{
		return new Actor(ActorKind.OTHER, -1, name);
	}

	public boolean isPlayer()
	{
		return kind == ActorKind.SELF || kind == ActorKind.PARTNER;
	}
}
