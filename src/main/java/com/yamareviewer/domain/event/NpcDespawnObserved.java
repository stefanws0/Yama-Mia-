package com.yamareviewer.domain.event;

import lombok.Value;

@Value
public class NpcDespawnObserved implements DomainEvent
{
	int tick;
	Actor actor;
	int npcId;
	int npcIndex;
	/** RuneLite's NpcUtil.isDying at despawn; false for a scene reload. */
	boolean dying;
}
